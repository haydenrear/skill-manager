#!/usr/bin/env python3
"""Run a test graph by name (or several, or every registered graph).

Every ``testGraph("X") { ... }`` in build.gradle.kts registers a Gradle
task named ``X``. This script invokes ``./gradlew X`` for a single graph,
``./gradlew A B C`` for several, or ``./gradlew validationRunAll`` to fan
out across every registered graph in declared order. Each
``RunTestGraphTask`` rolls its own per-node envelopes into
``summary.json`` + ``report.md`` inline at the end of plan execution, so
every run dir under ``build/validation-reports/<runId>/`` gets a report
regardless of how many graphs the invocation spans.

A SWEEP (``--all``, more than one graph, or ``--continue``) keeps going
past a red graph (Gradle ``--continue``) and ends with a summary of the
graphs this invocation selected, executed, passed, failed and did not run.
Those counts come from a ledger written by ``sweep-ledger.init.gradle``
during THIS Gradle invocation -- never from ``build/validation-reports/``,
which still holds passing reports from earlier runs (DEF-OUN-023). The
summary is also written to ``build/validation-sweeps/<sweepId>/sweep.json``.
The exit code is non-zero when any selected graph failed or did not run.

Usage:
    run.py <graph-name>           # single graph (e.g. run.py smoke)
    run.py doc-smoke artifact-dag smoke   # several graphs, a red one does not stop the rest
    run.py --all                  # every registered graph, serial, keeps going past a red
    run.py --all --fail-fast      # stop at the first red graph (the old behaviour)
    run.py smoke --resume-from-build build/validation-reports/<runId> --resume-from-node login.smoke
    run.py smoke --resume-from-build build/validation-reports/<runId> --run-only-node login.smoke
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from _common import add_test_graph_root_arg, run_gradle, target_project_root

SWEEP_LEDGER_ENV = "TEST_GRAPH_SWEEP_LEDGER"
SWEEP_INIT_SCRIPT = Path(__file__).resolve().parent / "sweep-ledger.init.gradle"
SWEEP_SCHEMA = "test-graph.sweep.v1"


def allocate_sweep_dir(test_graph_root: Path) -> Path:
    """Create a fresh ``build/validation-sweeps/<UTC stamp>[-n]`` directory."""
    base = test_graph_root / "build" / "validation-sweeps"
    base.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    for suffix in range(10_000):
        candidate = base / (stamp if suffix == 0 else f"{stamp}-{suffix}")
        try:
            candidate.mkdir()
            return candidate
        except FileExistsError:
            continue
    raise RuntimeError(f"could not allocate a unique sweep directory under {base}")


def read_ledger(path: Path) -> list[dict]:
    """Parse the init script's JSON lines. A missing file means no events."""
    if not path.is_file():
        return []
    records = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if isinstance(record, dict):
            records.append(record)
    return records


def summarize_sweep(requested: list[str], records: list[dict], run_all: bool) -> dict:
    """Compute the sweep outcome from this invocation's ledger events only.

    ``requested`` is the graph names given on the command line (empty for
    ``--all``). For ``--all`` the selected set is every graph task Gradle
    scheduled; for named graphs it is the names themselves, in Gradle's
    execution order where Gradle scheduled them.
    """
    scheduled: list[str] = []
    for record in records:
        if record.get("event") != "scheduled":
            continue
        name = record.get("task")
        wanted = bool(record.get("graph")) if run_all else name in requested
        if wanted and name not in scheduled:
            scheduled.append(name)

    if run_all:
        selected = list(scheduled)
    else:
        selected = list(scheduled) + [g for g in requested if g not in scheduled]

    finished: dict[str, dict] = {}
    for record in records:
        if record.get("event") == "finished" and record.get("task") in selected:
            finished[record["task"]] = record

    results = []
    for graph in selected:
        record = finished.get(graph)
        entry = {"graph": graph, "status": "not_run", "seconds": None,
                 "run_dirs": [], "reason": None}
        if record is None:
            entry["reason"] = (
                "scheduled, but Gradle never started it"
                if graph in scheduled
                else "Gradle never scheduled it (configuration failed or unknown task)"
            )
        elif record.get("outcome") in ("passed", "failed"):
            entry["status"] = record["outcome"]
            entry["seconds"] = record.get("seconds")
            entry["run_dirs"] = list(record.get("runDirs") or [])
            if record.get("failure"):
                entry["reason"] = record["failure"]
        else:
            entry["reason"] = record.get("skipMessage") or "skipped by Gradle"
        results.append(entry)

    def count(status: str) -> int:
        return sum(1 for r in results if r["status"] == status)

    selection_known = not run_all or bool(scheduled)
    return {
        "schema": SWEEP_SCHEMA,
        "mode": "all" if run_all else "graphs",
        "selection_known": selection_known,
        "graphs_selected": len(selected) if selection_known else None,
        "graphs_executed": count("passed") + count("failed"),
        "graphs_passed": count("passed"),
        "graphs_failed": count("failed"),
        "graphs_not_run": count("not_run"),
        "results": results,
    }


