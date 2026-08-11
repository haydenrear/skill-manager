import importlib.util
import sys
from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_program_model_contains_resumable_build_actions_and_invariants() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")

    for action in [
        "SetNodeRerunDisabled",
        "ResumeRunFromBuild",
        "RunOnlyNodeFromBuild",
    ]:
        assert f"@command {action}" in tla

    for invariant in [
        "EveryAttemptHasSavedInputContext",
        "RerunGuidanceOnlyForRerunnableFailures",
        "ResumptionsUseSavedInputContext",
        "BuildRerunsRespectDependencies",
    ]:
        assert f"@invariant {invariant}" in tla


def test_program_model_tracks_rerun_guidance_boundary() -> None:
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")
    executor = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt"
    ).read_text(encoding="utf-8")
    report_writer = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt"
    ).read_text(encoding="utf-8")

    assert "rerun_guidance" in manifest
    assert "RerunGuidanceAdapter" in manifest
    assert "rerunGuidance" in executor
    assert "--resume-from-node" in executor
    assert "--run-only-node" in executor
    assert "renderRerunGuidance" in report_writer


def test_nested_test_graph_has_four_rerun_nodes() -> None:
    build_file = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    for graph in ["rerunGraphJbang", "rerunGraphUv", "runOnlyJbang", "runOnlyUv"]:
        assert f'testGraph("{graph}")' in build_file

    for source in [
        "RerunGraphJbang.py",
        "RerunGraphUv.py",
        "RunOnlyJbang.py",
        "RunOnlyUv.py",
    ]:
        assert (REPO_ROOT / "test_graph/sources" / source).exists()


def test_rerun_harness_keeps_collision_suffix_in_run_identity() -> None:
    harness_path = REPO_ROOT / "test_graph/support/rerun_harness.py"
    spec = importlib.util.spec_from_file_location("rerun_harness_test", harness_path)
    assert spec is not None and spec.loader is not None
    harness = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = harness
    try:
        spec.loader.exec_module(harness)
    finally:
        sys.modules.pop(spec.name, None)

    output = "testGraph 'smoke' run=20260722-070547-17 steps=1 fullPlanSteps=6"

    assert harness.extract_run_ids(output) == {"smoke": "20260722-070547-17"}
