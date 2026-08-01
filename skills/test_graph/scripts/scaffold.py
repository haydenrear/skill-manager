#!/usr/bin/env python3
"""Scaffold a test_graph project into <repo-root>/test_graph/.

Copies the contents of ``project_sdk_sources/`` (SDKs, Gradle plugin,
build files, example node scripts, Gradle wrapper) into a new
``test_graph/`` subdirectory under the given repo root. The target
``test_graph/`` must not already exist or must be empty.

The ``sdk/``, ``build-logic/``, and ``standard-nodes/`` subtrees are generated
runtime bindings. Their portable provider manifest is committed; the symlinks
themselves are ignored and are materialized by the Test Graph wrappers.

Usage:
    scaffold.py <repo-root>
    scaffold.py <repo-root> --copy-sdk    # snapshot copies instead of symlinks

Example:
    scaffold.py ~/projects/myapp
        creates ~/projects/myapp/test_graph/ populated with the scaffold;
        sdk/, build-logic/, and standard-nodes/ symlink into the skill repo.
"""
from __future__ import annotations

import argparse
import shutil
import stat
import sys
from pathlib import Path

from _common import (
    PROVIDER_BINDINGS,
    ProviderBindingError,
    capture_managed_bindings,
    ensure_provider_binding_ignores,
    prepare_provider_bindings,
    project_sdk_sources,
    rollback_managed_bindings,
    write_provider_bindings_manifest,
)


SYMLINK_TARGETS = set(PROVIDER_BINDINGS)
PROVIDER_VALIDATION_BEGIN = "    // TEST-GRAPH-PROVIDER-VALIDATION-BEGIN\n"
PROVIDER_VALIDATION_END = "    // TEST-GRAPH-PROVIDER-VALIDATION-END\n"


def _ignore(dirname: str, names: list[str]) -> list[str]:
    # Skip build outputs, caches, and VCS artifacts if the source has them.
    skip = set()
    for name in names:
        if name in {".gradle", "build", "out", ".idea", "__pycache__", "node_modules"}:
            skip.add(name)
        elif name.endswith(".egg-info"):
            skip.add(name)
    return list(skip)


def _remove_provider_validation_sections(build_file: Path) -> None:
    """Keep provider acceptance graphs out of newly scaffolded consumers."""
    content = build_file.read_text(encoding="utf-8")
    begin_count = content.count(PROVIDER_VALIDATION_BEGIN)
    end_count = content.count(PROVIDER_VALIDATION_END)
    if begin_count != end_count:
        raise RuntimeError(
            f"unbalanced provider-validation markers in {build_file}: "
            f"{begin_count} begin, {end_count} end"
        )
    while PROVIDER_VALIDATION_BEGIN in content:
        begin = content.index(PROVIDER_VALIDATION_BEGIN)
        end = content.index(PROVIDER_VALIDATION_END, begin) + len(PROVIDER_VALIDATION_END)
        content = content[:begin] + content[end:]
    build_file.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "repo_root",
        help="your project's repo root - scaffold goes into <repo_root>/test_graph/",
    )
    parser.add_argument(
        "--copy-sdk",
        action="store_true",
        help="Snapshot-copy sdk/, build-logic/, and standard-nodes/ instead of symlinking. "
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

    for child in src.iterdir():
        dest = target / child.name
        if child.name in SYMLINK_TARGETS and not args.copy_sdk:
            continue
        if child.is_dir():
            shutil.copytree(child, dest, ignore=_ignore, dirs_exist_ok=False)
        else:
            shutil.copy2(child, dest)

    # Make gradlew executable (shutil.copy2 preserves perms on most FS, but not all).
    build_file = target / "build.gradle.kts"
    if build_file.is_file():
        _remove_provider_validation_sections(build_file)

    managed_bindings = False
    if not args.copy_sdk:
        before = capture_managed_bindings(target)
        try:
            write_provider_bindings_manifest(target)
            ensure_provider_binding_ignores(target)
            prepare_provider_bindings(target)
            managed_bindings = True
        except (OSError, ProviderBindingError) as error:
            print(
                f"warning: could not create managed provider symlinks "
                f"(falling back to copies): {error}",
                file=sys.stderr,
            )
            rollback_managed_bindings(target, before)
            for name in SYMLINK_TARGETS:
                shutil.copytree(src / name, target / name, ignore=_ignore)

    gw = target / "gradlew"
    if gw.exists():
        gw.chmod(gw.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    print(f"scaffolded test_graph project at {target}")
    if managed_bindings:
        print("  managed provider bindings: build-logic, sdk, standard-nodes")
        print("  (provider-bindings.json is committed; generated links are ignored)")
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
