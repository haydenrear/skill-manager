from pathlib import Path


SPEC_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_DIR.parents[1]


def test_current_model_tracks_input_context_snapshots() -> None:
    tla = (SPEC_DIR / "TestGraph.tla").read_text(encoding="utf-8")
    cfg = (SPEC_DIR / "MC.cfg").read_text(encoding="utf-8")

    assert "input_contexts" in tla
    assert "@invariant EveryAttemptHasSavedInputContext" in tla
    assert "envelopes[g] \\subseteq input_contexts[g]" in tla
    assert "EveryAttemptHasSavedInputContext" in cfg


def test_executor_writes_input_context_snapshot_before_node_invocation() -> None:
    context_kt = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Context.kt"
    ).read_text(encoding="utf-8")
    executor_kt = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt"
    ).read_text(encoding="utf-8")

    assert "fun writeInputContextSnapshot" in context_kt
    assert ".input.json" in context_kt
    assert "val inputContextFile = writeInputContextSnapshot" in executor_kt
    assert "inputContextFile" in executor_kt
    assert executor_kt.index("writeInputContextSnapshot") < executor_kt.index("NodeInvocation(")


def test_smoke_graph_asserts_input_context_snapshots() -> None:
    build_gradle = (REPO_ROOT / "project_sdk_sources/build.gradle.kts").read_text(
        encoding="utf-8"
    )
    node = (
        REPO_ROOT / "project_sdk_sources/sources/ContextSnapshotsPresent.py"
    ).read_text(encoding="utf-8")

    assert 'node("sources/ContextSnapshotsPresent.py")' in build_gradle
    assert 'NodeSpec("context.snapshots.present")' in node
    assert "input_context_snapshot_exists" in node
    assert "inputContextFile" in node
