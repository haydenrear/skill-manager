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
  # A JAVA THE SANDBOX CAN READ, and this is not a detail.
  #
  # The home's `skill-manager` is a JVM program. macOS's /usr/bin/java exists
  # but is a STUB that prints "Unable to locate a Java Runtime", and the real
  # JDK sits under the operator's home -- which the eval sandbox cannot read.
  # So the pinned CLI could not execute inside a run at all.
  #
  # Measured cost, in the sync case: `skt check --json` came back
  # `cli.state: error`, `skt sync` failed the same way, and the agent -- quite
  # rationally -- spent the next twenty Bash calls rebuilding the currency
  # check by hand from units.lock and `git rev-parse` over skills/*/.git.
  # 28 calls, $1.95, and not one of them was the skill's fault.
  #
  # Homebrew's JDK is outside the operator's home and IS readable.
  local jdk
  for jdk in /opt/homebrew/opt/openjdk/bin /opt/homebrew/opt/openjdk@21/bin \
             /opt/homebrew/opt/openjdk@17/bin; do
    [ -x "$jdk/java" ] && { p="$p:$jdk"; break; }
  done
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
cfg["permissions"]["allow"] = [
    "Bash", "Read", "Write", "Edit", "Skill", "Glob", "Grep",
    # NO WebFetch DOMAIN RULES HERE. They belong in --allow-tools, which is
    # where the eval reads them from -- see the note below eval_claude_home.
    # Listing them in this file did nothing, and keeping them would suggest it
    # was this that opened the network.
]
# NETWORK IS GRANTED BY --allow-tools, NOT BY THIS FILE.
#
# The eval builds its sandbox with `network:{allowedDomains:p}`, and p is
# computed (2.1.263):
#
#   let p = Y(r.flatMap((I)=>{ let q = Fr(I);
#       return q.toolName === Cr && q.ruleContent?.startsWith("domain:")
#              ? [q.ruleContent.slice(7)] : [] }))
#
# with Cr === "WebFetch" and, from the call site $d(h,w,E,p,r,...) against
# $d(e,t,r,...), r === operatorAllowedTools -- the `--allow-tools` list. So the
# domains come from `--allow-tools 'WebFetch(domain:<host>)'` in run.sh, and
# NOTHING in settings.json feeds them: `h` there reads sandbox only from
# ye("policySettings"), which is managed settings.
#
# I REPORTED THIS AS IMPOSSIBLE ONCE, WRONGLY. The probe that "proved" it had
# been REFUSED by the staleness guard -- sources changed, setup not re-run --
# and I read the previous run's kept trace and called it a result. The guard
# was working; the reading was not. Confirm a NEW temp dir before believing a
# probe, which is what `kept temp:` in the run output is for.
#
# Verified, fresh run, first call:
#   git ls-remote https://github.com/haydenrear/skt HEAD
#   f00b724f69d0b8499b0c50cbd017a3ed32a49873    HEAD
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

# eval_path, but for a home that is NOT $build/home.
#
# A home's bin/cli shim BINDS the home it lives in and refuses to honour
# SKILL_MANAGER_HOME -- it says so, by name. So driving the fixture's workspace
# home while $build/home's bin/cli sits earlier on PATH produces:
#
#   skill-manager: refusing to run against a home you did not name.
#     you named:  <workspace>/.skill-manager
#     this shim would have edited: <build>/home
#
# ...and the fixture cannot build its worktree. Correct refusal, wrong PATH.
# The build's shims stay first (they carry TMPDIR for git); only the home
# changes.
eval_path_for_home() {
  local build home p d
  build="$1"; home="$2"
  p="$build/shims:$home/bin/cli"
  # A JAVA THE SANDBOX CAN READ, and this is not a detail.
  #
  # The home's `skill-manager` is a JVM program. macOS's /usr/bin/java exists
  # but is a STUB that prints "Unable to locate a Java Runtime", and the real
  # JDK sits under the operator's home -- which the eval sandbox cannot read.
  # So the pinned CLI could not execute inside a run at all.
  #
  # Measured cost, in the sync case: `skt check --json` came back
  # `cli.state: error`, `skt sync` failed the same way, and the agent -- quite
  # rationally -- spent the next twenty Bash calls rebuilding the currency
  # check by hand from units.lock and `git rev-parse` over skills/*/.git.
  # 28 calls, $1.95, and not one of them was the skill's fault.
  #
  # Homebrew's JDK is outside the operator's home and IS readable.
  local jdk
  for jdk in /opt/homebrew/opt/openjdk/bin /opt/homebrew/opt/openjdk@21/bin \
             /opt/homebrew/opt/openjdk@17/bin; do
    [ -x "$jdk/java" ] && { p="$p:$jdk"; break; }
  done
  for d in /opt/homebrew/bin /usr/local/bin \
           /Library/Developer/CommandLineTools/usr/bin; do
    [ -d "$d" ] && p="$p:$d"
  done
  printf '%s' "$p:/usr/bin:/bin"
}

