#!/bin/bash
# How this ticket's unit runs were made.
#
#   ./runtests.sh          the four agent/store variables UNSET
#   ./runtests.sh --set    the four EXPORTED at a scratch home
#
# HomeBindsBothAxesTest is green BOTH ways -- 17/17 -- and that is the point
# of having both modes here. In the first round it was not: the suite was run
# only with the variables unset, and with CLAUDE_CONFIG_DIR exported it went
# 9/1 on a PRECONDITION, because `AgentHomes.setOverride` could not say "this
# variable is unset" and one case silently depended on the shell. HIS-14 added
# `AgentHomes.setUnset` for exactly that; review of #234, MED-4.
#
# `--set` still reports 19 failures in OTHER suites (AgentHomesTest,
# HomeDescriptorTest, LauncherShimsTest, the #145 test). They are the same 19,
# in the same suites, as on the BASE commit before any of this ticket's code:
# each is a correct assertion about an unset variable that has no way to say so.
# Filed as DEF-042, with `setUnset` as the fix they need.
#
# $HOME is a scratch directory in both modes. AgentHomes.userHome() prefers
# $HOME, so this keeps every fallback out of the operator's real agent homes --
# which is the failure this whole ticket exists for.
set -u
SCRATCH="${TMPDIR:-/tmp}/his14-runtests"
mkdir -p "$SCRATCH/home/.skill-manager" "$SCRATCH/home/.claude" \
         "$SCRATCH/home/.codex" "$SCRATCH/home/.gemini"
export HOME="$SCRATCH/home"
export JBANG_DIR="${JBANG_DIR:-$HOME/.jbang}"

if [ "${1:-}" = "--set" ]; then
  export SKILL_MANAGER_HOME="$SCRATCH/home/.skill-manager"
  export CLAUDE_CONFIG_DIR="$SCRATCH/home/.claude"
  export CODEX_HOME="$SCRATCH/home/.codex"
  export GEMINI_HOME="$SCRATCH/home/.gemini"
  shift
else
  unset SKILL_MANAGER_HOME CLAUDE_CONFIG_DIR CLAUDE_HOME CODEX_HOME GEMINI_HOME
fi

cd "$(dirname "$0")/../../../.." || exit 1
exec jbang RunTests.java "$@"
