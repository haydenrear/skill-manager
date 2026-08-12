import subprocess
import sys
from pathlib import Path

import pytest

CLI = Path(__file__).resolve().parents[1] / "src" / "skt" / "cli.py"

PENDING_COMMANDS = []  # all subcommands implemented as of SKT-5


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(CLI), *args], capture_output=True, text=True
    )


def test_entry_point_importable():
    sys.path.insert(0, str(CLI.parents[1]))
    from skt.cli import main  # noqa: F401


def test_help_runs_clean():
    result = run("--help")
    assert result.returncode == 0
    assert "skt" in result.stdout


def test_version():
    result = run("--version")
    assert result.returncode == 0
    assert result.stdout.strip() == "skt 0.3.0"


def test_no_args_prints_help():
    result = run()
    assert result.returncode == 0
    assert "startup report" in result.stdout


@pytest.mark.parametrize("command", PENDING_COMMANDS)
def test_pending_commands_are_honest_stubs(command):
    result = run(command)
    assert result.returncode == 2
    assert "not implemented yet" in result.stderr
    assert "SKT-" in result.stderr


@pytest.mark.parametrize("command", PENDING_COMMANDS)
def test_pending_commands_have_help(command):
    result = run(command, "--help")
    assert result.returncode == 0
