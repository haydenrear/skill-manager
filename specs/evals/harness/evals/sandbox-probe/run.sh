#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
CASE="$(basename "$HERE")"
BUILD="${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/$CASE"
[ -d "$BUILD/units" ] || { echo "run ./setup.sh first" >&2; exit 1; }

# The LAUNCHER's PATH must still find `claude`; resolve it before overriding.
CLAUDE="$(command -v claude)" || { echo "no claude on PATH" >&2; exit 1; }

export TMPDIR="$(eval_tmpdir "$BUILD")"
export SKILL_MANAGER_HOME="$BUILD/home"
export PATH="$(eval_path "$BUILD/home")"     # COMPLETE, not a prefix

KEEP=0; [ "${1:-}" = "--keep" ] && { KEEP=1; shift; }
cleanup() { [ "$KEEP" = "1" ] && { echo "kept: $BUILD"; return; }
            rm -rf "$BUILD" "$ROOT/.evalhome-$CASE"; echo "torn down"; }
trap cleanup EXIT

cd "$BUILD"
HOME="$ROOT/.evalhome-$CASE" CLAUDE_CODE_WALNUT_SPIRE=1 \
  "$CLAUDE" plugin eval . --case "$CASE" --ablation none --runs 1 \
    --keep-temp --max-cost-usd 1 --allow-tools 'Bash(touch:*)' "$@"
