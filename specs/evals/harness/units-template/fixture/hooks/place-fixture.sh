#!/usr/bin/env bash
# Places the workspace the case describes. The workspace was BUILT by
# build-env.sh, with the product's own commands, outside the sandbox -- this
# hook only copies it in.
#
# It does not construct a home. An earlier version did, out of mkdir and a
# stub json, and a home-shaped directory that no command can use is worse than
# no home: it invites the real command and then fails it. Measured, 8 calls
# became 18.
set -uo pipefail
CWD="${CLAUDE_PROJECT_DIR:-$PWD}"
SRC="${CLAUDE_PLUGIN_ROOT}/../../fixture-workspace"
note=""
if [ -d "$SRC/.git" ]; then
  cp -R "$SRC/." "$CWD/" 2>/dev/null
  # RE-ANCHOR LOCAL ORIGINS, because the copy lands somewhere else entirely.
  #
  # A fixture that gives a unit a LOCAL source writes that source's absolute
  # path into installed/<unit>.json at setup time, under $BUILD. The sandbox
  # cannot read $BUILD, and this copy lives at a fresh /private/tmp/e-XXXXXX
  # chosen per run -- so the recorded origin is unreadable from the only place
  # it is ever read.
  #
  # What that costs is not an error message. `skt check` resolves the tip with
  # `git ls-remote <origin>`; an origin it cannot reach is `unverifiable`,
  # which produces NO notification at all. Measured against check.collect():
  #   origin readable      -> ['new-version']
  #   origin unreadable    -> NONE, unverifiable: ['demo']
  # The agent asked the right question second (`skt check`, command 2 of 31),
  # got silence, and reconstructed the answer by hand over the next 29 calls.
  # That is the exact behaviour `one-command-not-a-reconstruction` exists to
  # catch, arriving here as a fact about the fixture.
  #
  # This hook runs OUTSIDE the Bash sandbox and after the copy, so it is the
  # one place that knows both spellings. Same job as HomeCloner's
  # reanchorProvisioned, for the same reason.
  note="workspace: a git checkout on branch epic/demo-epic"
  SRC_REAL="$(cd "$SRC" 2>/dev/null && pwd -P)"
  CWD_REAL="$(cd "$CWD" 2>/dev/null && pwd -P)"
  if [ -n "${SRC_REAL:-}" ] && [ -n "${CWD_REAL:-}" ]; then
    reanchored="$(python3 - "$SRC_REAL" "$CWD_REAL" <<'PYRE'
import json, pathlib, sys
src, cwd = sys.argv[1], sys.argv[2]
inst = pathlib.Path(cwd) / ".skill-manager" / "installed"
n = 0
for rec in sorted(inst.glob("*.json")):
    if rec.name.endswith(".projections.json"):
        continue
    try:
        d = json.loads(rec.read_text())
    except Exception:
        continue
    origin = d.get("origin") or ""
    # Only a path INTO the source workspace. A github URL is left alone, and
    # so is any absolute path that merely starts with the same characters --
    # the boundary check is what keeps `/x/fixture-workspace-2` out of it.
    if origin == src or origin.startswith(src + "/"):
        d["origin"] = cwd + origin[len(src):]
        rec.write_text(json.dumps(d, indent=2) + "\n")
        n += 1
print(n)
PYRE
)"
    if [ "${reanchored:-0}" != "0" ]; then
      note="$note; re-anchored $reanchored local unit origin(s) into this workspace"
    fi
  fi
  [ -d "$CWD/.skill-manager/bin" ] \
    && note="$note, with a REAL project Skill Manager home at ./.skill-manager" \
    || note="$note, and NO Skill Manager home -- build-env.sh could not clone one, so a red on a case needing one is the fixture's, not the agent's"
else
  note="workspace: THE FIXTURE WAS NOT BUILT ($SRC missing) -- run build-env.sh; a red here is the harness's, not the agent's"
fi
# A BIN THE RUN CAN REACH. PATH lookup inside the sandbox cannot enumerate
# /Library/Developer/CommandLineTools/usr/bin -- measured: `git --version` by
# ABSOLUTE path works, and `which git` still answers /usr/bin/git, the
# xcode-select stub that exits 72. Exec is permitted, directory search is not.
#
# So the working git is placed where the run CAN look: its own cwd. This hook
# runs outside the sandbox, so it can write it; `.eval-bin` is first on the
# agent's PATH, relatively, and resolves against this directory.
#
# XCRUN_NO_CACHE and a TMPDIR the sandbox permits are carried INSIDE the shim
# rather than exported, for the same reason the old one did: PATH is the only
# thing that reliably reaches a run.
if [ -d "$CWD" ]; then
  mkdir -p "$CWD/.eval-bin"
  for real in /Library/Developer/CommandLineTools/usr/bin/git \
              /opt/homebrew/bin/git /usr/local/bin/git; do
    [ -x "$real" ] || continue
    printf '#!/bin/sh\nexport XCRUN_NO_CACHE=1\nexec %s "$@"\n' "$real" > "$CWD/.eval-bin/git"
    chmod +x "$CWD/.eval-bin/git"
    note="$note; git at .eval-bin/git"
    break
  done
fi
printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":%s}}\n' \
  "$(printf '%s' "$note" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')"
