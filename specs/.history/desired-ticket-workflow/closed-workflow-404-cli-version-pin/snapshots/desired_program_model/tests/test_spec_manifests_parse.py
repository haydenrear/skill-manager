"""The manifests describing these models have to be readable by a machine.

Three times a hand-authored YAML file in this repository has been wrong in a
way nothing noticed. Twice it was a spec manifest, and both times the cost was
paid by whoever next needed the file to be true rather than by the commit that
broke it:

  * `specs/program_model/spec_manifest.yaml` named three TLA+ modules that do
    not exist in that directory, with its contract test red on main across two
    releases — ARTI-02 (#103) spent a whole ticket repairing it;
  * `specs/desired_program_model/spec_manifest.yaml` carried six prose entries
    under `status.done` with unquoted `: ` inside them, so no YAML parser could
    read the manifest describing a live 17-ticket workflow — ARTI-25 (#127).

The check itself lives in `.github/scripts/check_yaml.py`, because it has to
cover every hand-authored YAML in the tree and not only these three files: a
per-file fix leaves the fourth instance. This module is how the DECLARED LOCAL
SIGNAL for these models reaches it — `uv run --with pytest pytest
specs/program_model/tests specs/current/tests` — and how CI's wiring of it is
held in place, following `test_ci_does_not_gate_the_graph_matrix_off_again`
beside it: a check that exists and is not executed is the exact failure #103
was about.
"""

import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
CHECKER = ROOT / ".github/scripts/check_yaml.py"


def test_the_yaml_checker_is_present() -> None:
    assert CHECKER.is_file(), f"YAML/spec-manifest checker missing at {CHECKER}"


@pytest.mark.skipif(shutil.which("uv") is None, reason="uv is not on PATH")
def test_every_hand_authored_yaml_parses_and_the_manifests_are_shaped_right() -> None:
    """Run the real check, exactly as CI runs it.

    A subprocess rather than an import: the checker declares its own PyYAML
    dependency inline (PEP 723) and `uv run` supplies it, so this suite stays
    dependency-free and the thing under test is the same invocation the
    workflow performs, not a reimplementation of it.
    """
    proc = subprocess.run(
        ["uv", "run", str(CHECKER), "--quiet"],
        cwd=ROOT, capture_output=True, text=True,
    )
    assert proc.returncode == 0, (
        "hand-authored YAML in this repository does not parse, or a spec "
        f"manifest is malformed:\n{proc.stderr}\n{proc.stdout}"
    )


def test_ci_runs_the_yaml_check_on_every_push_not_on_the_schedule() -> None:
    """The wiring, checked where #103 proved wiring has to be checked.

    `check_yaml.py` catches the defect in about a second. It catches nothing at
    all if it sits in `.github/scripts/` unreferenced, or if it is attached to
    the nightly schedule — a manifest is broken by a commit, and a nightly run
    reports it to whoever pushes next rather than to whoever broke it.
    """
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    assert "check_yaml.py" in ci, "check_yaml.py is not referenced by ci.yml"

    lines = ci.splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == "unit-tests:")
    body = []
    for line in lines[start + 1:]:
        if line.strip() and not line.startswith("    ") and not line.startswith("#"):
            break
        body.append(line)
    assert any("check_yaml.py" in line for line in body), (
        "check_yaml.py is referenced by ci.yml but not from the `unit-tests` "
        "job, which is the job that runs on every push and pull request"
    )
