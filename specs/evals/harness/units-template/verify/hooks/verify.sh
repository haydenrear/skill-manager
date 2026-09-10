#!/usr/bin/env bash
# THE VERDICT WRITER. Runs on Stop, OUTSIDE the Bash sandbox -- which is the
# only reason it exists. Measured, one run, one workspace: this hook wrote
# `.eval/hook-wrote-this` while the agent's `touch ./probe-write` in the same
# directory returned "Operation not permitted". `plugin eval` forbids the Bash
# tool to write at cwd, $TMPDIR, $HOME and under .git, so a grader that reads a
# file the AGENT was told to create can only ever be red.
#
# Four rules from plugin_evals.md, and they are what keep this sound:
#   1. delete every verdict path BEFORE looking -- the agent has Write and can
#      plant any filename it can guess;
#   2. write a verdict only after a real program returns success;
#   3. exit 0 ALWAYS -- a Stop hook that exits non-zero can push the session
#      onward, and a verifier that changes the run it measures is not one;
#   4. run agent-authored code with no write anywhere, and write the verdict
#      yourself.
#
# RULE 4 IS WHY THIS DOES NOT EXEC WHAT THE AGENT TYPED. This hook runs
# unsandboxed AS THE OPERATOR: replaying arbitrary transcript text here would
# hand an eval subject a shell on the real machine. It replays only a command
# matching FRONT_DOOR_RE, and only inside a throwaway copy under $TMPDIR.
# Anything else is recorded and NOT run.
set -uo pipefail

CWD="${CLAUDE_PROJECT_DIR:-$PWD}"
EV="$CWD/.eval"
rm -rf "$EV"; mkdir -p "$EV"                                   # RULE 1

# Stop hooks receive their context as JSON on stdin.
STDIN_JSON="$(cat 2>/dev/null || true)"
printf '%s' "$STDIN_JSON" > "$EV/hook-stdin.json"

BUILD="$(cd "${CLAUDE_PLUGIN_ROOT}/../.." && pwd)"
SRC_WS="$BUILD/fixture-workspace"
LOG="$EV/verify.log"
exec 3>>"$LOG"
say() { printf '%s\n' "$*" >&3; }

# ONE HOOK, EVERY CASE. Each case ships a front-door.conf naming the command it
# is about; without that this file would fork per case and the four extraction
# defects fixed here would have to be fixed five more times.
#
#   VERB_RE   what counts as the front door, as a shell-word regex
#   REPLAY    yes -> re-issue the arguments for real in a throwaway copy
#   EXPECT    worktree | none -- what to look for afterwards
VERB_RE='(?:ticket\s+new|new)'
REPLAY=yes
EXPECT=worktree
# WHICH CASE IS THIS? Not from EVAL_CASE -- measured, it is not in the hook's
# environment or its stdin (whose keys are cwd, hook_event_name, permission_mode,
# session_id, stop_hook_active, transcript_path and friends). With it unset the
# conf path was `…/evals//front-door.conf`, nothing loaded, and every case
# silently ran the DEFAULT front door -- so the bootstrap case looked for
# `ticket new` and scored a correct answer as a miss.
#
# The build directory is named for the case, which is a fact about how setup.sh
# lays things out rather than about an interface that may or may not be
# populated.
EVAL_CASE_NAME="$(basename "$BUILD")"
CONF="$BUILD/evals/$EVAL_CASE_NAME/front-door.conf"
if [ -f "$CONF" ]; then . "$CONF"; else
  say "no front-door.conf for $EVAL_CASE_NAME -- using defaults, which is"
  say "probably wrong for any case that is not about \`ticket new\`"
fi
say "== verify: $(date -u +%FT%TZ)"
say "cwd=$CWD build=$BUILD"

# ---------------------------------------------------------------- the command
# What did the agent actually TRY? Taken from the transcript's tool_use inputs
# rather than from its prose: the prose is what the agent says it did, and the
# whole reason this file exists is that a plausible narration and a real result
# are different things.
TRANSCRIPT="$(printf '%s' "$STDIN_JSON" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("transcript_path") or "")
except Exception: print("")' 2>/dev/null)"
say "transcript=$TRANSCRIPT"

