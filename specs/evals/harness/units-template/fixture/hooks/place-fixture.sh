#!/usr/bin/env bash
# Places the workspace the case describes, BEFORE the agent runs.
#
# WHY THIS EXISTS. Run 4 of epic-provisions-a-ticket-worktree spent five of its
# eight calls looking for a repository and a Skill Manager home to operate on,
# because the workspace was a bare repo with neither. Those calls measure an
# agent improvising in an under-specified workspace, not the cost of the skill.
#
# scaffold_script: does NOT run -- measured, an inline body of `exit 3` still
# scored 1.00 -- so the fixture is placed by a hook or it is not placed.
set -uo pipefail
CWD="${CLAUDE_PROJECT_DIR:-$PWD}"
GIT=""
for c in /opt/homebrew/bin/git /usr/local/bin/git \
         /Library/Developer/CommandLineTools/usr/bin/git /usr/bin/git; do
  [ -x "$c" ] && "$c" --version >/dev/null 2>&1 && { GIT="$c"; break; }
done
note=""
if [ -n "$GIT" ]; then
  cd "$CWD" || exit 0
  if ! "$GIT" rev-parse --git-dir >/dev/null 2>&1; then
    "$GIT" init -q . 2>/dev/null
    "$GIT" config user.email eval@example.invalid; "$GIT" config user.name eval
  fi
  printf 'demo project\n' > README.md
  "$GIT" add -A >/dev/null 2>&1
  "$GIT" commit -qm "initial" >/dev/null 2>&1 || true
  "$GIT" checkout -q -B epic/demo-epic 2>/dev/null
  # A project home, so "with its own Skill Manager home" has a parent to come
  # from. Minimal on purpose: the case is about the provisioning command, not
  # about what a home contains.
  mkdir -p .skill-manager/installed .skill-manager/skills
  printf '{}\n' > .skill-manager/home.runtime.json
  note="workspace: a git checkout on branch epic/demo-epic, with a project Skill Manager home at ./.skill-manager"
else
  note="workspace: NO WORKING GIT FOUND -- the fixture could not be placed, and a red score here is this hook's, not the agent's"
fi
printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":%s}}\n' \
  "$(printf '%s' "$note" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')"
