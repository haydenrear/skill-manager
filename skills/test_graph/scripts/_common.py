"""Shared helpers for the test-graph skill scripts.

Two roots to keep straight:

1. ``skill_root()`` — the root of THIS repository (where SKILL.md lives).
   Used by scaffold to locate ``project_sdk_sources/`` and by
   ``new-*-node.py`` to locate the templates.

2. ``target_project_root()`` — the scaffolded test_graph project the
   user is currently operating on. Resolution (highest precedence
   first):

       a. ``--test-graph-root`` flag
       b. ``TEST_GRAPH_ROOT`` env var
       c. Walk up from cwd looking for ``settings.gradle.kts`` — the
          scaffold marker. This wins when the user has cd'd into the
          scaffolded project (or any of its subdirs).
       d. Fall back to ``<cwd>/test_graph/`` if it carries both
          ``settings.gradle.kts`` and a ``build.gradle.kts`` that
          mentions ``validationGraph``. This is the "running from the
          project repo root" convenience: a user at
          ``/path/to/myrepo/`` can invoke the scripts without flags
          and have them target ``/path/to/myrepo/test_graph/``.

The two-root split lets one checked-out skill serve many scaffolded
projects without path guessing or per-invocation flags.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

VALID_KINDS = {"testbed", "fixture", "action", "assertion", "evidence", "report"}

# Dotted lowercase segments: app.running, checkout.smoke, user.seeded.v2, ...
_NODE_ID_RE = re.compile(r"^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$")


def skill_root() -> Path:
    """Root of the test-graph skill repo (where SKILL.md lives).

    Layout: <skill-root>/scripts/_common.py
    parents: [0]=scripts/  [1]=<skill-root>
    """
    return Path(__file__).resolve().parents[1]


def project_sdk_sources() -> Path:
    """Template project directory that gets copied on scaffold."""
    return skill_root() / "project_sdk_sources"


def templates_dir() -> Path:
    return skill_root() / "templates"


def target_project_root(override: str | Path | None = None) -> Path:
    """Locate the active scaffolded test_graph project.

    Resolution order:

    1. ``override`` argument (typically ``--test-graph-root`` on a
       script).
    2. ``TEST_GRAPH_ROOT`` environment variable.
    3. Walk up from cwd looking for ``settings.gradle.kts`` — the
       scaffold marker.
    4. Fall back to ``<cwd>/test_graph/`` when it carries
       ``settings.gradle.kts`` AND a ``build.gradle.kts`` containing
       the literal ``validationGraph`` (the DSL entry point); this is
       the "running from the project repo root" shortcut. The
       ``validationGraph`` substring check guards against picking up an
       unrelated ``test_graph`` directory from some other tool.

    Any explicit override (1 or 2) must still point at a directory
    containing ``settings.gradle.kts`` — otherwise we'd silently write
    into a non-test_graph tree.
    """
    if override is None:
        override = os.environ.get("TEST_GRAPH_ROOT")

    if override is not None:
        root = Path(override).expanduser().resolve()
        if not (root / "settings.gradle.kts").is_file():
            sys.exit(
                f"error: --test-graph-root {root} is not a scaffolded test_graph "
                f"project (no settings.gradle.kts)."
            )
        return root

    cwd = Path.cwd().resolve()

    # (3) Walk up — wins when the user is anywhere inside a scaffold.
    cur = cwd
    while True:
        if (cur / "settings.gradle.kts").is_file():
            return cur
        if cur.parent == cur:
            break
        cur = cur.parent

    # (4) "Running from project repo root" — look for ./test_graph/.
    candidate = cwd / "test_graph"
    if _looks_like_test_graph_root(candidate):
        return candidate

    sys.exit(
        "error: not inside a test_graph project and no scaffolded test_graph/ "
        "found in the current directory.\n"
        "  Scaffold one first with:  "
        f"{skill_root() / 'scripts' / 'scaffold.py'} <repo-root>\n"
        "  Then either cd into <repo-root>/test_graph, run from <repo-root> "
        "directly, or pass --test-graph-root <path> / set TEST_GRAPH_ROOT."
    )


def _looks_like_test_graph_root(candidate: Path) -> bool:
    """True if ``candidate`` is plausibly a scaffolded test_graph project.

    Two cheap signals: ``settings.gradle.kts`` must exist, and
    ``build.gradle.kts`` must mention the ``validationGraph`` DSL
    entry point. The text scan stays a substring match — we don't
    invoke a Gradle parser just to detect the scaffold.
    """
    if not (candidate / "settings.gradle.kts").is_file():
        return False
    bg = candidate / "build.gradle.kts"
    if not bg.is_file():
        return False
    try:
        return "validationGraph" in bg.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return False


def target_sources_dir(override: str | Path | None = None) -> Path:
    """`sources/` inside the active scaffolded project."""
    return target_project_root(override) / "sources"


def add_test_graph_root_arg(parser: argparse.ArgumentParser) -> None:
    """Add the standard ``--test-graph-root`` / ``-R`` flag to a CLI.

    Default is left as ``None`` so :func:`target_project_root` falls
    through to ``TEST_GRAPH_ROOT`` env or auto-detection.
    """
    parser.add_argument(
        "--test-graph-root",
        "-R",
        default=None,
        help="Path to the scaffolded test_graph project. Defaults to "
             "auto-detection: walk up from cwd, then look for ./test_graph/ "
             "in the current directory, then fall back to TEST_GRAPH_ROOT env.",
    )


def validate_node_id(node_id: str) -> None:
    if not _NODE_ID_RE.match(node_id):
        sys.exit(
            f"error: node id must be dotted lowercase segments (got {node_id!r}); "
            f"e.g. 'checkout.smoke'"
        )


def validate_kind(kind: str) -> None:
    if kind not in VALID_KINDS:
        sys.exit(
            f"error: invalid kind {kind!r}; expected one of {sorted(VALID_KINDS)}"
        )


def class_name_from_id(node_id: str) -> str:
    """Camel-case a dotted node id into a Java class name (app.running -> AppRunning)."""
    return "".join(part[:1].upper() + part[1:] for part in node_id.split("."))


def snake_name_from_id(node_id: str) -> str:
    """Snake-case a dotted node id into a Python module name (app.running -> app_running)."""
    return node_id.replace(".", "_")


def render_template(template_path: Path, replacements: dict[str, str]) -> str:
    text = template_path.read_text()
    for key, value in replacements.items():
        text = text.replace(key, value)
    return text


def run_gradle(args: list[str], test_graph_root: str | Path | None = None) -> int:
    """Invoke ``gradlew`` from the active scaffolded project.

    Inherits stdio so the user sees output live. Accepts the same
    ``test_graph_root`` override as :func:`target_project_root`.
    """
    root = target_project_root(test_graph_root)
    gradlew = root / "gradlew"
    cmd = [str(gradlew)] + args if gradlew.exists() else ["gradle"] + args
    env = os.environ.copy()
    proc = subprocess.run(cmd, cwd=root, env=env)
    return proc.returncode
