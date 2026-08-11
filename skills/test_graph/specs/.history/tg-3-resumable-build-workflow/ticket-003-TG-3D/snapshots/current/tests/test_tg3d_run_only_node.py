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
    node_py = (REPO_ROOT / "test_graph/sources/RerunBuildFlow.py").read_text(
        encoding="utf-8"
    )

    assert "--run-only-node" in node_py
    assert "run_only_login_jbang_passed" in node_py
    assert "run_only_context_uv_passed" in node_py
    assert "run_only_does_not_continue_downstream" in node_py
    assert "run_only_rerun_false_rejected" in node_py
