#!/usr/bin/env bash
# The documented command. --case is NOT optional: case discovery recurses into
# the symlinked units, and spec-double-compiler ships its own example
# eval-plugin whose cases would otherwise run as part of this suite.
set -euo pipefail
BUILD="$(cd "$(dirname "$0")" && pwd)/build"
GLOB="${1:?usage: run.sh '<case-glob>' [--keep] [extra args...]}"; shift || true
KEEP=0; [ "${1:-}" = "--keep" ] && { KEEP=1; shift; }

# TEARDOWN. The environment is built for a run and removed after it, which is
# the point of committing the configs rather than the built tree: what is in
# git is what MAKES the environment, never the environment. --keep holds it for
# debugging. The kept temp dirs the CLI prints are untouched either way -- they
# hold the traces, and a trace should outlive the workspace it came from.
cleanup() {
  if [ "$KEEP" = "1" ]; then echo "kept build tree: $BUILD"; return; fi
  rm -rf "$BUILD" "$(dirname "$BUILD")/.evalhome"
  echo "torn down: build tree and EVALHOME removed (traces survive in the kept temp dirs above)"
}
trap cleanup EXIT
[ -d "$BUILD/units" ] || { echo "run ./build-env.sh first" >&2; exit 1; }
cd "$BUILD"
HOME="$(dirname "$BUILD")/.evalhome" CLAUDE_CODE_WALNUT_SPIRE=1 \
  claude plugin eval . --case "$GLOB" --ablation none --runs 1 \
    --keep-temp --max-cost-usd 2 \
    --allow-tools Bash Read Write Edit Skill "$@"
