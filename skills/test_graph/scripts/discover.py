#!/usr/bin/env python3
"""Discover test graphs and their execution plans.

Auto-detects the scaffolded test_graph project — works whether you're
inside the scaffold, at the project repo root (with a ``test_graph/``
subdir), or pass ``--test-graph-root`` / set ``TEST_GRAPH_ROOT``.

Usage:
    discover.py                  # list all registered test graphs
    discover.py <graph-name>     # plan + adjacency + render DAG to docs/

When a graph name is passed, this script also writes the graphviz DOT
for the graph to ``<project>/docs/<graph>.dot``, and — if ``dot`` is on
PATH — renders ``<project>/docs/<graph>.png``.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys

from _common import add_test_graph_root_arg, run_gradle, target_project_root


def _gradlew_cmd(root_override: str | None) -> tuple[list[str], "Path"]:
    root = target_project_root(root_override)
    gw = root / "gradlew"
    return ([str(gw)] if gw.exists() else ["gradle"]), root


def _capture_gradle(args: list[str], root_override: str | None) -> subprocess.CompletedProcess:
    """Run gradlew from the target project with stdout captured."""
    cmd, root = _gradlew_cmd(root_override)
    return subprocess.run(
        cmd + args,
        cwd=root,
        env=os.environ.copy(),
        capture_output=True,
        text=True,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "graph",
        nargs="?",
        help="Test graph name to plan. Omit to list all registered graphs.",
    )
    add_test_graph_root_arg(parser)
    args = parser.parse_args()

    if args.graph is None:
        return run_gradle(
            ["--console=plain", "-q", "validationListGraphs"],
            args.test_graph_root,
        )

    # 1. Plan + adjacency to stdout for the user.
    code = run_gradle(
        ["--console=plain", "-q", "validationPlanGraph", "--name", args.graph],
        args.test_graph_root,
    )
    if code != 0:
        return code

    # 2. DOT captured to <project>/docs/<graph>.dot.
    proc = _capture_gradle(
        ["--console=plain", "-q", "validationGraphDot", "--name", args.graph],
        args.test_graph_root,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        return proc.returncode

    root = target_project_root(args.test_graph_root)
    docs = root / "docs"
    docs.mkdir(parents=True, exist_ok=True)
    dot_path = docs / f"{args.graph}.dot"
    dot_path.write_text(proc.stdout)

    # 3. Render PNG if graphviz is installed.
    dot_bin = shutil.which("dot")
    if dot_bin is None:
        print()
        print(f"DOT written: {dot_path.relative_to(root)}")
        print("  install graphviz to auto-render a PNG (`brew install graphviz`).")
        return 0

    png_path = docs / f"{args.graph}.png"
    r = subprocess.run(
        [dot_bin, "-Tpng", str(dot_path), "-o", str(png_path)],
        capture_output=True,
        text=True,
    )
    if r.returncode != 0:
        sys.stderr.write(r.stderr)
        print()
        print(f"DOT written: {dot_path.relative_to(root)} (dot render failed)")
        return 0

    print()
    print("dependency graph:")
    print(f"  see it here: {png_path.relative_to(root)}")
    print(f"  (DOT source: {dot_path.relative_to(root)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
