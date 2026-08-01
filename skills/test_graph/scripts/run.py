#!/usr/bin/env python3
"""Run a test graph by name (or every registered graph).

Every ``testGraph("X") { ... }`` in build.gradle.kts registers a Gradle
task named ``X``. This script invokes ``./gradlew X`` for a single graph,
or ``./gradlew validationRunAll`` to fan out across every registered
graph in declared order. Each ``RunTestGraphTask`` rolls its own
per-node envelopes into ``summary.json`` + ``report.md`` inline at the
end of plan execution, so every run dir under
``build/validation-reports/<runId>/`` gets a report regardless of how
many graphs the invocation spans.

Usage:
    run.py <graph-name>           # single graph (e.g. run.py smoke)
    run.py --all                  # every registered graph, serial
    run.py smoke --resume-from-build build/validation-reports/<runId> --resume-from-node login.smoke
    run.py smoke --resume-from-build build/validation-reports/<runId> --run-only-node login.smoke
"""
from __future__ import annotations

import argparse
import os
import sys

from _common import add_test_graph_root_arg, run_gradle


def main() -> int:
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
        "graph",
        nargs="?",
        help="Test graph name (also the Gradle task name). "
             "List available graphs with `discover.py`.",
    )
    parser.add_argument(
        "--all",
        dest="run_all",
        action="store_true",
        help="Run every registered test graph sequentially (Gradle "
             "task `validationRunAll`). Mutually exclusive with <graph>.",
    )
    parser.add_argument(
        "--resume-from-build",
        help="Existing build/validation-reports/<runId> directory whose saved "
             "context/<node-id>.input.json should seed resumed execution. "
             "Requires <graph> and exactly one of --resume-from-node or "
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
    args = parser.parse_args()

    if args.run_all and args.graph:
        parser.error("cannot pass both <graph> and --all — pick one")
    if not args.run_all and not args.graph:
        parser.error("either <graph> or --all is required")
    replay_node_count = sum(bool(v) for v in (args.resume_from_node, args.run_only_node))
    if args.run_all and (args.resume_from_build or replay_node_count):
        parser.error("resume options apply to one graph; pass <graph> instead of --all")
    if args.resume_from_build and replay_node_count != 1:
        parser.error(
            "--resume-from-build requires exactly one of --resume-from-node "
            "or --run-only-node"
        )
    if not args.resume_from_build and replay_node_count:
        parser.error("--resume-from-build is required with --resume-from-node or --run-only-node")

    if args.run_all:
        # Each RunTestGraphTask now writes its own summary.json +
        # report.md inline; no second invocation needed to roll up.
        return run_gradle(
            ["--console=plain", "validationRunAll"],
            args.test_graph_root,
        )

    # Single-graph path: same story — the per-graph task emits its own
    # rollup inline, so we don't need a second `validationReport` call.
    gradle_args = ["--console=plain", args.graph]
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
