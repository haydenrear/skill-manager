from pathlib import Path


SPEC_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_DIR.parents[1]
PROJECT_ROOT = REPO_ROOT / "project_sdk_sources"


def test_current_model_tracks_rerun_guidance() -> None:
    tla = (SPEC_DIR / "TestGraph.tla").read_text(encoding="utf-8")

    assert "rerun_guidance" in tla
    assert "RerunGuidanceOnlyForRerunnableFailures" in tla


def test_executor_emits_rerun_guidance_for_rerunnable_failures() -> None:
    executor_kt = (
        PROJECT_ROOT
        / "build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt"
    ).read_text(encoding="utf-8")
    report_kt = (
        PROJECT_ROOT
        / "build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt"
    ).read_text(encoding="utf-8")

    assert "rerunGuidance" in executor_kt
    assert "spec.rerun" in executor_kt
    assert "--resume-from-node" in executor_kt
    assert "--run-only-node" in executor_kt
    assert "inputContextFile" in executor_kt
    assert "renderRerunGuidance" in report_kt
    assert "Saved input context" in report_kt


def test_docs_explain_failed_node_guidance() -> None:
    skill = (REPO_ROOT / "SKILL.md").read_text(encoding="utf-8")
    workflows = (REPO_ROOT / "references/workflows.md").read_text(encoding="utf-8")
    reference = (REPO_ROOT / "references/reference.md").read_text(encoding="utf-8")

    assert "rerun guidance" in skill.lower()
    assert "rerun guidance" in workflows.lower()
    assert "rerunGuidance" in reference
