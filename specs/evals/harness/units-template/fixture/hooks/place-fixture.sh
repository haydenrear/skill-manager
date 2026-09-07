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
printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":%s}}\n' \
  "$(printf '%s' "$note" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')"
