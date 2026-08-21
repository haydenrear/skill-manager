#!/usr/bin/env bash
# HIS-4 synthetic reproduction probe — PROJECT TIER.
#
# Builds, entirely in a scratch directory:
#   * a root home H
#   * a "provider" unit holding the scaffold source trees
#   * a "consumer" unit whose test_graph/build-logic is a TRACKED symlink
#     (mode 120000) pointing INTO THE PARENT STORE, plus the generated
#     .gitignore block that declares it not-content
#   * a project P whose child home materializes both -> ChildHomeMaterializer
#     dereferences the store link into a REAL DIRECTORY (CHM-5)
#   * an upstream that moves
# then runs `sync` in the CHILD home and reports what happened.
set -uo pipefail

SCRATCH="${1:?usage: repro.sh <scratch-dir>}"
REPO=/Users/hayde/IdeaProjects/wt-216-his-4
SM="$REPO/skill-manager"

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"
HOME_DIR="$SCRATCH/home"
AGENT="$SCRATCH/agents"
mkdir -p "$HOME_DIR" "$AGENT/.claude" "$AGENT/.codex" "$AGENT/.gemini"

sm() {
  local home="$1"; shift
  SKILL_MANAGER_HOME="$home" \
  CLAUDE_HOME="$AGENT" CLAUDE_CONFIG_DIR="$AGENT/.claude" \
  CODEX_HOME="$AGENT/.codex" GEMINI_HOME="$AGENT/.gemini" \
  "$SM" "$@"
}

g() { git -C "$1" "${@:2}"; }

PROVIDER="$SCRATCH/src/scaffold-provider"
CONSUMER="$SCRATCH/src/scaffolded-unit"

# ---------------------------------------------------------------- provider
mkdir -p "$PROVIDER/project_sdk_sources/build-logic"
cat > "$PROVIDER/SKILL.md" <<'EOF'
---
name: scaffold-provider
description: holds the scaffold source trees the consumer links to
---
EOF
cat > "$PROVIDER/skill-manager.toml" <<'EOF'
[skill]
name = "scaffold-provider"
version = "0.1.0"
description = "holds the scaffold source trees the consumer links to"
EOF
echo '// provider build-logic v1' > "$PROVIDER/project_sdk_sources/build-logic/build.gradle.kts"

