#!/usr/bin/env bash
# SETUP for ticket-agent-opens-a-ticket. Everything common lives in lib.sh; what is here is THIS case's
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
  # An ORDINARY ticket, not an epic one: no assignment marker, so the agent
  # should take git-issue-workflow's derived-path front door rather than the
  # declared-path epic route. That difference is the thing being measured.
  eval_fixture_checkout "$build" "$src" "$ws" main
  branch_home "$src" "$ws/.skill-manager"
}

eval_build_case "$BUILD" "$SRC"
build_fixture "$BUILD" "$SRC"
eval_finish_case "$BUILD" "$CASE"
