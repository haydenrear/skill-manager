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
: "${SKILL_MANAGER_CACHE_DIR:?SKILL_MANAGER_CACHE_DIR is required}"
: "${SKILL_DIR:?SKILL_DIR is required}"
: "${SKILL_SCRIPTS_DIR:?SKILL_SCRIPTS_DIR is required}"
: "${SKILL_NAME:?SKILL_NAME is required}"

# ARTI-08. The sentinel used to be an empty file in bin/, which is exactly the
# half `PruneCliIfOrphan` already removed — so the node built on it could not
# see the half that survives an uninstall. A real skill-script install writes a
# TREE under $SKILL_MANAGER_CACHE_DIR and leaves a wrapper that execs into it
# (`cache/skill-script-deploy-helm-helm-deploy/venv/bin/helm-deploy` in the
# operator's home), and the tree is what outlived its owner forever.
tree="$SKILL_MANAGER_CACHE_DIR/skill-script-$SKILL_NAME-skill-script-touched"
mkdir -p "$tree/venv/bin"
printf '#!/bin/sh\necho skill-script-touched\n' > "$tree/venv/bin/skill-script-touched"
chmod +x "$tree/venv/bin/skill-script-touched"

mkdir -p "$SKILL_MANAGER_BIN_DIR"
# A generated wrapper, not an empty file: it is the wrapper's own bytes that
# name the tree, which is the only evidence a home has of which install wrote
# a directory (ArtifactBackfill.provisionedTrees).
printf '#!/bin/sh\nexec "%s" "$@"\n' "$tree/venv/bin/skill-script-touched" \
  > "$SKILL_MANAGER_BIN_DIR/skill-script-touched"
chmod +x "$SKILL_MANAGER_BIN_DIR/skill-script-touched"

# Diagnostic breadcrumb — surfaces in node-logs/ if the assertion fails.
echo "skill-script-skill: touched $SKILL_MANAGER_BIN_DIR/skill-script-touched and $tree (skill=$SKILL_NAME)"
