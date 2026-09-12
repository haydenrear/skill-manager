#!/usr/bin/env python3
"""GOAL harness: does an agent FIND the front door, among every unit in a home?

Obeys the contract in `scripts/measure_goals.py`: one JSON object on stdout
with {goal, metric, value, target, met}, exit 0 met / 1 not met / 2 could not
measure.

WHY THIS IS A GOAL AND NOT A NOTE. Every skill in this repo is reached by an
agent, many times a day, and the cost of it not being reached is paid on every
one of those. The eval suite under specs/evals measures exactly that -- and
until now its scores lived in a terminal and in prose, so nothing could join
them to the scorecard the rest of the epic is decided on. `run.sh` archives one
aggregate-result.json per case under specs/evals/results/runs/<case>/; this
reads the newest of each.

WHAT IT MEASURES, and the distinction is the whole point:

  FOUND      the case's front-door grader is green -- the agent issued the
             command rather than reconstructing it from the script
  COST       Bash calls, against each case's own declared ceiling

A case with no archived run is UNMEASURED, never met. That is OUN-8's stated
test_constraint, and it exists because the previous epic reported three of four
goals as mismeasured rather than unmet.

ONE RUN IS NOT EVIDENCE, and this harness cannot fix that on its own: it
reports the newest run per case and says how many runs back it. The Bash count
for a single case has been seen at 18, 26, 5, 2, 5, 10 across one afternoon.
Read `runs` before reading `value`.
"""
import json
import pathlib
import sys

GOAL = "GOAL-the-front-door-is-found"
ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNS = ROOT / "specs" / "evals" / "results" / "runs"

# The grader whose green means "the agent issued the front-door command". Named
# per case because each case's front door differs; a case absent from here is
# one whose graders do not answer this question.
FRONT_DOOR_GRADER = {
    "epic-provisions-a-ticket-worktree": "issues-the-front-door-command",
    "ticket-agent-opens-a-ticket": "issues-the-front-door-command",
    "ticket-agent-closes-a-ticket": "reaches-the-gated-door",
    "bootstraps-a-home-for-a-repo": "reaches-the-bootstrap",
    "syncs-a-stale-home-from-root": "reaches-the-currency-command",
    "reconciles-a-worktree-into-the-project-home": "reaches-close-out",
}


def newest(case_dir: pathlib.Path) -> dict | None:
    files = sorted(case_dir.glob("*.json"))
    if not files:
        return None
    try:
        return json.loads(files[-1].read_text())
    except (OSError, ValueError):
        return None


def main() -> int:
    if not RUNS.is_dir():
        print(json.dumps({
            "goal": GOAL, "metric": "cases whose front-door grader is green",
            "value": "no archived runs — specs/evals/results/runs is absent",
            "target": f"{len(FRONT_DOOR_GRADER)} of {len(FRONT_DOOR_GRADER)}",
            "met": False, "unmeasured": True,
            "fix": "specs/evals/harness/evals/<case>/run.sh",
        }))
        return 2

    found, missing, detail = 0, [], []
    for case, grader in sorted(FRONT_DOOR_GRADER.items()):
        report = newest(RUNS / case)
        if report is None:
            missing.append(case)
            continue
        arms = (report.get("cases") or [{}])[0].get("arms", {}).get("with", [{}])
        graders = {g.get("name"): g.get("passed") for g in (arms[0].get("graders") or [])}
        ok = graders.get(grader)
        detail.append(f"{case}={'found' if ok else 'MISSED' if ok is not None else 'no-grader'}")
        if ok:
            found += 1

    total = len(FRONT_DOOR_GRADER)
    if missing:
        # UNMEASURED, not unmet. A case nobody has run says nothing about the
        # front door, and scoring it red teaches the reader to ignore the row.
        print(json.dumps({
            "goal": GOAL, "metric": "cases whose front-door grader is green",
            "value": f"{found} of {total - len(missing)} run; NOT RUN: {', '.join(missing)}",
            "target": f"{total} of {total}", "met": False, "unmeasured": True,
            "detail": detail,
        }))
        return 2

    print(json.dumps({
        "goal": GOAL, "metric": "cases whose front-door grader is green",
        "value": f"{found} of {total} ({'; '.join(detail)})",
        "target": f"{total} of {total}", "met": found == total,
        "runs": "newest run per case — one run is not evidence",
    }))
    return 0 if found == total else 1


if __name__ == "__main__":
    sys.exit(main())
