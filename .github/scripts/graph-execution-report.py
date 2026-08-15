#!/usr/bin/env python3
"""Merge per-graph records into the run-level execution report.

**This file is the answer to "how many test graphs did CI actually run?"**

GOAL-validation-floor (#100) metric (a) is *graphs executed per CI run*, with a
measured baseline of 0 of 27 and a target of >= 8. ARTI-16 (#117) decides that
goal. Before this ticket the only way to answer the question was to read a
workflow file and believe it — and the workflow file said twelve while the
runner executed zero, because ``vars.ENABLE_TEST_GRAPH`` was never set. A
number that can only be reached by reading configuration is a claim, not a
measurement.

So the count is emitted as a build artifact, from the jobs that ran:

    artifact:  graphs-executed
    file:      graphs-executed.json
    schema:    test-graph-ci-execution/v1

Read it without cloning anything:

    gh run download <run-id> -R haydenrear/skill-manager -n graphs-executed
    jq .graphs_executed graphs-executed.json

The same numbers are written to the job summary, with a grep-able
``GRAPHS_EXECUTED=<n>`` line for anything that would rather scrape than parse.

``graphs_executed`` counts graphs whose task ran, pass or fail. It is a measure
of whether the validation floor executes, which is a different question from
whether it is green — ``graphs_passed`` answers that one, and both are in the
file. Conflating them is how a suite gets switched off: a red count is
embarrassing, a zero count is invisible.

Usage:
    graph-execution-report.py --records records/ --scope core \
        --registered 27 --selected 8 --deferred '["doc-smoke", ...]' \
        --out graphs-executed.json
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

SCHEMA = "test-graph-ci-execution/v1"


def load_records(root: Path) -> list[dict]:
    # `actions/download-artifact` with a pattern lands each artifact in its own
    # subdirectory, so glob recursively rather than assuming a flat tree.
    records = []
    for path in sorted(root.rglob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print(f"::warning::unreadable graph record {path}: {exc}")
            continue
        if isinstance(data, dict) and "graph" in data:
            records.append(data)
    # One record per graph; a re-run attempt could produce duplicates.
    by_graph: dict[str, dict] = {}
    for rec in records:
        by_graph[rec["graph"]] = rec
    return [by_graph[k] for k in sorted(by_graph)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--records", required=True)
    ap.add_argument("--scope", required=True)
    ap.add_argument("--registered", type=int, required=True)
    ap.add_argument("--selected", type=int, required=True)
    ap.add_argument("--deferred", default="[]")
    ap.add_argument("--out", default="graphs-executed.json")
    args = ap.parse_args()

    records_root = Path(args.records)
    records = load_records(records_root) if records_root.is_dir() else []

    executed = [r for r in records if r.get("executed")]
    passed = [r for r in executed if r.get("passed")]
    failed = [r for r in executed if not r.get("passed")]
    missing = args.selected - len(records)

    report = {
        "schema": SCHEMA,
        "repository": os.environ.get("GITHUB_REPOSITORY", ""),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        "run_url": (
            f"{os.environ.get('GITHUB_SERVER_URL', '')}/"
            f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/"
            f"{os.environ.get('GITHUB_RUN_ID', '')}"
        ),
        "event": os.environ.get("GITHUB_EVENT_NAME", ""),
        "ref": os.environ.get("GITHUB_REF", ""),
        "sha": os.environ.get("GITHUB_SHA", ""),
        "scope": args.scope,
        "graphs_registered": args.registered,
        "graphs_selected": args.selected,
        "graphs_executed": len(executed),
        "graphs_passed": len(passed),
        "graphs_failed": len(failed),
        # Selected but with no record at all: the job never started, was
        # cancelled, or its artifact upload failed. Named so the gap is a
        # finding rather than a smaller number.
        "graphs_unaccounted": max(missing, 0),
        "nodes_reached": sum(r.get("nodes_reached") or 0 for r in records),
        "nodes_declared": sum(r.get("nodes_declared") or 0 for r in records),
        "deferred": json.loads(args.deferred),
        "graphs": records,
    }

    Path(args.out).write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    lines = [
        "## test-graph execution",
        "",
        f"GRAPHS_EXECUTED={report['graphs_executed']}",
        "",
        f"| scope | registered | selected | executed | passed | failed | unaccounted |",
        f"| --- | --- | --- | --- | --- | --- | --- |",
        f"| {args.scope} | {args.registered} | {args.selected} | "
        f"{report['graphs_executed']} | {report['graphs_passed']} | "
        f"{report['graphs_failed']} | {report['graphs_unaccounted']} |",
        "",
        f"nodes reached {report['nodes_reached']} of {report['nodes_declared']} declared",
        "",
        "| graph | outcome | nodes |",
        "| --- | --- | --- |",
    ]
    for r in records:
        nodes = (
            f"{r.get('nodes_reached')}/{r.get('nodes_declared')}"
            if r.get("nodes_declared") else "-"
        )
        lines.append(f"| `{r['graph']}` | {r.get('outcome')} | {nodes} |")
    if report["graphs_unaccounted"]:
        lines += [
            "",
            f"**{report['graphs_unaccounted']} selected graph(s) produced no record.** "
            "A cell that never started is not a pass; treat this run as incomplete.",
        ]
    lines += [
        "",
        "Machine-readable: artifact `graphs-executed`, file `graphs-executed.json`, "
        f"schema `{SCHEMA}`.",
        "",
        "```",
        f"gh run download {report['run_id']} -R {report['repository']} -n graphs-executed",
        "jq .graphs_executed graphs-executed.json",
        "```",
    ]
    summary = "\n".join(lines)
    print(summary)

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(summary + "\n")

    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write(f"graphs_executed={report['graphs_executed']}\n")
            fh.write(f"graphs_passed={report['graphs_passed']}\n")
            fh.write(f"graphs_failed={report['graphs_failed']}\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
