#!/usr/bin/env bash
# Confirms the session's toolchain. It does NOT hand out absolute paths any
# more, and that reversal is the point.
#
# Earlier versions reported a fallback chain of absolute paths, and the agent
# dutifully used them -- writing `/usr/bin/git` even in runs where run.sh had
# already put a working git first on PATH. The hook was teaching it to bypass
# the very thing the run script exists to set.
#
# A hook cannot verify on the agent's behalf anyway: it runs in a more
# permissive environment than the Bash sandbox, and it proved that by
# executing /usr/bin/git successfully in a run where the agent could not.
# So it reports what is on PATH and says to use it by name.
set -uo pipefail
# RESOLVE WHERE THE AGENT WILL, WHICH IS NOT WHERE THIS HOOK STARTS.
#
# The agent's PATH leads with RELATIVE entries -- `.eval-bin` and
# `.skill-manager/bin/cli` -- because those are the only places a run can read
# (see lib.sh eval_agent_path). A relative PATH entry resolves against the
# CURRENT DIRECTORY, and this hook does not start in the workspace.
#
# So it reported, to every case:
#
#     skt           -> NOT ON PATH (… that is a defect in the eval setup …)
#     skill-manager -> /opt/homebrew/bin/skill-manager
#
# Both false. The front door WAS on the agent's path, and the second line
# handed it the operator's global homebrew CLI -- the exact "teaching it to
# bypass the thing run.sh sets up" this file's own header warns about, one
# rewrite later.
#
# An agent told the front door is absent goes looking for it, and that is the
# 4-to-7 orientation calls every case was failing its cost grader on.
cd "${CLAUDE_PROJECT_DIR:-$PWD}" 2>/dev/null || true
out="Toolchain for this session. PATH is already set correctly by the run"
out="$out"$'\n'"script -- CALL THESE BY NAME, do not search for them and do not"
out="$out"$'\n'"write absolute paths:"
for t in git gh jbang python3 skt skill-manager; do
  if p="$(command -v "$t" 2>/dev/null)"; then out="$out"$'\n'"  $t -> $p"
  else out="$out"$'\n'"  $t -> NOT ON PATH (if a task needs it, that is a"
       out="$out"$'\n'"        defect in the eval setup, not something to work around)"; fi
done
out="$out"$'\n'"TMPDIR=${TMPDIR:-<unset>}"
printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":%s}}\n' \
  "$(printf '%s' "$out" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')"
