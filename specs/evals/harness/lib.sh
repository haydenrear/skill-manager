#!/usr/bin/env bash
# Shared by every case's setup.sh / run.sh. A case gets its OWN environment:
# its own branched Skill Manager home, its own wrappers, its own workspace.
set -euo pipefail

eval_root()  { cd "$(dirname "${BASH_SOURCE[0]}")" && pwd; }

# The PATH every run uses, and the reason each entry is on it.
#
#   <eval home>/bin/cli   THE POINT. skill-manager and skt live here, and an
#                         agent that cannot reach them reconstructs the script
#                         by hand -- measured four times, three different
#                         causes, never once reported as a failure.
#   a working git         macOS /usr/bin/git is an xcrun shim; see TMPDIR below.
#   /usr/bin:/bin         the rest of the toolchain.
eval_path() {
  local home p build
  home="$1"; build="$(dirname "$home")"
  # shims FIRST: a `git` that carries TMPDIR and XCRUN_NO_CACHE with it,
  # because PATH is the only variable that reaches the sandbox.
  p="$build/shims:$home/bin/cli"
  for d in /opt/homebrew/bin /usr/local/bin \
           /Library/Developer/CommandLineTools/usr/bin; do
    [ -d "$d" ] && p="$p:$d"
  done
  printf '%s' "$p:/usr/bin:/bin"
}

# TMPDIR must be somewhere the sandbox permits WRITES. Apple's git wrapper
# writes an xcrun cache into TMPDIR before doing anything else; with the system
# TMPDIR it dies with
#     git: error: couldn't create cache file '…/T/xcrun_db-…' (Operation not permitted)
# and `git worktree add` fails after printing "Preparing worktree". That single
# unset variable made a whole case UNMEASURABLE -- eight runs, ~$8, all of them
# an agent improvising against something that could not succeed.
eval_tmpdir() { printf '%s' "$1/tmp"; }

# A home BRANCHED FOR THE EVAL: a real clone, never the operator's own home.
# The run installs, syncs and provisions against it, and none of that reaches
# the home it came from.
branch_home() {
  local src="$1" dst="$2"
  rm -rf "$dst"; mkdir -p "$(dirname "$dst")"
  "$src/bin/cli/skill-manager" home clone --from "$src" --to "$dst" >/dev/null 2>&1 \
    || { echo "setup: could not branch the home from $src" >&2; return 1; }
  # REPAIR THE BRANCH, because it will be cloned AGAIN.
  #
  # A branched home still carries paths naming the home it came from. That is
  # harmless while nothing clones it, and `skt ticket new` clones it -- to give
  # the ticket worktree its own home. The second clone then refuses:
  #   "these paths name a THIRD home, so the clone does not re-anchor them
  #    and the next clone copies them through unchanged"
  # which is correct, and the remedy the product itself prints is this.
  "$dst/bin/cli/skill-manager" home repair --home "$dst" --fix >/dev/null 2>&1 || true
}

# PROVE THE ENVIRONMENT BEFORE SPENDING A RUN ON IT.
#
# Twelve runs and about $12 went on environment faults that a $0 probe would
# have caught: PATH is the ONLY variable that reaches the sandbox (TMPDIR and
# SKILL_MANAGER_HOME both came back unset inside a run), Apple's git needs a
# writable TMPDIR before it will do anything, and the home's `skill-manager`
# shim execs jbang -- so jbang must be on the PATH too or a home cannot even
# bootstrap.
#
# Every one of those is visible without an agent. This runs the front door for
# real, in a throwaway corner of the fixture, and fails setup if it does not
# work. A case should never be the thing that discovers its own environment is
# broken.
verify_env() {
  local build home ws out
  build="$1"; home="$build/home"; ws="$build/fixture-workspace"
  export PATH="$(eval_path "$home")"
  for t in git skt skill-manager jbang; do
    command -v "$t" >/dev/null 2>&1 || {
      echo "setup: '$t' is not on the eval PATH -- the home's CLI shim needs" >&2
      echo "       jbang, and skt needs git; fix eval_path before running" >&2
      return 1; }
  done
  out="$(cd "$ws" && skt ticket new ENVPROBE --path ./.envprobe 2>&1)" || {
    echo "setup: the front door does not work in this environment. A run would" >&2
    echo "       measure that, not the skill. skt said:" >&2
    printf '%s\n' "$out" | sed 's/^/         /' >&2
    return 1; }
  ( cd "$ws" && skt ticket close ENVPROBE >/dev/null 2>&1 || true
    rm -rf ./.envprobe )
  echo "verified: skt ticket new works in this environment"
}