# ONE IMPLEMENTATION, NOT TWO. The extraction rules used to live inline here,
# where the only way to test them was a live eval run: ~$1.25 and a 5 GB
# sandbox to read afterwards. SIX of this suite's findings are bugs in those
# forty lines and every one of them is decidable against a string, so they
# moved to lib/front_door.py with a corpus of commands agents really issued:
#
#     python3 units/verify/lib/front_door.py --self-test
#
# A copy of the rules here would drift from the copy the tests exercise, which
# is the failure this import exists to prevent.
python3 - "$TRANSCRIPT" "$EV" "$VERB_RE" "${PROG_RE:-}" "$CLAUDE_PLUGIN_ROOT/lib" <<'PYEOF' >>"$LOG" 2>&1
import json, pathlib, sys
transcript, ev = sys.argv[1], pathlib.Path(sys.argv[2])
verb_re, prog_re = sys.argv[3], sys.argv[4] or None
sys.path.insert(0, sys.argv[5])
import front_door

kw = {"verb_re": verb_re}
if prog_re: kw["prog_re"] = prog_re

# THE COMMANDS THE AGENT TRIED, from tool_use inputs and not from its prose.
# The prose is what it says it did, and this file exists because a plausible
# narration and a real result are different things.
cmds = []
if transcript and pathlib.Path(transcript).exists():
    for line in pathlib.Path(transcript).read_text(errors="replace").splitlines():
        try: e = json.loads(line)
        except Exception: continue
        content = (e.get("message") or {}).get("content")
        for b in content if isinstance(content, list) else []:
            if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") == "Bash":
                c = (b.get("input") or {}).get("command")
                if c: cmds.append(c)

hits, unsafe = [], []
for c in cmds:
    h, r = front_door.analyze(c, **kw)
    hits += h; unsafe += r

(ev / "commands.txt").write_text("\n---\n".join(cmds) + "\n" if cmds else "")
(ev / "front-door-candidates.txt").write_text("\n".join(hits) + "\n" if hits else "")
if unsafe:
    (ev / "front-door-rejected.txt").write_text("\n".join(unsafe) + "\n")
if hits:
    # LAST, not first: an agent that probes and then provisions has issued
    # both, and the one to replay is the one it settled on.
    (ev / "front-door").write_text(hits[-1] + "\n")
print(f"bash calls={len(cmds)} candidates={len(hits)} rejected={len(unsafe)}")
PYEOF

# A RED THAT EXPLAINS ITSELF. This early exit is why one failure reads as
# four: front-door, front-door-runs, worktree-has-its-own-home and
# source-undamaged are all unwritten below it, so ONE unrecognised command
# scores 0.17 and looks like a collapse.
#
# The graders still go red -- they should, nothing was verified -- but the
# reason is now recorded beside them instead of living only in a 5 GB kept
# sandbox. Diagnosing this case has cost three sweeps and ~$4 precisely
# because a red carried no information.
if [ ! -s "$EV/front-door" ]; then
  {
    echo "NO FRONT-DOOR COMMAND RECOGNISED."
    echo "VERB_RE=$VERB_RE  PROG_RE=${PROG_RE:-<default>}"
    echo
    echo "The graders below this point are red because nothing was replayed,"
    echo "NOT because each is a separate finding:"
    echo "  front-door, front-door-runs, worktree-has-its-own-home, source-undamaged"
    echo
    echo "--- every Bash command the agent ran, which is what to match against ---"
    cat "$EV/commands.txt" 2>/dev/null
    echo "--- pieces that looked like the front door but failed the arg allowlist ---"
    cat "$EV/front-door-rejected.txt" 2>/dev/null || echo "(none)"
  } > "$EV/WHY-NO-FRONT-DOOR.txt"
  say "no front-door command recognised — see .eval/WHY-NO-FRONT-DOOR.txt"
  cp -R "$EV" "$BUILD/eval-diagnostics-$(date -u +%H%M%S)" 2>/dev/null || true
  exit 0
fi
ARGS="$(cat "$EV/front-door")"
if [ "$REPLAY" != "yes" ]; then
  # NOTHING WAS REPLAYED, SO NOTHING WAS TOUCHED -- and that verdict has to be
  # WRITTEN, not merely true. `nothing-outside-the-sandbox-was-touched` reads
  # a file; exiting before writing it made that grader GUARANTEED RED on every
  # REPLAY=no case, which cost syncs-a-stale-home-from-root and
  # reconciles-a-worktree-into-the-project-home a grader each, permanently.
  #
  # A grader that cannot pass is worse than a missing one: it reads as a
  # finding about the skill on every run, and it is a fact about this hook.
  echo ok > "$EV/source-undamaged"
  say "REPLAY=no for this case: the command was recorded, not re-issued;"
  say "source-undamaged is trivially true because nothing ran against it"
  exit 0
