"""Cache-only and wall-deadline regressions for issue #19.

Two contracts under test: `--cached` spawns NO subprocess in ANY cache
state (a sentinel replaces subprocess.run/Popen and fails the test on
use), and the live path returns at its declared wall deadline with
every git child it started killed and reaped (the shim records PIDs so
the tests can prove the process groups died).
"""

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from skt import check as check_mod  # noqa: E402

from test_check import make_unit_upstream, unit_record  # noqa: E402
from test_status import make_home, make_repo  # noqa: E402


@pytest.fixture(autouse=True)
def isolate_root_home(tmp_path, monkeypatch):
    monkeypatch.delenv("SKILL_MANAGER_HOME", raising=False)
    monkeypatch.setenv("SKT_ROOT_HOME", str(tmp_path / "fake-root" / ".skill-manager"))


# --- cache-only: no subprocess in any cache state ---------------------------


def forbid_subprocess(monkeypatch):
    """Applied AFTER fixture git setup: any spawn from here on is a failure."""

    def boom(*a, **k):
        raise AssertionError("subprocess spawned on the cache-only path")

    monkeypatch.setattr(subprocess, "run", boom)
    monkeypatch.setattr(subprocess, "Popen", boom)


def run_cached_json(repo, capsys):
    t0 = time.monotonic()
    rc = check_mod.run(as_json=True, cached=True, start=repo)
    elapsed = time.monotonic() - t0
    return rc, elapsed, json.loads(capsys.readouterr().out)


def stale_record(home, notifications):
    return {
        "schema": check_mod.SCHEMA_VERSION,
        "home": str(home),
        "tier": "project",
        "checked_units": ["alpha"],
        "unverifiable": [],
        "network": True,
        "checked_at": time.time() - 10_000,
        "notifications": notifications,
    }


NOTE = {
    "kind": "new-version",
    "unit": "alpha",
    "installed": "aaaaaaaa",
    "remote": "bbbbbbbb",
    "message": "new version available for alpha — pull with: skt sync alpha",
}


def test_cached_missing_is_typed_fast_and_spawn_free(tmp_path, monkeypatch, capsys):
    repo = make_repo(tmp_path / "repo")
    home = make_home(repo, units={"alpha": {}})  # change-managed, but no cache/
    forbid_subprocess(monkeypatch)
    rc, elapsed, report = run_cached_json(repo, capsys)
    assert report["cache_state"] == "missing"
    assert report["notifications"] == []
    assert rc == 0  # documented non-error outcome: an absent result is "nothing to report"
    assert elapsed < 0.25
    assert not check_mod.state_file(home).exists(), "--cached must never write the cache"


def test_cached_expired_is_typed_stale_labeled_and_spawn_free(tmp_path, monkeypatch, capsys):
    repo = make_repo(tmp_path / "repo")
    home = make_home(repo, units={"alpha": {}})
    check_mod.state_file(home).parent.mkdir(parents=True)
    check_mod.state_file(home).write_text(json.dumps(stale_record(home, [NOTE])))
    forbid_subprocess(monkeypatch)
    rc, elapsed, report = run_cached_json(repo, capsys)
    assert report["cache_state"] == "expired"
    assert report["notifications"] == [], "stale notifications must not present as current"
    assert report["stale"]["notifications"][0]["unit"] == "alpha"
    assert rc == 0
    assert elapsed < 0.25
    text = check_mod.render_text(report)
    assert "expired" in text and "[stale]" in text


def test_cached_fresh_serves_cache_verbatim_and_spawn_free(tmp_path, monkeypatch, capsys):
    repo = make_repo(tmp_path / "repo")
    home = make_home(repo, units={"alpha": {}})
    record = stale_record(home, [NOTE])
    record["checked_at"] = time.time()
    check_mod.state_file(home).parent.mkdir(parents=True)
    check_mod.state_file(home).write_text(json.dumps(record))
    forbid_subprocess(monkeypatch)
    rc, elapsed, report = run_cached_json(repo, capsys)
    assert report["cache_state"] == "fresh" and report["from_cache"] is True
    assert report["notifications"] == [NOTE]
    assert report["checked_units"] == record["checked_units"]
    assert rc == check_mod.NOTIFY_EXIT
    assert elapsed < 0.25


# --- live deadline: hanging git children are bounded and reaped -------------


def hanging_git(tmp_path: Path, spool: Path) -> Path:
    """A `git` that passes plumbing through but hangs forever on the
    subcommands under test, recording its PID so the tests can prove the
    deadline machinery killed the process group."""
    real_git = shutil.which("git")
    bindir = tmp_path / "fake-bin"
    bindir.mkdir(exist_ok=True)
    script = bindir / "git"
    script.write_text(
        "#!/bin/sh\n"
        'for a in "$@"; do\n'
        '  case "$a" in\n'
        f'    ls-remote|status|rev-list) echo $$ >> "{spool}"; exec sleep 3600 ;;\n'
        "  esac\n"
        "done\n"
        f'exec "{real_git}" "$@"\n'
    )
    script.chmod(0o755)
    return bindir


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def assert_children_dead(spool: Path, grace: float = 5.0) -> None:
    """Process scan: every PID the shim recorded must be gone shortly after
    return (abandoned workers kill their groups at the shared deadline)."""
    pids = [int(line) for line in spool.read_text().split()] if spool.exists() else []
    assert pids, "shim never ran — the scenario did not exercise the deadline"
    alive = pids
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        alive = [p for p in pids if _alive(p)]
        if not alive:
            return
        time.sleep(0.05)
    raise AssertionError(f"live git children after the deadline: {alive}")


