"""Scaffold copy-mode, and the legacy preserve-mode workflow shape.

Managed bindings (``provider-bindings.json`` plus generated, ignored links)
are covered by ``test_provider_bindings.py``. What is left here is the two
paths that still commit or still bake in a filesystem path:

* ``scaffold.py --copy-sdk``, which writes real directories instead of
  bindings and therefore has no manifest to regenerate anything from;
* ``github-action.py --symlink-mode preserve``, the legacy-only mode that
  reads a committed symlink and writes a path into committed YAML.

The defect the preserve-mode cases pin down: ``_skill_manager_home`` used to
return the inferred home verbatim, so a project whose home sits inside its
own checkout (``<repo>/.skill-manager``) got the generating machine's
absolute path written into the workflow - a path no runner's workspace can
ever equal, and one that nothing regenerates because the workflow is
committed.
"""
from __future__ import annotations

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import _common  # noqa: E402
import scaffold  # noqa: E402


LINK_NAMES = tuple(sorted(_common.PROVIDER_BINDINGS))


def _run(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, *arguments], text=True, capture_output=True, check=False
    )


class CopySdkScaffoldTest(unittest.TestCase):
    """``--copy-sdk`` must stay a self-contained snapshot, not a binding."""

    def test_copy_sdk_writes_real_directories_and_no_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"

            completed = _run(str(SCRIPTS_DIR / "scaffold.py"), str(repo), "--copy-sdk")

            self.assertEqual(0, completed.returncode, completed.stderr)
            root = repo / "test_graph"
            for name in LINK_NAMES:
                entry = root / name
                self.assertTrue(entry.is_dir(), f"{name} missing")
                self.assertFalse(entry.is_symlink(), f"{name} should be a snapshot copy")
            self.assertFalse(
                (root / _common.PROVIDER_BINDINGS_MANIFEST).exists(),
                "a copy-mode scaffold has nothing to materialize, so a manifest "
                "would only invite prepare-bindings.py to overwrite the copies",
            )
            ignore = root / ".gitignore"
            if ignore.exists():
                self.assertNotIn(
                    _common.PROVIDER_BINDING_IGNORE_BEGIN,
                    ignore.read_text(encoding="utf-8"),
                    "copied SDK directories must stay tracked",
                )

    def test_user_edited_entries_are_copies_in_managed_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"

            completed = _run(str(SCRIPTS_DIR / "scaffold.py"), str(repo))

            self.assertEqual(0, completed.returncode, completed.stderr)
            root = repo / "test_graph"
            for name in ("sources", "build.gradle.kts", "settings.gradle.kts"):
                entry = root / name
                self.assertTrue(entry.exists(), f"{name} missing")
                self.assertFalse(entry.is_symlink(), f"{name} should be a copy")


class ManagedBindingFallbackTest(unittest.TestCase):
    """Scaffold's fallback shares one rollback with migrate-bindings.py.

    Where symlinks are unavailable - Windows without developer mode is the
    case this exists for - the managed attempt must leave nothing of itself
    behind before the copies land, or the copied directories arrive under an
    ignore block that hides them from the commit.
    """

    def test_symlink_failure_rolls_back_before_falling_back_to_copies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"
            stderr = io.StringIO()

            with patch.object(
                _common.os, "symlink", side_effect=OSError("symlinks unavailable")
            ), patch.object(sys, "argv", ["scaffold.py", str(repo)]):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(
                    stderr
                ):
                    self.assertEqual(0, scaffold.main())

            self.assertIn("falling back to copies", stderr.getvalue())
            root = repo / "test_graph"
            self.assertFalse(
                (root / _common.PROVIDER_BINDINGS_MANIFEST).exists(),
                "a manifest left by the failed attempt would point "
                "prepare-bindings.py at the copies it must not touch",
            )
            for name in LINK_NAMES:
                entry = root / name
                self.assertTrue(entry.is_dir(), f"{name} missing")
                self.assertFalse(entry.is_symlink(), f"{name} should be a copy")
            self.assertEqual(
                (_common.project_sdk_sources() / ".gitignore").read_bytes(),
                (root / ".gitignore").read_bytes(),
                "the ignore file must come back byte for byte, or the copied "
                "SDK directories stay ignored and never get committed",
            )


class LegacyPreserveWorkflowTest(unittest.TestCase):
    """Preserve mode is legacy-only; it still must not bake in a local path."""

    def _legacy_project(self, repo: Path, home: Path, *, relative: bool) -> Path:
        """A pre-managed scaffold: committed links, no manifest."""
        vendored = home / "skills" / "test-graph" / "project_sdk_sources"
        root = repo / "test_graph"
        root.mkdir(parents=True)
        (root / "settings.gradle.kts").write_text(
            'rootProject.name = "validation"\n', encoding="utf-8"
        )
        (root / "build.gradle.kts").write_text("validationGraph { }\n", encoding="utf-8")
        for name in LINK_NAMES:
            (vendored / name).mkdir(parents=True)
            target = (
                os.path.relpath(vendored / name, root)
                if relative
                else str(vendored / name)
            )
            os.symlink(target, root / name, target_is_directory=True)
        return root

    def _workflow(self, repo: Path) -> str:
        completed = _run(
            str(SCRIPTS_DIR / "github-action.py"),
            str(repo),
            "--symlink-mode",
            "preserve",
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        return (repo / ".github/workflows/test-graph.yml").read_text(encoding="utf-8")

    def test_home_inside_the_checkout_is_emitted_as_the_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve() / "repo"
            self._legacy_project(repo, repo / ".skill-manager", relative=True)

            workflow = self._workflow(repo)

            self.assertIn(
                "${{ github.workspace }}/.skill-manager",
                workflow,
                "a home inside the checkout must be expressed relative to the "
                "workspace or the workflow only runs on this machine",
            )
            self.assertNotIn(
                str(repo),
                workflow,
                f"the generating machine's path {repo} leaked into committed YAML",
            )

    def test_home_outside_the_checkout_stays_absolute(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            repo = parent / "repo"
            home = parent / "global-home" / ".skill-manager"
            self._legacy_project(repo, home, relative=False)

            workflow = self._workflow(repo)

            self.assertIn(home.as_posix(), workflow)
            self.assertNotIn("${{ github.workspace }}/.skill-manager", workflow)

    def test_preserve_validation_resolves_links_instead_of_string_matching(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve() / "repo"
            self._legacy_project(repo, repo / ".skill-manager", relative=True)

            workflow = self._workflow(repo)

            self.assertIn('actual="$(cd -P "$link" 2>/dev/null && pwd)"', workflow)
            self.assertNotIn(
                'test "$(readlink "$TEST_GRAPH_ROOT/sdk")" =',
                workflow,
                "a readlink string compare cannot hold for a relative link",
            )
            self.assertIn("migrate-bindings.py", workflow)

    def test_managed_projects_are_refused_by_preserve_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve() / "repo"
            scaffolded = _run(str(SCRIPTS_DIR / "scaffold.py"), str(repo))
            self.assertEqual(0, scaffolded.returncode, scaffolded.stderr)

            completed = _run(
                str(SCRIPTS_DIR / "github-action.py"),
                str(repo),
                "--symlink-mode",
                "preserve",
            )

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("provider-bindings.json", completed.stderr)


if __name__ == "__main__":
    unittest.main()
