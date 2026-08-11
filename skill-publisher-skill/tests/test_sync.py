import json
import stat
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from skt import sync as sync_mod  # noqa: E402

from test_check import advance_upstream, make_unit_upstream, unit_record  # noqa: E402
from test_status import make_home, make_repo  # noqa: E402


@pytest.fixture(autouse=True)
def isolate_root_home(tmp_path, monkeypatch):
    monkeypatch.delenv("SKILL_MANAGER_HOME", raising=False)
    monkeypatch.setenv("SKT_ROOT_HOME", str(tmp_path / "fake-root" / ".skill-manager"))


def fake_cli(home: Path, body: str) -> None:
    """A stand-in home CLI whose sync behavior the test controls."""
    cli = home / "bin" / "cli" / "skill-manager"
    cli.parent.mkdir(parents=True, exist_ok=True)
    cli.write_text("#!/usr/bin/env bash\n" + body)
    cli.chmod(cli.stat().st_mode | stat.S_IEXEC)


def test_sync_success_verifies_against_remote_tip(tmp_path, capsys):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    new_tip = advance_upstream(bare, tmp_path)
    record = home / "installed" / "alpha.json"
    # the fake CLI "moves the store" by updating the installed record:
    fake_cli(
        home,
        f"python3 -c \"import json;p='{record}';d=json.load(open(p));"
        f"d['gitHash']='{new_tip}';json.dump(d,open(p,'w'))\"\n",
    )
    assert sync_mod.run("alpha", start=repo) == 0
    assert new_tip[:8] in capsys.readouterr().out


def test_sync_detects_silent_noop(tmp_path, capsys):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    advance_upstream(bare, tmp_path)
    fake_cli(home, "exit 0\n")  # exits green, moves nothing — the documented trap
    assert sync_mod.run("alpha", start=repo) == 11
    out = capsys.readouterr().out
    assert "silent-no-op trap" in out


def test_sync_unknown_unit(tmp_path, capsys):
    repo = make_repo(tmp_path / "repo")
    make_home(repo, units={})
    assert sync_mod.run("ghost", start=repo) == 1


def test_sync_propagates_cli_failure(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    fake_cli(home, "echo boom >&2; exit 7\n")
    assert sync_mod.run("alpha", start=repo) == 7
