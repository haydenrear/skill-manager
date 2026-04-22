#!/usr/bin/env python3
"""Run a test graph by name, then aggregate the reports.

Every `testGraph("X") { ... }` in build.gradle.kts registers a Gradle
task named `X`. This script invokes `./gradlew X` then runs
`validationReport` to roll envelopes into summary.json.

Usage:
    run.py <graph-name>                        # auto-detect scaffold from cwd
    run.py <graph-name> --test-graph-root <p>  # explicit override
    TEST_GRAPH_ROOT=<p> run.py <graph-name>    # env-var override

Validate the plan first with `discover.py <graph-name>` — that surfaces
unresolved dependencies, topo-order issues, etc. before we actually spawn
testbeds and race ports.
"""
from __future__ import annotations

import argparse
import sys

from _common import add_test_graph_root_arg, run_gradle


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "graph",
        help="Test graph name (also the Gradle task name). "
             "List available graphs with discover.py (no args).",
    )
    add_test_graph_root_arg(parser)
    args = parser.parse_args()

    code = run_gradle(["--console=plain", args.graph], args.test_graph_root)
    if code != 0:
        return code
    return run_gradle(
        ["--console=plain", "-q", "validationReport"],
        args.test_graph_root,
    )


if __name__ == "__main__":
    sys.exit(main())
