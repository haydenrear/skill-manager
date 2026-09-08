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
# THE PATH IS COMPLETE, NOT A PREFIX. run.sh exports exactly this and does NOT
# append the operator's $PATH -- appending it is what let run 15 reach
# /Users/<op>/.skill-manager/bin/cli/skt, the operator's LIVE ROOT HOME, while
# the branched home sat unusable at entry 2. Fourteen runs measured a home
# nobody intended. If something is missing from a run, add it HERE.
#
# /usr/bin is LAST and after CommandLineTools deliberately: /usr/bin/git is an
# xcode-select stub that exits 72 with "Failed to locate 'git'".
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

# THE SANDBOX IS NOT OURS TO SET, AND THIS IS THE EVIDENCE.
#
# The obvious fix for "the workspace is read-only" is
# sandbox.filesystem.allowWrite in the eval HOME's settings.json. IT IS
# IGNORED. `claude plugin eval` builds its own sandbox config and passes it
# down; from the 2.1.263 binary, verbatim:
#
#   sandbox:{enabled:!0, failIfUnavailable:!0,
#            autoAllowBashIfSandboxed:!1, allowUnsandboxedCommands:!1,
#            filesystem:{ allowWrite:[e.home, e.tmpDir],
#                         denyWrite:[ ..., k.join(e.cwd,vt), ...home paths... ],
#                         allowRead :[e.home, e.tmpDir, ...pluginDirs, ...] }}
#
# Three consequences, each measured with the sandbox-probe case rather than
# read off that string alone:
#
#  1. THE WORKSPACE IS DENY-WRITTEN BY DESIGN. With the permission gate passed
#     (below), `touch ./probe-write` still returns "Operation not permitted".
#     A case that asks an agent to CREATE a worktree cannot be graded by
#     looking at the worktree. plugin_evals.md already says the sanctioned way
#     round it: verify in a `Stop` hook, which runs outside the sandbox, and
#     grade the verdict path it writes.
#
#  2. allowRead is the run's own tree PLUS THE PLUGIN DIRS -- and nothing else.
#     That is why $BUILD was invisible in run 15 and why PATH fell through to
#     the operator's live root home. Anything a run must READ has to be inside
#     a plugin directory. The units already are; the branched home is not.
#
#  3. autoAllowBashIfSandboxed:!1 with dontAsk means a WRITE command is denied
#     before any shell starts unless a rule pre-approves it. See eval_claude_home.
#
# Kept as a function returning nothing rather than deleted, so the next person
# to reach for the setting finds out here instead of over sixteen runs.
sandbox_settings_json() { :; }

# THE AGENT HOME, built to look exactly like a standard session's.
#
# `skill-manager sync` in the branched home derives that home's OWN agent
# projections: .claude/skills/<unit> symlinked into its skills/, .claude/plugins
# with a marketplace pointing at its plugin-marketplace/, and the enabledPlugins
# that turn the plugins on. That IS our standard setup, so the eval copies it
# rather than inventing a second one -- an eval whose context differs from a
# real session measures the difference.
#
# Copied, not symlinked at the top: settings.json needs the sandbox grant
# merged into it, and mutating the home's own file would change the thing under
# test. The skill entries stay symlinks into the branched home, which is why
# that home has to be readable -- see sandbox_settings_json.
eval_claude_home() {
  local build home ev src
  build="$1"; ev="$2"; home="$build/home"; src="$home/.claude"
  [ -d "$src" ] || { echo "setup: $src does not exist -- branch_home must sync" >&2; return 1; }
  rm -rf "$ev"; mkdir -p "$ev/Library"
  cp -R "$src" "$ev/.claude"
  python3 - "$ev/.claude/settings.json" <<'PYEOF'
import json, sys, pathlib
path = pathlib.Path(sys.argv[1])
cfg = json.loads(path.read_text()) if path.exists() else {}
# THE PERMISSION GATE COMES BEFORE THE SANDBOX GATE, and it is the one that
# actually stops these runs. Bisected with the probe case, one variable at a
# time: a READ-ONLY command (`pwd; ls -a; command -v git`) runs fine, and the
# SAME case with `touch ./probe-write` is refused with
#
#     subtype: permission_denied, tool_name: Bash,
#     decision_reason_type: "mode"      ("don't ask mode")
#
# -- refused BEFORE any shell starts, so sandbox.filesystem.allowWrite never
# gets a say. `plugin eval` runs every session in dontAsk, where a call runs
# only if a rule allows it. `--allow-tools` in run.sh is the OPERATOR half of
# that grant and this is the SETTINGS half; the run needs both, which is why
# widening --allow-tools alone changed nothing across four runs.
#
# (An earlier version set only defaultMode="auto" and no allow list. That is
# what "don't ask" already means -- it denied every write and cost four probe
# runs to separate from the sandbox question underneath it.)
cfg.setdefault("permissions", {})["defaultMode"] = "auto"
cfg["permissions"]["allow"] = ["Bash", "Read", "Write", "Edit", "Skill", "Glob", "Grep"]
path.write_text(json.dumps(cfg, indent=2) + "\n")
PYEOF
  # AUTH. The credential is in the login keychain and that path is HOME-relative,
  # so without this the CLI cannot authenticate and the run never starts.
  ln -sfn "$HOME/Library/Keychains" "$ev/Library/Keychains"
  [ -f "$HOME/.claude.json" ] && ln -sfn "$HOME/.claude.json" "$ev/.claude.json"
  [ -d "$HOME/.docker" ] && { mkdir -p "$ev/.docker"; \
    [ -f "$HOME/.docker/config.json" ] && cp "$HOME/.docker/config.json" "$ev/.docker/config.json"; }
  for q in .config .cache .local; do [ -e "$HOME/$q" ] && ln -sfn "$HOME/$q" "$ev/$q"; done
  return 0
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
  # AND SYNC IT, so the branch derives its OWN agent projections.
  #
  # A clone drops every binding whose path named the home it came from -- it
  # says so: "27 not inherited ... `skill-manager sync` re-derives this home's
  # own projections". Until that runs, the branched home's .claude is EMPTY,
  # and an eval built on it hands the agent a session with no skills in it at
  # all. This is what makes eval_claude_home possible.
  "$dst/bin/cli/skill-manager" sync >/dev/null 2>&1 || true
  [ -d "$dst/.claude/skills" ] || {
    echo "setup: sync did not derive $dst/.claude/skills -- the eval would run" >&2
    echo "       with no skills projected, which measures nothing" >&2
    return 1; }
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
