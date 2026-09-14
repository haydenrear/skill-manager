#!/usr/bin/env bash
# DEF-OHV-190 shell matrix: does the shim home anchor resolve under each shell
# ShimHomeContract.isShellShebang accepts?
#
# For each anchor form (old = ${BASH_SOURCE[0]:-$0}, new = ${BASH_SOURCE:-$0})
# and each shell, a shim is written into <home>/bin/cli/t that execs
# ${SKILL_MANAGER_SHIM_HOME}/cache/tool. Columns:
#   path   run by path (the kernel reads the shebang), in the original home
#   interp run as `<shell> <shim>`
#   copy   run by path from a cp -R of the home, ORIGINAL DELETED
#   link   run through a symlink to the copied shim from another directory
# Each cell is ok (tool ran from the right home) or rc=<n>/<what it resolved>.
set -u
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
cd "$T"

cell() {   # $1 expected home (relative to $T), rest: the command
  local want="$1"; shift
  local out rc
  out="$("$@" 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q "tool-ran-in:$T/$want\$"; then
    printf 'ok'
  else
    local err
    err="$(printf '%s' "$out" | head -1 | sed "s#$T#<tmp>#g" | cut -c1-60)"
    printf 'rc=%s(%s)' "$rc" "$err"
  fi
}

printf '%-5s %-11s | %-4s | %-6s | %-4s | %s\n' form shell path interp copy link
for form in old new; do
  if [ "$form" = old ]; then A='${BASH_SOURCE[0]:-$0}'; else A='${BASH_SOURCE:-$0}'; fi
  for sh in /bin/sh /bin/dash /bin/bash /bin/zsh /bin/ksh; do
    if [ ! -x "$sh" ]; then printf '%-5s %-11s | not installed\n' "$form" "$sh"; continue; fi
    rm -rf h copy lnk
    mkdir -p h/bin/cli h/cache
    printf '#!/bin/sh\nd=$(cd "$(dirname "$0")/.." && pwd)\necho "tool-ran-in:$d"\n' > h/cache/tool
    chmod +x h/cache/tool
    {
      printf '#!%s\n' "$sh"
      printf 'SKILL_MANAGER_SHIM_HOME="$(cd "$(dirname "%s")/../.." && pwd)"\n' "$A"
      printf 'exec "${SKILL_MANAGER_SHIM_HOME}/cache/tool" "$@"\n'
    } > h/bin/cli/t
    chmod +x h/bin/cli/t
    p="$(cell h ./h/bin/cli/t)"
    i="$(cell h "$sh" h/bin/cli/t)"
    cp -R h copy && rm -rf h
    c="$(cell copy ./copy/bin/cli/t)"
    mkdir lnk && ln -s "$T/copy/bin/cli/t" lnk/t
    l="$(cell copy ./lnk/t)"
    printf '%-5s %-11s | %-4s | %-6s | %-4s | %s\n' "$form" "$sh" "$p" "$i" "$c" "$l"
  done
done
echo
echo "/bin/sh is: $(/bin/sh -c 'echo ${BASH_VERSION:+bash $BASH_VERSION}${ZSH_VERSION:+zsh}' 2>/dev/null) ($(uname -s))"
