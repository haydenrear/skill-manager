#!/usr/bin/env python3
"""Add an environment target template to an existing environment repository.

Usage:
    scaffold-env.py <environment-repo-dir> --target local-preview
    scaffold-env.py <environment-repo-dir> --target local-github-action
    scaffold-env.py <environment-repo-dir> --target aws-preview
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from _env_repo_scaffold import DEFAULT_TEMPLATE, TARGETS, add_environment_template, add_lifecycle_node_templates, rel


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("environment_repo_dir", help="Existing environment repository directory.")
    parser.add_argument(
        "--template",
        default=DEFAULT_TEMPLATE,
        help="Template name under templates/. Defaults to branch-preview.",
    )
    parser.add_argument("--target", required=True, choices=sorted(TARGETS), help="Target template to add.")
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite scaffold-managed files that already exist with different content.",
    )
    parser.add_argument(
        "--lifecycle-nodes-dir",
        help="Optionally scaffold deploy/reset/delete lifecycle node templates into this test_graph sources directory.",
    )
    parser.add_argument(
        "--runtime",
        choices=["uv", "jbang", "all"],
        default="all",
        help="Runtime templates to write when --lifecycle-nodes-dir is set. Defaults to all.",
    )
    args = parser.parse_args()

    repo_root = Path(args.environment_repo_dir).expanduser().resolve()
    try:
        path = add_environment_template(repo_root, args.template, args.target, force=args.force)
        lifecycle_paths = []
        if args.lifecycle_nodes_dir:
            lifecycle_paths = add_lifecycle_node_templates(
                Path(args.lifecycle_nodes_dir).expanduser().resolve(),
                target=args.target,
                runtime=args.runtime,
                force=args.force,
            )
    except (FileExistsError, ValueError) as exc:
        sys.exit(f"error: {exc}")

    print(f"added {args.target} environment template to {repo_root}")
    print(f"managed file: {rel(path, repo_root)}")
    for lifecycle_path in lifecycle_paths:
        print(f"lifecycle node: {lifecycle_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
