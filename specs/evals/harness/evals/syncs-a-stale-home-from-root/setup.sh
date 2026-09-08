#!/usr/bin/env bash
# SETUP for syncs-a-stale-home-from-root. Everything common lives in lib.sh; what is here is THIS case's
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
  # MAKE A UNIT GENUINELY STALE, by rolling its recorded hash back rather than
  # by writing a fake one. A record that names a commit the unit's repository
  # really had is a state `skt check` can reason about; an invented hash is a
  # state it can only reject, and the case would then measure the fixture.
  local rec="$ws/.skill-manager/installed/git-issue-workflow.json"
  [ -f "$rec" ] && python3 - "$rec" <<'PYIN'
import json, sys, pathlib
p = pathlib.Path(sys.argv[1]); d = json.loads(p.read_text())
# The commit before the current tip of that unit, as recorded in this home.
d["gitHash"] = "6ce6538e" + "0" * 32
p.write_text(json.dumps(d, indent=2) + "\n")
PYIN
}

eval_build_case "$BUILD" "$SRC"
build_fixture "$BUILD" "$SRC"
eval_finish_case "$BUILD" "$CASE"
