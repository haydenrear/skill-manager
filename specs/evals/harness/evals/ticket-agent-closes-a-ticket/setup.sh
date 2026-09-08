#!/usr/bin/env bash
# SETUP for ticket-agent-closes-a-ticket. Everything common lives in lib.sh; what is here is THIS case's
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
  eval_fixture_checkout "$build" "$src" "$ws" main
  branch_home "$src" "$ws/.skill-manager"
  # A WORKTREE THAT REALLY EXISTS, made by the product's own front door rather
  # than by mkdir. A hand-made worktree-shaped directory is the same mistake as
  # a home-shaped one: `skt ticket close` resolves a ticket by SEARCHING for
  # what `ticket new` recorded, so a fake would be unfindable and the case
  # would measure the fixture.
  ( cd "$ws" \
    && export SKILL_MANAGER_HOME="$ws/.skill-manager" TMPDIR="$(eval_tmpdir "$build")" \
    && export PATH="$(eval_path_for_home "$build" "$ws/.skill-manager")" \
    && "$ws/.skill-manager/bin/cli/skt" ticket new TICKET-7 --base HEAD --path ./wt-ticket-7 ) \
    >"$build/fixture-ticket-7.log" 2>&1 || {
      sed 's/^/         /' "$build/fixture-ticket-7.log" >&2
      echo "setup: could not create the worktree this case is about -- a run" >&2
      echo "       would measure that, not the agent." >&2
      return 1; }
  [ -d "$ws/wt-ticket-7/.skill-manager" ] || {
    echo "setup: the worktree has no home; the close gate would have nothing" >&2
    echo "       to protect and the case would be vacuous." >&2
    return 1; }
}

eval_build_case "$BUILD" "$SRC"
build_fixture "$BUILD" "$SRC"
eval_finish_case "$BUILD" "$CASE"
