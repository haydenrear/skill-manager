#!/usr/bin/env python3
"""GOAL-who-imports-this -- baseline. READ ONLY.

Metric: can a SINGLE command answer "what in this home imports X", covering
`skill-imports` AND `skill_references`, transitively, without looping?

Two halves, and only the first is the goal:

  (1) IS THERE A COMMAND, AND DOES IT RUN?  Since OUN-3 there is:
      `skill-manager deps --who-imports <unit>`. The probe RUNS it, per home,
      rather than reading its --help. Matching help text was good enough for a
      baseline whose claim was "no such command"; it is not good enough for a
      goal whose claim is that the command ANSWERS -- a flag can exist and
      return nothing.

  (2) DOES IT AGREE WITH AN INDEPENDENT WALK?  This script computes the same
      answer from the unit trees, by different code, and the two must match
      per home. That is the point of keeping the python walker: not to ship a
      second implementation, but to have something the product can be WRONG
      against. The numbers in the epic's purpose -- 6 importers in the project
      home, 8 in root -- were produced by hand with recursive grep, and a
      number produced by hand is an anecdote, not a baseline.

  (3) DOES IT TERMINATE?  Each invocation is run under a timeout. The edges
      form real loops -- git-epic-workflow and git-issue-workflow import each
      other -- so "it returned" is a property to measure, not to assume.

The walk in this script is NOT a shipped feature and must not become one. It
lives in a measurement script precisely so nobody mistakes it for the
product's answer.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import unit_graph as g  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
TARGET = "skill-manager"

TIMEOUT_SECONDS = 120


def ask_product(home: Path, target: str):
    """Run the product's command against one home. Returns (rc, out, seconds)."""
    started = None
    try:
        proc = subprocess.run(
            [str(REPO / "skill-manager"), "deps", "--who-imports", target],
            cwd=REPO, capture_output=True, text=True, timeout=TIMEOUT_SECONDS,
            env={**os.environ, "SKILL_MANAGER_HOME": str(home)})
    except subprocess.TimeoutExpired:
        # NOT a failure to measure -- a failure OF the thing measured. The
        # goal's target says "terminates on cycles", so a run that does not
        # come back is the defect itself.
        return None, f"did not return within {TIMEOUT_SECONDS}s", TIMEOUT_SECONDS
    except OSError as ex:
        return None, str(ex), None
    return proc.returncode, proc.stdout + proc.stderr, None


DIRECT_HEADER = re.compile(r"^\s*direct \((\d+)\):\s*$", re.M)
UNIT_LINE = re.compile(r"^    (\S+)$", re.M)


def parse_direct(text: str):
    """The unit names the product listed as direct importers."""
    m = DIRECT_HEADER.search(text)
    if not m:
        return set(), 0
    tail = text[m.end():]
    stop = tail.find("\n\n")
    block = tail[:stop] if stop >= 0 else tail
    return set(UNIT_LINE.findall(block)), int(m.group(1))


def main() -> int:
    homes = ([Path(a) for a in sys.argv[1:] if not a.startswith("-")]
             or g.default_homes())
    rows = []
    disagreements = []
    missing_command = []
    timeouts = []
    for home in homes:
        try:
            order, units = g.load_home(home)
        except OSError:
            continue
        if TARGET not in units:
            continue
        index = g.installed_index(home)
        by_name = g.importers(order, TARGET, ("imports",), index)
        by_coord = g.importers(order, TARGET, ("references",), index)
        walked = set(g.importers(order, TARGET, index=index))
        trans = g.transitive_importers(order, TARGET, index=index)

        rc, out, waited = ask_product(home, TARGET)
        if rc is None:
            (timeouts if waited else missing_command).append(
                {"home": g.home_label(home), "why": out})
            product, claimed = set(), None
        else:
            product, claimed = parse_direct(out)

        agrees = rc == 0 and product == walked
        if rc == 0 and not agrees:
            disagreements.append({
                "home": g.home_label(home),
                "product_only": sorted(product - walked),
                "walk_only": sorted(walked - product),
            })
        rows.append({
            "home": g.home_label(home),
            "home_path": str(home),
            "product_exit": rc,
            "product_direct": sorted(product),
            "product_claimed_count": claimed,
            "walk_direct": sorted(walked),
            "agrees": agrees,
            "by_skill_imports": len(by_name),
            "by_skill_references": len(by_coord),
            "only_by_reference": sorted(set(by_coord) - set(by_name)),
            "transitive": len(trans),
            "transitive_units": trans,
        })

    answered = [r for r in rows if r["product_exit"] == 0]
    met = (bool(rows) and not timeouts and not missing_command
           and not disagreements and len(answered) == len(rows))
    counts = sorted({len(r["walk_direct"]) for r in rows})
    out = {
        "goal": "GOAL-who-imports-this",
        "metric": ("can a single command answer it for " + TARGET +
                   ", covering skill-imports AND skill_references, transitively"),
        "value": ("one command, agreeing with an independent walk in "
                  f"{len(answered)} of {len(rows)} homes" if rows
                  else "no home carries " + TARGET),
        "target": "one command, both mechanisms, transitive, terminates on cycles",
        "met": met,
        "command": "skill-manager deps --who-imports " + TARGET,
        "target_unit": TARGET,
        "homes_reporting": len(rows),
        "distinct_direct_counts": counts,
        "timed_out": timeouts,
        "no_command": missing_command,
        "disagreements": disagreements,
        "why": (None if met else
                "the product and an independent walk of the same trees do not "
                "agree in every home; see disagreements"),
        "per_home": rows,
    }
    print(json.dumps(out, indent=1))
    return 0 if met else 1


if __name__ == "__main__":
    sys.exit(main())
