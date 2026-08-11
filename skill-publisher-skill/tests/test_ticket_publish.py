import json
import stat
import subprocess
import sys
import types
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from skt import publish as publish_mod  # noqa: E402
from skt import ticket as ticket_mod  # noqa: E402

from test_check import make_unit_upstream, unit_record  # noqa: E402
from test_status import make_home, make_repo  # noqa: E402


@pytest.fixture(autouse=True)
def isolate_root_home(tmp_path, monkeypatch):
    monkeypatch.delenv("SKILL_MANAGER_HOME", raising=False)
    monkeypatch.setenv("SKT_ROOT_HOME", str(tmp_path / "fake-root" / ".skill-manager"))


# --- fake git_issue_workflow wrapper ---------------------------------------


class FakeWtError(RuntimeError):
    def __init__(self, reason, fix="", log="", exit_code=1):
        super().__init__(reason)
        self.reason, self.fix, self.log, self.exit_code = reason, fix, log, exit_code


class FakeBootstrapFailed(FakeWtError):
    pass


class FakeCloseRefused(FakeWtError):
    pass


def fake_giw(monkeypatch, **behaviors):
    mod = types.ModuleType("git_issue_workflow")
    mod.WtError = FakeWtError
    mod.BootstrapFailed = FakeBootstrapFailed
    mod.CloseRefused = FakeCloseRefused
    contract = types.SimpleNamespace(
        worktree="/tmp/repo-T-1", branch="feature/T-1", close="wt close T-1",
        launch="/tmp/repo-T-1/.skill-manager/bin/launch/claude",
        if_exit_8=None, propagate=None,
    )
    mod.wt_new = behaviors.get("wt_new", lambda t, b=None, **k: contract)
    mod.wt_info = behaviors.get("wt_info", lambda t, **k: contract)
    mod.wt_close = behaviors.get(
        "wt_close",
        lambda t, **k: types.SimpleNamespace(
            worktree="/tmp/repo-T-1", branch="feature/T-1", delete=None,
            home_work="/repo/.skill-manager (one tier only)", dry_run_clean=False,
        ),
    )
    monkeypatch.setitem(sys.modules, "git_issue_workflow", mod)
    return mod


def test_ticket_new_prints_contract_and_home_warning(tmp_path, monkeypatch, capsys):
    fake_giw(monkeypatch)
    assert ticket_mod.run("new", "T-1") == 0
    out = capsys.readouterr().out
    assert "/tmp/repo-T-1" in out
    assert "skt publish" in out  # the home warning


def test_ticket_close_refused_renders_remedy(tmp_path, monkeypatch, capsys):
    def refuse(t, **k):
        raise FakeCloseRefused(
            "the home still holds work (unit: eval-unit)",
            fix="skill-manager home sync --from /w/.skill-manager --to /r/.skill-manager --merge",
        )

    fake_giw(monkeypatch, wt_close=refuse)
    assert ticket_mod.run("close", "T-1") == 4
    out = capsys.readouterr().out
    assert "close refused" in out
    assert "fix:" in out


def test_ticket_close_success_reports_home_work(tmp_path, monkeypatch, capsys):
    fake_giw(monkeypatch)
    assert ticket_mod.run("close", "T-1") == 0
    out = capsys.readouterr().out
    assert "home-work:" in out


def test_ticket_requires_verb_and_id(capsys):
    assert ticket_mod.run(None, None) == 1


# --- publish ----------------------------------------------------------------


