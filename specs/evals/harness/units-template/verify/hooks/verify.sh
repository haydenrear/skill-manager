#!/usr/bin/env bash
# THE VERDICT WRITER. Runs on Stop, which is OUTSIDE the Bash sandbox -- that
# is the whole reason it exists. Measured: the Bash tool cannot write in a
# `plugin eval` run at cwd, $TMPDIR, $HOME or under .git, so any grader that
# reads a file the AGENT was supposed to create can only ever be red.
#
# Three rules from plugin_evals.md, and they are what make this sound:
#   1. delete every verdict path before looking -- the agent has Write and can
#      plant any filename it can guess;
#   2. write a verdict only after a real program returns success;
#   3. exit 0 ALWAYS -- a Stop hook that fails non-zero can push the session on,
#      and a verifier that changes the run it measures is not a verifier.
set -uo pipefail
CWD="${CLAUDE_PROJECT_DIR:-$PWD}"
rm -rf "$CWD/.eval"; mkdir -p "$CWD/.eval"          # RULE 1
echo "hook-can-write" > "$CWD/.eval/hook-wrote-this"
exit 0                                              # RULE 3
