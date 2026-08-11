#!/usr/bin/env bash
# SessionStart hook: inject the skt startup report into the session.
#
# Contract (scored by the epic's eval suite): every invocation appends one
# line to <home>/logs/skt/hook.log — the mode-independent proof that the
# report was present at session start. stdout becomes session context, so
# it carries the bounded report and nothing else. NEVER exits non-zero:
# a broken orientation hook must not break the session it orients.
set -u

pick_python() {
  if [ -n "${SKT_PYTHON:-}" ]; then printf '%s' "$SKT_PYTHON"; return 0; fi
  for candidate in python3.14 python3.13 python3.12 python3.11 python3 python; do
    resolved="$(command -v "$candidate" 2>/dev/null)" || continue
    if "$resolved" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null; then
      printf '%s' "$resolved"; return 0
    fi
  done
  return 1
}

resolve_skt() {
  if [ -n "${SKILL_MANAGER_HOME:-}" ] && [ -x "$SKILL_MANAGER_HOME/bin/cli/skt" ]; then
    printf '%s' "$SKILL_MANAGER_HOME/bin/cli/skt"; return 0
  fi
  if command -v skt >/dev/null 2>&1; then
    printf '%s' "skt"; return 0
  fi
  if [ -n "${CLAUDE_PLUGIN_ROOT:-}" ] && [ -f "$CLAUDE_PLUGIN_ROOT/src/skt/cli.py" ]; then
    py="$(pick_python)" || return 1
    printf '%s %s' "$py" "$CLAUDE_PLUGIN_ROOT/src/skt/cli.py"; return 0
  fi
  return 1
}

log_line() {
  [ -n "${SKILL_MANAGER_HOME:-}" ] || return 0
  logdir="$SKILL_MANAGER_HOME/logs/skt"
  mkdir -p "$logdir" 2>/dev/null || return 0
  printf '%s session-start session=%s %s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "${CLAUDE_SESSION_ID:-unknown}" \
    "$1" >> "$logdir/hook.log" 2>/dev/null || true
}

SKT_CMD="$(resolve_skt)" || { log_line "skt-unresolvable"; exit 0; }

REPORT="$($SKT_CMD status 2>/dev/null)" || { log_line "status-failed"; exit 0; }
log_line "status-injected"
printf '%s\n' "$REPORT"
CHECK="$($SKT_CMD check --cached 2>/dev/null)"
rc=$?
if [ "$rc" -eq 10 ]; then
  log_line "check-notified"
  printf '\n%s\n' "$CHECK"
fi
exit 0
