import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from test_check import make_unit_upstream, unit_record, advance_upstream  # noqa: E402
from test_status import make_home, make_repo  # noqa: E402

PLUGIN_ROOT = Path(__file__).resolve().parents[1]
HOOKS = PLUGIN_ROOT / "hooks"


def run_hook(script: str, home: Path, cwd: Path) -> subprocess.CompletedProcess:
    env = {
        "PATH": "/usr/bin:/bin",  # deliberately minimal: macOS python3 is 3.9
        "SKT_PYTHON": sys.executable,  # the override the probe honors
        "SKILL_MANAGER_HOME": str(home),
        "CLAUDE_PLUGIN_ROOT": str(PLUGIN_ROOT),
        "CLAUDE_SESSION_ID": "test-session",
    }
    return subprocess.run(
        ["bash", str(HOOKS / script)], capture_output=True, text=True, cwd=cwd, env=env
    )


def seed_cache(home: Path, cwd: Path) -> None:
    """One explicit live check, as SessionStart's bounded refresh would run.

    PostToolUse is contract-cache-only: without this seed it reports
    cache_state=missing and stays silent.
    """
    subprocess.run(
        [sys.executable, str(PLUGIN_ROOT / "src" / "skt" / "cli.py"), "check"],
        capture_output=True, text=True, cwd=cwd,
        env={"PATH": "/usr/bin:/bin", "SKILL_MANAGER_HOME": str(home)},
        check=False,
    )


def test_hooks_json_references_existing_executables():
    config = json.loads((HOOKS / "hooks.json").read_text())
    events = config["hooks"]
    assert set(events) == {"SessionStart", "PostToolUse"}
    for event, matchers in events.items():
        for matcher in matchers:
            for hook in matcher["hooks"]:
                assert hook["type"] == "command"
                script = hook["command"].replace("${CLAUDE_PLUGIN_ROOT}", str(PLUGIN_ROOT)).strip('"')
                path = Path(script)
                assert path.is_file(), f"{event} references missing {path}"
                assert os.access(path, os.X_OK), f"{path} not executable"


def test_session_start_injects_report_and_logs(tmp_path):
    repo = make_repo(tmp_path / "repo")
    home = make_home(repo, units={"alpha": {}})
    proc = run_hook("skt-session-start.sh", home, repo)
    assert proc.returncode == 0
    assert "skt status" in proc.stdout  # the report header
    assert "alpha" in proc.stdout
    log = home / "logs" / "skt" / "hook.log"
    assert log.is_file()
    line = log.read_text().strip()
    assert "session-start" in line and "status-injected" in line and "test-session" in line


def test_session_start_never_fails_without_skt(tmp_path):
    repo = make_repo(tmp_path / "repo")
    home = tmp_path / "not-a-home"
    env = {"PATH": "/usr/bin:/bin", "SKILL_MANAGER_HOME": str(home)}
    proc = subprocess.run(
        ["bash", str(HOOKS / "skt-session-start.sh")],
        capture_output=True, text=True, cwd=repo, env=env,
    )
    assert proc.returncode == 0


def test_post_tool_silent_when_current(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    seed_cache(home, repo)
    proc = run_hook("skt-post-tool.sh", home, repo)
    assert proc.returncode == 0
    assert proc.stdout.strip() == ""


def test_post_tool_notifies_on_stale_unit(tmp_path):
    repo = make_repo(tmp_path / "repo")
    bare, tip = make_unit_upstream(tmp_path, "alpha")
    home = make_home(repo, units={"alpha": unit_record(bare, tip)})
    advance_upstream(bare, tmp_path)
    seed_cache(home, repo)
    proc = run_hook("skt-post-tool.sh", home, repo)
    assert proc.returncode == 0
    payload = json.loads(proc.stdout)
    context = payload["hookSpecificOutput"]["additionalContext"]
    assert "new version available for alpha" in context
    log = home / "logs" / "skt" / "hook.log"
    assert "post-tool" in log.read_text()
