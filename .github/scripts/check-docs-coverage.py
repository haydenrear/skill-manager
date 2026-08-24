#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# ///
"""Measure how much of the derived-artifact contract the DOCS explain.

GOAL-mechanism-documented's clause-1 instrument, and the re-run of the baseline
at results/epic-home-integrity-sync/baseline/docs-coverage.txt.

WHY THIS EXISTS
---------------
The artifact mechanism — `artifacts list`, `skill-manager build`, cold shims,
what a clone inherits versus declares — landed in the artifact-DAG epic and was
never written down for agents. `skt check` prints artifact state into every
session's opening context, so every agent is told artifacts exist; nothing tells
them what to do about it.

That is not hypothetical. This epic's own wave 2 produced TWO wrong root-cause
diagnoses of #223, both from reading source because no document describes the
mechanism: the first blamed the clone/verify sanction rule, the second proposed
eager rebuilds. Both were wrong and both were caught by the owner, not by a test.

A count cannot show the prose is RIGHT — that is the judged half of the goal.
What it can show is whether the contract is stated at all.

WHAT IT CANNOT SHOW, CORRECTED 2026-08-23
-----------------------------------------
This docstring used to end "...and whether it is stated ONCE rather than copied
into four units that will drift apart."  THAT WAS FALSE and it was measured
false by the adversarial review of PR #248.

This script counts TERM HITS PER UNIT.  A unit that links to the contract and a
unit that restates it in full score IDENTICALLY — in fact the restating unit
scores HIGHER, because it contains more of the terms.  So the instrument is not
merely blind to clause 1; it is biased in the wrong direction, and reports a
copy as better coverage than a link.

Measured instance: at 4-of-4, `skt/SKILL.md` carried a nine-line blockquote of
the whole contract including a machine-specific "13 stale of 39", and
`git-issue-workflow` restated it twice more — while this script reported the
same 4-of-4 it would have reported for four pure pointers.

CONSEQUENCE FOR HIS-6: this script decides "is the contract reachable from each
instructing unit", nothing more.  GOAL-mechanism-documented's CLAUSE 1 — stated
once, linked not restated — must be decided by READING THE PROSE, not by this
number.  Reporting this number as clause 1's verdict would report the epic's own
signature defect as a pass.
"""

from __future__ import annotations

import os
import re
import sys

ROOTS = [os.path.expanduser("~/.skill-manager/skills"),
         os.path.expanduser("~/.skill-manager/plugins")]

# The units that INSTRUCT agents about homes. These are the four that must
# reach the contract — skt states it, the rest link to it.
INSTRUCTING = ("skt", "skill-manager", "git-issue-workflow", "git-epic-workflow")

TERMS = {
    "skill-manager build": r"skill-manager build\b",
    "cold shim": r"cold shim|ColdArtifact",
    "declared-only": r"declared-only",
    "artifacts list/show/stale": r"\bartifacts (list|show|stale)\b",
}

SKIP = ("/.git", "/node_modules", "/.history", "/build/", "/.venv")


def md_files(path: str) -> list[str]:
    out = []
    for dirpath, _, files in os.walk(path):
        if any(s in dirpath for s in SKIP):
            continue
        out += [os.path.join(dirpath, f) for f in files if f.endswith(".md")]
    return out


def main() -> int:
    units = []
    for root in ROOTS:
        if not os.path.isdir(root):
            continue
        for name in sorted(os.listdir(root)):
            p = os.path.join(root, name)
            if os.path.isdir(p):
                units.append((name, p))

    totals = {k: 0 for k in TERMS}
    reached = {}
    print(f"{'unit':<26} {'md':>4}  " + "  ".join(f"{k[:13]:>13}" for k in TERMS))
    print("-" * 84)
    for name, path in units:
        files = md_files(path)
        if not files:
            continue
        hits = {k: 0 for k in TERMS}
        for f in files:
            try:
                text = open(f, encoding="utf-8", errors="ignore").read()
            except OSError:
                continue
            for k, rx in TERMS.items():
                if re.search(rx, text, re.I):
                    hits[k] += 1
        for k in TERMS:
            totals[k] += hits[k]
        if name in INSTRUCTING:
            reached[name] = sum(hits.values())
        if sum(hits.values()):
            print(f"{name:<26} {len(files):>4}  " + "  ".join(f"{hits[k]:>13}" for k in TERMS))

    print("-" * 84)
    print(f"{'TOTAL':<26} {'':>4}  " + "  ".join(f"{totals[k]:>13}" for k in TERMS))
    print("\nUnits that instruct agents about homes, and whether the contract reaches them:")
    for name in INSTRUCTING:
        got = reached.get(name)
        state = "absent" if not got else f"{got} file(s)"
        print(f"  {name:<22} {state}")
    missing = [n for n in INSTRUCTING if not reached.get(n)]
    print(f"\n{len(INSTRUCTING) - len(missing)} of {len(INSTRUCTING)} instructing units reach the contract.")
    if missing:
        print("  missing: " + ", ".join(missing))
    return 0


if __name__ == "__main__":
    sys.exit(main())
