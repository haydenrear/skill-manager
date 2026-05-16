#!/usr/bin/env python3
"""Scaffold a GitHub Actions workflow for a test_graph project.

The normal test_graph scaffold keeps ``test_graph/sdk`` and
``test_graph/build-logic`` as symlinks into the installed test-graph
skill. A GitHub runner only works if those symlinks point at a real
skill-manager install, so this script writes a workflow that installs
the skill with skill-manager before running graph discovery and
execution.

Usage:
    github-action.py [repo-root]
    github-action.py [repo-root] --graph smoke
    github-action.py [repo-root] --symlink-mode preserve

By default the generated workflow repairs the checkout's symlinks to
the runner's installed test-graph skill. Use ``--symlink-mode preserve``
when the checked-in symlink target already points under a fixed
``$SKILL_MANAGER_HOME/skills/test-graph/project_sdk_sources`` path and
you want the runner to create that same home.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

from _common import target_project_root


DEFAULT_WORKFLOW = "test-graph.yml"
DEFAULT_SKILL_COORDINATE = "github:haydenrear/test_graph_skill"
DEFAULT_SKILL_MANAGER_HOME = "/Users/runner/.skill-manager"
DEFAULT_TRIGGER_PATHS = [
    "test_graph/**",
    "src/**",
    "pyproject.toml",
    "uv.lock",
    "build.gradle*",
    "settings.gradle*",
    "pom.xml",
    "package.json",
    "package-lock.json",
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "repo_root",
        nargs="?",
        help="Project repo root. Defaults to auto-detection from cwd.",
    )
    parser.add_argument(
        "--workflow-name",
        default=DEFAULT_WORKFLOW,
        help=f"Workflow filename under .github/workflows/ (default: {DEFAULT_WORKFLOW}).",
    )
    parser.add_argument(
        "--workflow-title",
        default="Test Graph",
        help="Human workflow name shown in GitHub Actions.",
    )
    parser.add_argument(
        "--job-name",
        default="Test Graph",
        help="Human job name shown in GitHub Actions.",
    )
    parser.add_argument(
        "--graph",
        action="append",
        default=[],
        help="Graph task to run. Repeat for multiple graphs. Defaults to --all.",
    )
    parser.add_argument(
        "--runner",
        default="macos-latest",
        help="GitHub runner label. The generated setup uses Homebrew, so macos-latest is the default.",
    )
    parser.add_argument(
        "--timeout-minutes",
        type=int,
        default=60,
        help="Job timeout in minutes.",
    )
    parser.add_argument(
        "--skill-coordinate",
        default=DEFAULT_SKILL_COORDINATE,
        help="skill-manager coordinate used to install the test-graph skill.",
    )
    parser.add_argument(
        "--skill-manager-home",
        default=None,
        help="Absolute SKILL_MANAGER_HOME for the runner. Defaults to /Users/runner/.skill-manager "
             "in repair mode, or the home inferred from scaffold symlinks in preserve mode.",
    )
    parser.add_argument(
        "--symlink-mode",
        choices=("repair", "preserve"),
        default="repair",
        help="repair rewrites checkout symlinks to the runner skill install; preserve requires "
             "the existing symlinks to resolve under SKILL_MANAGER_HOME.",
    )
    parser.add_argument(
        "--token-secret",
        default=None,
        help="Optional GitHub secret name to expose as SKILL_MANAGER_GITHUB_TOKEN/GH_TOKEN/GITHUB_TOKEN "
             "for private skill installs.",
    )
    parser.add_argument(
        "--path",
        action="append",
        default=[],
        help="Additional workflow path trigger. Repeat as needed for project source directories.",
    )
    parser.add_argument(
        "--artifact-name",
        default="test-graph-validation-reports",
        help="Name for the uploaded validation report artifact.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite an existing workflow file.",
    )
    args = parser.parse_args()

    repo_root, test_graph_root = _resolve_roots(args.repo_root)
    workflow_name = _workflow_name(args.workflow_name)
    workflow_path = repo_root / ".github" / "workflows" / workflow_name

    if workflow_path.exists() and not args.force:
        sys.exit(f"error: {workflow_path} already exists; pass --force to overwrite it")

    skill_manager_home = _skill_manager_home(
        args.skill_manager_home,
        args.symlink_mode,
        test_graph_root,
    )

    paths = _dedupe([workflow_path.relative_to(repo_root).as_posix(), *DEFAULT_TRIGGER_PATHS, *args.path])
    text = render_workflow(
        workflow_title=args.workflow_title,
        job_id=_job_id(args.job_name),
        job_name=args.job_name,
        runner=args.runner,
        timeout_minutes=args.timeout_minutes,
        skill_manager_home=skill_manager_home,
        skill_coordinate=args.skill_coordinate,
        token_secret=args.token_secret,
        trigger_paths=paths,
        graphs=args.graph,
        artifact_name=args.artifact_name,
        symlink_mode=args.symlink_mode,
    )

    workflow_path.parent.mkdir(parents=True, exist_ok=True)
    workflow_path.write_text(text, encoding="utf-8")

    print(f"created {workflow_path.relative_to(repo_root)}")
    print("next steps:")
    print("  1. review path triggers and secrets in the workflow")
    print("  2. commit the workflow with the test_graph scaffold")
    print("  3. run it from the GitHub Actions workflow_dispatch button")
    return 0


def _resolve_roots(repo_arg: str | None) -> tuple[Path, Path]:
    if repo_arg:
        candidate = Path(repo_arg).expanduser().resolve()
        if candidate.name == "test_graph" and (candidate / "settings.gradle.kts").is_file():
            test_graph_root = candidate
            repo_root = candidate.parent
        else:
            repo_root = candidate
            test_graph_root = repo_root / "test_graph"
    else:
        test_graph_root = target_project_root()
        repo_root = test_graph_root.parent

    if not repo_root.is_dir():
        sys.exit(f"error: repo root does not exist or is not a directory: {repo_root}")
    if not (test_graph_root / "settings.gradle.kts").is_file():
        sys.exit(
            f"error: {repo_root} does not contain a scaffolded test_graph/ "
            "(missing test_graph/settings.gradle.kts)"
        )
    if not (test_graph_root / "build.gradle.kts").is_file():
        sys.exit(
            f"error: {repo_root} does not contain a scaffolded test_graph/ "
            "(missing test_graph/build.gradle.kts)"
        )
    return repo_root, test_graph_root


def _workflow_name(value: str) -> str:
    name = value.strip()
    if not name:
        sys.exit("error: --workflow-name cannot be empty")
    if "/" in name or "\\" in name:
        sys.exit("error: --workflow-name must be a filename, not a path")
    if not name.endswith((".yml", ".yaml")):
        name = f"{name}.yml"
    return name


def _job_id(name: str) -> str:
    ident = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return ident or "test-graph"


def _skill_manager_home(override: str | None, mode: str, test_graph_root: Path) -> str:
    if override:
        home = Path(override).expanduser()
        if not home.is_absolute():
            sys.exit("error: --skill-manager-home must be absolute")
        return home.as_posix()

    if mode == "repair":
        return DEFAULT_SKILL_MANAGER_HOME

    inferred = _infer_skill_manager_home(test_graph_root)
    if inferred is None:
        sys.exit(
            "error: --symlink-mode preserve requires test_graph/sdk or "
            "test_graph/build-logic to point under "
            "<home>/skills/test-graph/project_sdk_sources/.\n"
            "  Pass --skill-manager-home explicitly, or use --symlink-mode repair."
        )
    return inferred.as_posix()


def _infer_skill_manager_home(test_graph_root: Path) -> Path | None:
    for name in ("sdk", "build-logic"):
        link = test_graph_root / name
        if not link.is_symlink():
            continue
        target = Path(os.readlink(link))
        if not target.is_absolute():
            target = (link.parent / target).resolve()
        parts = target.parts
        suffix = ("skills", "test-graph", "project_sdk_sources", name)
        for idx in range(0, len(parts) - len(suffix) + 1):
            if parts[idx : idx + len(suffix)] == suffix:
                home_parts = parts[:idx]
                if not home_parts:
                    return Path("/")
                return Path(*home_parts)
    return None


def _dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.add(value)
            out.append(value)
    return out


def render_workflow(
    *,
    workflow_title: str,
    job_id: str,
    job_name: str,
    runner: str,
    timeout_minutes: int,
    skill_manager_home: str,
    skill_coordinate: str,
    token_secret: str | None,
    trigger_paths: list[str],
    graphs: list[str],
    artifact_name: str,
    symlink_mode: str,
) -> str:
    env = {
        "HOMEBREW_NO_AUTO_UPDATE": "1",
        "SKILL_MANAGER_HOME": skill_manager_home,
        "TEST_GRAPH_SKILL_HOME": f"{skill_manager_home}/skills/test-graph",
        "TEST_GRAPH_ROOT": "${{ github.workspace }}/test_graph",
    }
    if token_secret:
        secret = f"${{{{ secrets.{token_secret} }}}}"
        env.update(
            {
                "SKILL_MANAGER_GITHUB_TOKEN": secret,
                "GH_TOKEN": secret,
                "GITHUB_TOKEN": secret,
            }
        )

    workflow = [
        f"name: {_yaml_scalar(workflow_title)}",
        "",
        "on:",
        "  workflow_dispatch:",
        "  pull_request:",
        "    paths:",
        *_yaml_list(trigger_paths, 6),
        "  push:",
        "    branches: [main]",
        "    paths:",
        *_yaml_list(trigger_paths, 6),
        "",
        "permissions:",
        "  contents: read",
        "",
        "jobs:",
        f"  {job_id}:",
        f"    name: {_yaml_scalar(job_name)}",
        f"    runs-on: {_yaml_scalar(runner)}",
        f"    timeout-minutes: {timeout_minutes}",
        "",
        "    env:",
        *_yaml_map(env, 6),
        "",
        "    steps:",
        "      - name: Check out repository",
        "        uses: actions/checkout@v4",
        "",
    ]

    if token_secret:
        workflow.extend(
            [
                "      - name: Check optional private-install token",
                "        run: |",
                "          if [ -z \"${SKILL_MANAGER_GITHUB_TOKEN:-}\" ]; then",
                f"            echo \"::error::Missing {token_secret} repository secret for private skill installs.\"",
                "            exit 1",
                "          fi",
                "",
            ]
        )

    workflow.extend(
        [
            "      - name: Prepare skill-manager home",
            "        run: |",
            "          sudo mkdir -p \"$SKILL_MANAGER_HOME\"",
            "          sudo chown -R \"$USER\":\"$(id -gn)\" \"$SKILL_MANAGER_HOME\"",
            "          echo \"$SKILL_MANAGER_HOME/bin/cli\" >> \"$GITHUB_PATH\"",
            "          echo \"$SKILL_MANAGER_HOME/pm/uv/current/bin\" >> \"$GITHUB_PATH\"",
            "          echo \"$SKILL_MANAGER_HOME/pm/node/current/bin\" >> \"$GITHUB_PATH\"",
            "",
            "      - name: Restore Homebrew and build caches",
            "        uses: actions/cache@v4",
            "        with:",
            "          path: |",
            "            ~/Library/Caches/Homebrew",
            "            ~/.gradle/caches",
            "            ~/.gradle/wrapper",
            "            ~/.jbang",
            "            ~/.cache/uv",
            "            ~/Library/Caches/uv",
            "          key: ${{ runner.os }}-test-graph-build-${{ hashFiles('test_graph/gradle/wrapper/gradle-wrapper.properties', 'test_graph/**/*.gradle.kts') }}",
            "          restore-keys: |",
            "            ${{ runner.os }}-test-graph-build-",
            "",
            "      - name: Restore skill-manager tool caches",
            "        id: skill-manager-tool-cache",
            "        uses: actions/cache/restore@v4",
            "        with:",
            "          path: |",
            "            ${{ env.SKILL_MANAGER_HOME }}/pm",
            "            ${{ env.SKILL_MANAGER_HOME }}/npm",
            "            ${{ env.SKILL_MANAGER_HOME }}/bin/cli",
            "          key: ${{ runner.os }}-skill-manager-tools-v1",
            "          restore-keys: |",
            "            ${{ runner.os }}-skill-manager-tools-",
            "",
            "      - name: Install Homebrew packages",
            "        run: |",
            "          brew tap haydenrear/skill-manager",
            "          if brew list skill-manager >/dev/null 2>&1; then",
            "            brew upgrade haydenrear/skill-manager/skill-manager || true",
            "          else",
            "            brew install haydenrear/skill-manager/skill-manager",
            "          fi",
            "          if brew list jbang >/dev/null 2>&1; then",
            "            brew upgrade jbang || true",
            "          else",
            "            brew install jbang",
            "          fi",
            "          if brew list python@3.13 >/dev/null 2>&1; then",
            "            brew upgrade python@3.13 || true",
            "          else",
            "            brew install python@3.13",
            "          fi",
            "          PYTHON_313=\"$(brew --prefix python@3.13)/bin/python3.13\"",
            "          echo \"UV_PYTHON=$PYTHON_313\" >> \"$GITHUB_ENV\"",
            "          echo \"$(brew --prefix python@3.13)/bin\" >> \"$GITHUB_PATH\"",
            "          skill-manager --help >/dev/null",
            "          jbang --version",
            "          \"$PYTHON_313\" --version",
            "",
            "      - name: Install test-graph skill",
            "        run: |",
            f"          skill-manager install -y {_shell_quote(skill_coordinate)}",
            "          test -d \"$TEST_GRAPH_SKILL_HOME/project_sdk_sources/sdk\"",
            "          test -d \"$TEST_GRAPH_SKILL_HOME/project_sdk_sources/build-logic\"",
            "",
            "      - name: Save skill-manager tool caches",
            "        if: always() && steps.skill-manager-tool-cache.outputs.cache-hit != 'true'",
            "        uses: actions/cache/save@v4",
            "        with:",
            "          path: |",
            "            ${{ env.SKILL_MANAGER_HOME }}/pm",
            "            ${{ env.SKILL_MANAGER_HOME }}/npm",
            "            ${{ env.SKILL_MANAGER_HOME }}/bin/cli",
            "          key: ${{ runner.os }}-skill-manager-tools-v1",
            "",
        ]
    )

    workflow.extend(_symlink_step(symlink_mode))
    workflow.extend(_discover_step(graphs))
    workflow.extend(_run_step(graphs))
    workflow.extend(
        [
            "      - name: Upload validation reports",
            "        if: always()",
            "        uses: actions/upload-artifact@v4",
            "        with:",
            f"          name: {_yaml_scalar(artifact_name)}",
            "          path: test_graph/build/validation-reports/",
            "          if-no-files-found: warn",
            "",
        ]
    )
    return "\n".join(workflow)


def _symlink_step(mode: str) -> list[str]:
    if mode == "preserve":
        return [
            "      - name: Validate scaffold symlinks resolve to skill-manager install",
            "        run: |",
            "          test \"$(readlink \"$TEST_GRAPH_ROOT/sdk\")\" = \"$TEST_GRAPH_SKILL_HOME/project_sdk_sources/sdk\"",
            "          test \"$(readlink \"$TEST_GRAPH_ROOT/build-logic\")\" = \"$TEST_GRAPH_SKILL_HOME/project_sdk_sources/build-logic\"",
            "",
        ]
    return [
        "      - name: Point scaffold symlinks at installed skill",
        "        run: |",
        "          relink() {",
        "            name=\"$1\"",
        "            link=\"$TEST_GRAPH_ROOT/$name\"",
        "            target=\"$TEST_GRAPH_SKILL_HOME/project_sdk_sources/$name\"",
        "            if [ -L \"$link\" ]; then",
        "              rm \"$link\"",
        "            fi",
        "            if [ ! -e \"$link\" ]; then",
        "              ln -s \"$target\" \"$link\"",
        "            fi",
        "            if [ -L \"$link\" ]; then",
        "              test \"$(readlink \"$link\")\" = \"$target\"",
        "            else",
        "              test -d \"$link\"",
        "            fi",
        "          }",
        "          relink sdk",
        "          relink build-logic",
        "",
    ]


def _discover_step(graphs: list[str]) -> list[str]:
    lines = [
        "      - name: Discover test graph",
        "        run: |",
        "          \"$TEST_GRAPH_SKILL_HOME/scripts/discover.py\"",
    ]
    for graph in graphs:
        lines.append(f"          \"$TEST_GRAPH_SKILL_HOME/scripts/discover.py\" {_shell_quote(graph)}")
    lines.append("")
    return lines


def _run_step(graphs: list[str]) -> list[str]:
    lines = [
        "      - name: Run test graph",
        "        working-directory: test_graph",
        "        run: |",
    ]
    if graphs:
        for graph in graphs:
            lines.append(f"          \"$TEST_GRAPH_SKILL_HOME/scripts/run.py\" {_shell_quote(graph)}")
    else:
        lines.append("          \"$TEST_GRAPH_SKILL_HOME/scripts/run.py\" --all")
    lines.append("")
    return lines


def _yaml_list(values: list[str], indent: int) -> list[str]:
    prefix = " " * indent
    return [f"{prefix}- {_yaml_scalar(value)}" for value in values]


def _yaml_map(values: dict[str, str], indent: int) -> list[str]:
    prefix = " " * indent
    return [f"{prefix}{key}: {_yaml_scalar(value)}" for key, value in values.items()]


def _yaml_scalar(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _shell_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


if __name__ == "__main__":
    sys.exit(main())
