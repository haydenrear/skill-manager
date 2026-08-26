#!/usr/bin/env python3
"""Write one machine-readable record for a single test-graph matrix job.

One file per graph, uploaded as an artifact, merged by
``graph-execution-report.py`` into the run-level record that
GOAL-validation-floor metric (a) is read from.

The point of a per-job file is that it survives the job failing: the step runs
``if: always()``, so a graph that fails is *recorded as executed and failed*
rather than vanishing from the tally. A metric that only counts green runs
measures optimism.

Node counts are best-effort. ``run.py`` prints ``  [i/N] <node> (<runner>)``
per node, so ``N`` is how many the graph declared.

``nodes_attempted`` is NOT the highest ``i`` seen — that reads as progress and
is not. When a node fails, ``run.py`` still enumerates every remaining node,
each suffixed ``skipped after earlier failure``; a `smoke` run that died at
node 23 of 52 still prints ``[52/52]``. The first version of this script took
the maximum and reported 52 of 52 for a graph that ran 23, which is the same
species of comfortable-looking number this whole ticket is about. Lines
carrying that suffix are excluded.

If the log is missing or the format changes, the counts come back null and the
graph-level verdict is unaffected.

Usage:
    graph-record.py --graph smoke --outcome success \
        --log ci-logs/run.log --out graph-records/smoke.json
"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

_NODE_RE = re.compile(r"^\s*\[(\d+)/(\d+)\]\s")
_SKIPPED = "skipped after earlier failure"


def node_counts(log: Path) -> tuple[int | None, int | None]:
    if not log.is_file():
        return None, None
    attempted = 0
    declared = 0
    try:
        text = log.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None, None
    for line in text.splitlines():
        m = _NODE_RE.match(line)
        if not m:
            continue
        declared = max(declared, int(m.group(2)))
        if _SKIPPED not in line:
            attempted += 1
    if declared == 0:
        return None, None
    return attempted, declared


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--graph", required=True)
    ap.add_argument("--outcome", required=True,
                    help="the Run graph step's outcome: success|failure|skipped|cancelled")
    ap.add_argument("--log", default="ci-logs/run.log")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    attempted, declared = node_counts(Path(args.log))

    # "executed" means the graph task actually ran, whatever its verdict.
    # A skipped or cancelled matrix cell did not execute and must not be
    # counted as one.
    executed = args.outcome in ("success", "failure")

    record = {
        "graph": args.graph,
        "outcome": args.outcome,
        "executed": executed,
        "passed": args.outcome == "success",
        "nodes_declared": declared,
        "nodes_attempted": attempted,
        "job_url": (
            f"{os.environ.get('GITHUB_SERVER_URL', '')}/"
            f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/"
            f"{os.environ.get('GITHUB_RUN_ID', '')}"
        ),
    }

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(record, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
