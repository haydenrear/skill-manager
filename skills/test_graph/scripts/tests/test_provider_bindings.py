from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
SKILL_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import _common  # noqa: E402


def _scaffold_root(parent: Path) -> Path:
    root = parent / "consumer" / "test_graph"
    root.mkdir(parents=True)
    (root / "settings.gradle.kts").write_text(
        'rootProject.name = "fixture"\n', encoding="utf-8"
    )
    return root


def _provider_root(parent: Path) -> Path:
    root = parent / "provider"
    for relative in _common.PROVIDER_BINDINGS.values():
        (root / relative).mkdir(parents=True)
    return root


def _load_migrate_module():
    """Import migrate-bindings.py, whose hyphenated name blocks `import`."""
    spec = importlib.util.spec_from_file_location(
        "migrate_bindings", SCRIPTS_DIR / "migrate-bindings.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _git(repo: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *arguments],
        text=True,
        capture_output=True,
        check=True,
    ).stdout


def _legacy_git_project(
    parent: Path,
    bindings: tuple[str, ...] | None = None,
) -> tuple[Path, Path, Path]:
    """A committed pre-managed scaffold: tracked links, no manifest.

    ``bindings`` selects which of the three legacy links the project tracked;
    the default is all three. Two-link projects are the real population this
    matters for - four of the eight projects in the first rollout were one.

    Returns ``(repo, test_graph_root, provider_root)``.
    """
    repo = parent / "repo"
    root = _scaffold_root(repo)
    (root / "build.gradle.kts").write_text("validationGraph { }\n", encoding="utf-8")
    (root / ".gitignore").write_text(
        "# project rules that predate managed bindings\n/docs\n/reports\n",
        encoding="utf-8",
    )
    provider = _provider_root(repo)
    for name, relative in _common.PROVIDER_BINDINGS.items():
        if bindings is not None and name not in bindings:
            continue
        os.symlink(provider / relative, root / name, target_is_directory=True)
    (repo / "keep.txt").write_text("keep\n", encoding="utf-8")
    for command in (
        ["init", "-q"],
        ["config", "user.name", "Test Graph"],
        ["config", "user.email", "test-graph@example.invalid"],
        ["add", "."],
        ["commit", "-q", "-m", "legacy scaffold"],
    ):
        _git(repo, *command)
    return repo, root, provider


def _skill_project(
    project_root: Path,
    paths: list[str] | None,
    filename: str = "skill-project.toml",
) -> Path:
    """A project manifest, optionally carrying one ``[[vendored]]`` block.

    ``paths is None`` writes a manifest with no ``[[vendored]]`` at all, which
    is the shape ``meta-orchestrator``'s own ``test_graph/`` is in: a project
    manifest exists, so ``project resolve`` runs, and it validates none of the
    generated bindings.

    ``filename`` exists because ``SkillProjectParser.findManifest`` accepts
    ``skill-manager-project.toml`` too; a search that knows only the primary
    name is blind to a repository that uses the legacy one.
    """
    lines = [
        "[project]",
        'name = "fixture"',
        'version = "0.1.0"',
        "",
        "[skills.test-graph]",
        'source = "github:haydenrear/test_graph_skill"',
        "",
    ]
    if paths is not None:
        lines += [
            "[[vendored]]",
            'name = "test-graph-sdk"',
            "paths = [" + ", ".join(f'"{entry}"' for entry in paths) + "]",
            'from_unit = "test-graph"',
            'from_subpath = "project_sdk_sources"',
            'on_invalid = "error"',
            "",
        ]
    manifest = project_root / filename
    manifest.write_text("\n".join(lines), encoding="utf-8")
    return manifest


