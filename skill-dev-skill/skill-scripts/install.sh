#!/usr/bin/env bash
set -euo pipefail

: "${SKILL_MANAGER_BIN_DIR:?SKILL_MANAGER_BIN_DIR is required}"
: "${SKILL_MANAGER_CACHE_DIR:?SKILL_MANAGER_CACHE_DIR is required}"
: "${SKILL_MANAGER_HOME:?SKILL_MANAGER_HOME is required}"
: "${SKILL_DIR:?SKILL_DIR is required}"
: "${SKILL_NAME:?SKILL_NAME is required}"

uv_bin="${SKILL_MANAGER_HOME}/pm/uv/current/bin/uv"
if [[ ! -x "$uv_bin" ]]; then
  skill_manager_bin=""
  if command -v skill-manager >/dev/null 2>&1; then
    skill_manager_bin="$(command -v skill-manager)"
  elif [[ -n "${SKILL_MANAGER_INSTALL_DIR:-}" && -x "${SKILL_MANAGER_INSTALL_DIR}/skill-manager" ]]; then
    skill_manager_bin="${SKILL_MANAGER_INSTALL_DIR}/skill-manager"
  fi
  if [[ -n "$skill_manager_bin" ]]; then
    "$skill_manager_bin" pm setup uv
  fi
fi
if [[ ! -x "$uv_bin" ]]; then
  if command -v uv >/dev/null 2>&1; then
    uv_bin="$(command -v uv)"
  else
    echo "skill-dev install requires bundled uv; run: skill-manager pm setup uv" >&2
    exit 127
  fi
fi

mkdir -p "$SKILL_MANAGER_BIN_DIR" "$SKILL_MANAGER_CACHE_DIR/uv-tools"

UV_TOOL_BIN_DIR="$SKILL_MANAGER_BIN_DIR" \
UV_TOOL_DIR="$SKILL_MANAGER_CACHE_DIR/uv-tools" \
  "$uv_bin" tool install "$SKILL_DIR" --force --reinstall --python python3

test -x "$SKILL_MANAGER_BIN_DIR/skill-dev"
echo "skill-dev-skill: installed skill-dev to $SKILL_MANAGER_BIN_DIR/skill-dev (skill=$SKILL_NAME)"
