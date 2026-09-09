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
if [ -d "$SRC/.git" ]; then
  cp -R "$SRC/." "$CWD/" 2>/dev/null
  note="workspace: a git checkout on branch epic/demo-epic"
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
