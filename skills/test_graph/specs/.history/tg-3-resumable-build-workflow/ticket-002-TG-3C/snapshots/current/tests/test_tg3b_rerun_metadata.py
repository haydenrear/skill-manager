import json
import subprocess
from pathlib import Path


SPEC_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_DIR.parents[1]
PROJECT_ROOT = REPO_ROOT / "project_sdk_sources"


def test_current_model_tracks_rerunnable_nodes() -> None:
    tla = (SPEC_DIR / "TestGraph.tla").read_text(encoding="utf-8")

    assert "rerunnable_nodes" in tla
    assert "@command SetNodeRerunDisabled" in tla
    assert "rerunnable_nodes' = rerunnable_nodes \\ {n}" in tla


def test_python_nodespec_describes_default_and_disabled_rerun(tmp_path: Path) -> None:
    default_out = tmp_path / "default.json"
    disabled_out = tmp_path / "disabled.json"

    subprocess.run(
        [
            "uv",
            "run",
            str(PROJECT_ROOT / "sources/ContextSnapshotsPresent.py"),
            f"--describe-out={default_out}",
        ],
        cwd=PROJECT_ROOT,
        check=True,
    )
    subprocess.run(
        [
            "uv",
            "run",
            str(PROJECT_ROOT / "sources/RerunDisabledProbe.py"),
            f"--describe-out={disabled_out}",
        ],
        cwd=PROJECT_ROOT,
        check=True,
    )

    assert json.loads(default_out.read_text(encoding="utf-8"))["rerun"] is True
    assert json.loads(disabled_out.read_text(encoding="utf-8"))["rerun"] is False


def test_java_nodespec_describes_default_rerun(tmp_path: Path) -> None:
    out = tmp_path / "app-running.json"

    subprocess.run(
        [
            "jbang",
            str(PROJECT_ROOT / "sources/AppRunning.java"),
            f"--describe-out={out}",
        ],
        cwd=PROJECT_ROOT,
        check=True,
    )

    assert json.loads(out.read_text(encoding="utf-8"))["rerun"] is True