def edited_home(tmp_path, monkeypatch=None):
    """A project home whose store unit checkout carries an uncommitted edit."""
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    unit_dir = home / "skills" / "alpha"
    subprocess.run(["git", "clone", "-q", str(bare), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# improved\n")
    return repo, home, bare


def recording_cli(home: Path, fail_on: str | None = None) -> Path:
    calls = home / "cli-calls.log"
    cli = home / "bin" / "cli" / "skill-manager"
    cli.parent.mkdir(parents=True, exist_ok=True)
    body = f'echo "$@" >> "{calls}"\n'
    if fail_on:
        body += f'case "$1 $2" in "{fail_on}") exit 5;; esac\n'
    cli.write_text("#!/usr/bin/env bash\n" + body)
    cli.chmod(cli.stat().st_mode | stat.S_IEXEC)
    return calls


def test_publish_check_lists_and_exits_10(tmp_path, capsys):
    repo, home, _ = edited_home(tmp_path)
    assert publish_mod.run(None, check_only=True, start=repo) == 10
    assert "alpha (dirty)" in capsys.readouterr().out


def test_publish_check_clean_home(tmp_path, capsys):
    repo = make_repo(tmp_path / "repo")
    make_home(repo, units={})
    assert publish_mod.run(None, check_only=True, start=repo) == 0


def test_publish_project_tier_syncs_to_root_then_publishes(tmp_path, monkeypatch, capsys):
    repo, home, _ = edited_home(tmp_path)
    fake_root = tmp_path / "fake-root" / ".skill-manager"
    (fake_root / "installed").mkdir(parents=True)
    calls = recording_cli(home)
    assert publish_mod.run("alpha", ticket="T-7", start=repo) == 0
    logged = calls.read_text().splitlines()
    assert logged[0].startswith("home sync --from")
    assert str(fake_root) in logged[0]
    assert logged[1] == "unit publish alpha --ticket T-7"


def test_publish_root_tier_skips_home_sync(tmp_path, monkeypatch, capsys):
    fake_root = tmp_path / "fake-root"
    repo = make_repo(fake_root / "anywhere")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(fake_root, units={"alpha": unit_record(bare, tip)})
    unit_dir = home / "skills" / "alpha"
    subprocess.run(["git", "clone", "-q", str(bare), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# improved\n")
    monkeypatch.setenv("SKILL_MANAGER_HOME", str(home))
    calls = recording_cli(home)
    assert publish_mod.run("alpha", ticket="T-7", start=repo) == 0
    logged = calls.read_text().splitlines()
    assert len(logged) == 1 and logged[0].startswith("unit publish alpha")


def test_publish_sync_failure_stops_with_remedy(tmp_path, capsys):
    repo, home, _ = edited_home(tmp_path)
    fake_root = tmp_path / "fake-root" / ".skill-manager"
    (fake_root / "installed").mkdir(parents=True)
    recording_cli(home, fail_on="home sync")
    assert publish_mod.run("alpha", ticket="T-7", start=repo) == 5
    out = capsys.readouterr().out
    assert "error: home sync" in out and "fix:" in out


def test_publish_ambiguous_edits_require_a_name(tmp_path, capsys):
    repo, home, _ = edited_home(tmp_path)
    bare2, tip2 = make_unit_upstream(tmp_path, "beta")
    record = {
        "name": "beta", "version": "1.0.0", "unitKind": "SKILL",
        "origin": str(bare2), "gitHash": tip2, "gitRef": "main",
    }
    (home / "installed" / "beta.json").write_text(json.dumps(record))
    unit_dir = home / "skills" / "beta"
    subprocess.run(["git", "clone", "-q", str(bare2), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# also improved\n")
    assert publish_mod.run(None, start=repo) == 1
    assert "one at a time" in capsys.readouterr().out


def test_publish_infers_ticket_from_branch(tmp_path, capsys):
    repo, home, _ = edited_home(tmp_path)
    subprocess.run(["git", "-C", str(repo), "checkout", "-q", "-b", "feature/T-42"], check=True)
    fake_root = tmp_path / "fake-root" / ".skill-manager"
    (fake_root / "installed").mkdir(parents=True)
    calls = recording_cli(home)
    assert publish_mod.run("alpha", start=repo) == 0
    assert "--ticket T-42" in calls.read_text()
