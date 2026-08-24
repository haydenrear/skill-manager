#!/usr/bin/env bash
# HIS-19 -- command-level before/after over a simulated `brew upgrade`.
#
# `set -e` and every instrument asserts itself. The FIRST version of this script
# had neither: a python one-liner that rewrites the legacy home's pin line
# failed to compile its regex, the script carried on, and the transcript
# reported "the two homes are indistinguishable ... both exit 0" -- which was
# true, and meant nothing, because the legacy home had never been made legacy.
# Vacuity ledger row 14, in a shell script rather than in a grep.
set -euo pipefail
cd /Users/hayde/IdeaProjects/wt-246-his-19
SIM=/Users/hayde/IdeaProjects/wt-246-his-19/probe/sim
CLI=./skill-manager
export CLAUDE_HOME="$SIM/agent" CLAUDE_CONFIG_DIR="$SIM/agent/.claude" \
       CODEX_HOME="$SIM/agent/.codex" GEMINI_HOME="$SIM/agent/.gemini"

pinline() { grep -F 'SKILL_MANAGER_CLI:-' "$1/bin/cli/skill-manager" | head -1 \
            | sed 's|.*SKILL_MANAGER_CLI:-||; s|}"$||'; }
runpin()  { local out rc; out="$("$1/bin/cli/skill-manager" 2>&1 | head -2 | tr '\n' ' ')" \
            && rc=0 || rc=$?; printf '%-8s rc=%-3s %s\n' "$2" "$rc" "$out"; }
verify()  { local rc; $CLI home verify --home "$1" >/dev/null 2>&1 && rc=0 || rc=$?; \
            printf '%-8s home verify rc=%s\n' "$2" "$rc"; }
say()     { printf '\n=== %s ===\n' "$*"; }

install_keg() {   # $1 version
  local k="$SIM/prefix/Cellar/skill-manager/$1"
  mkdir -p "$k/libexec/bin" "$k/bin"
  { echo '#!/usr/bin/env bash'; echo "printf 'BUILD $1\\n'"; } > "$k/libexec/bin/skill-manager"
  chmod +x "$k/libexec/bin/skill-manager"
  ln -sf ../libexec/bin/skill-manager "$k/bin/skill-manager"
}
link_keg() {      # $1 version
  mkdir -p "$SIM/prefix/bin" "$SIM/prefix/opt"
  ln -sfn "../Cellar/skill-manager/$1/bin/skill-manager" "$SIM/prefix/bin/skill-manager"
  ln -sfn "../Cellar/skill-manager/$1" "$SIM/prefix/opt/skill-manager"
}

rm -rf "$SIM"; mkdir -p "$SIM/home" "$SIM/legacy" "$SIM/agent"
install_keg 0.23.0; link_keg 0.23.0
DUR="$SIM/home/.skill-manager"; LEG="$SIM/legacy/.skill-manager"
KEG023="$SIM/prefix/Cellar/skill-manager/0.23.0/bin/skill-manager"

unset SKILL_MANAGER_CLI || true
SKILL_MANAGER_HOME="$DUR" $CLI home describe --init >/dev/null 2>&1
SKILL_MANAGER_HOME="$LEG" $CLI home describe --init >/dev/null 2>&1
SKILL_MANAGER_CLI="$KEG023" $CLI home shims --home "$DUR" >/dev/null 2>&1
SKILL_MANAGER_CLI="$KEG023" $CLI home shims --home "$LEG" >/dev/null 2>&1

# The LEGACY home is put back into the PRE-HIS-19 shape: the identical generated
# file, with the pin line rewritten to the versioned keg path the old writer
# recorded. The rewrite ASSERTS it happened -- see the header.
python3 - "$LEG/bin/cli/skill-manager" "$KEG023" <<'PY'
import sys, pathlib, re
f, keg = pathlib.Path(sys.argv[1]), sys.argv[2]
t = f.read_text()
new, n = re.subn(r'(?m)^(cli="\$\{SKILL_MANAGER_CLI:-)[^}]*(\}")$', r'\1' + keg + r'\2', t)
assert n == 1, f"pin line rewritten {n} times, expected exactly 1 -- instrument failure"
f.write_text(new)
PY
test "$(pinline "$LEG")" = "$KEG023" || { echo "FIXTURE FAILED: legacy pin is $(pinline "$LEG")"; exit 1; }

say "BEFORE the upgrade -- one sandbox, two pins"
echo "durable pin : $(pinline "$DUR")"
echo "legacy  pin : $(pinline "$LEG")"
runpin "$DUR" durable; runpin "$LEG" legacy
verify "$DUR" durable; verify "$LEG" legacy

say "brew upgrade 0.23.0 -> 0.24.0 (install, DELETE the old keg, re-point both aliases)"
install_keg 0.24.0
rm -rf "$SIM/prefix/Cellar/skill-manager/0.23.0"
link_keg 0.24.0
echo "the keg the legacy home pins is now: $([ -e "$KEG023" ] && echo present || echo GONE)"

say "AFTER the upgrade"
runpin "$DUR" durable; runpin "$LEG" legacy
verify "$DUR" durable
$CLI home verify --home "$LEG" 2>&1 | grep -E "CLI pin|re-pin it with" | head -3 || true
verify "$LEG" legacy
echo "   ^ legacy was EXIT 0 here before HIS-19 -- the 0.24.0 incident, verbatim"

say "MIGRATION -- through HIS-13's repairer, no second mechanism"
SKILL_MANAGER_CLI="$SIM/prefix/Cellar/skill-manager/0.24.0/bin/skill-manager" \
  $CLI home repair --home "$LEG" --fix 2>&1 | grep -E "repair|DANGLING|pin" | head -4 || true
echo "legacy  pin : $(pinline "$LEG")"
runpin "$LEG" legacy; verify "$LEG" legacy

say "AND AGAIN -- 0.24.0 -> 0.25.0, the treadmill test"
install_keg 0.25.0
rm -rf "$SIM/prefix/Cellar/skill-manager/0.24.0"
link_keg 0.25.0
runpin "$LEG" legacy; verify "$LEG" legacy
runpin "$DUR" durable; verify "$DUR" durable