# THE PATH THE AGENT ACTUALLY GETS, which is not the one setup uses.
#
# eval_path names absolute paths under $BUILD. Setup needs those -- it runs
# outside the sandbox. THE AGENT CANNOT READ ANY OF THEM: `plugin eval`'s
# allowRead is the run's own tree plus the plugin dirs, so `$BUILD/shims` and
# `$BUILD/home/bin/cli` are "Operation not permitted" from inside a run.
#
# Measured, every case, first call:
#
#   $ skt status
#   bash: /private/tmp/skill-evals/<case>/home/bin/cli/skt: Operation not permitted
#
# One wasted call per run before the agent starts looking for the real one --
# across six cases and every rerun, which is the most repeated cost in the
# suite. And with `$BUILD/shims` dead, the git wrapper in it never runs either,
# so git falls through to /usr/bin/git, the xcode-select stub that exits 72.
#
# What the agent CAN reach: its own cwd, which the fixture hook fills with the
# workspace INCLUDING a real `.skill-manager`. So the home's own bin/cli goes
# on PATH RELATIVELY. A relative PATH entry resolves against cwd at exec time,
# and cwd is the workspace -- so `skt` is on PATH for real, from the first
# call, with no absolute path anyone has to guess.
#
# Nothing under $BUILD is on it. If a run needs something, it has to be
# somewhere a run can read.
eval_agent_path() {
  local p d jdk
  # BOTH RELATIVE, and both placed in the workspace by the fixture hook:
  #   .eval-bin            a git the sandbox can actually reach (see the hook)
  #   .skill-manager/bin/cli   the home's own front door
  p=".eval-bin:.skill-manager/bin/cli"
  for jdk in /opt/homebrew/opt/openjdk/bin /opt/homebrew/opt/openjdk@21/bin \
             /opt/homebrew/opt/openjdk@17/bin; do
    [ -x "$jdk/java" ] && { p="$p:$jdk"; break; }
  done
  # CommandLineTools BEFORE /usr/bin: /usr/bin/git is the xcode-select stub.
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
# branch_home <src> <dst> [need_projections]
#
# `need_projections` is only for the home whose .claude the EVAL HOME is built
# from. The fixture workspace also gets a branched home and does NOT need
# agent projections -- the agent's skills come from the eval HOME, not from the
# home sitting in the workspace under test. Guarding both alike failed setup on
# a home that was perfectly correct for its job.
branch_home() {
  local src="$1" dst="$2" need="${3:-no}"
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
  # AND BRING THE UNITS UNDER TEST TO THEIR REPO TIP.
  #
  # The branch is a copy of whatever the SOURCE home happened to hold, and a
  # source home goes stale the moment a unit is fixed. Measured: the root home
  # sat one commit behind skt while two defects the evals had just found were
  # already fixed and pushed -- so a rerun would have measured code without
  # them and reported the fix as ineffective. That is the same "one run of
  # stale bytes" mistake this harness has now paid for three times.
  #
  # Uses EVAL_CLI (the repo's raw build) rather than the branch's own bin/cli
  # shim, which binds the home it lives in and refuses --home.
  local cli unit
  cli="${EVAL_CLI:-$(cd "$(eval_root)/../../.." && pwd)/skill-manager}"
  if [ -x "$cli" ]; then
    for unit in ${EVAL_SYNC_UNITS:-skt git-issue-workflow git-epic-workflow}; do
      "$cli" sync "$unit" --home "$dst" >/dev/null 2>&1 \
        && echo "  synced $unit to tip in $(basename "$(dirname "$dst")")/$(basename "$dst")" >&2 \
        || echo "  note: could not sync $unit into the branch (continuing)" >&2
    done
  else
    echo "  note: no build at $cli — the branch keeps the source home's versions" >&2
  fi
  # AND SYNC IT, so the branch derives its OWN agent projections.
  #
  # A clone drops every binding whose path named the home it came from -- it
  # says so: "27 not inherited ... `skill-manager sync` re-derives this home's
  # own projections". Until that runs, the branched home's .claude is EMPTY,
  # and an eval built on it hands the agent a session with no skills in it at
  # all. This is what makes eval_claude_home possible.
  "$dst/bin/cli/skill-manager" sync >/dev/null 2>&1 || true
  if [ "$need" = "projections" ] && [ ! -d "$dst/.claude/skills" ]; then
    echo "setup: sync did not derive $dst/.claude/skills -- the eval HOME is" >&2
    echo "       built from that directory, so the run would start with no" >&2
    echo "       skills projected, which measures nothing" >&2
    return 1
  fi
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
  for t in git skt skill-manager jbang java; do
    command -v "$t" >/dev/null 2>&1 || {
      echo "setup: '$t' is not on the eval PATH -- the home's CLI shim needs" >&2
      echo "       jbang and java, and skt needs git; fix eval_path" >&2
      return 1; }
  done
  # PRESENT IS NOT THE SAME AS WORKING, and the difference cost $1.95.
  #
  # `command -v skill-manager` finds the shim FILE. Running it needs a JVM, and
  # macOS's /usr/bin/java is a STUB that resolves and then refuses. So the check
  # that passed was "a file exists" while what a run needs is "this program
  # answers" -- and the thing that discovered the gap was an agent, mid-run,
  # spending twenty calls reconstructing a check by hand.
  #
  # This still cannot prove the SANDBOX can reach these: setup runs outside it,
  # with different read permissions. That limit stands. It does prove the
  # curated PATH names a CLI that executes.
  local ran
  ran="$(skill-manager --version 2>&1)" || {
    echo "setup: the home's CLI is on PATH but does not RUN:" >&2
    printf '%s\n' "$ran" | sed 's/^/         /' >&2
    return 1; }
  # A SIBLING path: `skt ticket new` now refuses an inside-the-repo worktree,
  # because `ticket close` searches the repository's PARENT and could never
  # find one. The probe uses the shape the product supports.
  out="$(cd "$ws" && skt ticket new ENVPROBE --path ../.envprobe 2>&1)" || {
    echo "setup: the front door does not work in this environment. A run would" >&2
    echo "       measure that, not the skill. skt said:" >&2
    printf '%s\n' "$out" | sed 's/^/         /' >&2
    return 1; }
  ( cd "$ws" && skt ticket close ENVPROBE >/dev/null 2>&1 || true
    rm -rf "$(dirname "$ws")/.envprobe" )
  echo "verified: skt ticket new works in this environment"
}

# A RUN MUST NOT MEASURE A STALE BUILD.
#
# setup.sh copies units-template/ and the case dir into $BUILD; run.sh then
# runs whatever is there. Edit a hook or a grader without re-running setup and
# the next run silently measures the OLD one -- which happened, cost $1.15, and
# produced a verifier failure that had already been fixed on disk. Same shape
# as a patch applied without asserting its anchor, which this session has now
# paid for four times.
#
# setup stamps what it copied; run refuses if the sources have moved since.
eval_sources_digest() {
  local root; root="$(eval_root)"
  { find "$root/units-template" "$root/evals" -type f -exec shasum {} + 2>/dev/null | sort; \
    shasum "$root/lib.sh" 2>/dev/null; } | shasum | cut -d' ' -f1
}

eval_stamp_sources() { eval_sources_digest > "$1/.sources"; }

eval_require_fresh() {
  local build="$1" now was
  now="$(eval_sources_digest)"; was="$(cat "$build/.sources" 2>/dev/null || echo none)"
  [ "$now" = "$was" ] && return 0
  echo "run: the harness sources changed since setup.sh built $build." >&2
  echo "     A run now would measure the OLD copy. Re-run ./setup.sh first." >&2
  echo "     (stamped $was, sources $now)" >&2
  return 1
}

# ---------------------------------------------------------------------------
# THE SHARED SETUP. Everything every case needs, so a case's setup.sh is its
# FIXTURE and nothing else.
#
# Factored out after the first case, not before it: the epic case was written
# whole, run sixteen times, and the parts that turned out to be common are the
# parts that are here. Guessing at this shape up front is how a harness grows
# options nobody uses.
#
# A case supplies `build_fixture <build> <src-home>` and calls eval_build_case.
eval_build_case() {
  local build src root
  build="$1"; src="$2"; root="$(eval_root)"

  # ROOM TO BUILD, CHECKED BEFORE BUILDING. A case is a ~5 GB home clone, and
  # a sweep builds one per case while the previous ones are still on disk.
  # Reaching ENOSPC does not fail a run politely: the harness writes every
  # command's output to a file, so once the disk is full NO command can run at
  # all -- not `df`, not `rm`. The recovery is manual and it is the operator's.
  #
  # This repo's own memory note says to measure worktree cost with FREE SPACE
  # rather than `du`, because copy-on-write clones lie to `du`. That is what
  # this does.
  local free_gb
  free_gb="$(df -g "$(dirname "$build")" 2>/dev/null | awk 'NR==2{print $4}')"
  if [ -n "$free_gb" ] && [ "$free_gb" -lt "${EVAL_MIN_FREE_GB:-25}" ]; then
    echo "setup: ${free_gb}G free, need ${EVAL_MIN_FREE_GB:-25}G — a case is a ~5G home clone" >&2
    echo "       and a full disk stops every command, not just this one." >&2
    echo "  free it:  rm -rf $(dirname "$build")/*  /private/tmp/e-*" >&2
    echo "            (the second are --keep sandboxes; chmod -R u+w them first)" >&2
    return 1
  fi
  rm -rf "$build"; mkdir -p "$build" "$(eval_tmpdir "$build")"
  branch_home "$src" "$build/home" projections

  # ONE WRAPPER PER UNIT, so every case loads EVERY unit in the home. The thing
  # under test is retrieval AMONG the skills; handing a case only the units its
  # task needs does the retrieval for the agent and overfits disclosure.
  mkdir -p "$build/units"
  local d u p pn c
  for d in "$build"/home/skills/*/; do
    [ -d "$d" ] || continue; u="$(basename "$d")"
    mkdir -p "$build/units/$u/.claude-plugin" "$build/units/$u/skills"
    printf '{"name":"%s","version":"0.1.0","description":"live unit from the branched home"}\n' \
      "$u" > "$build/units/$u/.claude-plugin/plugin.json"
    ln -sfn "$d" "$build/units/$u/skills/$u"
  done
  for p in "$build"/home/plugins/*/; do
    [ -d "$p" ] || continue; pn="$(basename "$p")"
    mkdir -p "$build/units/$pn/.claude-plugin" "$build/units/$pn/skills"
    printf '{"name":"%s","version":"0.1.0","description":"live plugin from the branched home"}\n' \
      "$pn" > "$build/units/$pn/.claude-plugin/plugin.json"
    for c in "$p"skills/*/; do [ -d "$c" ] && ln -sfn "$c" "$build/units/$pn/skills/$(basename "$c")"; done
  done
  cp -R "$root/units-template/." "$build/units/"

  # PATH and TMPDIR as SESSION ENV, so the agent inherits them rather than
  # discovering them.
  # THE AGENT'S ENVIRONMENT. Three deliberate differences from setup's:
  #
  #   PATH   eval_agent_path -- nothing under $BUILD, because a run cannot read
  #          it. The home's bin/cli is RELATIVE and resolves against cwd.
  #   TMPDIR NOT SET. Overriding it with $BUILD/tmp pointed git's xcrun cache at
  #          a directory the sandbox permits neither reads nor writes to; the
  #          run's own TMPDIR is inside allowWrite and already correct.
  #   XCRUN_NO_CACHE  set here instead, which is what the git shim carried and
  #          the only reason that shim had to be on PATH at all.
  #
  # SKILL_MANAGER_HOME is also gone: it named $BUILD/home, which is unreadable,
  # and the home's shim binds the home it lives in anyway -- `skt status` from
  # the workspace resolves the right home with no variable at all.
  cat > "$build/units/toolchain/.claude-plugin/settings.json" <<JSON
{ "env": { "PATH": "$(eval_agent_path)",
           "XCRUN_NO_CACHE": "1" } }
JSON

  # A `git` SHIM carrying TMPDIR and XCRUN_NO_CACHE, because PATH is the only
  # variable that reaches the sandbox and Apple's git writes an xcrun cache
  # before it does anything. Putting the environment INSIDE something on PATH
  # is deterministic; telling the agent to export it is advice it takes after
  # the first failure.
  mkdir -p "$build/shims"
  local real_git; real_git="$(command -v git)"
  cat > "$build/shims/git" <<GITSHIM
#!/usr/bin/env bash
export TMPDIR="$(eval_tmpdir "$build")"
export XCRUN_NO_CACHE=1
export SKILL_MANAGER_HOME="$build/home"
exec "$real_git" "\$@"
GITSHIM
  chmod +x "$build/shims/git"
}

# A git checkout with a REAL branched home in it, which is what most fixtures
# are. `branch` is checked out at the end; the agent dirs are gitignored the
# way a real project gitignores them (without that, `skill-manager sync` makes
# the tree dirty and every worktree command refuses on a clean-slate check).
eval_fixture_checkout() {
  local build src ws branch git
  build="$1"; src="$2"; ws="$3"; branch="${4:-main}"
  mkdir -p "$ws"
  git="$(PATH="$(eval_path "$build/home")" command -v git)"
  ( cd "$ws" && export TMPDIR="$(eval_tmpdir "$build")" && "$git" init -q . \
    && "$git" config user.email eval@example.invalid && "$git" config user.name eval \
    && printf 'demo project\n' > README.md \
    && printf '.skill-manager/\n.claude/\n.codex/\n.gemini/\n' > .gitignore \
    && "$git" add -A && "$git" commit -qm initial && "$git" checkout -q -B "$branch" )
}

# Regenerate every case with THIS machine's unit list, then build the agent
# home and stamp the sources. The last thing a setup.sh does.
eval_finish_case() {
  local build case_name root list c
  build="$1"; case_name="$2"; root="$(eval_root)"
  rm -rf "$build/evals"; cp -R "$root/evals" "$build/evals"
  list=$(cd "$build/units" && ls | sed 's|^|  - ../../units/|')
  for c in "$build"/evals/*/case.yaml; do
    python3 "$root/rewrite-case.py" "$c" "$list" "$(eval_path "$build/home")" \
            "$(eval_tmpdir "$build")" "$build/home"
  done
  eval_claude_home "$build" "$root/.evalhome-$case_name"
  echo "home:  $build/home (branched for this eval)"
  echo "units: $(ls "$build/units" | wc -l | tr -d ' ')"
  eval_stamp_sources "$build"
}

# The run command every case uses. Differences that matter are the grants.
eval_run_case() {
  local build case_name root claude keep=0
  build="$1"; case_name="$2"; shift 2
  root="$(eval_root)"
  [ -d "$build/units" ] || { echo "run ./setup.sh first" >&2; return 1; }
  eval_require_fresh "$build" || return 1
  # THE LAUNCHER's PATH must still find `claude` -- resolve it BEFORE the
  # override. Overriding first resolved an older claude that answered
  # "unknown command 'eval' (Did you mean enable?)".
  claude="$(command -v claude)" || { echo "no claude on PATH" >&2; return 1; }
  [ "${1:-}" = "--keep" ] && { keep=1; shift; }
  # THE AGENT INHERITS WHAT THIS EXPORTS, which is the thing that actually
  # decides its PATH -- the toolchain plugin's settings.json env does not
  # override it. Measured: a probe with eval_agent_path in settings.json still
  # resolved `skt` to $BUILD/home/bin/cli and got "Operation not permitted",
  # because run.sh had exported the absolute path first.
  #
  # So the AGENT path is exported here (nothing under $BUILD, which a run
  # cannot read; the home's bin/cli relative, resolving against the workspace
  # cwd), and TMPDIR is left alone -- the run's own is inside allowWrite, and
  # pointing it at $BUILD/tmp is what made git's xcrun cache unwritable.
  export XCRUN_NO_CACHE=1
  export PATH="$(eval_agent_path)"   # COMPLETE, never "$(...):$PATH"
  # ARCHIVE THE RESULT WHERE THE SCORECARD CAN READ IT.
  #
  # A run writes aggregate-result.json under $BUILD, which run.sh then tears
  # down -- so every score this suite has ever produced lived only in a
  # terminal and in prose. `scripts/measure_goals.py` is this repo's scorecard
  # and it reads harnesses, not narratives; nothing could join the two.
  #
  # One JSON per case, committed, newest wins. That is what makes an eval an
  # instrument rather than an anecdote.
  eval_archive_result() {
    local b="$1" c="$2" newest dest
    newest="$(ls -td "$b"/evals/results/*/ 2>/dev/null | head -1)"
    [ -n "$newest" ] && [ -f "$newest/aggregate-result.json" ] || return 0
    dest="$(eval_root)/../results/runs/$c"
    mkdir -p "$dest"
    cp "$newest/aggregate-result.json" "$dest/$(basename "${newest%/}").json"
    echo "archived: specs/evals/results/runs/$c/$(basename "${newest%/}").json"
    # AND THE DIAGNOSTICS, when the verifier wrote any. A score says a case
    # failed; this says WHY, and it is the difference between diagnosing from
    # a 40 KB text file and diagnosing from a 5 GB kept sandbox that nothing
    # expires (EV-I-23). Only written on a red, so a green run archives one
    # small JSON as before.
    local diag
    for diag in "$b"/eval-diagnostics-*; do
      [ -d "$diag" ] || continue
      mkdir -p "$dest/diagnostics"
      cp "$diag"/WHY-NO-FRONT-DOOR.txt "$dest/diagnostics/$(basename "${newest%/}")-$(basename "$diag").txt" 2>/dev/null \
        && echo "  diagnostics: why the front door was not recognised"
    done
  }
  trap 'eval_archive_result "'"$build"'" "'"$case_name"'"; [ '"$keep"' = 1 ] && echo "kept: '"$build"'" || { rm -rf "'"$build"'" "'"$root"'/.evalhome-'"$case_name"'"; echo "torn down"; }' EXIT

  ( cd "$build" && HOME="$root/.evalhome-$case_name" CLAUDE_CODE_WALNUT_SPIRE=1 \
      "$claude" plugin eval . --case "$case_name" --ablation none \
        --runs "${EVAL_RUNS:-1}" \
        --keep-temp --max-cost-usd 2 \
        --allow-tools Bash 'Bash(skt:*)' 'Bash(git:*)' 'Bash(skill-manager:*)' \
          'Bash(python3:*)' Read Write Edit Skill \
          'WebFetch(domain:github.com)' 'WebFetch(domain:codeload.github.com)' \
          'WebFetch(domain:objects.githubusercontent.com)' "$@" )
}
