#!/usr/bin/env bash
# Builds the eval environment from a Skill Manager home. Nothing machine-
# specific is checked in: this script creates the wrappers and the EVALHOME,
# and both are gitignored.
#
#   ./build-env.sh [SKILL_MANAGER_HOME] [BUILD_DIR]
#
# WHY WRAPPERS AND NOT COPIES. `claude plugin eval` requires a plugins: entry to
# resolve UNDER the containment root, but containment stops at the entry -- it
# does not reach inside a loaded plugin. So a thin wrapper whose skills/ holds a
# SYMLINK out to the live unit loads fine, and the suite reads the home's real
# units with no copies and no drift. Symlink the skill INSIDE the wrapper, never
# the wrapper itself: a symlinked entry loads under its RESOLVED identity, and
# every grader naming the expected namespace then silently scores 0.
set -euo pipefail
HOME_DIR="${1:-${SKILL_MANAGER_HOME:-$HOME/.skill-manager}}"
BUILD="${2:-$(cd "$(dirname "$0")" && pwd)/build}"
HERE="$(cd "$(dirname "$0")" && pwd)"

[ -d "$HOME_DIR/skills" ] || { echo "not a home: $HOME_DIR" >&2; exit 1; }
rm -rf "$BUILD/units"; mkdir -p "$BUILD/units"

# EVERY unit in the home gets a wrapper, and every case loads every wrapper.
# The thing under test is retrieval AMONG the skills; handing a case only the
# units its task needs does the retrieval for the agent and overfits
# progressive disclosure.
for d in "$HOME_DIR"/skills/*/; do
  u="$(basename "$d")"
  mkdir -p "$BUILD/units/$u/.claude-plugin" "$BUILD/units/$u/skills"
  printf '{"name":"%s","version":"0.1.0","description":"live unit, symlinked from the home"}\n' \
    "$u" > "$BUILD/units/$u/.claude-plugin/plugin.json"
  ln -sfn "$d" "$BUILD/units/$u/skills/$u"
done
for p in "$HOME_DIR"/plugins/*/; do
  [ -d "$p" ] || continue
  pn="$(basename "$p")"
  mkdir -p "$BUILD/units/$pn/.claude-plugin" "$BUILD/units/$pn/skills"
  ver="$(python3 -c "import json,sys;print(json.load(open('$p/.claude-plugin/plugin.json')).get('version','0.1.0'))" 2>/dev/null || echo 0.1.0)"
  printf '{"name":"%s","version":"%s","description":"live plugin, contained skills symlinked from the home"}\n' \
    "$pn" "$ver" > "$BUILD/units/$pn/.claude-plugin/plugin.json"
  for c in "$p"skills/*/; do
    [ -d "$c" ] && ln -sfn "$c" "$BUILD/units/$pn/skills/$(basename "$c")"
  done
done
# The harness's own plugins: hooks, no skills.
for t in "$HERE"/units-template/*/; do cp -R "$t" "$BUILD/units/$(basename "$t")"; done
rm -rf "$BUILD/evals"; cp -R "$HERE/evals" "$BUILD/evals"

# The plugins: list every case must carry, regenerated so a new unit in the
# home is picked up rather than silently missing from the run.
LIST=$(cd "$BUILD/units" && ls | sed 's|^|  - ../../units/|')
for c in "$BUILD"/evals/*/case.yaml; do
  python3 - "$c" "$LIST" <<'PY'
import re,sys,pathlib
p=pathlib.Path(sys.argv[1]); s=p.read_text()
# (?m) ONLY. With (?s), `.` matches newlines and `  - .*\n` swallows the
# rest of the file -- measured: the generated case lost its whole execution
# block and the CLI reported "execution.prompt is required".
s=re.sub(r"(?m)^plugins:\n(?:  - .*\n)+", "plugins:\n"+sys.argv[2]+"\n", s)
p.write_text(s)
PY
done

# EVALHOME. The credential lives in the keychain and the keychain path is
# HOME-relative, so Library/Keychains must be symlinked or the run cannot
# authenticate. .docker must be a real directory holding a real file.
EV="$(dirname "$BUILD")/.evalhome"; rm -rf "$EV"; mkdir -p "$EV/.docker" "$EV/Library"
[ -f "$HOME/.docker/config.json" ] && cp "$HOME/.docker/config.json" "$EV/.docker/config.json"
for q in .claude .claude.json .config .cache .local; do ln -sfn "$HOME/$q" "$EV/$q"; done
ln -sfn "$HOME/Library/Keychains" "$EV/Library/Keychains"

echo "units:    $(ls "$BUILD/units" | wc -l | tr -d ' ') wrappers in $BUILD/units"
echo "cases:    $(ls "$BUILD/evals" | wc -l | tr -d ' ')"
echo "evalhome: $EV"
echo
echo "run:  ./run.sh '<case-glob>'"
