#!/usr/bin/env bash
# HIGH-1 and HIGH-2, end to end through the real CLI.
#   E: `sync --from` over a materialized child copy  -> must NOT destroy it
#   F: a CONFLICTING `sync --merge`                  -> must name its files
set -uo pipefail
S="${1:?scratch}"; MODE="${2:?E|F}"
REPO=/Users/hayde/IdeaProjects/wt-216-his-4; SM="$REPO/skill-manager"
rm -rf "$S"; mkdir -p "$S"; H="$S/home"; A="$S/agents"
mkdir -p "$H" "$A/.claude" "$A/.codex" "$A/.gemini"
sm(){ local home=$1; shift; SKILL_MANAGER_HOME="$home" CLAUDE_HOME="$A" CLAUDE_CONFIG_DIR="$A/.claude" \
      CODEX_HOME="$A/.codex" GEMINI_HOME="$A/.gemini" "$SM" "$@"; }
g(){ git -C "$1" "${@:2}"; }
P="$S/src/prov"; C="$S/src/consumer"
mkdir -p "$P/project_sdk_sources/build-logic"
printf -- '---\nname: prov\ndescription: provider\n---\n' > "$P/SKILL.md"
printf '[skill]\nname = "prov"\nversion = "0.1.0"\ndescription = "provider"\n' > "$P/skill-manager.toml"
echo '// v1' > "$P/project_sdk_sources/build-logic/build.gradle.kts"
mkdir -p "$C/test_graph"
printf -- '---\nname: consumer\ndescription: consumer\n---\n' > "$C/SKILL.md"
printf '[skill]\nname = "consumer"\nversion = "0.1.0"\ndescription = "consumer"\n' > "$C/skill-manager.toml"
echo 'shared line' > "$C/SHARED.md"
printf '# TEST-GRAPH-MANAGED-BINDINGS-BEGIN\n/build-logic\n# TEST-GRAPH-MANAGED-BINDINGS-END\n' > "$C/test_graph/.gitignore"
ln -s ../../prov/project_sdk_sources/build-logic "$C/test_graph/build-logic"
g "$C" init -q --initial-branch=main; g "$C" config user.email t@t.invalid; g "$C" config user.name t
g "$C" add -A -f; g "$C" commit -q -m v1
BARE="$S/src/consumer.git"; git clone -q --bare "$C" "$BARE"
g "$C" remote add origin "$BARE"; g "$C" fetch -q origin; g "$C" branch -q --set-upstream-to=origin/main main
sm "$H" install "$P" --yes >/dev/null 2>&1
sm "$H" install "git+file://$BARE" --yes >/dev/null 2>&1
PR="$S/project"; mkdir -p "$PR"
cat > "$PR/skill-project.toml" <<EOF
[project]
name = "p"

[skills.a]
source = "$H/skills/prov"

[skills.b]
source = "$H/skills/consumer"
EOF
sm "$H" project resolve --skip-gateway --project-dir "$PR" >/dev/null 2>&1
CH="$PR/.skill-manager"; CS="$CH/skills/consumer"
echo "### materialized shape"; ls -la "$CS/test_graph" | sed -n '4,6p'
echo "CHILD-ONLY bytes:"; echo 'bytes only the child home has' > "$CS/test_graph/build-logic/child-only.txt"; ls "$CS/test_graph/build-logic"

if [ "$MODE" = "E" ]; then
  echo; echo "### HIGH-1: sync --from over the materialized copy"
  sm "$CH" sync consumer --from "$H/skills/consumer" --yes > "$S/from.log" 2>&1
  echo "exit=$?"; grep -E "✗|✓ synced|applied" "$S/from.log" | head -4
  echo "--- build-logic now:"
  if [ -L "$CS/test_graph/build-logic" ]; then echo "SYMLINK -> $(readlink "$CS/test_graph/build-logic")   <-- ISOLATION BREAK"
  elif [ -d "$CS/test_graph/build-logic" ]; then echo "DIRECTORY (correct)"; else echo "ABSENT   <-- DESTROYED"; fi
  echo "--- child-only bytes: $( [ -f "$CS/test_graph/build-logic/child-only.txt" ] && echo PRESENT || echo 'GONE  <-- DATA LOSS')"
fi

if [ "$MODE" = "F" ]; then
  echo; echo "### HIGH-2: a genuinely CONFLICTING --merge"
  echo 'the agent edited this line' > "$CS/SHARED.md"
  echo 'upstream edited this line' > "$C/SHARED.md"
  g "$C" add -A; g "$C" commit -q -m "upstream touches the same file"; g "$C" push -q origin main
  sm "$CH" sync consumer --merge --yes > "$S/merge.log" 2>&1
  echo "exit=$?"
  grep -E "merge conflict in|already clear|resolve in|reset|MERGE_CONFLICT" "$S/merge.log" | head -6
  echo "--- record errors:"
  python3 -c "
import json;r=json.load(open('$CH/installed/consumer.json'));print(json.dumps(r.get('errors'))[:400])"
  echo "--- agent edit still present: $(grep -c 'agent edited' "$CS/SHARED.md" 2>/dev/null || echo 0)"
fi
