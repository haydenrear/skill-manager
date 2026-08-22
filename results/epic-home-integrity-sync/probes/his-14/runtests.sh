#!/bin/bash
# Run the unit suite with the operator's real agent homes out of reach:
# $HOME points at a scratch dir (AgentHomes.userHome() prefers $HOME), and the
# four agent/store variables are left UNSET, which is what the suite's own
# fallback assertions require.
P=/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/dcb6a969-c6fd-484c-873c-6d6175b7d220/scratchpad/his14
export HOME="$P/fakehome"
export JBANG_DIR=/Users/hayde/.jbang
export GIT_CONFIG_GLOBAL=/Users/hayde/.gitconfig
unset SKILL_MANAGER_HOME CLAUDE_CONFIG_DIR CLAUDE_HOME CODEX_HOME GEMINI_HOME
cd /Users/hayde/IdeaProjects/wt-232-his-14
exec jbang RunTests.java "$@"
