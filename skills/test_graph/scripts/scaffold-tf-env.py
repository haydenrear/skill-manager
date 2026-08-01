#!/usr/bin/env python3
"""Scaffold a Git-ready OpenTofu environment repository.

Usage:
    scaffold-tf-env.py <environment-repo-dir>
    scaffold-tf-env.py <environment-repo-dir> --target local-preview --target aws-preview
    scaffold-tf-env.py <environment-repo-dir> --include-tofu-shim
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from _env_repo_scaffold import (
    DEFAULT_TEMPLATE,
    TARGETS,
    add_environment_template,
    add_tofu_shim,
    ensure_repository,
    normalize_template,
    rel,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("environment_repo_dir", help="Directory to create or update.")
    parser.add_argument(
        "--template",
        default=DEFAULT_TEMPLATE,
        help="Template name under templates/. Defaults to branch-preview.",
    )
    parser.add_argument(
        "--target",
        action="append",
        choices=sorted(TARGETS),
        default=None,
        help="Target template to add. May be repeated. Defaults to local-preview.",
    )
    parser.add_argument(
        "--include-tofu-shim",
        action="store_true",
        help="Add bin/tofu validation shim for local test graph runs. Do not use as a real provider.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite scaffold-managed files that already exist with different content.",
    )
    args = parser.parse_args()

    repo_root = Path(args.environment_repo_dir).expanduser().resolve()
    try:
        created = ensure_repository(repo_root, args.template, force=args.force)
        targets = args.target or ["local-preview"]
        for target in targets:
            created.append(add_environment_template(repo_root, args.template, target, force=args.force))
        if args.include_tofu_shim:
            created.append(add_tofu_shim(repo_root, force=args.force))
    except (FileExistsError, ValueError) as exc:
        sys.exit(f"error: {exc}")

    print(f"scaffolded environment repository at {repo_root}")
    print(f"template: templates/{normalize_template(args.template)}")
    print("managed files:")
    for path in created:
        print(f"  {rel(path, repo_root)}")
    print()
    print("next steps:")
    print(f"  cd {repo_root}")
    print("  git init")
    print("  git add .")
    print("  git commit -m 'Initial environment repository'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
