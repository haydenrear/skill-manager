from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_tg6f_github_action_and_aws_lifecycle_graphs_are_registered() -> None:
    graph = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    for name in [
        "environmentRepositoryGithubActionLifecycle",
        "environmentRepositoryGithubActionLifecycleDestroy",
        "environmentRepositoryAwsLifecycle",
        "environmentRepositoryAwsLifecycleDestroy",
    ]:
        assert f'testGraph("{name}")' in graph

    for source in [
        "sources/Tg6GithubActionLifecycleProvisionMissing.py",
        "sources/Tg6GithubActionLifecycleContextJbang.java",
        "sources/Tg6GithubActionLifecycleDeployExisting.py",
        "sources/Tg6GithubActionLifecycleDeployMarkers.py",
        "sources/Tg6GithubActionLifecycleReset.py",
        "sources/Tg6GithubActionLifecycleResetMarkers.py",
        "sources/Tg6GithubActionLifecycleSkipDestroy.py",
        "sources/Tg6GithubActionLifecycleDestroy.py",
        "sources/Tg6GithubActionLifecycleDestroyMarkers.py",
        "sources/Tg6AwsLifecycleGuard.py",
        "sources/Tg6AwsLifecycleProvisionMissing.py",
        "sources/Tg6AwsLifecycleContextJbang.java",
        "sources/Tg6AwsLifecycleDeployExisting.py",
        "sources/Tg6AwsLifecycleDeployMarkers.py",
        "sources/Tg6AwsLifecycleReset.py",
        "sources/Tg6AwsLifecycleResetMarkers.py",
        "sources/Tg6AwsLifecycleSkipDestroy.py",
        "sources/Tg6AwsLifecycleDestroy.py",
        "sources/Tg6AwsLifecycleDestroyMarkers.py",
    ]:
        assert source in graph


def test_tg6f_lifecycle_nodes_assert_target_backends_and_external_markers() -> None:
    sources = REPO_ROOT / "test_graph/sources"
    support = (REPO_ROOT / "test_graph/support/tg6_lifecycle_support.py").read_text(encoding="utf-8")
    gh_provision = (sources / "Tg6GithubActionLifecycleProvisionMissing.py").read_text(encoding="utf-8")
    gh_deploy_markers = (sources / "Tg6GithubActionLifecycleDeployMarkers.py").read_text(encoding="utf-8")
    gh_destroy_markers = (sources / "Tg6GithubActionLifecycleDestroyMarkers.py").read_text(encoding="utf-8")
    aws_guard = (sources / "Tg6AwsLifecycleGuard.py").read_text(encoding="utf-8")
    gh_jbang = (sources / "Tg6GithubActionLifecycleContextJbang.java").read_text(encoding="utf-8")
    aws_jbang = (sources / "Tg6AwsLifecycleContextJbang.java").read_text(encoding="utf-8")
    aws_provision = (sources / "Tg6AwsLifecycleProvisionMissing.py").read_text(encoding="utf-8")
    aws_destroy = (sources / "Tg6AwsLifecycleDestroy.py").read_text(encoding="utf-8")
    aws_markers = (sources / "Tg6AwsLifecycleDeployMarkers.py").read_text(encoding="utf-8")

    assert "local-github-action" in support
    assert "github-action" in support
    assert "aws-preview" in support
    assert "TEST_GRAPH_RUN_AWS_LIFECYCLE" in support
    assert "AWS_PROFILE" in support
    assert "AWS_ACCESS_KEY_ID" in support
    assert "AWS_WEB_IDENTITY_TOKEN_FILE" in support
    assert "target_is_local_github_action" in gh_provision
    assert "backend_is_github_action" in gh_provision
    assert "tg6.github-action.lifecycle.context.jbang" in gh_jbang
    assert "SideEffect.envAll()" in gh_jbang
    assert "deploy_existing_skipped_recreate_apply" in gh_deploy_markers
    assert "destroy_ran_tofu_destroy" in gh_destroy_markers
    assert "aws_guard_reason_matches_state" in aws_guard
    assert "tg6.aws.lifecycle.context.jbang" in aws_jbang
    assert "aws_guard_reason_projected" in aws_jbang
    assert "aws_provision_guarded_without_cloud" in aws_provision
    assert "SideEffect.environment(action)" in support
    assert "ENABLED = aws_lifecycle_enabled()" in aws_provision
    assert "ENABLED = aws_lifecycle_enabled() and destroy_requested()" in aws_destroy
    assert "aws_provision_runtime_not_invoked_when_guarded" in aws_markers


def test_tg6f_references_document_guarded_lifecycle_graphs() -> None:
    env_reference = (REPO_ROOT / "references/environment-repositories.md").read_text(encoding="utf-8")
    gha_reference = (REPO_ROOT / "references/github-actions.md").read_text(encoding="utf-8")

    for phrase in [
        "environmentRepositoryGithubActionLifecycle",
        "environmentRepositoryGithubActionLifecycleDestroy",
        "environmentRepositoryAwsLifecycle",
        "environmentRepositoryAwsLifecycleDestroy",
        "TEST_GRAPH_RUN_AWS_LIFECYCLE=true",
        "TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true",
        "AWS_PROFILE=preview",
    ]:
        assert phrase in env_reference

    for phrase in [
        "Environment Repository Graphs",
        "local-github-action",
        "environmentRepositoryAwsLifecycleDestroy",
        "TEST_GRAPH_RUN_AWS_LIFECYCLE=true",
        "TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=true",
    ]:
        assert phrase in gha_reference


def test_tg6f_program_model_has_aws_guard_semantics() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")
    mc = (SPEC_ROOT / "MC.cfg").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")

    assert "@command GuardAwsBranchEnvironment" in tla
    assert "@invariant AwsProvisioningRequiresExplicitGuard" in tla
    assert "aws_execution_guarded" in tla
    assert "AwsTargets = {AwsPreview}" in mc
    assert "AwsProvisioningRequiresExplicitGuard" in mc
    assert "workflow: closed" in manifest
    assert "tg6f_guarded_lifecycle_validation_landed" in manifest
    assert "AwsLifecycleGraphAdapter" in manifest
