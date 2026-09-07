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
