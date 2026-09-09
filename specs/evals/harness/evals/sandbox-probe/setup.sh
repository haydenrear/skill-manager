#!/usr/bin/env bash
# SETUP for sandbox-probe. Deliberately the SAME environment every other case
# gets -- branched home, agent home, curated PATH -- because its whole job is
# to prove that environment before a real case is billed against it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
CASE="$(basename "$HERE")"
SRC="${1:-${SKILL_MANAGER_HOME:-$HOME/.skill-manager}}"
BUILD="${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/$CASE"

rm -rf "$BUILD"; mkdir -p "$BUILD" "$(eval_tmpdir "$BUILD")"
branch_home "$SRC" "$BUILD/home" projections

# Only the harness's own units: this case tests the SANDBOX, not retrieval, and
# loading 25 units to write one file would be paying for nothing.
mkdir -p "$BUILD/units"
cp -R "$ROOT/units-template/." "$BUILD/units/"
cat > "$BUILD/units/toolchain/.claude-plugin/settings.json" <<JSON
{ "env": { "PATH": "$(eval_agent_path)", "XCRUN_NO_CACHE": "1" } }
JSON

mkdir -p "$BUILD/shims"
cat > "$BUILD/shims/git" <<SHIM
#!/bin/sh
export TMPDIR="$(eval_tmpdir "$BUILD")"
export XCRUN_NO_CACHE=1
export SKILL_MANAGER_HOME="$BUILD/home"
exec /Library/Developer/CommandLineTools/usr/bin/git "\$@"
SHIM
chmod +x "$BUILD/shims/git"

WS="$BUILD/fixture-workspace"
eval_fixture_checkout "$BUILD" "$SRC" "$WS" main
branch_home "$SRC" "$WS/.skill-manager"

rm -rf "$BUILD/evals"; mkdir -p "$BUILD/evals"
cp -R "$HERE" "$BUILD/evals/$CASE"

eval_claude_home "$BUILD" "$ROOT/.evalhome-$CASE"
echo "probe env: $BUILD"
echo "agent home: $ROOT/.evalhome-$CASE (skills: $(ls "$BUILD/home/.claude/skills" | wc -l | tr -d ' '))"

eval_stamp_sources "$BUILD"
