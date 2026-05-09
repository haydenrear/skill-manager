#!/usr/bin/env bash
# Transitive variant of the skill-script smoke fixture. Drops a
# distinct sentinel so the transitive test can't pass on the standalone
# skill-script-skill fixture's side-effect.
set -euo pipefail

: "${SKILL_MANAGER_BIN_DIR:?SKILL_MANAGER_BIN_DIR is required}"
: "${SKILL_DIR:?SKILL_DIR is required}"
: "${SKILL_SCRIPTS_DIR:?SKILL_SCRIPTS_DIR is required}"
: "${SKILL_NAME:?SKILL_NAME is required}"

mkdir -p "$SKILL_MANAGER_BIN_DIR"
touch "$SKILL_MANAGER_BIN_DIR/skill-script-transitive-touched"
chmod +x "$SKILL_MANAGER_BIN_DIR/skill-script-transitive-touched"

echo "skill-script-inner: touched $SKILL_MANAGER_BIN_DIR/skill-script-transitive-touched (skill=$SKILL_NAME)"
