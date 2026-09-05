#!/usr/bin/env bash
set -euo pipefail

: "${SKILL_MANAGER_BIN_DIR:?SKILL_MANAGER_BIN_DIR is required}"
: "${SKILL_MANAGER_CACHE_DIR:?SKILL_MANAGER_CACHE_DIR is required}"
: "${SKILL_MANAGER_HOME:?SKILL_MANAGER_HOME is required}"
: "${SKILL_DIR:?SKILL_DIR is required}"
: "${SKILL_NAME:?SKILL_NAME is required}"

uv_bin="${SKILL_MANAGER_HOME}/pm/uv/current/bin/uv"
if [[ ! -x "$uv_bin" ]]; then
  # PATH LAST, NOT FIRST. `command -v skill-manager` finds whatever home's
  # entrypoint is on the operator's PATH -- typically the ROOT home's -- and
  # that shim binds the home it lives in and REFUSES to act on another with
  # exit 79. Measured: the `onboard` test graph provisions a scratch home,
  # this line found ~/.skill-manager/bin/cli/skill-manager, and the refusal
  # killed the install. The refusal is correct; asking the wrong binary was
  # the bug. HBR-1 fixed this shape in two installers in this tree and did
  # not reach this one.
  #
  # `pm setup` takes no --home, so the refusal's own suggested remedy does
  # not apply here: the only fix is to ask a CLI that already binds THIS home.
  skill_manager_bin=""
  for candidate in \
    "${SKILL_MANAGER_CLI:-}" \
    "${SKILL_MANAGER_HOME}/bin/cli/skill-manager" \
    "${SKILL_MANAGER_INSTALL_DIR:-}/skill-manager" \
    "$(command -v skill-manager 2>/dev/null || true)"
  do
    [[ -n "$candidate" && -x "$candidate" ]] || continue
    skill_manager_bin="$candidate"
    break
  done
  if [[ -n "$skill_manager_bin" ]]; then
    # NON-FATAL, deliberately. This whole block is an attempt to provision the
    # bundled uv; the script already falls back to a system uv below and to a
    # named remedy after that. Under `set -e` a refusal here aborted before
    # either fallback could run, turning "the preferred toolchain is missing"
    # into "the install failed".
    "$skill_manager_bin" pm setup uv || true
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