def sweep_exit_code(gradle_rc: int, summary: dict) -> int:
    """Non-zero whenever the sweep was not a complete green run."""
    if gradle_rc != 0:
        return gradle_rc
    if (
        not summary["selection_known"]
        or summary["graphs_failed"]
        or summary["graphs_not_run"]
    ):
        return 1
    return 0


def _display_path(path: str, root: Path) -> str:
    try:
        return str(Path(path).relative_to(root))
    except ValueError:
        return path


def format_summary(summary: dict, sweep_id: str, root: Path, record_path: Path) -> str:
    labels = {"passed": "PASSED", "failed": "FAILED", "not_run": "NOT RUN"}
    selected = summary["graphs_selected"]
    lines = [
        "",
        f"== test graph sweep {sweep_id} ==",
        "Counted from this invocation's own task outcomes, not from build/validation-reports/.",
        f"graphs_selected={'unknown' if selected is None else selected}  "
        f"graphs_executed={summary['graphs_executed']}  "
        f"graphs_passed={summary['graphs_passed']}  "
        f"graphs_failed={summary['graphs_failed']}  "
        f"graphs_not_run={summary['graphs_not_run']}",
    ]
    if not summary["selection_known"]:
        lines.append(
            "Gradle never reached task execution, so the selected set is unknown "
            "and no graph executed."
        )
    width = max([len(r["graph"]) for r in summary["results"]] + [5])
    for r in summary["results"]:
        detail = []
        if r["seconds"] is not None:
            detail.append(f"{r['seconds']:.1f}s")
        detail.extend(_display_path(d, root) for d in r["run_dirs"])
        if r["status"] != "passed" and r["reason"]:
            detail.append(f"({r['reason'].splitlines()[0]})")
        lines.append(f"  {labels[r['status']]:<8} {r['graph']:<{width}}  {'  '.join(detail)}".rstrip())
    lines.append(f"sweep record: {record_path}")
    return "\n".join(lines)


