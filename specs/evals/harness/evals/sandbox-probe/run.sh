#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
CASE="$(basename "$HERE")"
BUILD="${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/$CASE"
[ -d "$BUILD/units" ] || { echo "run ./setup.sh first" >&2; exit 1; }
eval_require_fresh "$BUILD" || exit 1

# The LAUNCHER's PATH must still find `claude`; resolve it before overriding.
CLAUDE="$(command -v claude)" || { echo "no claude on PATH" >&2; exit 1; }

export XCRUN_NO_CACHE=1
export PATH="$(eval_agent_path)"             # what a RUN can reach, see lib.sh

KEEP=0; [ "${1:-}" = "--keep" ] && { KEEP=1; shift; }
cleanup() { [ "$KEEP" = "1" ] && { echo "kept: $BUILD"; return; }
            rm -rf "$BUILD" "$ROOT/.evalhome-$CASE"; echo "torn down"; }
trap cleanup EXIT

cd "$BUILD"
HOME="$ROOT/.evalhome-$CASE" CLAUDE_CODE_WALNUT_SPIRE=1 \
  "$CLAUDE" plugin eval . --case "$CASE" --ablation none --runs 1 \
    --keep-temp --max-cost-usd 1 --allow-tools Bash 'Bash(skt:*)' 'Bash(git:*)' 'Bash(touch:*)' "$@"
