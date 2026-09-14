#!/bin/bash
# OHV-8: onboard from a machine with NO home, as README "Install the CLI" + `onboard` tell a new user.
# Everything under $R. The operator's real homes are watched by a tripwire, never written.
set -u
R=/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/9972f0f0-9e78-40bb-8155-34bfc5133ca5/scratchpad/ohv8/${1:?run dir name}
EPIC=/Users/hayde/IdeaProjects/wt-ohv-8/skill-manager
RELEASED=/opt/homebrew/bin/skill-manager
SKT=/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/9972f0f0-9e78-40bb-8155-34bfc5133ca5/scratchpad/ohv8/skt-pr46
rm -rf "$R"; mkdir -p "$R/home" "$R/bin" "$R/log" "$R/work"
LOG="$R/log"
REAL_FILES="/Users/hayde/.claude/settings.json /Users/hayde/.claude/plugins/known_marketplaces.json /Users/hayde/.claude/plugins/installed_plugins.json /Users/hayde/.codex/config.toml /Users/hayde/.gemini/settings.json /Users/hayde/.skill-manager/gateway.properties /Users/hayde/.skill-manager/units.lock.toml /Users/hayde/.skill-manager/artifacts.lock.toml /Users/hayde/.skill-manager/cli-lock.toml"
snap() { # $1 out
  { for f in $REAL_FILES; do if [ -e "$f" ]; then shasum -a 256 "$f"; else echo "absent $f"; fi; done
    ls -la /Users/hayde/.skill-manager/bin/cli /Users/hayde/.skill-manager/installed | shasum -a 256 | sed 's/-$/ls-bin-cli+installed/'
  } > "$1"
}
touch "$LOG/marker"
snap "$LOG/tripwire-before.txt"

# "brew install" -> the epic build on PATH first (the released one stays later on PATH, unused).
# Run 1 used `ln -s`; ./skill-manager resolves SkillManager.java beside the INVOKED path, so a
# symlink on PATH cannot start it (instrument fault, kept in fresh-machine-run1-instrument-fault).
printf '#!/bin/bash\nexec %s "$@"\n' "$EPIC" > "$R/bin/skill-manager"; chmod +x "$R/bin/skill-manager"
export HOME="$R/home"
export JAVA_TOOL_OPTIONS="-Duser.home=$R/home"
export CLAUDE_HOME="$R/home" CLAUDE_CONFIG_DIR="$R/home/.claude" CODEX_HOME="$R/home/.codex" GEMINI_HOME="$R/home/.gemini"
export SKILL_MANAGER_GATEWAY_URL="http://127.0.0.1:51799"   # the operator's live gateway holds 51717
export JBANG_DIR=/Users/hayde/.jbang                          # reuse the JDK/deps cache; not a home
unset SKILL_MANAGER_HOME SKILL_MANAGER_INSTALL_DIR
export PATH="$R/bin:$PATH"
cd "$R/work"

n=0
step() { # label, cmd...
  n=$((n+1)); local label="$(printf '%02d' $n)-$1"; shift
  local t0=$(date +%s)
  "$@" > "$LOG/$label.out" 2> "$LOG/$label.err"; local rc=$?
  echo "$label rc=$rc secs=$(( $(date +%s) - t0 )) cmd: $*" | tee -a "$LOG/steps.txt"
  return 0
}
{ echo "started $(date -u +%FT%TZ)"; echo "HOME=$HOME"; env | grep -E '^(JAVA_TOOL_OPTIONS|CLAUDE_|CODEX_HOME|GEMINI_HOME|SKILL_MANAGER_|JBANG_DIR)=' | sort; echo "which skill-manager: $(command -v skill-manager)"; } > "$LOG/env.txt"
step no-home-before ls -la "$HOME"
step version skill-manager --version
step help skill-manager --help
step gateway-up skill-manager gateway up --port 51799
step onboard skill-manager onboard
step home-exists ls -la "$HOME/.skill-manager"
step list skill-manager list
step gateway-status skill-manager gateway status
H="$HOME/.skill-manager"
# GOAL-verdict-names-its-build: the four skill-manager verdicts, epic vs released, on this one home.
for b in epic:$EPIC released:$RELEASED; do
  bl=${b%%:*}; bp=${b#*:}
  step "$bl-home-verify" env SKILL_MANAGER_HOME="$H" "$bp" home verify --home "$H"
  step "$bl-home-repair" env SKILL_MANAGER_HOME="$H" "$bp" home repair --home "$H" --json
  step "$bl-home-drift" env SKILL_MANAGER_HOME="$H" "$bp" home drift --home "$H" --json
  step "$bl-artifacts-list" env SKILL_MANAGER_HOME="$H" "$bp" artifacts list --json
  step "$bl-home-repair-text" env SKILL_MANAGER_HOME="$H" "$bp" home repair --home "$H"
  step "$bl-artifacts-list-text" env SKILL_MANAGER_HOME="$H" "$bp" artifacts list
done
# skt check from skt#46's branch, judged through the home's pin, then through a released pin.
PIN="$H/bin/cli/skill-manager"
if [ -e "$PIN" ]; then
  cp -p "$PIN" "$R/pin.original"
  step skt-check-pin-as-installed env SKILL_MANAGER_HOME="$H" uv run --project "$SKT" skt check
  step skt-check-pin-as-installed-json env SKILL_MANAGER_HOME="$H" uv run --project "$SKT" skt check --json
  printf '#!/bin/sh\nexec %s "$@"\n' "$EPIC" > "$PIN"; chmod +x "$PIN"
  step skt-check-pin-epic env SKILL_MANAGER_HOME="$H" uv run --project "$SKT" skt check
  step skt-check-pin-epic-json env SKILL_MANAGER_HOME="$H" uv run --project "$SKT" skt check --json
  printf '#!/bin/sh\nexec %s "$@"\n' "$RELEASED" > "$PIN"; chmod +x "$PIN"
  step skt-check-pin-released env SKILL_MANAGER_HOME="$H" uv run --project "$SKT" skt check
  step skt-check-pin-released-json env SKILL_MANAGER_HOME="$H" uv run --project "$SKT" skt check --json
  cp -p "$R/pin.original" "$PIN"
else
  echo "no pin at $PIN: skt check not run" | tee -a "$LOG/steps.txt"
fi
step gateway-down skill-manager gateway down
echo "ended $(date -u +%FT%TZ)" >> "$LOG/env.txt"

# tripwire
export HOME=/Users/hayde
snap "$LOG/tripwire-after.txt"
if diff "$LOG/tripwire-before.txt" "$LOG/tripwire-after.txt" > "$LOG/tripwire.diff"; then echo "TRIPWIRE: real files unchanged" | tee -a "$LOG/steps.txt"; else echo "TRIPWIRE: CHANGED (see tripwire.diff)" | tee -a "$LOG/steps.txt"; fi
find /Users/hayde/.skill-manager -newer "$LOG/marker" -not -path '*/cache/*' -not -path '*/logs/*' -not -name gateway.log -not -path '*/gateway-data/*' 2>/dev/null | head -50 > "$LOG/root-newer-than-start.txt"
echo "root files newer than start (excl cache/logs/gateway): $(wc -l < "$LOG/root-newer-than-start.txt")" | tee -a "$LOG/steps.txt"
lsof -nP -iTCP:51799 -sTCP:LISTEN > "$LOG/port-51799-after.txt" 2>&1
echo done
