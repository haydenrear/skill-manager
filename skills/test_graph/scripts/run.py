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
"""
from __future__ import annotations

import argparse
import sys

from _common import add_test_graph_root_arg, run_gradle


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "graph",
        nargs="?",
        help="Test graph name (also the Gradle task name). "
             "List available graphs with `./gradlew validationListGraphs`.",
    )
    parser.add_argument(
        "--all",
        dest="run_all",
        action="store_true",
        help="Run every registered test graph sequentially (Gradle "
             "task `validationRunAll`). Mutually exclusive with <graph>.",
    )
    add_test_graph_root_arg(parser)
    args = parser.parse_args()

    if args.run_all and args.graph:
        parser.error("cannot pass both <graph> and --all — pick one")
    if not args.run_all and not args.graph:
        parser.error("either <graph> or --all is required")

    if args.run_all:
        # Each RunTestGraphTask now writes its own summary.json +
        # report.md inline; no second invocation needed to roll up.
        return run_gradle(
            ["--console=plain", "validationRunAll"],
            args.test_graph_root,
        )

    # Single-graph path: same story — the per-graph task emits its own
    # rollup inline, so we don't need a second `validationReport` call.
    return run_gradle(["--console=plain", args.graph], args.test_graph_root)


if __name__ == "__main__":
    sys.exit(main())
