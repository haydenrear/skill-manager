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

python3 - "$TRANSCRIPT" "$EV" <<'PYEOF' 2>>"$LOG"
import json, pathlib, re, sys
transcript, ev = sys.argv[1], pathlib.Path(sys.argv[2])

# MATCH THE FORM THE SKILL TEACHES, NOT THE ONE IT DOES NOT.
#
# The first version required a literal `skt ticket new`. The agent wrote what
# git-epic-workflow's SKILL.md actually prescribes:
#
#   SKT="${SKILL_MANAGER_HOME:-$HOME/.skill-manager}/bin/cli/skt"
#   [ -x "$SKT" ] || SKT="$(command -v skt)"
#   "$SKT" ticket new DEMO-1 --base "$(git rev-parse HEAD)" --path ./wt-demo-1
#
# -- a textbook call, scored as a miss, because the grader only knew the
# un-taught spelling. A grader that penalises following the documentation is
# worse than no grader. The program token is now anything: `skt`, a path, or a
# variable expansion.
SEP  = re.compile(r"(?:\|\||&&|[;&|\n])")
VERB = re.compile(r"""(?x)
    ^ (?P<prog>"?\$\{?\w+\}?"? | \S*/?(?:skt|wt) )   # $SKT, "$SKT", /path/skt, skt, wt
      \s+ (?: ticket \s+ new | new ) \b              # `skt ticket new` or `wt new`
      (?P<args> .* ) $
""")
HELP  = re.compile(r"(?:^|\s)(?:--help|-h)(?:\s|$)")
REDIR = re.compile(r"\s*\d?>>?.*$")
# RULE 4: this hook runs UNSANDBOXED AS THE OPERATOR, so nothing from the
# transcript is executed as written. Only these argument shapes replay, and the
# only command substitution allowed is a git rev-parse -- which is how the
# skill teaches you to resolve a base.
SAFE_ARG = re.compile(r"""(?x) ^(?:
      [A-Za-z0-9_./:@=-]+                       # a plain token, flag or path
    | --?[A-Za-z-]+                             # a flag
    | "?\$\(git\s+rev-parse\s+[A-Za-z0-9_/^~-]+\)"?   # a resolved base
)$""")

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

import shlex
hits, unsafe = [], []
for c in cmds:
    for piece in SEP.split(c):
        piece = REDIR.sub("", piece.strip())
        m = VERB.match(piece) if piece else None
        if not m or HELP.search(piece): continue
        raw = m.group("args").strip()
        try: toks = shlex.split(raw, posix=False)
        except ValueError: toks = None
        if toks and all(SAFE_ARG.match(t) for t in toks):
            hits.append(raw)
        else:
            unsafe.append(piece)

(ev / "commands.txt").write_text("\n---\n".join(cmds) + "\n" if cmds else "")
(ev / "front-door-candidates.txt").write_text("\n".join(hits) + "\n" if hits else "")
if unsafe:
    (ev / "front-door-rejected.txt").write_text("\n".join(unsafe) + "\n")
if hits:
    # The LAST one: an agent may probe, correct itself and try again, and the
    # command it settled on is the answer it is giving.
    (ev / "front-door").write_text(hits[-1] + "\n")
print(f"bash calls={len(cmds)} candidates={len(hits)} rejected={len(unsafe)}")
PYEOF

[ -s "$EV/front-door" ] || { say "no front-door command in the transcript; nothing to replay"; exit 0; }
ARGS="$(cat "$EV/front-door")"
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

BEFORE="$(find "$SRC_WS" -maxdepth 2 | sort | shasum | cut -d' ' -f1)"

( cd "$SANDBOX" \
  && export SKILL_MANAGER_HOME="$SANDBOX/.skill-manager" \
  && export PATH="$BUILD/shims:$SANDBOX/.skill-manager/bin/cli:/opt/homebrew/bin:/Library/Developer/CommandLineTools/usr/bin:/usr/bin:/bin" \
  && export TMPDIR="$BUILD/tmp" \
  && eval "\"$SANDBOX/.skill-manager/bin/cli/skt\" ticket new $ARGS" ) >>"$LOG" 2>&1
RC=$?
say "replay exit=$RC"

# ------------------------------------------------------------- the verdicts
# RULE 2: each of these is written only after a real check returns success.
[ "$RC" = "0" ] && echo ok > "$EV/front-door-runs"

# LOOK WHERE BOTH ROUTES PUT IT. With `--path` the worktree is inside the
# sandbox; WITHOUT it `skt ticket new` uses its derived path,
# `<parent>/<repo>-<ticket>` -- a SIBLING of the sandbox. Searching only the
# sandbox scored a real worktree as missing.
WT="$(find "$(dirname "$SANDBOX")" -maxdepth 2 -type d \
        \( -name 'wt-*' -o -name "$(basename "$SANDBOX")-*" \) 2>/dev/null | head -1)"
if [ -n "$WT" ] && [ -e "$WT/.git" ]; then
  echo ok > "$EV/worktree-created"
  say "worktree=$WT"
  [ -d "$WT/.skill-manager/bin/cli" ] && echo ok > "$EV/worktree-has-its-own-home"
fi

AFTER="$(find "$SRC_WS" -maxdepth 2 | sort | shasum | cut -d' ' -f1)"
[ "$BEFORE" = "$AFTER" ] && echo ok > "$EV/source-undamaged" \
  || say "SOURCE CHANGED: $BEFORE -> $AFTER"

say "verdicts: $(ls "$EV" | tr '\n' ' ')"
exit 0                                                          # RULE 3
