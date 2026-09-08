#!/usr/bin/env bash
# SETUP for reconciles-a-worktree-into-the-project-home. Everything common lives in lib.sh; what is here is THIS case's
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
  ( cd "$ws" \
    && export SKILL_MANAGER_HOME="$ws/.skill-manager" TMPDIR="$(eval_tmpdir "$build")" \
    && export PATH="$(eval_path_for_home "$build" "$ws/.skill-manager")" \
    && "$ws/.skill-manager/bin/cli/skt" ticket new TICKET-9 --base HEAD --path ./wt-ticket-9 ) \
    >"$build/fixture-ticket-9.log" 2>&1 || {
      sed 's/^/         /' "$build/fixture-ticket-9.log" >&2 echo "setup: could not create the worktree" >&2; return 1; }
  # AN EDIT INSIDE THE WORKTREE'S HOME, which is the whole subject. It is
  # gitignored, so it reaches the tier above ONLY through close-out -- git does
  # not carry it, the PR does not carry it, and the epic branch does not carry
  # it. Without a real difference here the reconcile has nothing to move and a
  # do-nothing answer scores the same as a correct one.
  local sk="$ws/wt-ticket-9/.skill-manager/skills/git-issue-workflow/SKILL.md"
  [ -f "$sk" ] || { echo "setup: no unit to edit in the worktree home" >&2; return 1; }
  printf '\n<!-- edited in the ticket worktree, %s -->\n' "TICKET-9" >> "$sk"
}

eval_build_case "$BUILD" "$SRC"
build_fixture "$BUILD" "$SRC"
eval_finish_case "$BUILD" "$CASE"