fi
say "replaying args: $ARGS"

# --------------------------------------------------------------- the replay
# ISOLATED BY CONSTRUCTION. A throwaway copy of the fixture under the build's
# own tmp, its own SKILL_MANAGER_HOME, removed on every exit path. The hook is
# unsandboxed, so this containment is the only thing between an eval subject
# and the operator's real tree.
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/eval-verify-XXXXXX")" || { say "mktemp failed"; exit 0; }
trap 'rm -rf "$SANDBOX"' EXIT                                   # RULE 3 companion
if [ ! -d "$SRC_WS/.git" ]; then say "no fixture at $SRC_WS -- cannot replay"; exit 0; fi
# COPY THE CHECKOUT WITH cp, AND THE HOME WITH `home clone`. They are not the
# same operation, and using cp for both broke this twice:
#
#   * unrepaired -- the copy's shims still named the source home and the
#     worktree clone refused with 9 x FOREIGN_PATH_IN_SHIM. Correctly.
#   * cp + `home repair --fix` -- shims clean, "69 of 69 repaired", and
#     `skt ticket new` still failed its projection step. The copied home had 0
#     installed records against the source's 52: repair fixes what a home
#     POINTS AT, it does not reconstruct what a home HOLDS.
#
# The control that makes this the harness's defect and not the product's:
# verify_env runs the same `skt ticket new` against a properly branched home at
# setup, and it passes. `home clone` is the product's own operation for moving
# a home; it is copy-on-write and it re-anchors as it goes.
# EXCLUDE THE FIXTURE'S OWN WORKTREES. A git worktree's `.git` is a FILE
# pointing into the parent repo's .git/worktrees/, which a copy does not carry,
# so a copied `wt-*` arrives as a pile of UNTRACKED files -- and the next
# `skt ticket new` refuses with "working tree is not clean — an epic worktree
# pins its base from a clean slate". Correct refusal about a mess the copy
# made. The sandbox builds its own worktree with REPLAY_PRE instead.
tar -C "$SRC_WS" --exclude=.skill-manager --exclude='./wt-*' -cf - . 2>>"$LOG" \
  | tar -C "$SANDBOX" -xf - 2>>"$LOG" \
  || { say "checkout copy failed"; exit 0; }
if [ -d "$SRC_WS/.skill-manager/bin/cli" ]; then
  "$SRC_WS/.skill-manager/bin/cli/skill-manager" home clone \
      --from "$SRC_WS/.skill-manager" --to "$SANDBOX/.skill-manager" >>"$LOG" 2>&1 \
    && say "cloned the home into the sandbox" \
    || { say "home clone into the sandbox FAILED -- see above; not replaying"; exit 0; }
fi

# RELOCATE THE RUN'S OWN PATHS ONTO THE COPY. An agent that passes an absolute
# path (`--root /private/tmp/e-XXXX/home/cwd`) is being correct about its own
# session; replaying that verbatim would act on the run's tree instead of the
# throwaway one, or on nothing at all.
ARGS="${ARGS//$CWD/$SANDBOX}"

# WHICH BINARY REPLAYS. Normally the copy's own, so the replay exercises the
# home under test. A bootstrap case has no home in its fixture -- that is the
# point of it -- so there is nothing in the copy to run and the eval home's
# build stands in.
# THE SOURCE HOME FOR THE REPLAY. Normally the copy's own -- the replay should
# exercise the home under test. A BOOTSTRAP case has no home in its fixture,
# which is the entire point of it, so pointing SKILL_MANAGER_HOME at the copy
# makes the command fail with "source home does not exist" for a reason that
# has nothing to do with the agent. There, the source is the eval home and the
# sandbox is the DESTINATION.
case "${REPLAY_HOME_KIND:-copy}" in
  eval-home) REPLAY_HOME="$BUILD/home" ;;
  *)         REPLAY_HOME="$SANDBOX/.skill-manager" ;;
esac
REPLAY_REL="${REPLAY_EXE_REL:-bin/cli/${REPLAY_BIN:-skt}}"
REPLAY_EXE="$SANDBOX/.skill-manager/$REPLAY_REL"
[ -x "$REPLAY_EXE" ] || REPLAY_EXE="$BUILD/home/$REPLAY_REL"
[ -x "$REPLAY_EXE" ] || { say "no $REPLAY_BIN to replay with"; exit 0; }
say "replay binary: $REPLAY_EXE"

