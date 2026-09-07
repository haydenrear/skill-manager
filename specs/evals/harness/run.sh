#!/usr/bin/env bash
# The documented command. --case is NOT optional: case discovery recurses into
# the symlinked units, and spec-double-compiler ships its own example
# eval-plugin whose cases would otherwise run as part of this suite.
set -euo pipefail
BUILD="$(cd "$(dirname "$0")" && pwd)/build"
GLOB="${1:?usage: run.sh '<case-glob>' [extra args...]}"; shift || true
[ -d "$BUILD/units" ] || { echo "run ./build-env.sh first" >&2; exit 1; }
cd "$BUILD"
HOME="$(dirname "$BUILD")/.evalhome" CLAUDE_CODE_WALNUT_SPIRE=1 \
  claude plugin eval . --case "$GLOB" --ablation none --runs 1 \
    --keep-temp --max-cost-usd 2 \
    --allow-tools Bash Read Write Edit Skill "$@"
