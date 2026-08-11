from pathlib import Path


SPEC_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_DIR.parents[1]
PROJECT_ROOT = REPO_ROOT / "project_sdk_sources"


def test_current_model_tracks_run_only_node_from_build() -> None:
    tla = (SPEC_DIR / "TestGraph.tla").read_text(encoding="utf-8")

    assert "single_node_reruns" in tla
    assert "@command RunOnlyNodeFromBuild" in tla
    assert "@port TestGraphProgramPort.run_only_node_from_build" in tla
    assert "n \\in input_contexts[g]" in tla
    assert "n \\in rerunnable_nodes" in tla
    assert "single_node_reruns' = [single_node_reruns EXCEPT ![g] = @ \\cup {n}]" in tla
    assert "active_graphs' = active_graphs \\cup {g}" not in tla.split(
        "RunOnlyNodeFromBuild(g, n) =="
    )[1].split("\\* @command RunNodePass")[0]


def test_run_script_exposes_run_only_replay_option() -> None:
    run_py = (REPO_ROOT / "scripts/run.py").read_text(encoding="utf-8")
    task_kt = (
        PROJECT_ROOT
        / "build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt"
    ).read_text(encoding="utf-8")

    assert "--run-only-node" in run_py
    assert "--resume-from-build requires exactly one of --resume-from-node" in run_py
    assert "or --run-only-node" in run_py
    assert 'f"--run-only-node={args.run_only_node}"' in run_py
    assert '@Option(\n        option = "run-only-node"' in task_kt
    assert "PlanExecutor.BuildReplayMode.RUN_ONLY_NODE" in task_kt


def test_executor_runs_only_selected_node_from_saved_context() -> None:
    executor_kt = (
        PROJECT_ROOT
        / "build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt"
    ).read_text(encoding="utf-8")

    assert "enum class BuildReplayMode" in executor_kt
    assert "RUN_ONLY_NODE" in executor_kt
    assert "listOf(resumeSpec)" in executor_kt
    assert "not continuing downstream graph nodes" in executor_kt
    assert "!resumeSpec.rerun" in executor_kt
    assert "readInputContextSnapshot" in executor_kt


def test_self_validation_graph_covers_uv_and_jbang_run_only() -> None:
    build_gradle = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(
        encoding="utf-8"
    )
    run_only_jbang = (REPO_ROOT / "test_graph/sources/RunOnlyJbang.py").read_text(
        encoding="utf-8"
    )
    run_only_uv = (REPO_ROOT / "test_graph/sources/RunOnlyUv.py").read_text(
        encoding="utf-8"
    )
    rerun_jbang = (REPO_ROOT / "test_graph/sources/RerunGraphJbang.py").read_text(
        encoding="utf-8"
    )
    rerun_uv = (REPO_ROOT / "test_graph/sources/RerunGraphUv.py").read_text(
        encoding="utf-8"
    )

    for graph in ["rerunGraphJbang", "rerunGraphUv", "runOnlyJbang", "runOnlyUv"]:
        assert f'testGraph("{graph}")' in build_gradle
    assert "--run-only-node" in run_only_jbang
    assert "--run-only-node" in run_only_uv
    assert "login.smoke (jbang)" in run_only_jbang
    assert "user.seeded (uv)" in run_only_uv
    assert "did_not_continue_downstream" in run_only_jbang
    assert "did_not_continue_downstream" in run_only_uv
    assert "--resume-from-node" in rerun_jbang
    assert "--resume-from-node" in rerun_uv
    assert "login.smoke (jbang)" in rerun_jbang
    assert "user.seeded (uv)" in rerun_uv
