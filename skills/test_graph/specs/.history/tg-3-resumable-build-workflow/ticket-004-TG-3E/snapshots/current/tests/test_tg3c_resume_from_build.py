from pathlib import Path


SPEC_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_DIR.parents[1]
PROJECT_ROOT = REPO_ROOT / "project_sdk_sources"


def test_current_model_tracks_resume_from_build() -> None:
    tla = (SPEC_DIR / "TestGraph.tla").read_text(encoding="utf-8")
    cfg = (SPEC_DIR / "MC.cfg").read_text(encoding="utf-8")

    assert "resumed_nodes" in tla
    assert "@command ResumeRunFromBuild" in tla
    assert "n \\in input_contexts[g]" in tla
    assert "n \\in rerunnable_nodes" in tla
    assert "ResumptionsUseSavedInputContext" in cfg
    assert "BuildRerunsRespectDependencies" in cfg


def test_run_script_exposes_graph_resume_options() -> None:
    run_py = (REPO_ROOT / "scripts/run.py").read_text(encoding="utf-8")
    task_kt = (
        PROJECT_ROOT
        / "build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt"
    ).read_text(encoding="utf-8")

    assert "--resume-from-build" in run_py
    assert "--resume-from-node" in run_py
    assert "resume options apply to one graph" in run_py
    assert '@Option(\n        option = "resume-from-build"' in task_kt
    assert "PlanExecutor.ResumeFromBuild" in task_kt


def test_executor_loads_saved_context_before_resumed_node() -> None:
    context_kt = (
        PROJECT_ROOT
        / "build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Context.kt"
    ).read_text(encoding="utf-8")
    executor_kt = (
        PROJECT_ROOT
        / "build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt"
    ).read_text(encoding="utf-8")

    assert "fun fromJson" in context_kt
    assert "fun readInputContextSnapshot" in context_kt
    assert "val initialContext = readInputContextSnapshot" in executor_kt
    assert "plan.drop(resumeIndex)" in executor_kt
    assert "!resumeSpec.rerun" in executor_kt
    assert "saved input context is missing" in executor_kt


def test_rerun_smoke_graphs_cover_uv_and_java() -> None:
    build_gradle = (PROJECT_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    java_probe = (PROJECT_ROOT / "sources/RerunJavaProbe.java").read_text(
        encoding="utf-8"
    )
    uv_probe = (PROJECT_ROOT / "sources/RerunDisabledProbe.py").read_text(
        encoding="utf-8"
    )

    assert 'testGraph("rerunSmokeUv")' in build_gradle
    assert 'node("sources/RerunDisabledProbe.py")' in build_gradle
    assert 'testGraph("rerunSmokeJava")' in build_gradle
    assert 'node("sources/RerunJavaProbe.java")' in build_gradle
    assert 'NodeSpec.of("rerun.java.probe")' in java_probe
    assert ".rerun(false)" in java_probe
    assert 'NodeSpec("rerun.disabled.probe")' in uv_probe
    assert ".rerun(False)" in uv_probe
