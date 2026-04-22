#!/usr/bin/env python3
"""Scaffold a test_graph project into <repo-root>/test_graph/.

Copies the contents of `project_sdk_sources/` (SDKs, Gradle plugin, build
files, example node scripts, Gradle wrapper) into a new `test_graph/`
subdirectory under the given repo root. The target `test_graph/` must
not already exist or must be empty.

Usage:
    scaffold.py <repo-root>

Example:
    scaffold.py ~/projects/myapp
        → creates ~/projects/myapp/test_graph/ populated with the scaffold.
"""
from __future__ import annotations

import argparse
import shutil
import stat
import sys
from pathlib import Path

from _common import project_sdk_sources


def _ignore(dirname: str, names: list[str]) -> list[str]:
    # Skip build outputs, caches, and VCS artifacts if the source has them.
    skip = set()
    for name in names:
        if name in {".gradle", "build", "out", ".idea", "__pycache__", "node_modules"}:
            skip.add(name)
        elif name.endswith(".egg-info"):
            skip.add(name)
    return list(skip)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "repo_root",
        help="your project's repo root — scaffold goes into <repo_root>/test_graph/",
    )
    args = parser.parse_args()

    repo_root = Path(args.repo_root).expanduser().resolve()
    if not repo_root.exists():
        repo_root.mkdir(parents=True)
    if not repo_root.is_dir():
        sys.exit(f"error: {repo_root} exists and is not a directory")

    target = repo_root / "test_graph"
    if target.exists() and any(target.iterdir()):
        sys.exit(
            f"error: {target} already exists and is not empty.\n"
            "  remove or empty it first, then re-run."
        )
    target.mkdir(parents=True, exist_ok=True)

    src = project_sdk_sources()
    if not src.is_dir():
        sys.exit(
            f"error: project_sdk_sources/ not found at {src} — this skill "
            "repo may be broken."
        )

    for child in src.iterdir():
        dest = target / child.name
        if child.is_dir():
            shutil.copytree(child, dest, ignore=_ignore, dirs_exist_ok=False)
        else:
            shutil.copy2(child, dest)

    # Make gradlew executable (shutil.copy2 preserves perms on most FS, but not all).
    gw = target / "gradlew"
    if gw.exists():
        gw.chmod(gw.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    print(f"scaffolded test_graph project at {target}")
    print()
    print("next steps:")
    print(f"  cd {target}")
    print("  ./gradlew validationListGraphs")
    print("  ./gradlew validationPlanGraph --name=smoke")
    print("  ./gradlew smoke")
    return 0


if __name__ == "__main__":
    sys.exit(main())