@pytest.mark.parametrize("count", [11, 20])
def test_live_check_hanging_remotes_returns_at_deadline(tmp_path, monkeypatch, count):
    """11 remotes was the reproduced ~20s hang (ceil(11/8) * 10s), 20 the
    ~30s one: both must return at the budget with every unit unverifiable."""
    repo = make_repo(tmp_path / "repo")
    names = [f"unit{i:02d}" for i in range(count)]
    make_home(repo, units={n: {} for n in names})
    spool = tmp_path / "pids"
    bindir = hanging_git(tmp_path, spool)
    monkeypatch.setenv("PATH", f"{bindir}:{os.environ.get('PATH', '')}")
    budget = 1.5  # same machinery, scaled so the suite stays fast
    monkeypatch.setattr(check_mod, "NETWORK_BUDGET_SECONDS", budget)
    t0 = time.monotonic()
    report = check_mod.collect(repo)
    elapsed = time.monotonic() - t0
    # Returns AT the deadline (plus kill/reap slack) — not ceil(n/8) *
    # REMOTE_TIMEOUT after it, which is what awaiting executor shutdown cost.
    assert elapsed < budget + 1.0
    assert sorted(report["unverifiable"]) == names
    assert report["notifications"] == []
    assert_children_dead(spool)


def test_root_tier_hanging_local_git_is_bounded(tmp_path, monkeypatch):
    fake_root = tmp_path / "fake-root"
    repo = make_repo(fake_root / "anywhere")
    home = make_home(fake_root, units={"alpha": {}})
    unit_dir = home / "skills" / "alpha"
    (unit_dir / ".git").mkdir(parents=True)  # triggers the probe; the shim hangs before git looks inside
    monkeypatch.setenv("SKILL_MANAGER_HOME", str(home))
    # Isolate the local phase: remote tips resolve instantly to the installed hash.
    monkeypatch.setattr(
        check_mod, "_remote_tip_safe", lambda origin, ref, deadline=None: "a" * 40
    )
    spool = tmp_path / "pids"
    bindir = hanging_git(tmp_path, spool)
    monkeypatch.setenv("PATH", f"{bindir}:{os.environ.get('PATH', '')}")
    monkeypatch.setattr(check_mod, "LOCAL_TIMEOUT_SECONDS", 0.5)
    t0 = time.monotonic()
    report = check_mod.collect(repo)
    elapsed = time.monotonic() - t0
    assert elapsed < 3
    # A probe that never finished must not fabricate a publish prompt...
    assert all(n["kind"] != "sync-with-root" for n in report["notifications"])
    # ...and must not present the unit as verified-clean either: the gap
    # is labeled unverifiable, so the cached record carries the evidence
    # and the cache-refusal predicate counts it.
    assert "alpha" in report["unverifiable"]
    assert_children_dead(spool)


# --- cache publication: atomic, and never a fake fresh success --------------


def test_all_unverifiable_refresh_writes_no_cache(tmp_path, monkeypatch, capsys):
    repo = make_repo(tmp_path / "repo")
    home = make_home(repo, units={"alpha": {}, "beta": {}})
    monkeypatch.setattr(check_mod, "_remote_tip_safe", lambda *a, **k: None)
    assert check_mod.run(as_json=True, cached=False, start=repo) == 0
    capsys.readouterr()
    assert not check_mod.state_file(home).exists(), (
        "a refresh that resolved nothing must not become a fresh success"
    )
    rc, _, report = run_cached_json(repo, capsys)
    assert rc == 0 and report["cache_state"] == "missing"


def test_successful_live_check_writes_one_atomic_record_then_cache_only(tmp_path, monkeypatch, capsys):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    assert check_mod.run(as_json=True, cached=False, start=repo) == 0
    live = json.loads(capsys.readouterr().out)
    cache_dir = check_mod.state_file(home).parent
    assert [p.name for p in cache_dir.iterdir()] == [check_mod.state_file(home).name], (
        "exactly one record, no temp-file litter"
    )
    forbid_subprocess(monkeypatch)  # the immediately following hook call is cache-only
    rc, elapsed, report = run_cached_json(repo, capsys)
    assert report["cache_state"] == "fresh" and report["from_cache"] is True
    assert report["notifications"] == live["notifications"]
    assert report["checked_units"] == live["checked_units"]
    assert rc == 0
    assert elapsed < 0.25
