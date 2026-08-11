from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_tg6e_local_lifecycle_graphs_are_registered() -> None:
    graph = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    assert 'testGraph("environmentRepositoryLocalLifecycle")' in graph
    assert 'testGraph("environmentRepositoryLocalLifecycleDestroy")' in graph
    for source in [
        "sources/Tg6LocalLifecycleProvisionMissing.py",
        "sources/Tg6LocalLifecycleDeployExisting.py",
        "sources/Tg6LocalLifecycleDeployMarkers.py",
        "sources/Tg6LocalLifecycleReset.py",
        "sources/Tg6LocalLifecycleResetMarkers.py",
        "sources/Tg6LocalLifecycleSkipDestroy.py",
        "sources/Tg6LocalLifecycleDestroy.py",
        "sources/Tg6LocalLifecycleDestroyMarkers.py",
    ]:
        assert source in graph


def test_tg6e_local_lifecycle_nodes_assert_external_runtime_evidence() -> None:
    sources = REPO_ROOT / "test_graph/sources"
    scaffold = (REPO_ROOT / "scripts/_env_repo_scaffold.py").read_text(encoding="utf-8")
    harness = (REPO_ROOT / "test_graph/support/branch_environment_harness.py").read_text(encoding="utf-8")
    provision = (sources / "Tg6LocalLifecycleProvisionMissing.py").read_text(encoding="utf-8")
    deploy_markers = (sources / "Tg6LocalLifecycleDeployMarkers.py").read_text(encoding="utf-8")
    reset = (sources / "Tg6LocalLifecycleReset.py").read_text(encoding="utf-8")
    reset_markers = (sources / "Tg6LocalLifecycleResetMarkers.py").read_text(encoding="utf-8")
    skip_destroy = (sources / "Tg6LocalLifecycleSkipDestroy.py").read_text(encoding="utf-8")
    destroy = (sources / "Tg6LocalLifecycleDestroy.py").read_text(encoding="utf-8")
    destroy_markers = (sources / "Tg6LocalLifecycleDestroyMarkers.py").read_text(encoding="utf-8")

    assert 'rm -rf "generated/${target}"' in scaffold
    assert 'if read_json(path).get("runId") == run_id' in harness
    assert 'SideEffect.environment("provision")' in provision
    assert "missing_environment_was_provisioned" in provision
    assert "deploy_existing_skipped_recreate_apply" in deploy_markers
    assert "environment_repository_command_labels" in deploy_markers
    assert 'SideEffect.environment("reset")' in reset
    assert "reset_clears_application_state" in reset
    assert "reset_reapplied_environment" in reset_markers
    assert "skip_destroy_keeps_environment_active" in skip_destroy
    assert "TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT" in destroy
    assert 'SideEffect.environment("destroy")' in destroy
    assert "destroy_ran_tofu_destroy" in destroy_markers
