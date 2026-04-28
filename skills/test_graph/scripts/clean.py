#!/usr/bin/env python3
"""Wipe the scaffolded project's ``build/`` directory.

Thin wrapper around ``./gradlew clean`` (provided by Gradle's ``base``
plugin, which the validation plugin auto-applies). Removes
``build/validation-reports/`` along with the rest of ``build/``, so
the next run starts from a clean slate.

Auto-detects the scaffolded project — works whether you're inside
the scaffold, at the project repo root with a ``test_graph/`` subdir,
or pass ``--test-graph-root`` / set ``TEST_GRAPH_ROOT``.

Usage:
    clean.py
    clean.py --test-graph-root <path>
"""
from __future__ import annotations

import argparse
import sys

from _common import add_test_graph_root_arg, run_gradle


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_test_graph_root_arg(parser)
    args = parser.parse_args()
    return run_gradle(["--console=plain", "clean"], args.test_graph_root)


if __name__ == "__main__":
    sys.exit(main())
