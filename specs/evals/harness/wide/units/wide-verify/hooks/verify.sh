#!/usr/bin/env bash
# THE WIDE LANE'S VERDICT WRITER. Stop hook, outside the Bash sandbox.
#
# Unlike ../../../units-template/verify/hooks/verify.sh this replays NOTHING:
# a wide case asks which command the agent reached for, and that is decidable
# from the transcript alone. lib/expect.py matches text and never executes it.
#
# Rules 1 and 3 of plugin_evals.md live here; 2 and 4 live in expect.py.
set -uo pipefail

CWD="${CLAUDE_PROJECT_DIR:-$PWD}"
EV="$CWD/.eval"
rm -rf "$EV"; mkdir -p "$EV"                                   # RULE 1

STDIN_JSON="$(cat 2>/dev/null || true)"
printf '%s' "$STDIN_JSON" > "$EV/hook-stdin.json"
TRANSCRIPT="$(printf '%s' "$STDIN_JSON" | /usr/bin/python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("transcript_path") or "")
except Exception: print("")' 2>/dev/null)"

BUILD="$(cd "${CLAUDE_PLUGIN_ROOT}/../.." && pwd)"
/usr/bin/python3 "$CLAUDE_PLUGIN_ROOT/lib/expect.py" \
  "$TRANSCRIPT" "$BUILD/evals" "$EV" > "$EV/verify.log" 2>&1

# THE TRANSCRIPT ITSELF, so a regex or llm red can be read against the reply
# it scored. The sandbox holding it is torn down after the run; a wide round's
# first reds (an llm FAIL, a missing command) could not be judged for exactly
# that reason. Copied AFTER expect.py so no grader can read it as a verdict.
[ -f "$TRANSCRIPT" ] && cp "$TRANSCRIPT" "$EV/transcript.jsonl" 2>/dev/null

# Keep the few-KB record of what the agent ran; the sandbox holding the same
# facts is ~5 GB and is torn down. Named by case, because wide runs share one
# build and would otherwise overwrite each other.
CASE="$(cat "$EV/case" 2>/dev/null || echo unknown)"
cp -R "$EV" "$BUILD/eval-diagnostics-$CASE-$(date -u +%H%M%S)" 2>/dev/null || true
exit 0                                                          # RULE 3
