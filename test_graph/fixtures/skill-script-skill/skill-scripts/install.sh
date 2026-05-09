#!/usr/bin/env bash
# Smoke fixture for the skill-script CLI backend. Drops a sentinel file
# (an empty executable) into $SKILL_MANAGER_BIN_DIR so the smoke node
# can assert the script ran end-to-end without needing a real toolchain.
#
# Verifies the contract that the backend sets the env vars the docs
# promise — fails loudly if any are missing rather than silently
# touching nothing.
set -euo pipefail

: "${SKILL_MANAGER_BIN_DIR:?SKILL_MANAGER_BIN_DIR is required}"
: "${SKILL_DIR:?SKILL_DIR is required}"
: "${SKILL_SCRIPTS_DIR:?SKILL_SCRIPTS_DIR is required}"
: "${SKILL_NAME:?SKILL_NAME is required}"

mkdir -p "$SKILL_MANAGER_BIN_DIR"
touch "$SKILL_MANAGER_BIN_DIR/skill-script-touched"
chmod +x "$SKILL_MANAGER_BIN_DIR/skill-script-touched"

# Diagnostic breadcrumb — surfaces in node-logs/ if the assertion fails.
echo "skill-script-skill: touched $SKILL_MANAGER_BIN_DIR/skill-script-touched (skill=$SKILL_NAME)"
