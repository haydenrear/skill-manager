#!/usr/bin/env python3
"""Scaffold a test_graph project into <repo-root>/test_graph/.

Copies the contents of ``project_sdk_sources/`` (SDKs, Gradle plugin,
build files, example node scripts, Gradle wrapper) into a new
``test_graph/`` subdirectory under the given repo root. The target
``test_graph/`` must not already exist or must be empty.

The ``sdk/`` and ``build-logic/`` subtrees are created as **symlinks**
into the skill repo's ``project_sdk_sources/`` rather than copies, so
upstream upgrades land in every consumer scaffold without rsync. Move
or delete the skill repo and the symlinks dangle - that's the cost,
and it's why the rest of the scaffold (sources/, build.gradle.kts,
gradle wrapper, examples) stays as a copy: those are user-edited.

Usage:
    scaffold.py <repo-root>
    scaffold.py <repo-root> --copy-sdk    # snapshot copies instead of symlinks

Example:
    scaffold.py ~/projects/myapp
        creates ~/projects/myapp/test_graph/ populated with the scaffold;
        sdk/ and build-logic/ symlink into the skill repo.
"""
from __future__ import annotations

import argparse
import os
import shutil
import stat
import sys
from pathlib import Path

from _common import project_sdk_sources


# Subtrees scaffolded as symlinks into the skill repo. Updating the
# skill propagates instantly to every consumer scaffold - no manual
# rsync, no drift between project copies. The flip side: a moved or
# deleted skill repo dangles every consumer's symlink, so don't blow
# away the skill checkout while a project depends on it.
SYMLINK_TARGETS = {"sdk", "build-logic"}


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
        help="your project's repo root - scaffold goes into <repo_root>/test_graph/",
    )
    parser.add_argument(
        "--copy-sdk",
        action="store_true",
        help="Snapshot-copy sdk/ and build-logic/ instead of symlinking. "
             "Use when the consumer needs to be self-contained "
             "(detached environments, archives, Windows without "
             "developer-mode symlink permission).",
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
            f"error: project_sdk_sources/ not found at {src} - this skill "
            "repo may be broken."
        )

    symlinks_used: list[str] = []
    for child in src.iterdir():
        dest = target / child.name
        if child.name in SYMLINK_TARGETS and not args.copy_sdk:
            # Absolute symlink so the scaffold remains valid no matter
            # where the consumer cd's to. Resolve through `child` so a
            # symlinked skill repo (e.g. one mounted into a container)
            # still gets its real path stamped in.
            try:
                os.symlink(child.resolve(), dest, target_is_directory=child.is_dir())
                symlinks_used.append(child.name)
                continue
            except OSError as e:
                # Falling back to a copy keeps us working on Windows
                # without developer-mode and on filesystems that reject
                # symlinks (some FUSE mounts). Note loudly.
                print(
                    f"warning: could not symlink {child.name} (falling back to copy): {e}",
                    file=sys.stderr,
                )
        if child.is_dir():
            shutil.copytree(child, dest, ignore=_ignore, dirs_exist_ok=False)
        else:
            shutil.copy2(child, dest)

    # Make gradlew executable (shutil.copy2 preserves perms on most FS, but not all).
    gw = target / "gradlew"
    if gw.exists():
        gw.chmod(gw.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    print(f"scaffolded test_graph project at {target}")
    if symlinks_used:
        print(f"  symlinked into skill repo: {', '.join(sorted(symlinks_used))}")
        print(f"  (changes to {src} take effect immediately in this scaffold)")
    print()
    scripts = Path(__file__).resolve().parent
    print("next steps:")
    print(f"  cd {repo_root}")
    print(f"  {scripts / 'discover.py'}")
    print(f"  {scripts / 'discover.py'} smoke")
    print(f"  {scripts / 'run.py'} smoke")
    print(f"  {scripts / 'run.py'} --all")
    return 0


if __name__ == "__main__":
    sys.exit(main())