def _integration_constituent(parent: Path, with_constituent_git: bool) -> tuple[Path, Path, Path]:
    """The integration-repository shape, with and without constituent ``.git``.

    An integration parent tracks constituent repositories as ordinary files. In
    the parent's MAIN working tree each constituent carries its own ``.git``; in
    an outer WORKTREE none of them does. Nothing else differs, so any check
    whose answer changes between these two fixtures is keying on metadata that
    is an artifact of which tree it happens to be run in.

    Returns ``(integration_root, test_graph_root, provider_root)``. No ``.git``
    anywhere at the integration root either, so the ceiling cannot fall back to
    the parent repository's own metadata.
    """
    integration = parent / "integration-parent"
    constituent = integration / "constituents" / "foo"
    root = constituent / "test_graph"
    root.mkdir(parents=True)
    (root / "settings.gradle.kts").write_text(
        'rootProject.name = "fixture"\n', encoding="utf-8"
    )
    (root / "build.gradle.kts").write_text("validationGraph { }\n", encoding="utf-8")
    provider = _provider_root(constituent)
    for name in ("sdk", "build-logic"):
        os.symlink(
            provider / _common.PROVIDER_BINDINGS[name],
            root / name,
            target_is_directory=True,
        )
    (integration / "integration.toml").write_text(
        '[integration]\nname = "fixture-integration"\nhost = "github"\n\n'
        '[[constituent]]\nname = "foo"\npath = "constituents/foo"\n',
        encoding="utf-8",
    )
    # The integration parent's own manifest. It declares nothing about a
    # constituent's internals, which is exactly how the real one is written.
    _skill_project(integration, None)
    if with_constituent_git:
        (constituent / ".git").mkdir()
    return integration, root, provider


def _skill_install_without_provider(parent: Path) -> Path:
    """A test-graph install whose project_sdk_sources/ is not there.

    This is the shape that makes provider selection fail for real: the
    scripts are present and runnable, so ``skill_root()`` resolves, but the
    skill-root candidate carries none of the three bindings.
    """
    skill = parent / "skill-without-provider"
    (skill / "scripts").mkdir(parents=True)
    for script in SCRIPTS_DIR.glob("*.py"):
        shutil.copy2(script, skill / "scripts" / script.name)
    return skill


