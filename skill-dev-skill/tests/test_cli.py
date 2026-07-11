from __future__ import annotations

import json
import os
import contextlib
import io
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from skill_dev.cli import main


def run(args, cwd: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    full_env = os.environ.copy()
    if env:
        full_env.update(env)
    return subprocess.run(args, cwd=cwd, env=full_env, text=True, capture_output=True, check=True)


def git(cwd: Path, *args: str) -> str:
    return run(["git", *args], cwd).stdout.strip()


class SkillDevCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.home = self.root / "sm-home"
        self.project = self.root / "project"
        self.installed = self.home / "skills" / "reviewer-skill" / "latest"
        (self.home / "installed").mkdir(parents=True)
        self.installed.mkdir(parents=True)
        self.project.mkdir()

        git(self.project, "init", "-b", "main")
        git(self.project, "config", "user.email", "test@example.com")
        git(self.project, "config", "user.name", "Test User")
        (self.project / "README.md").write_text("project\n")
        git(self.project, "add", "README.md")
        git(self.project, "commit", "-m", "init")

        git(self.installed, "init", "-b", "main")
        git(self.installed, "config", "user.email", "test@example.com")
        git(self.installed, "config", "user.name", "Test User")
        (self.installed / "SKILL.md").write_text("# reviewer\n")
        (self.installed / "skill-manager.toml").write_text('[skill]\nname = "reviewer-skill"\nversion = "0.1.0"\n')
        git(self.installed, "add", "SKILL.md", "skill-manager.toml")
        git(self.installed, "commit", "-m", "install")
        self.installed_head = git(self.installed, "rev-parse", "HEAD")

        record = {
            "name": "reviewer-skill",
            "version": "0.1.0",
            "kind": "GIT",
            "installSource": "GIT",
            "origin": "file://fake",
            "gitHash": self.installed_head,
            "gitRef": "main",
            "unitKind": "SKILL",
        }
        (self.home / "installed" / "reviewer-skill.json").write_text(json.dumps(record))

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def invoke(self, *args: str) -> int:
        old_cwd = Path.cwd()
        old_home = os.environ.get("SKILL_MANAGER_HOME")
        old_path = os.environ.get("PATH")
        try:
            os.chdir(self.project)
            os.environ["SKILL_MANAGER_HOME"] = str(self.home)
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return main(list(args))
        finally:
            os.chdir(old_cwd)
            if old_home is None:
                os.environ.pop("SKILL_MANAGER_HOME", None)
            else:
                os.environ["SKILL_MANAGER_HOME"] = old_home
            if old_path is None:
                os.environ.pop("PATH", None)
            else:
                os.environ["PATH"] = old_path

    def test_open_creates_project_worktree_and_gitignore(self) -> None:
        rc = self.invoke("open", "reviewer-skill", "--branch", "skill-dev/test")

        self.assertEqual(0, rc)
        worktree = self.project / "skill-dev" / "reviewer-skill"
        self.assertTrue((worktree / "SKILL.md").is_file())
        self.assertEqual("skill-dev/test", git(worktree, "branch", "--show-current"))
        self.assertIn("skill-dev/", (self.project / ".gitignore").read_text().splitlines())

    def test_open_refuses_existing_without_reuse(self) -> None:
        self.assertEqual(0, self.invoke("open", "reviewer-skill", "--branch", "skill-dev/test"))

        self.assertEqual(2, self.invoke("open", "reviewer-skill", "--branch", "skill-dev/test-2"))

    def test_git_passthrough_runs_in_worktree(self) -> None:
        self.assertEqual(0, self.invoke("open", "reviewer-skill", "--branch", "skill-dev/test"))

        self.assertEqual(0, self.invoke("git", "reviewer-skill", "--", "status", "--short"))

    def test_sync_delegates_to_skill_manager_merge_from_worktree(self) -> None:
        old_path = os.environ.get("PATH")
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        log = self.root / "sync.log"
        skill_manager = fake_bin / "skill-manager"
        skill_manager.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n%s\\n' \"$SKILL_MANAGER_HOME\" \"$*\" > \"" + str(log) + "\"\n"
        )
        skill_manager.chmod(0o755)
        try:
            os.environ["PATH"] = str(fake_bin) + os.pathsep + os.environ["PATH"]
            self.assertEqual(0, self.invoke("open", "reviewer-skill", "--branch", "skill-dev/test"))

            self.assertEqual(0, self.invoke("sync", "reviewer-skill"))
        finally:
            if old_path is None:
                os.environ.pop("PATH", None)
            else:
                os.environ["PATH"] = old_path
        lines = log.read_text().splitlines()
        self.assertEqual(str(self.home.resolve()), lines[0])
        self.assertEqual(
            f"sync reviewer-skill --from {(self.project / 'skill-dev' / 'reviewer-skill').resolve()} --merge --yes",
            lines[1],
        )

    def test_sync_reports_missing_skill_manager_without_traceback(self) -> None:
        old_path = os.environ.get("PATH")
        self.assertEqual(0, self.invoke("open", "reviewer-skill", "--branch", "skill-dev/test"))
        fake_bin = self.root / "git-only-bin"
        fake_bin.mkdir()
        git_path = shutil.which("git")
        self.assertIsNotNone(git_path)
        (fake_bin / "git").symlink_to(git_path)
        try:
            os.environ["PATH"] = str(fake_bin)

            self.assertEqual(2, self.invoke("sync", "reviewer-skill"))
        finally:
            if old_path is None:
                os.environ.pop("PATH", None)
            else:
                os.environ["PATH"] = old_path

    def test_sync_uses_explicit_home_for_delegate(self) -> None:
        old_home = os.environ.get("SKILL_MANAGER_HOME")
        old_path = os.environ.get("PATH")
        fake_bin = self.root / "home-bin"
        fake_bin.mkdir()
        log = self.root / "explicit-home.log"
        skill_manager = fake_bin / "skill-manager"
        skill_manager.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$SKILL_MANAGER_HOME\" > \"" + str(log) + "\"\n"
        )
        skill_manager.chmod(0o755)
        try:
            os.environ["PATH"] = str(fake_bin) + os.pathsep + os.environ["PATH"]
            os.environ["SKILL_MANAGER_HOME"] = str(self.root / "wrong-home")
            self.assertEqual(0, self.invoke("open", "reviewer-skill", "--branch", "skill-dev/test"))

            old_cwd = Path.cwd()
            try:
                os.chdir(self.project)
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    rc = main(["--home", str(self.home), "sync", "reviewer-skill"])
            finally:
                os.chdir(old_cwd)
        finally:
            if old_home is None:
                os.environ.pop("SKILL_MANAGER_HOME", None)
            else:
                os.environ["SKILL_MANAGER_HOME"] = old_home
            if old_path is None:
                os.environ.pop("PATH", None)
            else:
                os.environ["PATH"] = old_path
        self.assertEqual(0, rc)
        self.assertEqual(str(self.home.resolve()), log.read_text().strip())

    def test_plugin_metadata_resolves_plugin_directory(self) -> None:
        plugin = self.home / "plugins" / "demo-plugin"
        plugin.mkdir(parents=True)
        git(plugin, "init", "-b", "main")
        (plugin / ".claude-plugin").mkdir()
        (plugin / ".claude-plugin" / "plugin.json").write_text("{}")
        git(plugin, "add", ".claude-plugin/plugin.json")
        git(plugin, "-c", "user.email=test@example.com", "-c", "user.name=Test User", "commit", "-m", "plugin")
        head = git(plugin, "rev-parse", "HEAD")
        (self.home / "installed" / "demo-plugin.json").write_text(json.dumps({
            "name": "demo-plugin",
            "version": "0.1.0",
            "kind": "GIT",
            "installSource": "GIT",
            "gitHash": head,
            "gitRef": "main",
            "unitKind": "PLUGIN",
        }))

        self.assertEqual(0, self.invoke("open", "demo-plugin", "--branch", "skill-dev/plugin"))
        self.assertTrue((self.project / "skill-dev" / "demo-plugin" / ".claude-plugin" / "plugin.json").is_file())


if __name__ == "__main__":
    unittest.main()
