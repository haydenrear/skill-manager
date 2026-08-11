"""Regression tests for the five checkpoint-1 defects (issue #80)."""

import json
import subprocess
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from skt import check as check_mod  # noqa: E402
from skt import publish as publish_mod  # noqa: E402
from skt import status  # noqa: E402
from skt.homes import drift_pending  # noqa: E402

from test_check import make_unit_upstream, unit_record  # noqa: E402
from test_status import make_home, make_repo  # noqa: E402
from test_ticket_publish import edited_home, recording_cli  # noqa: E402


@pytest.fixture(autouse=True)
def isolate_root_home(tmp_path, monkeypatch):
    monkeypatch.delenv("SKILL_MANAGER_HOME", raising=False)
    monkeypatch.delenv("SKILL_MANAGER_CLI", raising=False)
    monkeypatch.setenv("SKT_ROOT_HOME", str(tmp_path / "fake-root" / ".skill-manager"))


# 1. drift acknowledged ------------------------------------------------------


def test_acknowledged_drift_is_not_pending(tmp_path):
    home = tmp_path / ".skill-manager"
    home.mkdir()
    (home / "home.drift.json").write_text(json.dumps({"acknowledged": True, "units": ["x"]}))
    assert drift_pending(home) is False


def test_unacknowledged_drift_is_pending(tmp_path):
    home = tmp_path / ".skill-manager"
    home.mkdir()
    (home / "home.drift.json").write_text(json.dumps({"acknowledged": False, "units": ["x"]}))
    assert drift_pending(home) is True


def test_unreadable_drift_record_counts_as_pending(tmp_path):
    home = tmp_path / ".skill-manager"
    home.mkdir()
    (home / "home.drift.json").write_text("{not json")
    assert drift_pending(home) is True


# 2. parent-home resolution fails loudly -------------------------------------


def test_worktree_without_project_home_fails_loudly(tmp_path, capsys):
    repo = make_repo(tmp_path / "repo")  # main tree, NO home here
    subprocess.run(
        ["git", "-C", str(repo), "worktree", "add", "-q", "-b", "feature/T-1",
         str(tmp_path / "repo-T-1")],
        check=True,
    )
    wt = tmp_path / "repo-T-1"
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(wt, units={"alpha": unit_record(bare, tip)})
    unit_dir = home / "skills" / "alpha"
    subprocess.run(["git", "clone", "-q", str(bare), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# improved\n")
    recording_cli(home)
    rc = publish_mod.run("alpha", ticket="T-1", start=wt)
    out = capsys.readouterr().out
    assert rc == 1
    assert "cannot resolve the parent home" in out
    assert "--to <parent-home>" in out
    # crucially: the CLI was never invoked — no silent skip-and-publish
    assert not (home / "cli-calls.log").exists() or "unit publish" not in (
        (home / "cli-calls.log").read_text()
    )


def test_worktree_with_project_home_syncs_into_it(tmp_path, capsys):
    repo = make_repo(tmp_path / "repo")
    make_home(repo, units={})  # the main tree HAS a home
    subprocess.run(
        ["git", "-C", str(repo), "worktree", "add", "-q", "-b", "feature/T-2",
         str(tmp_path / "repo-T-2")],
        check=True,
    )
    wt = tmp_path / "repo-T-2"
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(wt, units={"alpha": unit_record(bare, tip)})
    unit_dir = home / "skills" / "alpha"
    subprocess.run(["git", "clone", "-q", str(bare), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# improved\n")
    calls = recording_cli(home)
    assert publish_mod.run("alpha", ticket="T-2", start=wt) == 0
    logged = calls.read_text()
    assert f"--to {repo / '.skill-manager'}" in logged


# 3. CLI-pin livelock guard --------------------------------------------------


def test_skill_manager_cli_env_is_stripped(tmp_path, monkeypatch, capsys):
    repo, home, _ = edited_home(tmp_path)
    fake_root = tmp_path / "fake-root" / ".skill-manager"
    (fake_root / "installed").mkdir(parents=True)
    calls = home / "cli-calls.log"
    cli = home / "bin" / "cli" / "skill-manager"
    cli.parent.mkdir(parents=True, exist_ok=True)
    cli.write_text(
        "#!/usr/bin/env bash\n"
        f'echo "SMC=${{SKILL_MANAGER_CLI:-unset}} $@" >> "{calls}"\n'
    )
    cli.chmod(0o755)
    monkeypatch.setenv("SKILL_MANAGER_CLI", str(cli))  # the livelock setup
    assert publish_mod.run("alpha", ticket="T-7", start=repo) == 0
    for line in calls.read_text().splitlines():
        assert line.startswith("SMC=unset ")


# 4. hook-path robustness ----------------------------------------------------


def test_remote_tip_timeout_is_unverifiable_not_a_crash(tmp_path, monkeypatch, capsys):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    make_home(repo, units={"alpha": unit_record(bare, tip)})

    def hang(*a, **k):
        raise subprocess.TimeoutExpired(cmd="git ls-remote", timeout=10)

    monkeypatch.setattr(check_mod, "_remote_tip", hang)
    report = check_mod.collect(repo)
    assert report["unverifiable"] == ["alpha"]
    assert report["notifications"] == []
    assert "unverifiable" in check_mod.render_text(report)


def test_check_run_never_raises(tmp_path, monkeypatch):
    def explode(*a, **k):
        raise RuntimeError("boom")

    monkeypatch.setattr(check_mod, "collect", explode)
    assert check_mod.run(as_json=False, cached=False, start=tmp_path) == 1


def test_ls_remotes_run_in_parallel(tmp_path, monkeypatch):
    repo = make_repo(tmp_path / "repo")
    units = {}
    for i in range(6):
        bare, tip = make_unit_upstream(tmp_path, f"unit{i}")
        units[f"unit{i}"] = unit_record(bare, tip)
    make_home(repo, units=units)

    def slow_tip(origin, ref):
        time.sleep(0.3)
        return "0" * 40

    monkeypatch.setattr(check_mod, "_remote_tip", slow_tip)
    t0 = time.monotonic()
    check_mod.collect(repo)
    elapsed = time.monotonic() - t0
    assert elapsed < 1.2  # serial would be >= 1.8s


# 6. context purity ----------------------------------------------------------


def test_epic_field_is_clean_when_not_on_branch(tmp_path):
    repo = make_repo(tmp_path / "repo")
    subprocess.run(["git", "-C", str(repo), "branch", "epic/myfeature"], check=True)
    make_home(repo, units={})
    report = status.collect(repo)
    assert report["checkout"]["epic"] == "myfeature"
    assert report["checkout"]["on_epic_branch"] is False
    assert "available" in status.render_text(report)


def test_epic_field_on_branch(tmp_path):
    repo = make_repo(tmp_path / "repo", branch="epic/myfeature")
    make_home(repo, units={})
    report = status.collect(repo)
    assert report["checkout"]["epic"] == "myfeature"
    assert report["checkout"]["on_epic_branch"] is True
