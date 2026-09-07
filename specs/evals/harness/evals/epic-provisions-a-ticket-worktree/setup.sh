#!/usr/bin/env bash
# SETUP for epic-provisions-a-ticket-worktree. Makes the environment realistic:
# a Skill Manager home BRANCHED for this eval, wrappers for every unit in it,
# and a workspace that looks like an epic checkout.
#
# Realistic is the whole job. A workspace the case describes but does not have
# gets measured as the agent's failure: an earlier version handed the agent a
# home-SHAPED directory of stubs, which is worse than none -- it invites the
# real command and then fails it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
CASE="$(basename "$HERE")"
SRC="${1:-${SKILL_MANAGER_HOME:-$HOME/.skill-manager}}"
# THE BUILD TREE LIVES UNDER /tmp, NOT IN THE REPOSITORY.
#
# The eval sandbox will not let the agent READ paths under the operator's home
# directory. Measured: `skt ticket new --help` works from an ordinary shell and
# fails inside a run with "the home ... holds no copy of skt
# (plugins/skt/src/skt/cli.py)" -- the wrapper executes, because PATH resolves
# it, and then cannot read the plugin source beside it. The sandbox keeps its
# own temps under /private/tmp, so that root is reachable.
#
# AND /private/tmp, NOT /tmp -- which WAS a workaround and is now just the
# right spelling. On macOS /tmp is a symlink to /private/tmp. A home built
# under one spelling and cloned under the other used to fail the clone
# (skill-manager#330): `home clone` re-anchored generated files by
# substituting ONE spelling of the source root, so every shim was copied
# through untouched and verification -- which resolves properly -- reported
# them. `skt ticket new` rolled back with "home bootstrap failed" two layers
# from the cause, which is what this pin was working around.
#
# FIXED in skill-manager 93dcf5eb; verified end to end on this shape, and
# regression-covered by CloneReanchorsEveryAliasTest rather than by this
# harness. The pin stays because /private/tmp is what everything resolves to
# and there is no reason to feed an alias in, NOT because the alias breaks.
#
# (The note that used to be here called the error message self-contradictory
# and blamed the check. That was wrong: the check was right about a real leak
# and the RE-ANCHOR was the broken half. Left recorded, because reading a
# message instead of reproducing is how the issue got the wrong root cause.)
#
# Override with EVAL_BUILD_ROOT if a machine wants them elsewhere.
BUILD="${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/$CASE"

rm -rf "$BUILD"; mkdir -p "$BUILD" "$(eval_tmpdir "$BUILD")"
branch_home "$SRC" "$BUILD/home"

mkdir -p "$BUILD/units"
for d in "$BUILD"/home/skills/*/; do
  u="$(basename "$d")"; mkdir -p "$BUILD/units/$u/.claude-plugin" "$BUILD/units/$u/skills"
  printf '{"name":"%s","version":"0.1.0","description":"live unit from the branched home"}\n' \
    "$u" > "$BUILD/units/$u/.claude-plugin/plugin.json"
  ln -sfn "$d" "$BUILD/units/$u/skills/$u"
done
for p in "$BUILD"/home/plugins/*/; do
  [ -d "$p" ] || continue; pn="$(basename "$p")"
  mkdir -p "$BUILD/units/$pn/.claude-plugin" "$BUILD/units/$pn/skills"
  printf '{"name":"%s","version":"0.1.0","description":"live plugin from the branched home"}\n' \
    "$pn" > "$BUILD/units/$pn/.claude-plugin/plugin.json"
  for c in "$p"skills/*/; do [ -d "$c" ] && ln -sfn "$c" "$BUILD/units/$pn/skills/$(basename "$c")"; done
done
cp -R "$ROOT/units-template/." "$BUILD/units/"

# The toolchain plugin carries PATH and TMPDIR as SESSION ENV, so the agent
# inherits them instead of discovering them. Machine-specific, so written here
# and never committed.
cat > "$BUILD/units/toolchain/.claude-plugin/settings.json" <<JSON
{ "env": { "PATH": "$(eval_path "$BUILD/home")",
           "TMPDIR": "$(eval_tmpdir "$BUILD")",
           "SKILL_MANAGER_HOME": "$BUILD/home" } }
JSON

# A `git` SHIM, first on PATH. Measured: PATH propagates into the sandbox and
# nothing else does -- SKILL_MANAGER_HOME came back <unset> and TMPDIR with it.
# Apple's git writes an xcrun cache into TMPDIR before doing anything, so with
# the system TMPDIR it dies:
#     git: error: couldn't create cache file '…/T/xcrun_db-…' (Operation not permitted)
# and `git worktree add` fails after "Preparing worktree".
#
# Putting the environment INSIDE something on PATH is deterministic, where
# telling the agent to export it is advice it may take after the first failure.
mkdir -p "$BUILD/shims"
REAL_GIT="$(command -v git)"
cat > "$BUILD/shims/git" <<GITSHIM
#!/usr/bin/env bash
export TMPDIR="$(eval_tmpdir "$BUILD")"
export XCRUN_NO_CACHE=1
export SKILL_MANAGER_HOME="$BUILD/home"
exec "$REAL_GIT" "\$@"
GITSHIM
chmod +x "$BUILD/shims/git"

WS="$BUILD/fixture-workspace"; mkdir -p "$WS"
GIT="$(PATH="$(eval_path "$BUILD/home")" command -v git)"
( cd "$WS" && export TMPDIR="$(eval_tmpdir "$BUILD")" && "$GIT" init -q . \
  && "$GIT" config user.email eval@example.invalid && "$GIT" config user.name eval \
  && printf 'demo project\n' > README.md && printf '.skill-manager/\n' > .gitignore \
  && "$GIT" add -A && "$GIT" commit -qm initial && "$GIT" checkout -q -B epic/demo-epic )
branch_home "$SRC" "$WS/.skill-manager"

rm -rf "$BUILD/evals"; cp -R "$ROOT/evals" "$BUILD/evals"
LIST=$(cd "$BUILD/units" && ls | sed 's|^|  - ../../units/|')
for c in "$BUILD"/evals/*/case.yaml; do
  python3 "$ROOT/rewrite-case.py" "$c" "$LIST" "$(eval_path "$BUILD/home")" \
          "$(eval_tmpdir "$BUILD")" "$BUILD/home"
done

# EVALHOME, outside the target tree: the CLI scans the target, and a .cache
# symlink sends it 16 directories deep through uv's venvs. The credential lives
# in the keychain and that path is HOME-relative, so Library/Keychains must be
# symlinked or nothing authenticates.
EV="$ROOT/.evalhome-$CASE"; rm -rf "$EV"; mkdir -p "$EV/.docker" "$EV/Library"
[ -f "$HOME/.docker/config.json" ] && cp "$HOME/.docker/config.json" "$EV/.docker/config.json"
for q in .claude .claude.json .config .cache .local; do ln -sfn "$HOME/$q" "$EV/$q"; done
ln -sfn "$HOME/Library/Keychains" "$EV/Library/Keychains"

verify_env "$BUILD" || {
  echo "setup: REFUSING to leave a broken environment behind." >&2
  exit 1
}

echo "home:  $BUILD/home (branched for this eval)"
echo "units: $(ls "$BUILD/units" | wc -l | tr -d ' ')"
echo "PATH:  $(eval_path "$BUILD/home")"
