"""Regressions for checkpoint-2 hook-contract findings (issue #82)."""

import json
import subprocess
import sys
import types
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from skt import check as check_mod  # noqa: E402
from skt import ticket as ticket_mod  # noqa: E402

from test_check import advance_upstream, make_unit_upstream, unit_record  # noqa: E402
from test_hooks import HOOKS  # noqa: E402
from test_status import make_home, make_repo  # noqa: E402
from test_ticket_publish import fake_giw  # noqa: E402


@pytest.fixture(autouse=True)
def isolate_root_home(tmp_path, monkeypatch):
    monkeypatch.delenv("SKILL_MANAGER_HOME", raising=False)
    monkeypatch.setenv("SKT_ROOT_HOME", str(tmp_path / "fake-root" / ".skill-manager"))


def run_post_tool(home: Path, cwd: Path) -> subprocess.CompletedProcess:
    env = {
        "PATH": "/usr/bin:/bin",
        "SKT_PYTHON": sys.executable,
        "SKILL_MANAGER_HOME": str(home),
        "CLAUDE_PLUGIN_ROOT": str(Path(__file__).resolve().parents[1]),
        "CLAUDE_SESSION_ID": "dedup-session",
    }
    return subprocess.run(
        ["bash", str(HOOKS / "skt-post-tool.sh")],
        capture_output=True, text=True, cwd=cwd, env=env,
    )


def test_post_tool_notifies_once_per_check_result(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    advance_upstream(bare, tmp_path)
    first = run_post_tool(home, repo)
    assert first.returncode == 0
    assert "new version available" in json.loads(first.stdout)["hookSpecificOutput"]["additionalContext"]
    second = run_post_tool(home, repo)
    assert second.returncode == 0
    assert second.stdout.strip() == "", "same cached result must not re-inject"
    log = (home / "logs" / "skt" / "hook.log").read_text()
    assert log.count("check-notified") == 1


def test_network_budget_yields_unverifiable_not_late(tmp_path, monkeypatch):
    repo = make_repo(tmp_path / "repo")
    units = {}
    for i in range(4):
        bare, tip = make_unit_upstream(tmp_path, f"unit{i}")
        units[f"unit{i}"] = unit_record(bare, tip)
    make_home(repo, units=units)

    def slow_tip(origin, ref):
        import time

        time.sleep(0.5)
        return "0" * 40

    monkeypatch.setattr(check_mod, "_remote_tip", slow_tip)
    monkeypatch.setattr(check_mod, "NETWORK_BUDGET_SECONDS", 0.2)
    report = check_mod.collect(repo)
    assert len(report["unverifiable"]) >= 1, "budget-exceeded units become unverifiable"


def test_close_note_inspects_target_worktree_home(tmp_path, monkeypatch, capsys):
    # The worktree being closed has an edited unit; cwd is somewhere else
    # with a clean home. The note must name the worktree's edited unit.
    elsewhere = make_repo(tmp_path / "elsewhere")
    make_home(elsewhere, units={})
    wt_repo = make_repo(tmp_path / "wt-repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    wt_home = make_home(wt_repo, units={"alpha": unit_record(bare, tip)})
    unit_dir = wt_home / "skills" / "alpha"
    subprocess.run(["git", "clone", "-q", str(bare), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# improved\n")

    giw = fake_giw(monkeypatch)
    giw.wt_info = lambda t, **k: types.SimpleNamespace(
        worktree=str(wt_repo), branch="feature/T-1", close="wt close T-1",
        launch=None, if_exit_8=None, propagate=None,
    )
    monkeypatch.chdir(elsewhere)
    assert ticket_mod.run("close", "T-1") == 0
    out = capsys.readouterr().out
    assert "edited unit(s) in this home before close: alpha" in out
