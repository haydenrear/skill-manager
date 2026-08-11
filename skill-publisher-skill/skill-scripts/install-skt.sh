#!/usr/bin/env bash
set -euo pipefail

: "${SKILL_MANAGER_BIN_DIR:?SKILL_MANAGER_BIN_DIR is required}"
: "${SKILL_MANAGER_CACHE_DIR:?SKILL_MANAGER_CACHE_DIR is required}"
: "${SKILL_DIR:?SKILL_DIR is required}"
: "${SKILL_NAME:=skt}"

# skt is stdlib-only but needs python >= 3.11 (tomllib). macOS's
# /usr/bin/python3 is 3.9, so a bare `python3` in the wrapper breaks the
# moment PATH is minimal. Probe at INSTALL time and bake the absolute
# interpreter into the wrapper; SKT_PYTHON overrides for tests/pins.
pick_python() {
  if [[ -n "${SKT_PYTHON:-}" ]]; then
    printf '%s' "$SKT_PYTHON"; return 0
  fi
  for candidate in python3.14 python3.13 python3.12 python3.11 python3 python; do
    local resolved
    resolved="$(command -v "$candidate" 2>/dev/null)" || continue
    if "$resolved" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null; then
      printf '%s' "$resolved"; return 0
    fi
  done
  return 1
}

PY="$(pick_python)" || {
  echo "skt install requires python >= 3.11 (tomllib); none found on PATH" >&2
  exit 127
}

ENTRYPOINT="$SKILL_DIR/src/skt/cli.py"
if [[ ! -f "$ENTRYPOINT" ]]; then
  echo "skt entrypoint not found at $ENTRYPOINT" >&2
  exit 1
fi

mkdir -p "$SKILL_MANAGER_BIN_DIR" "$SKILL_MANAGER_CACHE_DIR"

WRAPPER="$SKILL_MANAGER_BIN_DIR/skt"
cat > "$WRAPPER" <<SH
#!/usr/bin/env bash
set -euo pipefail

exec "$PY" "$ENTRYPOINT" "\$@"
SH
chmod 0755 "$WRAPPER"

"$WRAPPER" --help >/dev/null
"$WRAPPER" status --json >/dev/null 2>&1 || {
  echo "skt wrapper failed its status probe (interpreter: $PY)" >&2
  exit 1
}
echo "installed skt for $SKILL_NAME at $WRAPPER (python: $PY)"