def run_sweep(args: argparse.Namespace, graphs: list[str]) -> int:
    root = target_project_root(args.test_graph_root)
    sweep_dir = allocate_sweep_dir(root)
    ledger = sweep_dir / "ledger.jsonl"
    ledger.touch()
    gradle_args = ["--console=plain", "--init-script", str(SWEEP_INIT_SCRIPT)]
    if not args.fail_fast:
        gradle_args.append("--continue")
    gradle_args += ["validationRunAll"] if args.run_all else graphs
    what = "every registered graph" if args.run_all else ", ".join(graphs)
    policy = "stopping at the first red graph" if args.fail_fast else "continuing past red graphs"
    print(f"sweep {sweep_dir.name}: {what} ({policy})", flush=True)
    try:
        rc = run_gradle(gradle_args, str(root), extra_env={SWEEP_LEDGER_ENV: str(ledger)})
    except KeyboardInterrupt:
        rc = 130
    summary = summarize_sweep(graphs, read_ledger(ledger), args.run_all)
    summary["sweep_id"] = sweep_dir.name
    summary["gradle_exit_code"] = rc
    summary["continue_past_failures"] = not args.fail_fast
    record = sweep_dir / "sweep.json"
    record.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(format_summary(summary, sweep_dir.name, root, record), flush=True)
    return sweep_exit_code(rc, summary)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0],
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Replay examples:\n"
            "  Continue a graph from a saved node context:\n"
            "    run.py smoke --resume-from-build build/validation-reports/<runId> "
            "--resume-from-node login.smoke\n"
            "  Run only one node from a saved node context:\n"
            "    run.py smoke --resume-from-build build/validation-reports/<runId> "
            "--run-only-node login.smoke\n\n"
            "Use --resume-from-build with exactly one node selector. "
            "--resume-from-node continues downstream; --run-only-node does not. "
            "Both preserve the source build and write a fresh replay report."
        ),
    )
    parser.add_argument(
        "graphs",
        nargs="*",
        metavar="graph",
        help="Test graph name(s) (also the Gradle task names). "
             "List available graphs with `discover.py`. More than one "
             "runs as a sweep that continues past failures.",
    )
    parser.add_argument(
        "--all",
        dest="run_all",
        action="store_true",
        help="Run every registered test graph sequentially (Gradle "
             "task `validationRunAll`), continuing past failures. "
             "Mutually exclusive with <graph>.",
    )
    policy = parser.add_mutually_exclusive_group()
    policy.add_argument(
        "--continue",
        dest="keep_going",
        action="store_true",
        help="Run as a sweep that keeps going past a failing graph and ends "
             "with an executed/passed/failed/not-run summary. Implied by --all "
             "and by more than one graph; with a single graph it adds the summary.",
    )
    policy.add_argument(
        "--fail-fast",
        dest="fail_fast",
        action="store_true",
        help="In a sweep, stop at the first failing graph. Graphs after it are "
             "reported as not run.",
    )
    parser.add_argument(
        "--resume-from-build",
        help="Existing build/validation-reports/<runId> directory whose saved "
             "context/<node-id>.input.json should seed resumed execution. "
             "Requires one <graph> and exactly one of --resume-from-node or "
             "--run-only-node.",
    )
    parser.add_argument(
        "--resume-from-node",
        help="Node id to resume from. The selected node's saved input context "
             "must exist under --resume-from-build/context/.",
    )
    parser.add_argument(
        "--run-only-node",
        help="Node id to run by itself from --resume-from-build. The selected "
             "node's saved input context must exist under "
             "--resume-from-build/context/.",
    )
    add_test_graph_root_arg(parser)
    args = parser.parse_args(argv)

    graphs: list[str] = []
    for graph in args.graphs:
        if graph not in graphs:
            graphs.append(graph)

    if args.run_all and graphs:
        parser.error("cannot pass both <graph> and --all — pick one")
    if not args.run_all and not graphs:
        parser.error("either <graph> or --all is required")
    replay_node_count = sum(bool(v) for v in (args.resume_from_node, args.run_only_node))
    replaying = bool(args.resume_from_build or replay_node_count)
    if replaying and (args.run_all or len(graphs) != 1 or args.keep_going or args.fail_fast):
        parser.error("resume options apply to one graph; pass a single <graph> without --all/--continue")
    if args.resume_from_build and replay_node_count != 1:
        parser.error(
            "--resume-from-build requires exactly one of --resume-from-node "
            "or --run-only-node"
        )
    if not args.resume_from_build and replay_node_count:
        parser.error("--resume-from-build is required with --resume-from-node or --run-only-node")

    if args.run_all or len(graphs) > 1 or args.keep_going:
        return run_sweep(args, graphs)
    if args.fail_fast:
        parser.error("--fail-fast applies to a sweep (--all or more than one graph)")

    # Single-graph path: unchanged. The per-graph task emits its own rollup
    # inline, and Gradle's exit code is the graph's verdict.
    gradle_args = ["--console=plain", graphs[0]]
    if args.resume_from_build:
        # Normalize traversal without following a symlink. The Gradle task is
        # the authority that verifies this is a real direct report-root child.
        resume_from_build = os.path.abspath(os.path.expanduser(args.resume_from_build))
        gradle_args += [
            f"--resume-from-build={resume_from_build}",
        ]
        if args.resume_from_node:
            gradle_args.append(f"--resume-from-node={args.resume_from_node}")
        if args.run_only_node:
            gradle_args.append(f"--run-only-node={args.run_only_node}")
    return run_gradle(gradle_args, args.test_graph_root)


if __name__ == "__main__":
    sys.exit(main())
