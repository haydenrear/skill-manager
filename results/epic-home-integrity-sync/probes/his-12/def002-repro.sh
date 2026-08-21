set -u
SCRATCH=/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/dcb6a969-c6fd-484c-873c-6d6175b7d220/scratchpad/his12/def002
rm -rf "$SCRATCH"; mkdir -p "$SCRATCH"
cd /Users/hayde/IdeaProjects/wt-161-his-12

# The PROJECT home the operator is working in. Minimal: installed/ + skills/.
PROJ="$SCRATCH/project/.skill-manager"; mkdir -p "$PROJ/installed" "$PROJ/skills"
# The ROOT home, with a pinned entrypoint, whose bin/cli is on the operator's PATH.
ROOT="$SCRATCH/root/.skill-manager"; mkdir -p "$ROOT/installed" "$ROOT/skills" "$ROOT/bin/cli"
cat > "$ROOT/bin/cli/skill-manager" <<'SHIM'
#!/usr/bin/env bash
# skill-manager:cli-pin — stand-in for a real home entrypoint.
export SKILL_MANAGER_HOME="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
echo "STUB ROOT-HOME CLI, bound to $SKILL_MANAGER_HOME"
SHIM
chmod +x "$ROOT/bin/cli/skill-manager"

# A git-backed unit, installed into the PROJECT home, then edited locally so
# that `sync` refuses and prints its remedy.
SRC="$SCRATCH/src/probe-unit"; mkdir -p "$SRC"
cat > "$SRC/SKILL.md" <<'MD'
---
name: probe-unit
description: A local fixture for the HIS-12 DEF-002 probe.
---
# probe-unit
MD
git -C "$SRC" init -q && git -C "$SRC" add -A
git -C "$SRC" -c user.email=p@p -c user.name=p commit -qm init

export SKILL_MANAGER_HOME="$PROJ"
export CLAUDE_CONFIG_DIR="$SCRATCH/project/.claude"
export CODEX_HOME="$SCRATCH/project/.codex"
export GEMINI_HOME="$SCRATCH/project/.gemini"
export PATH="$ROOT/bin/cli:$PATH"
unset SKILL_MANAGER_CLI

./skill-manager install "$SRC" --yes >/dev/null 2>&1
echo "locally edited" >> "$PROJ/skills/probe-unit/SKILL.md"
echo "--- the remedy this sync prints (SKILL_MANAGER_HOME=$PROJ) ---"
./skill-manager sync probe-unit 2>&1 | grep -A3 're-run with' || ./skill-manager sync probe-unit 2>&1 | tail -20