# ---------------------------------------------------------------- consumer
mkdir -p "$CONSUMER/test_graph"
cat > "$CONSUMER/SKILL.md" <<'EOF'
---
name: scaffolded-unit
description: a change-managed unit carrying a scaffolded gitignored tree
---
EOF
cat > "$CONSUMER/skill-manager.toml" <<'EOF'
[skill]
name = "scaffolded-unit"
version = "0.1.0"
description = "a change-managed unit carrying a scaffolded gitignored tree"
EOF
echo '// consumer test_graph project' > "$CONSUMER/test_graph/build.gradle.kts"
cat > "$CONSUMER/test_graph/.gitignore" <<'EOF'
**/__pycache__/**

# TEST-GRAPH-MANAGED-BINDINGS-BEGIN
# Generated runtime links; provider-bindings.json is the durable record.
/build-logic
# TEST-GRAPH-MANAGED-BINDINGS-END
EOF
# THE SHAPE: a relative symlink that escapes the unit and lands in the sibling
# store unit. That is what `os.path.relpath(source, destination.parent)` emits
# for a workspace-relative provider, and it is what makes
# ChildHomeMaterializer.walk() call it insideParentStore -> dereference.
ln -s ../../scaffold-provider/project_sdk_sources/build-logic "$CONSUMER/test_graph/build-logic"

if [ "${MODE:-A}" = "C" ] || [ "${MODE:-A}" = "D" ]; then
  # A store link at a path the unit's .gitignore does NOT cover, so the
  # dereference is VISIBLE to git on both sides of a stash.
  mkdir -p "$PROVIDER/shared-docs"
  echo 'shared v1' > "$PROVIDER/shared-docs/NOTE.md"
  ln -s ../scaffold-provider/shared-docs "$CONSUMER/shared-docs"
fi

g "$CONSUMER" init --initial-branch=main -q
g "$CONSUMER" config user.email his4@test.invalid
g "$CONSUMER" config user.name his4
# -f: the .gitignore covers build-logic, and the real units carry it TRACKED
# from before the managed-bindings migration. That is the state the measured
# index stages describe.
g "$CONSUMER" add -A -f
g "$CONSUMER" commit -q -m "consumer v1, scaffold link tracked at 120000"

BARE="$SCRATCH/src/scaffolded-unit.git"
git clone -q --bare "$CONSUMER" "$BARE"
g "$CONSUMER" remote add origin "$BARE"
g "$CONSUMER" fetch -q origin
g "$CONSUMER" branch -q --set-upstream-to=origin/main main

echo "### consumer HEAD tree"
g "$CONSUMER" ls-tree HEAD test_graph/

# ---------------------------------------------------------- install into root
echo
echo "### install provider"
sm "$HOME_DIR" install "$PROVIDER" --yes >"$SCRATCH/install-provider.log" 2>&1
echo "exit=$?"; tail -3 "$SCRATCH/install-provider.log"
echo
echo "### install consumer from bare"
sm "$HOME_DIR" install "git+file://$BARE" --yes >"$SCRATCH/install-consumer.log" 2>&1
echo "exit=$?"; tail -5 "$SCRATCH/install-consumer.log"

STORE="$HOME_DIR/skills/scaffolded-unit"
echo
echo "### root store shape"
ls -la "$STORE/test_graph" 2>&1 | sed -n '1,12p'
echo "root store record gitHash: $(python3 -c "
import json,sys
try: print(json.load(open('$HOME_DIR/installed/scaffolded-unit.json')).get('gitHash'))
except Exception as e: print('ERR',e)
")"

# ------------------------------------------------------------- project home
PROJ="$SCRATCH/project"
mkdir -p "$PROJ"
cat > "$PROJ/skill-project.toml" <<EOF
[project]
name = "his4-project"

[skills.provider]
source = "$HOME_DIR/skills/scaffold-provider"

[skills.consumer]
source = "$HOME_DIR/skills/scaffolded-unit"
EOF

echo
echo "### project resolve"
sm "$HOME_DIR" project resolve --skip-gateway --project-dir "$PROJ" >"$SCRATCH/resolve.log" 2>&1
echo "exit=$?"; tail -8 "$SCRATCH/resolve.log"

CHILD="$PROJ/.skill-manager"
CSTORE="$CHILD/skills/scaffolded-unit"
echo
echo "### child store shape"
ls -la "$CSTORE/test_graph" 2>&1 | sed -n '1,12p'
echo "--- child git status"
g "$CSTORE" status --porcelain 2>&1 | head
echo "--- child record gitHash: $(python3 -c "
import json
try: print(json.load(open('$CHILD/installed/scaffolded-unit.json')).get('gitHash'))
except Exception as e: print('ERR',e)
")"

if [ "${MODE:-A}" = "D" ]; then
  # THE NEGATIVE CONTROL. An agent edits real content in the child copy. That
  # is exactly the work the refusal exists to protect, and it must STILL be
  # refused after the materialization stops counting as a local change.
  echo 'AN AGENT WROTE THIS' >> "$CSTORE/SKILL.md"
fi

# ---------------------------------------------------------------- upstream moves
echo
echo "### upstream moves"
echo '// consumer test_graph project, revision 2' > "$CONSUMER/test_graph/build.gradle.kts"
cat > "$CONSUMER/SKILL.md" <<'EOF'
---
name: scaffolded-unit
description: a change-managed unit carrying a scaffolded gitignored tree, rev 2
---
EOF
if [ "${MODE:-A}" = "C" ]; then
  # Upstream RE-POINTS the store link -- an ordinary upstream change to a path
  # the child home has dereferenced into a real directory.
  mkdir -p "$PROVIDER/shared-docs-v2"
  echo 'shared v2' > "$PROVIDER/shared-docs-v2/NOTE.md"
  rm "$CONSUMER/shared-docs"
  ln -s ../scaffold-provider/shared-docs-v2 "$CONSUMER/shared-docs"
fi
if [ "${MODE:-A}" = "B" ]; then
  # MODE B: upstream performs the managed-bindings MIGRATION -- it stops
  # tracking the scaffold link and leaves only the .gitignore that declares it
  # not-content. That is what the real units' history shows (HEAD's test_graph/
  # holds 10 entries and none of the three links), and it is the only shape
  # that can put the tracked symlink on the BASE side of a later merge while
  # OUR side has deleted it.
  g "$CONSUMER" rm -q --cached test_graph/build-logic
fi
g "$CONSUMER" add -A
g "$CONSUMER" commit -q -m "upstream moves"
g "$CONSUMER" push -q origin main
UPSTREAM_HEAD=$(g "$CONSUMER" rev-parse HEAD)
echo "upstream HEAD = $UPSTREAM_HEAD"

# ---------------------------------------------------------------- the sync
echo
echo "### sync in the CHILD home"
sm "$CHILD" sync scaffolded-unit --yes >"$SCRATCH/sync.log" 2>&1
echo "exit=$?"
tail -25 "$SCRATCH/sync.log"

echo
echo "### after: child store git"
g "$CSTORE" status --porcelain 2>&1 | head
echo "--- ls-files -s stages"
g "$CSTORE" ls-files -s test_graph/ 2>&1 | head
echo "--- unmerged"
g "$CSTORE" ls-files -u 2>&1 | head
echo "--- MERGE_HEAD present? $( [ -f "$CSTORE/.git/MERGE_HEAD" ] && echo yes || echo no)"
echo "--- HEAD = $(g "$CSTORE" rev-parse HEAD 2>&1)"
echo "--- record:"
python3 -c "
import json
try:
    r=json.load(open('$CHILD/installed/scaffolded-unit.json'))
    print('gitHash', r.get('gitHash'))
    print('errors', json.dumps(r.get('errors')))
except Exception as e: print('ERR',e)
"
echo
echo "### the PRINTED REMEDY: sync --merge"
sm "$CHILD" sync scaffolded-unit --merge --yes >"$SCRATCH/sync-merge.log" 2>&1
echo "exit=$?"
grep -E "✗|synced|merge" "$SCRATCH/sync-merge.log" | head -12
echo "--- status"; g "$CSTORE" status --porcelain | head
echo "--- ls-files -u"; g "$CSTORE" ls-files -u
echo "--- MERGE_HEAD? $( [ -f "$CSTORE/.git/MERGE_HEAD" ] && echo yes || echo no)"
echo "--- HEAD $(g "$CSTORE" rev-parse HEAD)"
echo "--- stash"; g "$CSTORE" stash list
echo "--- record"; python3 -c "
import json
r=json.load(open('$CHILD/installed/scaffolded-unit.json'))
print('gitHash', r.get('gitHash')); print('errors', json.dumps(r.get('errors')))
"
echo "--- tree"; ls -la "$CSTORE" | head -12

echo
echo "### second sync"
sm "$CHILD" sync scaffolded-unit --yes >"$SCRATCH/sync2.log" 2>&1
echo "exit=$?"
tail -20 "$SCRATCH/sync2.log"
