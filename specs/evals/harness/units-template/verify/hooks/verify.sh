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

python3 - "$TRANSCRIPT" "$EV" "$VERB_RE" "${PROG_RE:-}" <<'PYEOF' 2>>"$LOG"
import json, pathlib, re, shlex, sys
transcript, ev = sys.argv[1], pathlib.Path(sys.argv[2])
verb_re, prog_re = sys.argv[3], sys.argv[4] or r"skt|wt|skill-manager|bootstrap-home\.sh"

# MATCH THE PROGRAM BY ITS BASENAME, NOT BY THE SHAPE OF ITS PATH.
#
# Three times now this grader has missed a CORRECT command because it only knew
# one spelling, and each time the spelling it missed was the one the skill
# actually teaches:
#
#   skt ticket new …                                    (literal -- matched)
#   "$SKT" ticket new …                                 (missed: variable)
#   "${SKILL_MANAGER_HOME:-$HOME/.skill-manager}/skills/…/bootstrap-home.sh"
#                                                       (missed: quoted path
#                                                        with an expansion in it)
#
# A grader that penalises following the documentation is worse than no grader,
# so the rule is now structural: take the first word of the segment, strip
# quotes, take whatever follows the last "/", and ask whether THAT is a program
# we care about. Every spelling of the path in front of it becomes irrelevant,
# which is the only way this stops recurring.
SEP   = re.compile(r"(?:\|\||&&|[;&|\n])")
PROG  = re.compile(r"^(?:" + prog_re + r")$")
VERB  = re.compile(r"^(?:" + verb_re + r")\b") if verb_re else None
HELP  = re.compile(r"(?:^|\s)(?:--help|-h)(?:\s|$)")
REDIR = re.compile(r"\s*\d?>>?.*$")
# RULE 4: this hook runs UNSANDBOXED AS THE OPERATOR, so nothing from the
# transcript executes as written. Only these argument shapes replay, and the
# only command substitution allowed is a git rev-parse -- which is how the
# skill teaches you to resolve a base.
SAFE_ARG = re.compile(r"""(?x) ^(?:
      [A-Za-z0-9_./:@=-]+
    | --?[A-Za-z-]+
    | "?\$\(git\s+rev-parse\s+[A-Za-z0-9_/^~-]+\)"?
)$""")

VAR_ONLY = re.compile(r'^"?\$\{?\w+\}?"?$')

def basename_of(tok):
    """The program name, with every quote and path segment stripped away.

    Quotes are removed from ANYWHERE in the word, not just its ends: the shell
    lets them close mid-word (`"$HOME"/bin/thing`), and a strip() only reaches
    the outside."""
    tok = tok.replace('"', "").replace("'", "").strip()
    return tok.rsplit("/", 1)[-1]

def is_program(tok):
    """The first word names a program we care about.

    Two accepted shapes, and the second is not laxity. `SKT="…/bin/cli/skt";
    "$SKT" ticket new …` is exactly what git-epic-workflow's SKILL.md
    prescribes, and the variable hides the basename by design. A bare variable
    is admitted only because the VERB is checked next -- `"$X" ticket new` is
    unambiguous, and `"$X" status` matches nothing.
    """
    return bool(PROG.match(basename_of(tok))) or bool(VAR_ONLY.match(tok.strip()))

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
    for piece in SEP.split(c):
        piece = REDIR.sub("", piece.strip())
        if not piece or HELP.search(piece): continue
        # WHITESPACE FIELDS, NOT shlex. This is the fourth extraction defect in
        # this file and they share one cause: shell text is not a token stream.
        # `shlex.split(posix=False)` SPLITS
        #     "${SKILL_MANAGER_HOME:-$HOME/.skill-manager}"/skills/…/bootstrap-home.sh
        # into two words at the closing quote, so the program's basename came
        # out as `.skill-manager}` and a correct command scored as a miss --
        # again. A plain split keeps a concatenated word whole, which is what
        # the shell does with it.
        words = piece.split()
        if not words or not is_program(words[0]): continue
        rest = words[1:]
        if VERB is None and VAR_ONLY.match(words[0].strip()):
            continue   # nothing would distinguish `"$X" --root …` from any other tool
        if VERB:
            joined = " ".join(rest)
            m = VERB.match(joined)
            if not m: continue
            tail = joined[m.end():].strip()
            rest = tail.split() if tail else []
        if all(SAFE_ARG.match(t.replace('"', "").replace("'", "")) or SAFE_ARG.match(t)
               for t in rest):
            hits.append(" ".join(rest))
        else:
            unsafe.append(piece)

(ev / "commands.txt").write_text("\n---\n".join(cmds) + "\n" if cmds else "")
(ev / "front-door-candidates.txt").write_text("\n".join(hits) + "\n" if hits else "")
if unsafe:
    (ev / "front-door-rejected.txt").write_text("\n".join(unsafe) + "\n")
if hits:
    (ev / "front-door").write_text(hits[-1] + "\n")
print(f"bash calls={len(cmds)} candidates={len(hits)} rejected={len(unsafe)}")
PYEOF

[ -s "$EV/front-door" ] || { say "no front-door command in the transcript; nothing to replay"; exit 0; }
ARGS="$(cat "$EV/front-door")"
if [ "$REPLAY" != "yes" ]; then
  say "REPLAY=no for this case: the command was recorded, not re-issued"
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
tar -C "$SRC_WS" --exclude=.skill-manager -cf - . 2>>"$LOG" \
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
