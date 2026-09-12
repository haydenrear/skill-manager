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

  # BEHIND ITS SOURCE -- which is what this case is named for, and is NOT what
  # the fixture used to build.
  #
  # It wrote `"6ce6538e" + "0"*32` into the record and left the checkout at the
  # tip, under a comment claiming it was rolling back to a real commit rather
  # than inventing a hash. `6ce6538` IS real; `6ce6538e` followed by 32 zeros
  # is not, and the checkout never moved. Measured against skt.check.collect(),
  # that state produces TWO verdicts about one unit, at once:
  #
  #   [record-disagrees-with-checkout]  … the record is what every other
  #                                     command reads      -> "skt sync demo"
  #   ahead_of_remote: ['demo']         "ahead of the remote tip
  #                                      (nothing to pull)"
  #
  # The second is the exact signal that makes an agent conclude the home is
  # current, and check.py's own comment records where that leads: ~30 Bash
  # calls reconstructing the check by hand. The case scored the agent on a
  # contradiction the fixture manufactured.
  #
  # Rolling the CHECKOUT back and recording that same real commit produces one
  # verdict, and it is the right one:
  #
  #   [new-version] new version available for demo -- pull with: skt sync demo
  local store="$ws/.skill-manager/skills/git-issue-workflow"
  local rec="$ws/.skill-manager/installed/git-issue-workflow.json"
  [ -d "$store" ] && [ -f "$rec" ] || { echo "fixture: no git-issue-workflow store/record" >&2; return 1; }

  # A SOURCE THAT ANSWERS WITHOUT THE NETWORK. `skt check` resolves the tip
  # with `git ls-remote <origin>`, so pointing origin at github makes this
  # case's result depend on whether the sandbox reached github -- and an
  # unreachable origin is `unverifiable`, which hands the agent NO signal and
  # scores it anyway. A bare mirror taken before the rollback holds the tip,
  # is inside the workspace the sandbox can read, and needs no network at all.
  # The case is about whether the agent finds the currency command, not about
  # whether a remote resolves.
  local mirror="$ws/.skill-manager/sources/git-issue-workflow.git"
  mkdir -p "$(dirname "$mirror")"
  git clone --quiet --bare "$store" "$mirror"

  local old
  old="$(git -C "$store" rev-parse HEAD~1)"
  git -C "$store" checkout --quiet --detach "$old"

  python3 - "$rec" "$old" "$mirror" <<'PYIN'
import json, pathlib, sys
rec, old, mirror = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
d = json.loads(rec.read_text())
d["gitHash"] = old          # the FULL sha of a commit this repository really has
d["origin"] = mirror        # ...and a source that holds the tip, offline
rec.write_text(json.dumps(d, indent=2) + "\n")
PYIN
}

eval_build_case "$BUILD" "$SRC"
build_fixture "$BUILD" "$SRC"
eval_finish_case "$BUILD" "$CASE"
