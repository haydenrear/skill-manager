#!/usr/bin/env bash
# SETUP for the WIDE lane: many tiny cases, ONE build.
#
# A deep case gets its own branched home (~5 GB). Thirty tiny cases built that
# way would be ~150 GB and thirty home clones before the first dollar is spent,
# so every `w-*` case shares this one build instead:
#
#   $BUILD/home               one branched home, every unit wrapped from it
#   $BUILD/units              every skill and plugin in that home + toolchain,
#                             fixture, wide-verify (NOT the replay `verify`:
#                             its Stop hook deletes .eval, which would race
#                             wide-verify's verdicts)
#   $BUILD/fixture-workspace  a git checkout with a real branched home, and
#                             cases/<case>/ holding each case's own fixture/
#
# Each wide prompt names its own directory (`cases/<case>/`) and carries an
# `EVAL-CASE: <case>` line, which is how the shared Stop hook tells runs apart.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
. "$ROOT/lib.sh"
SRC="${1:-${SKILL_MANAGER_HOME:-$HOME/.skill-manager}}"
BUILD="${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/wide"

# The verdict extractor's own corpus, under every interpreter a hook may use,
# before building anything a broken extractor would make meaningless.
for py in /usr/bin/python3 python3; do
  command -v "$py" >/dev/null 2>&1 || continue
  "$py" "$HERE/units/wide-verify/lib/expect.py" --self-test >/dev/null \
    || { echo "setup: expect.py fails its self-test under $py" >&2; exit 1; }
done

eval_build_case "$BUILD" "$SRC"
rm -rf "$BUILD/units/verify"
cp -R "$HERE/units/." "$BUILD/units/"

# BRANCH CODE, WHEN A CASE IS ABOUT A CHANGE THAT IS NOT INSTALLED YET.
#
# A case may ship `units-override.txt` with `unit=checkout` lines. The checkout
# is COPIED into $BUILD (a run cannot read under the operator's home, so a
# symlink to it would load nothing) and the unit's wrapper is pointed at the
# copy. The build is shared, so an override applies to EVERY wide case in the
# round -- which is recorded in $BUILD/overrides.txt and printed, never silent.
# Only the unit's SKILL surface changes: CLIs on PATH still come from the home.
: > "$BUILD/overrides.txt"
for f in "$ROOT"/evals/w-*/units-override.txt; do
  [ -f "$f" ] || continue
  while IFS='=' read -r unit checkout; do
    [ -n "${unit// }" ] || continue
    case "$unit" in \#*) continue ;; esac
    prior="$(awk -F= -v u="$unit" '$1==u{print $2}' "$BUILD/overrides.txt")"
    if [ -n "$prior" ] && [ "$prior" != "$checkout" ]; then
      echo "setup: $unit overridden twice ($prior vs $checkout, from $f)" >&2; exit 1
    fi
    [ -n "$prior" ] && continue
    [ -d "$BUILD/units/$unit/skills/$unit" ] || [ -L "$BUILD/units/$unit/skills/$unit" ] \
      || { echo "setup: override names $unit, which the home does not have ($f)" >&2; exit 1; }
    [ -f "$checkout/SKILL.md" ] \
      || { echo "setup: override $unit=$checkout has no SKILL.md ($f)" >&2; exit 1; }
    mkdir -p "$BUILD/overrides"
    rsync -a --delete --exclude .git --exclude __pycache__ --exclude .venv \
      --exclude .skill-manager --exclude .claude "$checkout/" "$BUILD/overrides/$unit/"
    ln -sfn "$BUILD/overrides/$unit" "$BUILD/units/$unit/skills/$unit"
    echo "$unit=$checkout" >> "$BUILD/overrides.txt"
    echo "override: $unit <- $checkout ($(git -C "$checkout" rev-parse --short HEAD 2>/dev/null || echo no-git)$(git -C "$checkout" diff --quiet 2>/dev/null || echo ', UNCOMMITTED changes'))"
  done < "$f"
done

WS="$BUILD/fixture-workspace"
eval_fixture_checkout "$BUILD" "$SRC" "$WS" main
branch_home "$SRC" "$WS/.skill-manager"

# Each case's fixture, committed so the tree is clean: several front doors
# refuse a dirty tree, and a case is not about that unless it says so.
n=0
for c in "$ROOT"/evals/w-*/; do
  [ -d "$c" ] || continue
  name="$(basename "$c")"
  mkdir -p "$WS/cases/$name"
  [ -d "$c/fixture" ] && cp -R "$c/fixture/." "$WS/cases/$name/"
  n=$((n + 1))
done
git_bin="$(PATH="$(eval_path "$BUILD/home")" command -v git)"
( cd "$WS" && export TMPDIR="$(eval_tmpdir "$BUILD")" \
  && "$git_bin" add -A cases && "$git_bin" commit -qm "wide case fixtures" --allow-empty )

eval_finish_case "$BUILD" wide
verify_env "$BUILD"
echo "wide cases: $n (run: ./run.sh [glob] -- default 'w-*')"
