import json
import subprocess
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from skt import check as check_mod  # noqa: E402

from test_status import make_home, make_repo  # noqa: E402


GIT = ["git", "-c", "user.email=t@t", "-c", "user.name=t"]


def make_unit_upstream(base: Path, name: str) -> tuple[Path, str]:
    """A bare origin plus its current tip hash."""
    bare = base / f"{name}-upstream.git"
    subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(bare)], check=True)
    work = base / f"{name}-seed"
    work.mkdir()
    (work / "SKILL.md").write_text(f"# {name}\n")
    subprocess.run(["git", "init", "-q", "-b", "main", str(work)], check=True)
    subprocess.run([*GIT, "-C", str(work), "add", "-A"], check=True)
    subprocess.run([*GIT, "-C", str(work), "commit", "-q", "-m", "v1"], check=True)
    subprocess.run(
        ["git", "-C", str(work), "push", "-q", str(bare), "main"], check=True
    )
    tip = subprocess.run(
        ["git", "-C", str(work), "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()
    return bare, tip


def advance_upstream(bare: Path, base: Path) -> str:
    clone = base / f"{bare.stem}-advance"
    subprocess.run(["git", "clone", "-q", str(bare), str(clone)], check=True)
    (clone / "SKILL.md").write_text("# advanced\n")
    subprocess.run([*GIT, "-C", str(clone), "add", "-A"], check=True)
    subprocess.run([*GIT, "-C", str(clone), "commit", "-q", "-m", "v2"], check=True)
    subprocess.run(["git", "-C", str(clone), "push", "-q"], check=True)
    return subprocess.run(
        ["git", "-C", str(clone), "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()


@pytest.fixture(autouse=True)
def isolate_root_home(tmp_path, monkeypatch):
    monkeypatch.delenv("SKILL_MANAGER_HOME", raising=False)
    monkeypatch.setenv("SKT_ROOT_HOME", str(tmp_path / "fake-root" / ".skill-manager"))


def unit_record(bare: Path, tip: str) -> dict:
    return {"origin": str(bare), "gitHash": tip, "gitRef": "main"}


def test_current_unit_reports_nothing(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    make_home(repo, units={"alpha": unit_record(bare, tip)})
    report = check_mod.collect(repo)
    assert report["notifications"] == []
    assert report["checked_units"] == ["alpha"]


def test_stale_unit_notifies_new_version(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    make_home(repo, units={"alpha": unit_record(bare, tip)})
    new_tip = advance_upstream(bare, tmp_path)
    report = check_mod.collect(repo)
    assert len(report["notifications"]) == 1
    note = report["notifications"][0]
    assert note["kind"] == "new-version"
    assert note["remote"] == new_tip[:8]
    assert "skt sync alpha" in note["message"]


def test_multiple_stale_units_get_dependency_hint(tmp_path):
    repo = make_repo(tmp_path / "repo")
    units = {}
    for name in ("alpha", "beta"):
        bare, tip = make_unit_upstream(tmp_path, name)
        units[name] = unit_record(bare, tip)
        advance_upstream(bare, tmp_path)
    make_home(repo, units=units)
    report = check_mod.collect(repo)
    assert len(report["notifications"]) == 2
    assert "dependency order" in report["hint"]


def test_root_tier_prompts_publish_for_dirty_store_unit(tmp_path, monkeypatch):
    fake_root = tmp_path / "fake-root"
    repo = make_repo(fake_root / "anywhere")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(fake_root, units={"alpha": unit_record(bare, tip)})
    unit_dir = home / "skills" / "alpha"
    subprocess.run(["git", "clone", "-q", str(bare), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# locally improved\n")
    monkeypatch.setenv("SKILL_MANAGER_HOME", str(home))
    report = check_mod.collect(repo)
    kinds = {n["kind"] for n in report["notifications"]}
    assert "sync-with-root" in kinds
    note = next(n for n in report["notifications"] if n["kind"] == "sync-with-root")
    assert note["state"] == "dirty"
    assert "publish changes globally" in note["message"]


def test_project_tier_never_prompts_push_side(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    unit_dir = home / "skills" / "alpha"
    subprocess.run(["git", "clone", "-q", str(bare), str(unit_dir)], check=True)
    (unit_dir / "SKILL.md").write_text("# locally improved\n")
    report = check_mod.collect(repo)
    assert all(n["kind"] != "sync-with-root" for n in report["notifications"])


def test_cached_path_avoids_network(tmp_path, monkeypatch):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    report = check_mod.collect(repo)
    check_mod._write_cache(report)
    def boom(*a, **k):
        raise AssertionError("network hit on cached path")
    monkeypatch.setattr(check_mod, "_remote_tip", boom)
    cached = check_mod.cached_report(home, ttl=900)
    assert cached["from_cache"] is True and cached["cache_state"] == "fresh"
    t0 = time.monotonic()
    check_mod.cached_report(home, ttl=900)
    assert time.monotonic() - t0 < 0.05


def test_expired_cache_is_not_served_as_current(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    report = check_mod.collect(repo)
    report["checked_at"] = time.time() - 10_000
    check_mod._write_cache(report)
    cached = check_mod.cached_report(home, ttl=900)
    assert cached["cache_state"] == "expired"
    assert cached["notifications"] == []
    assert cached["stale"]["checked_units"] == ["alpha"]


def test_exit_codes_distinguish_notify(tmp_path, capsys):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    make_home(repo, units={"alpha": unit_record(bare, tip)})
    assert check_mod.run(as_json=False, cached=False, start=repo) == 0
    advance_upstream(bare, tmp_path)
    assert check_mod.run(as_json=False, cached=False, start=repo) == check_mod.NOTIFY_EXIT