def _fake_gradlew(root: Path, output: str) -> None:
    gradlew = root / "gradlew"
    gradlew.write_text(f"#!/bin/sh\necho '{output}'\n", encoding="utf-8")
    gradlew.chmod(gradlew.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class ProviderBindingsTest(unittest.TestCase):
    def test_repo_root_gradle_build_does_not_shadow_child_test_graph(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "consumer"
            repo.mkdir()
            (repo / "settings.gradle.kts").write_text(
                'rootProject.name = "consumer"\n', encoding="utf-8"
            )
            (repo / "build.gradle.kts").write_text(
                'plugins { java }\n', encoding="utf-8"
            )
            test_graph = repo / "test_graph"
            test_graph.mkdir()
            (test_graph / "settings.gradle.kts").write_text(
                'rootProject.name = "validation"\n', encoding="utf-8"
            )
            (test_graph / "build.gradle.kts").write_text(
                'validationGraph { }\n', encoding="utf-8"
            )

            with patch.object(_common.Path, "cwd", return_value=repo):
                self.assertEqual(test_graph.resolve(), _common.target_project_root())

    def test_new_scaffold_uses_ignored_managed_bindings_and_ci_preparation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"
            scaffolded = subprocess.run(
                [sys.executable, str(SCRIPTS_DIR / "scaffold.py"), str(repo)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, scaffolded.returncode, scaffolded.stderr)
            root = repo / "test_graph"
            self.assertTrue((root / _common.PROVIDER_BINDINGS_MANIFEST).is_file())
            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue((root / name).is_symlink())
            ignored = (root / ".gitignore").read_text(encoding="utf-8")
            self.assertIn(_common.PROVIDER_BINDING_IGNORE_BEGIN, ignored)

            workflow = subprocess.run(
                [sys.executable, str(SCRIPTS_DIR / "github-action.py"), str(repo)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, workflow.returncode, workflow.stderr)
            workflow_text = (
                repo / ".github/workflows/test-graph.yml"
            ).read_text(encoding="utf-8")
            self.assertIn("scripts/prepare-bindings.py", workflow_text)
            self.assertIn("Legacy compatibility", workflow_text)

    def test_legacy_links_warn_without_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = _scaffold_root(parent)
            provider = _provider_root(parent)
            for name, relative in _common.PROVIDER_BINDINGS.items():
                os.symlink(provider / relative, root / name, target_is_directory=True)
            before = {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS}

            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                _common.prepare_provider_bindings_or_warn(root)

            self.assertIn("legacy Test Graph provider symlinks remain supported", stderr.getvalue())
            self.assertEqual(
                before,
                {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS},
            )
            self.assertFalse(_common.provider_bindings_manifest(root).exists())

    def test_workspace_provider_is_preferred_and_links_are_relative(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = _scaffold_root(parent)
            provider = _provider_root(parent)
            workspace_relative = os.path.relpath(provider, root)
            _common.write_provider_bindings_manifest(
                root, workspace_provider=workspace_relative
            )

            result = _common.prepare_provider_bindings(root)

            self.assertIsNotNone(result)
            assert result is not None
            self.assertEqual("workspace-relative", result["provider"]["kind"])
            for name, relative in _common.PROVIDER_BINDINGS.items():
                link = root / name
                self.assertTrue(link.is_symlink())
                self.assertFalse(Path(os.readlink(link)).is_absolute())
                self.assertEqual(
                    (provider / relative).resolve(),
                    link.resolve(strict=True),
                )

    def test_missing_workspace_provider_falls_back_to_running_skill(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _scaffold_root(Path(temporary))
            _common.write_provider_bindings_manifest(
                root, workspace_provider="../missing-provider"
            )

            result = _common.prepare_provider_bindings(root)

            self.assertIsNotNone(result)
            assert result is not None
            self.assertEqual("skill-root", result["provider"]["kind"])
            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue(Path(os.readlink(root / name)).is_absolute())

    def test_real_copy_mode_path_is_never_replaced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _scaffold_root(Path(temporary))
            _common.write_provider_bindings_manifest(root)
            (root / "sdk").mkdir()

            with self.assertRaisesRegex(
                _common.ProviderBindingError, "refuses to replace real paths"
            ):
                _common.prepare_provider_bindings(root)

    def test_migration_untracks_only_generated_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"
            root = _scaffold_root(repo)
            provider = _provider_root(repo)
            for name, relative in _common.PROVIDER_BINDINGS.items():
                os.symlink(provider / relative, root / name, target_is_directory=True)
            (repo / "keep.txt").write_text("keep\n", encoding="utf-8")
            for command in (
                ["git", "init", "-q"],
                ["git", "config", "user.name", "Test Graph"],
                ["git", "config", "user.email", "test-graph@example.invalid"],
                ["git", "add", "."],
                ["git", "commit", "-q", "-m", "legacy scaffold"],
            ):
                subprocess.run(command, cwd=repo, check=True)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                    "--workspace-provider",
                    os.path.relpath(provider, root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            tracked = subprocess.run(
                ["git", "ls-files"], cwd=repo, text=True, capture_output=True, check=True
            ).stdout.splitlines()
            self.assertIn("keep.txt", tracked)
            for name in _common.PROVIDER_BINDINGS:
                self.assertNotIn(f"consumer/test_graph/{name}", tracked)
                self.assertTrue((root / name).is_symlink())
            manifest = json.loads(
                (root / _common.PROVIDER_BINDINGS_MANIFEST).read_text(encoding="utf-8")
            )
            self.assertEqual(_common.PROVIDER_BINDINGS_SCHEMA, manifest["schema_version"])
            self.assertIn("/sdk", (root / ".gitignore").read_text(encoding="utf-8"))

    def test_run_gradle_prepares_managed_bindings_before_launch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _scaffold_root(Path(temporary))
            (root / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
            _common.write_provider_bindings_manifest(root)

            with patch.object(
                _common.subprocess,
                "run",
                return_value=type("Result", (), {"returncode": 0})(),
            ) as run:
                self.assertEqual(0, _common.run_gradle(["smoke"], root))

            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue((root / name).is_symlink())
            run.assert_called_once()


class MigrationAtomicityTest(unittest.TestCase):
    """A migration that cannot finish must not start, and must leave no trace.

    The failure being pinned: the manifest, the ignore block and the staged
    link removals used to land before the provider was ever resolved. A
    project left in that state is worse than an unmigrated one -
    ``prepare_provider_bindings_or_warn`` sees a manifest, stops falling back
    to the still-working legacy symlinks, and hard-exits every wrapper.
    """

    def _failing_migration(
        self, root: Path, parent: Path
    ) -> subprocess.CompletedProcess[str]:
        """Migrate from an install whose provider has not landed yet.

        No ``--workspace-provider``: the failure under test is the skill-root
        candidate being incomplete, which is what
        :func:`_common.select_provider_root` decides. An explicitly named
        provider is refused earlier and separately - see
        ``test_an_unresolvable_workspace_provider_is_refused``.
        """
        skill = _skill_install_without_provider(parent)
        return subprocess.run(
            [
                sys.executable,
                str(skill / "scripts" / "migrate-bindings.py"),
                "--test-graph-root",
                str(root),
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_failed_migration_leaves_no_trace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, _provider = _legacy_git_project(parent)
            # Unrelated staged work: a rollback must not reach past its own
            # three paths (which `git reset` or `git checkout -- .` would).
            (repo / "keep.txt").write_text("keep editing\n", encoding="utf-8")
            _git(repo, "add", "keep.txt")

            ignore_before = (root / ".gitignore").read_bytes()
            status_before = _git(repo, "status", "--porcelain")
            links_before = {
                name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS
            }

            completed = self._failing_migration(root, parent)

            self.assertNotEqual(0, completed.returncode, completed.stdout)
            self.assertIn("no complete Test Graph provider", completed.stderr)
            self.assertFalse(
                _common.provider_bindings_manifest(root).exists(),
                "a manifest from a failed migration takes the wrappers off the "
                "legacy path with nothing to replace it",
            )
            self.assertEqual(
                ignore_before,
                (root / ".gitignore").read_bytes(),
                "the managed-bindings ignore block outlived the failed migration",
            )
            self.assertEqual(
                status_before,
                _git(repo, "status", "--porcelain"),
                "the failed migration left staged index changes behind",
            )
            self.assertEqual(
                links_before,
                {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS},
            )

    def test_an_unresolvable_workspace_provider_is_refused(self) -> None:
        """The install is COMPLETE here, so the skill-root fallback would work.

        That is the whole defect: falling back would exit 0 and commit a first
        candidate that resolves on no machine, handing every consumer of the
        repository an absolute skill-root link.
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, _provider = _legacy_git_project(parent)
            ignore_before = (root / ".gitignore").read_bytes()
            status_before = _git(repo, "status", "--porcelain")
            links_before = {
                name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS
            }

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                    # The fan-out typo: .skill-manger, not .skill-manager.
                    "--workspace-provider",
                    "../.skill-manger/skills/test-graph",
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            # State first, deliberately: the failure this pins down exits 0,
            # so an exit-code assertion would be the thing that catches it and
            # the report would say "0 == 0" instead of naming the manifest
            # that should not exist.
            self.assertFalse(
                _common.provider_bindings_manifest(root).exists(),
                "a manifest naming an unresolvable first candidate is the "
                "defect itself, committed",
            )
            self.assertEqual(ignore_before, (root / ".gitignore").read_bytes())
            self.assertEqual(status_before, _git(repo, "status", "--porcelain"))
            self.assertEqual(
                links_before,
                {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS},
            )
            self.assertNotEqual(0, completed.returncode, completed.stdout)
            self.assertIn("--workspace-provider", completed.stderr)
            self.assertIn(
                "Refusing to fall back to the installed skill", completed.stderr
            )

    def test_an_omitted_workspace_provider_still_binds_to_the_installed_skill(
        self,
    ) -> None:
        """Omitting the flag is a deliberate skill-root binding, not a typo."""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, _provider = _legacy_git_project(parent)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            document = json.loads(
                _common.provider_bindings_manifest(root).read_text(encoding="utf-8")
            )
            self.assertEqual(
                [{"kind": "skill-root"}], document["provider_candidates"]
            )
            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue(Path(os.readlink(root / name)).is_absolute())

    def test_a_failing_rollback_reports_the_original_error_too(self) -> None:
        """The rollback's own failure must not eat the reason for the failure."""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(parent)
            lock = repo / ".git" / "index.lock"
            lock.write_text("", encoding="utf-8")
            migrate = _load_migrate_module()
            stderr = io.StringIO()

            with patch.object(
                migrate,
                "rollback_managed_bindings",
                side_effect=OSError("read-only file system"),
            ), patch.object(
                sys,
                "argv",
                [
                    "migrate-bindings.py",
                    "--test-graph-root",
                    str(root),
                    "--workspace-provider",
                    os.path.relpath(provider, root),
                ],
            ):
                with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
                    migrate.main()
            lock.unlink()

            reported = stderr.getvalue()
            self.assertIn(
                "cannot untrack generated provider links",
                reported,
                "the original error is the one that explains the failure",
            )
            self.assertIn("the rollback then failed too", reported)
            self.assertIn("read-only file system", reported)
            self.assertIn("partially migrated", reported)

    def test_a_locked_index_rolls_the_migration_back(self) -> None:
        """The failure that lands *after* the provider resolves.

        Resolving up front cannot help here - a concurrent Git process takes
        ``index.lock`` while the manifest and the ignore block are already
        written - so only the rollback keeps the invariant.
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(parent)
            ignore_before = (root / ".gitignore").read_bytes()
            status_before = _git(repo, "status", "--porcelain")
            links_before = {
                name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS
            }
            lock = repo / ".git" / "index.lock"
            lock.write_text("", encoding="utf-8")

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                    "--workspace-provider",
                    os.path.relpath(provider, root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            lock.unlink()

            self.assertNotEqual(0, completed.returncode, completed.stdout)
            self.assertIn("cannot untrack generated provider links", completed.stderr)
            self.assertFalse(_common.provider_bindings_manifest(root).exists())
            self.assertEqual(ignore_before, (root / ".gitignore").read_bytes())
            self.assertEqual(status_before, _git(repo, "status", "--porcelain"))
            self.assertEqual(
                links_before,
                {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS},
            )

    def test_discover_still_runs_on_the_legacy_path_after_a_failed_migration(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, _provider = _legacy_git_project(parent)
            _fake_gradlew(root, "graphs: smoke")

            self.assertNotEqual(0, self._failing_migration(root, parent).returncode)

            discovered = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "discover.py"),
                    "--test-graph-root",
                    str(root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, discovered.returncode, discovered.stderr)
            self.assertIn("graphs: smoke", discovered.stdout)
            self.assertIn(
                "legacy Test Graph provider symlinks remain supported",
                discovered.stderr,
            )

    def test_successful_migration_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(parent)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                    "--workspace-provider",
                    os.path.relpath(provider, root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn("migrated Test Graph provider bindings", completed.stdout)
            self.assertIn("staged generated-link removals", completed.stdout)
            document = json.loads(
                _common.provider_bindings_manifest(root).read_text(encoding="utf-8")
            )
            self.assertEqual(
                "workspace-relative",
                document["provider_candidates"][0]["kind"],
            )
            ignored = (root / ".gitignore").read_text(encoding="utf-8")
            self.assertIn("# project rules that predate managed bindings", ignored)
            self.assertIn(_common.PROVIDER_BINDING_IGNORE_BEGIN, ignored)
            staged = _git(repo, "status", "--porcelain").splitlines()
            for name in _common.PROVIDER_BINDINGS:
                self.assertIn(f"D  consumer/test_graph/{name}", staged)
                link = root / name
                self.assertTrue(link.is_symlink())
                self.assertFalse(Path(os.readlink(link)).is_absolute())

    def test_rollback_restores_captured_bindings_byte_for_byte(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, provider = _legacy_git_project(parent)
            before = _common.capture_managed_bindings(root)

            _common.write_provider_bindings_manifest(
                root, workspace_provider=os.path.relpath(provider, root)
            )
            _common.ensure_provider_binding_ignores(root)
            _common.prepare_provider_bindings(root)
            _common.rollback_managed_bindings(root, before)

            self.assertFalse(_common.provider_bindings_manifest(root).exists())
            self.assertEqual(before.gitignore, (root / ".gitignore").read_bytes())
            self.assertEqual(
                before.links,
                {
                    name: os.readlink(root / name)
                    for name in sorted(_common.PROVIDER_BINDINGS)
                },
            )

    def test_untracked_index_entries_are_restored_exactly(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, _provider = _legacy_git_project(parent)
            migrate = _load_migrate_module()
            status_before = _git(repo, "status", "--porcelain")
            listed_before = _git(repo, "ls-files", "-s")

            git_root, tracked, entries = migrate._generated_link_index(root)
            assert git_root is not None
            self.assertEqual(len(_common.PROVIDER_BINDINGS), len(tracked))
            migrate._untrack_generated_links(git_root, tracked)
            self.assertNotEqual(status_before, _git(repo, "status", "--porcelain"))

            migrate._restore_index(git_root, entries)

            self.assertEqual(status_before, _git(repo, "status", "--porcelain"))
            self.assertEqual(listed_before, _git(repo, "ls-files", "-s"))


class CanonicalBindingSetTest(unittest.TestCase):
    """Migration normalizes up to the canonical set, and cannot do it quietly.

    Two propositions, tested separately because they fail separately:

    1. A two-link project gains ``standard-nodes`` and the run SAYS which
       bindings it added; a three-link project says, in a distinguishable
       sentence, that it added none. A report that only appears when something
       happened is indistinguishable from a report that was never written.

    2. A project whose ``skill-project.toml`` would not validate every binding
       this run generates is refused BEFORE the first write.
       ``ProjectVendoredResolver.check`` iterates declared paths only, so an
       undeclared generated link is neither an error nor a warning - it is
       never classified - which is why the disagreement has to be prevented
       rather than reported downstream.
    """

    def _migrate(self, root: Path, provider: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPTS_DIR / "migrate-bindings.py"),
                "--test-graph-root",
                str(root),
                "--workspace-provider",
                os.path.relpath(provider, root),
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    # ------------------------------------------------------------ half one

    def test_a_two_link_project_names_the_binding_it_gained(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, provider = _legacy_git_project(
                parent, bindings=("sdk", "build-logic")
            )
            self.assertFalse((root / "standard-nodes").exists())

            completed = self._migrate(root, provider)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn(
                "generated links the project did not have: standard-nodes",
                completed.stdout,
            )
            # Bytes, not the exit code: the manifest that was actually written.
            document = json.loads(
                _common.provider_bindings_manifest(root).read_text(encoding="utf-8")
            )
            self.assertEqual(
                ["build-logic", "sdk", "standard-nodes"],
                sorted(document["bindings"]),
            )
            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue((root / name).is_symlink(), name)
                self.assertTrue((root / name).resolve().is_dir(), name)

    def test_a_three_link_project_says_it_added_nothing(self) -> None:
        """The other half. Without it, a report that never fires still passes."""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, provider = _legacy_git_project(parent)

            completed = self._migrate(root, provider)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn(
                "generated links the project did not have: none "
                "(1:1 with the links found)",
                completed.stdout,
            )
            self.assertNotIn(
                "generated links the project did not have: standard-nodes",
                completed.stdout,
            )

    # ------------------------------------------------------------ half two

    def test_a_partial_vendored_declaration_is_refused_before_any_write(self) -> None:
        """Two of three declared: the shipped hyper-experiments-finance shape."""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(
                parent, bindings=("sdk", "build-logic")
            )
            _skill_project(
                repo,
                ["consumer/test_graph/sdk", "consumer/test_graph/build-logic"],
            )
            ignore_before = (root / ".gitignore").read_bytes()
            status_before = _git(repo, "status", "--porcelain")
            links_before = {
                name: os.readlink(root / name)
                for name in _common.PROVIDER_BINDINGS
                if (root / name).is_symlink()
            }

            completed = self._migrate(root, provider)

            # State first: an exit-code assertion would pass on a script that
            # refused after writing the manifest, which is the worse outcome.
            self.assertFalse(
                _common.provider_bindings_manifest(root).exists(),
                "the refusal wrote a manifest the [[vendored]] block does not cover",
            )
            self.assertEqual(ignore_before, (root / ".gitignore").read_bytes())
            self.assertEqual(status_before, _git(repo, "status", "--porcelain"))
            self.assertEqual(
                links_before,
                {
                    name: os.readlink(root / name)
                    for name in _common.PROVIDER_BINDINGS
                    if (root / name).is_symlink()
                },
            )
            self.assertFalse((root / "standard-nodes").exists())
            self.assertNotEqual(0, completed.returncode, completed.stdout)
            self.assertIn(
                "declares 2 of the 3 bindings this migration generates and would "
                "not validate the 1 remaining: standard-nodes",
                completed.stderr,
            )
            self.assertIn("test-graph-sdk", completed.stderr)
            self.assertIn(
                "'consumer/test_graph/standard-nodes'",
                completed.stderr,
                "the refusal must print the paths list to paste back",
            )

    def test_a_manifest_claiming_nothing_here_reports_rather_than_deadlocks(
        self,
    ) -> None:
        """meta-orchestrator's own test_graph: nothing validates these bindings.

        Reported, not refused. A manifest that claims none of this project's
        paths may be an integration parent's, whose manifest declares nothing
        about a constituent's internals by design - refusing there is a block no
        operator can clear. The report must still be unmissable.
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(parent)
            _skill_project(repo, None)

            completed = self._migrate(root, provider)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn(
                "vendored-agreement: unclaimed - `skill-manager project resolve` "
                "validates NONE of the 3 managed bindings this run generates",
                completed.stdout,
            )
            self.assertIn(
                "none declaring a path inside this project; nearest is",
                completed.stdout,
            )
            self.assertIn("[[vendored]]", completed.stdout)
            self.assertIn('from_subpath = "project_sdk_sources"', completed.stdout)
            # Bytes: it really did migrate.
            document = json.loads(
                _common.provider_bindings_manifest(root).read_text(encoding="utf-8")
            )
            self.assertEqual(
                ["build-logic", "sdk", "standard-nodes"], sorted(document["bindings"])
            )

    def test_a_legacy_named_manifest_is_searched_too(self) -> None:
        """F2: findManifest accepts skill-manager-project.toml as well.

        A search that knows only the primary name reports "no manifest" about a
        manifest `project resolve` reads, and then materializes the very
        undeclared binding this guard exists to prevent - a zero that means
        "could not look" reported as "looked and found nothing".
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(
                parent, bindings=("sdk", "build-logic")
            )
            _skill_project(
                repo,
                ["consumer/test_graph/sdk", "consumer/test_graph/build-logic"],
                filename="skill-manager-project.toml",
            )
            self.assertFalse((repo / "skill-project.toml").exists())

            completed = self._migrate(root, provider)

            self.assertFalse(
                _common.provider_bindings_manifest(root).exists(),
                "the legacy manifest was not searched, so the guard let the "
                "undeclared binding through",
            )
            self.assertFalse((root / "standard-nodes").exists())
            self.assertNotEqual(0, completed.returncode, completed.stdout)
            self.assertIn("skill-manager-project.toml", completed.stderr)
            self.assertIn(
                "would not validate the 1 remaining: standard-nodes", completed.stderr
            )

    def test_the_verdict_does_not_depend_on_constituent_git_metadata(self) -> None:
        """F1: identical bytes, constituent .git present or not, one answer.

        In an integration repository a constituent carries its own ``.git`` in
        the parent's main working tree and none in an outer worktree. Keying the
        search ceiling on ``git rev-parse --show-toplevel`` therefore gave two
        different verdicts for the same tree, and refused in the worktree - the
        tree the fan-out actually runs in - with a remedy pointing at the
        integration parent's manifest, the wrong record entirely.
        """
        verdicts = {}
        for with_git in (False, True):
            with tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary)
                integration, root, provider = _integration_constituent(
                    parent, with_constituent_git=with_git
                )
                self.assertEqual(
                    with_git, (integration / "constituents" / "foo" / ".git").exists()
                )
                self.assertFalse((integration / ".git").exists())

                completed = self._migrate(root, provider)

                # Bytes, both times: the migration completed.
                document = json.loads(
                    _common.provider_bindings_manifest(root).read_text(encoding="utf-8")
                )
                self.assertEqual(
                    ["build-logic", "sdk", "standard-nodes"],
                    sorted(document["bindings"]),
                    f"with_constituent_git={with_git}",
                )
                self.assertTrue((root / "standard-nodes").is_symlink())
                verdict = [
                    line.strip()
                    for line in completed.stdout.splitlines()
                    if "vendored-agreement:" in line
                ]
                self.assertEqual(1, len(verdict), completed.stdout)
                ceiling = [
                    line.split("search ceiling:", 1)[1]
                    .strip()
                    .replace(str(parent.resolve()), "")
                    for line in completed.stdout.splitlines()
                    if "search ceiling:" in line
                ]
                self.assertEqual(1, len(ceiling), completed.stdout)
                verdicts[with_git] = (
                    completed.returncode,
                    verdict[0].split(" - ")[0],
                    ceiling[0],
                )

        self.assertEqual(
            verdicts[False],
            verdicts[True],
            "the verdict or the ceiling flipped on constituent .git metadata alone",
        )
        self.assertEqual(
            (
                0,
                "vendored-agreement: unclaimed",
                "integration root /integration-parent",
            ),
            verdicts[True],
            "an integration parent's manifest must not produce an unsatisfiable refusal",
        )

    def test_no_manifest_and_a_manifest_claiming_nothing_read_differently(self) -> None:
        """F2b: two different facts must not print as one sentence.

        Both are ``unclaimed``, but "there is no manifest above this project"
        and "a manifest exists and claims none of these paths" are different
        findings, and the verdict line exists precisely so that a reader never
        has to guess which one happened.
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, provider = _legacy_git_project(parent)
            absent = self._migrate(root, provider)
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(parent)
            _skill_project(repo, None)
            present = self._migrate(root, provider)

        self.assertEqual(0, absent.returncode, absent.stderr)
        self.assertEqual(0, present.returncode, present.stderr)
        self.assertIn(
            "no project manifest found in 2 searched directories "
            "(skill-project.toml, skill-manager-project.toml)",
            absent.stdout,
        )
        self.assertNotIn("none declaring a path inside this project", absent.stdout)
        self.assertIn(
            "none declaring a path inside this project; nearest is", present.stdout
        )
        self.assertNotIn("no project manifest found in", present.stdout)
        # Both must name the ceiling they stopped at.
        for completed in (absent, present):
            self.assertIn("search ceiling: repository root", completed.stdout)

    def test_a_complete_vendored_declaration_migrates_and_reports_agreement(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(
                parent, bindings=("sdk", "build-logic")
            )
            _skill_project(
                repo,
                [
                    "consumer/test_graph/sdk",
                    "consumer/test_graph/build-logic",
                    "consumer/test_graph/standard-nodes",
                ],
            )

            completed = self._migrate(root, provider)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn(
                "vendored-agreement: ok - all 3 managed bindings are declared",
                completed.stdout,
            )
            self.assertIn("test-graph-sdk", completed.stdout)

    def test_a_project_with_no_manifest_says_so_rather_than_nothing(self) -> None:
        """A silent pass and an unchecked pass must not look the same."""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, provider = _legacy_git_project(parent)

            completed = self._migrate(root, provider)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn("vendored-agreement: unclaimed", completed.stdout)
            self.assertIn("no project manifest found in", completed.stdout)
            self.assertIn("search ceiling:", completed.stdout)

    def test_a_manifest_outside_the_repository_is_not_consulted(self) -> None:
        """The search is bounded, and the bound is named in the verdict.

        The out-of-repo manifest here DECLARES all three paths, so an unbounded
        search would report ``ok`` on the strength of a manifest above the
        checkout. This is not hypothetical: an earlier mutation run of this
        suite found a stray ``skill-project.toml`` sitting directly in ``$TMPDIR``,
        written by an unrelated process on this machine.
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, provider = _legacy_git_project(parent)
            outside = _skill_project(
                parent,
                [
                    "repo/consumer/test_graph/sdk",
                    "repo/consumer/test_graph/build-logic",
                    "repo/consumer/test_graph/standard-nodes",
                ],
            )
            self.assertTrue(outside.is_file())

            completed = self._migrate(root, provider)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn("vendored-agreement: unclaimed", completed.stdout)
            self.assertNotIn(
                "vendored-agreement: ok",
                completed.stdout,
                "a manifest above the repository decided the verdict",
            )
            self.assertNotIn(str(outside), completed.stdout)

    def test_a_declaration_under_an_absolute_parent_link_still_matches(self) -> None:
        """The disguised shape, on the declaration side.

        ``consumer/`` is an ABSOLUTE symlink, so the declared path
        ``consumer/test_graph/sdk`` is relative text whose parent resolves
        somewhere else entirely. A lexical comparison against the project root
        this migration was handed calls those two paths different and refuses a
        correctly declared project; only comparing resolved physical parents
        gets it right. This is the same shape that defeated three earlier
        checks in this repository.
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo = parent / "repo"
            real = repo / "real"
            root = real / "consumer" / "test_graph"
            root.mkdir(parents=True)
            (root / "settings.gradle.kts").write_text(
                'rootProject.name = "fixture"\n', encoding="utf-8"
            )
            (root / "build.gradle.kts").write_text(
                "validationGraph { }\n", encoding="utf-8"
            )
            provider = _provider_root(repo)
            for name in ("sdk", "build-logic"):
                os.symlink(
                    provider / _common.PROVIDER_BINDINGS[name],
                    root / name,
                    target_is_directory=True,
                )
            # Absolute link text, and it is the PARENT of the declared paths.
            os.symlink(real / "consumer", repo / "consumer", target_is_directory=True)
            self.assertTrue(Path(os.readlink(repo / "consumer")).is_absolute())
            _skill_project(
                repo,
                [
                    "consumer/test_graph/sdk",
                    "consumer/test_graph/build-logic",
                    "consumer/test_graph/standard-nodes",
                ],
            )
            for command in (
                ["init", "-q"],
                ["config", "user.name", "Test Graph"],
                ["config", "user.email", "test-graph@example.invalid"],
                ["add", "."],
                ["commit", "-q", "-m", "legacy scaffold behind a linked parent"],
            ):
                _git(repo, *command)

            completed = self._migrate(root, provider)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn(
                "vendored-agreement: ok - all 3 managed bindings are declared",
                completed.stdout,
            )
            self.assertIn(
                "generated links the project did not have: standard-nodes",
                completed.stdout,
            )


if __name__ == "__main__":
    unittest.main()
