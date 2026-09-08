#!/usr/bin/env bash
# SETUP for bootstraps-a-home-for-a-repo. Everything common lives in lib.sh; what is here is THIS case's
# fixture, which is the only part that differs between cases.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
CASE="$(basename "$HERE")"
SRC="${1:-${SKILL_MANAGER_HOME:-$HOME/.skill-manager}}"
# /private/tmp, not the repository and not /tmp: the sandbox cannot read under
# the operator's home, and /private/tmp is the spelling everything resolves to.
BUILD="${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/$CASE"

build_fixture() {
  local build="$1" src="$2" ws="$1/fixture-workspace"
  # A repo that has NEVER been given a home -- deliberately no branch_home
  # here. That is the one-time-per-repository state `wt new` prints as its
  # `fix:` line, and the case is whether the agent recognises it and reaches
  # for bootstrap-home.sh rather than inventing a directory.
  eval_fixture_checkout "$build" "$src" "$ws" main
}

eval_build_case "$BUILD" "$SRC"
build_fixture "$BUILD" "$SRC"
eval_finish_case "$BUILD" "$CASE"