# STATE THE VERB NEEDS, BUILT IN THE SANDBOX BY THE FRONT DOOR ITSELF.
#
# A `close` case has to have something to close. Copying the fixture's worktree
# does not carry one: a git worktree's `.git` is a FILE pointing into the parent
# repo's .git/worktrees/, and `skt ticket close` resolves a ticket by searching
# git's worktree list -- so in a copied checkout it correctly reports
#
#     error: no worktree for ticket TICKET-7 … and nothing named '*-TICKET-7'
#
# and the replay measures the copy rather than the command. So the sandbox
# builds the precondition with the product's own front door, and the agent's
# arguments are then replayed against real state.
if [ -n "${REPLAY_PRE:-}" ]; then
  ( cd "$SANDBOX" \
    && export SKILL_MANAGER_HOME="${REPLAY_HOME:-$SANDBOX/.skill-manager}" \
    && export PATH="$BUILD/shims:$SANDBOX/.skill-manager/bin/cli:/opt/homebrew/bin:/Library/Developer/CommandLineTools/usr/bin:/usr/bin:/bin" \
    && export TMPDIR="$BUILD/tmp" \
    && eval "$REPLAY_PRE" ) >>"$LOG" 2>&1 \
    && say "precondition built: $REPLAY_PRE" \
    || { say "PRECONDITION FAILED ($REPLAY_PRE) -- not replaying; a red here is"
         say "the harness's, not the agent's"; exit 0; }
fi

BEFORE="$(find "$SRC_WS" -maxdepth 2 | sort | shasum | cut -d' ' -f1)"

( cd "$SANDBOX" \
  && export SKILL_MANAGER_HOME="${REPLAY_HOME:-$SANDBOX/.skill-manager}" \
  && export PATH="$BUILD/shims:$SANDBOX/.skill-manager/bin/cli:/opt/homebrew/bin:/Library/Developer/CommandLineTools/usr/bin:/usr/bin:/bin" \
  && export TMPDIR="$BUILD/tmp" \
  && eval "\"$REPLAY_EXE\" ${REPLAY_VERB-ticket new} $ARGS" ) >>"$LOG" 2>&1
RC=$?
say "replay exit=$RC"

# ------------------------------------------------------------- the verdicts
# RULE 2: each of these is written only after a real check returns success.
[ "$RC" = "0" ] && echo ok > "$EV/front-door-runs"

# LOOK WHERE BOTH ROUTES PUT IT. With `--path` the worktree is inside the
# sandbox; WITHOUT it `skt ticket new` uses its derived path,
# `<parent>/<repo>-<ticket>` -- a SIBLING of the sandbox. Searching only the
# sandbox scored a real worktree as missing.
case "$EXPECT" in
worktree-gone)
  # CLOSING. The gate is the point: `wt close` / `skt ticket close` REFUSE
  # while removing the worktree would destroy unpublished work, so exit 0 and
  # an absent worktree together are the verdict -- either alone is not.
  if [ "$RC" = "0" ] && [ ! -d "$SANDBOX/$WT_NAME" ]; then
    echo ok > "$EV/worktree-removed"
  fi
  ;;
home)
  # BOOTSTRAPPING. A home-SHAPED directory is not a home: an earlier fixture
  # made one out of mkdir and a stub json, and it was worse than none because
  # it invited the real command and then failed it. bin/cli is the cheapest
  # thing only a real bootstrap produces.
  [ -d "$SANDBOX/.skill-manager/bin/cli" ] && [ -d "$SANDBOX/.skill-manager/installed" ] \
    && echo ok > "$EV/home-exists"
  ;;
worktree)
WT="$(find "$(dirname "$SANDBOX")" -maxdepth 2 -type d \
        \( -name 'wt-*' -o -name "$(basename "$SANDBOX")-*" \) 2>/dev/null | head -1)"
if [ -n "$WT" ] && [ -e "$WT/.git" ]; then
  echo ok > "$EV/worktree-created"
  say "worktree=$WT"
  [ -d "$WT/.skill-manager/bin/cli" ] && echo ok > "$EV/worktree-has-its-own-home"
fi
  ;;
esac

AFTER="$(find "$SRC_WS" -maxdepth 2 | sort | shasum | cut -d' ' -f1)"
[ "$BEFORE" = "$AFTER" ] && echo ok > "$EV/source-undamaged" \
  || say "SOURCE CHANGED: $BEFORE -> $AFTER"

say "verdicts: $(ls "$EV" | tr '\n' ' ')"
exit 0                                                          # RULE 3
