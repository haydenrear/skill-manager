#!/usr/bin/env python3
"""Which Bash command in a transcript was the front door, and with what args.

EXTRACTED FROM THE STOP HOOK, and the reason is a count. SIX of this suite's
findings are bugs in these forty lines, and every one was found by a live eval
run at ~$1.25 with a 5 GB sandbox to read afterwards:

  EV-I-02  required a literal `skt` while the docs teach `"$SKT"`
           captured its own `;` separator into the path
           graded a `--help` probe as the provisioning command
           shlex.split(posix=False) splits `"${VAR}"/path/thing` at the quote
  EV-I-21  .split() breaks `--base "$(git rev-parse HEAD)"` into four fields

All six are decidable offline against a string. This module is that, and
CORPUS below is the regression suite — every entry a command an agent actually
issued in a run of this suite, not a shape someone imagined.

    python3 front_door.py --self-test

RUNS ON /usr/bin/python3, WHICH IS 3.9 HERE. The interactive shell's python3
is 3.14; a hook's is the system one, and `re.Pattern | None` in a signature is
a TypeError at import on 3.9 — the module fails to load entirely and every
grader below it goes red for a reason with nothing to do with the run. That
is the fifth time this suite has produced a red that was purely instrumental.
The future import makes annotations strings, so they are never evaluated.
"""
from __future__ import annotations

import re
import shlex
import sys

SEP = re.compile(r"(?:\|\||&&|[;&|\n])")
REDIR = re.compile(r"\s*\d?>>?.*$")
HELP = re.compile(r"(?:^|\s)(?:--help|-h)(?:\s|$)")
VAR_ONLY = re.compile(r'^"?\$\{?\w+\}?"?$')
DEFAULT_PROG = r"skt|wt|skill-manager|bootstrap-home\.sh"

# RULE 4 of plugin_evals.md: the hook that replays this runs UNSANDBOXED as the
# operator, so nothing from a transcript is executed as written. Only these
# argument shapes replay, and the only command substitution admitted is a git
# rev-parse — which is how the skill teaches you to resolve a base.
SAFE_ARG = re.compile(r"""(?x) ^(?:
      [A-Za-z0-9_./:@=-]+
    | --?[A-Za-z-]+
    | "?\$\(git\s+rev-parse\s+[A-Za-z0-9_/^~-]+\)"?
)$""")


def basename_of(tok: str) -> str:
    """The program name, with quotes stripped from ANYWHERE in the word.

    The shell lets a quote close mid-word (`"$HOME"/bin/thing`), so a strip()
    that only reaches the ends produced the basename `.skill-manager}`.
    """
    return tok.replace('"', "").replace("'", "").strip().rsplit("/", 1)[-1]


def is_program(tok: str, prog: re.Pattern, verb: re.Pattern | None) -> bool:
    """Two accepted shapes, and the second is not laxity.

    `SKT="…/bin/cli/skt"; "$SKT" ticket new …` is what SKILL.md prescribes, and
    the variable hides the basename by design. A bare variable is admitted only
    when a VERB will disambiguate it next: `"$X" ticket new` is unambiguous,
    `"$X" --root …` is not, and is refused.
    """
    if prog.match(basename_of(tok)):
        return True
    return verb is not None and bool(VAR_ONLY.match(tok.strip()))


def analyze(command: str, verb_re: str = r"(?:ticket\s+new|new)",
            prog_re: str = DEFAULT_PROG) -> tuple[list[str], list[str]]:
    """(front-door argument strings, pieces that looked right but were refused).

    The second list is why this returns a pair rather than a string. A red
    grader used to carry no information at all, and diagnosing one such red
    cost three sweeps and roughly four dollars — so the hook now names the
    pieces it ALMOST took, and those are the next entries in CORPUS.

    Split for the program word, shlex for the arguments — different problems,
    and one tool solves neither alone. shlex splits `"${VAR}"/path/thing` at
    the closing quote; .split() breaks `--base "$(git rev-parse HEAD)"` into
    four fields.
    """
    hits: list[str] = []
    rejected: list[str] = []
    prog = re.compile(r"^(?:" + prog_re + r")$")
    verb = re.compile(r"^(?:" + verb_re + r")\b") if verb_re else None
    for piece in SEP.split(command):
        piece = REDIR.sub("", piece.strip())
        if not piece or HELP.search(piece):
            continue
        words = piece.split()
        if not words or not is_program(words[0], prog, verb):
            continue
        if verb is None and VAR_ONLY.match(words[0].strip()):
            continue
        try:
            rest = shlex.split(piece, posix=False)[1:]
        except ValueError:
            rest = words[1:]
        if verb is not None:
            joined = " ".join(rest)
            m = verb.match(joined)
            if not m:
                continue
            tail = joined[m.end():].strip()
            try:
                rest = shlex.split(tail, posix=False) if tail else []
            except ValueError:
                rest = tail.split() if tail else []
        if all(SAFE_ARG.match(t.replace('"', "").replace("'", "")) or SAFE_ARG.match(t)
               for t in rest):
            hits.append(" ".join(rest))
        else:
            rejected.append(piece)
    return hits, rejected


def match(command: str, verb_re: str = r"(?:ticket\s+new|new)",
          prog_re: str = DEFAULT_PROG) -> str | None:
    """The LAST front-door command's arguments, or None.

    Last, not first: an agent that probes and then provisions has issued both,
    and the one to replay is the one it settled on.
    """
    hits, _ = analyze(command, verb_re, prog_re)
    return hits[-1] if hits else None


# REAL COMMANDS FROM REAL TRANSCRIPTS. Every entry was issued by an agent in a
# run of this suite, which is what makes this a regression corpus rather than a
# guess about what agents write.
CORPUS = [
    ('skt ticket new DEMO-1 --base HEAD --path ../wt-DEMO-1', True, 'literal'),
    ('"$SKT" ticket new DEMO-1 --base "$(git rev-parse HEAD)" --path ../wt-DEMO-1',
     True, 'EV-I-21 — quoted substitution containing spaces'),
    ('SKT="${SKILL_MANAGER_HOME:-$HOME/.skill-manager}/bin/cli/skt"; '
     '[ -x "$SKT" ] || SKT="$(command -v skt)"; echo "resolved: $SKT"; '
     '"$SKT" ticket new DEMO-1 --base "$(git rev-parse HEAD)" --path ../wt-DEMO-1',
     True, 'the full preamble SKILL.md teaches'),
    ('./.skill-manager/bin/cli/skt ticket new T-1 --base HEAD --path ../wt-T-1',
     True, 'relative path to the home CLI'),
    ('cd /x; ./.skill-manager/bin/cli/skt ticket new --help 2>&1 | head -40',
     False, 'a help probe provisions nothing'),
    ('git status && git log --oneline -3', False, 'not the front door'),
    ('skt ticket new X --path "$(rm -rf /)"', False, 'injection must never replay'),
]


def self_test() -> int:
    bad = 0
    for cmd, expect, note in CORPUS:
        got = match(cmd) is not None
        ok = got == expect
        bad += not ok
        print(f"{'ok  ' if ok else 'FAIL'}  {'match' if got else 'no   '}  {note}")
        if not ok:
            print(f"        {cmd[:100]}")
    print(f"\n{len(CORPUS) - bad} of {len(CORPUS)} — {'all good' if not bad else str(bad) + ' FAILED'}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else 0)
