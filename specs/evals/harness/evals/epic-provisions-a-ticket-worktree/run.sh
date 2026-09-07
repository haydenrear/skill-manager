#!/usr/bin/env bash
# RUN for epic-provisions-a-ticket-worktree. The PATH lives here, on purpose:
# it is the single thing that decides whether the agent can reach the front
# door or spends its turns reconstructing one.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
CASE="$(basename "$HERE")"
BUILD="$ROOT/build/$CASE"
[ -d "$BUILD/units" ] || { echo "run ./setup.sh first" >&2; exit 1; }

# TWO PATHS, and conflating them broke a run. The AGENT's PATH is the curated
# one and reaches it through the toolchain plugin's settings.json, written by
# setup.sh. THE LAUNCHER's PATH must still find `claude` itself -- overriding it
# here resolved a different, older claude that answered
# "unknown command 'eval' (Did you mean enable?)".
CLAUDE="$(command -v claude)" || { echo "no claude on PATH" >&2; exit 1; }

export TMPDIR="$(eval_tmpdir "$BUILD")"
export SKILL_MANAGER_HOME="$BUILD/home"
export PATH="$(eval_path "$BUILD/home"):$PATH"

KEEP=0; [ "${1:-}" = "--keep" ] && { KEEP=1; shift; }
cleanup() {
  [ "$KEEP" = "1" ] && { echo "kept: $BUILD"; return; }
  rm -rf "$BUILD" "$ROOT/.evalhome-$CASE"
  echo "torn down (traces survive in the kept temp dirs above)"
}
trap cleanup EXIT

cd "$BUILD"
HOME="$ROOT/.evalhome-$CASE" CLAUDE_CODE_WALNUT_SPIRE=1 \
  "$CLAUDE" plugin eval . --case "$CASE" --ablation none --runs 1 \
    --keep-temp --max-cost-usd 2 \
    --allow-tools Bash Read Write Edit Skill "$@"
