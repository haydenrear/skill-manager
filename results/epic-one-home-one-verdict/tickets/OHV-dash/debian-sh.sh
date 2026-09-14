#!/bin/bash
# Run inside debian:stable-slim, where /bin/sh is dash.
set -u
ls -l /bin/sh
for form in old new; do
  if [ $form = old ]; then A='${BASH_SOURCE[0]:-$0}'; else A='${BASH_SOURCE:-$0}'; fi
  rm -rf /t && mkdir -p /t/h/bin/cli /t/h/cache && cd /t
  printf '#!/bin/sh\necho tool-ran\n' > h/cache/tool; chmod +x h/cache/tool
  printf '#!/bin/sh\nSKILL_MANAGER_SHIM_HOME="$(cd "$(dirname "%s")/../.." && pwd)"\necho home=$SKILL_MANAGER_SHIM_HOME\nexec "${SKILL_MANAGER_SHIM_HOME}/cache/tool"\n' "$A" > h/bin/cli/t
  chmod +x h/bin/cli/t
  cp -R h copy && rm -rf h
  out=$(./copy/bin/cli/t 2>&1); rc=$?
  echo "$form: rc=$rc | $(echo "$out" | tr '\n' ' ')"
done
