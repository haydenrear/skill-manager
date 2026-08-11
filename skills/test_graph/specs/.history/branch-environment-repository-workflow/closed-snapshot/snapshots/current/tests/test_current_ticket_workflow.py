from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1].parent


def test_current_ticket_workflow_scaffold_points_to_desired_plan() -> None:
    manifest = SPEC_ROOT / "current/spec_manifest.yaml"
    plan = SPEC_ROOT / "desired_program_model/ticket_plan.yaml"

    assert manifest.exists()
    assert plan.exists()
    assert "TG-5" in manifest.read_text(encoding="utf-8")
    assert "TG-5" in plan.read_text(encoding="utf-8")


def test_current_model_contains_tg5a_side_effect_runtime_slice() -> None:
    tla = (SPEC_ROOT / "current/TestGraph.tla").read_text(encoding="utf-8")
    assert "side_effect_runtime_configured" in tla
    assert "ConfigureSideEffectRuntime(g)" in tla

    assert "ConfigureSideEffectRuntime" in (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")


def test_current_model_contains_tg5b_provisioning_state_slice() -> None:
    tla = (SPEC_ROOT / "current/TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")

    assert "provisioning_state_configured" in tla
    assert "ProvisionBranchEnvironment(g, b, target, backend)" in tla
    assert "ResetBranchEnvironment(g, b, target, backend)" in tla
    assert "RequestMergedBranchDestroy(g, b, target, backend)" in tla
    assert "DestroyMergedBranchEnvironment(g, b, target, backend)" in tla

    assert "ProvisionBranchEnvironment" in manifest
    assert "ResetBranchEnvironment" in manifest


def test_current_model_contains_tg5c_environment_repository_contract_slice() -> None:
    tla = (SPEC_ROOT / "current/TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")

    assert "feature_branches" in tla
    assert "environment_repo_configured" in tla
    assert "branch_environment_specs" in tla
    assert "RegisterFeatureBranch(g, b)" in tla
    assert "ConfigureEnvironmentRepository(g)" in tla
    assert "DeclareBranchEnvironment(g, b, target, backend)" in tla
    assert "EnvironmentRepositoryRequiresProvisioningState" in tla
    assert "BranchEnvironmentsAreDeclaredForFeatureBranches" in tla
    assert "ProvisionedEnvironmentsAreDeclared" in tla

    assert "ConfigureEnvironmentRepository" in manifest
    assert "DeclareBranchEnvironment" in manifest


def test_current_manifest_records_tg5d_generated_git_fixture_without_new_model_actions() -> None:
    tla = (SPEC_ROOT / "current/TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")
    plan = (SPEC_ROOT / "desired_program_model/ticket_plan.yaml").read_text(encoding="utf-8")

    assert "generatedEnvironmentRepositoryFixture" in manifest
    assert "GeneratedGitEnvironmentRepositoryFixtureAdapter" in plan
    assert "git init/add/commit" in plan
    assert "GeneratedGitEnvironmentRepositoryFixtureAdapter" not in tla


def test_current_model_contains_tg5e_environment_execution_and_env_propagation_slice() -> None:
    tla = (SPEC_ROOT / "current/TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")
    plan = (SPEC_ROOT / "desired_program_model/ticket_plan.yaml").read_text(encoding="utf-8")

    assert "reused_branch_environments" in tla
    assert "propagated_environment_contexts" in tla
    assert "environment_context_keys" in tla
    assert "ReuseBranchEnvironment(g, b, target, backend)" in tla
    assert "PropagateEnvironmentContext(g, b, target, backend)" in tla
    assert "ReusedEnvironmentsAreProvisioned" in tla
    assert "PropagatedEnvironmentContextRequiresProvisionedContext" in tla

    assert "EnvironmentRepositoryAdapter" in plan
    assert "OpenTofuCommandAdapter" in plan
    assert "DownstreamEnvProjectionAdapter" in plan
    assert "environmentRepositoryContract" in manifest


def test_current_model_contains_tg5f_branch_environment_lifecycle_slice() -> None:
    tla = (SPEC_ROOT / "current/TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")
    plan = (SPEC_ROOT / "desired_program_model/ticket_plan.yaml").read_text(encoding="utf-8")

    assert "name: TG-5G" in manifest
    assert "deployed_branch_environments" in tla
    assert "DeployApplicationToBranchEnvironment(g, b, target, backend)" in tla
    assert "DeployedEnvironmentsHaveRequiredContext" in tla
    assert "deployed_branch_environments' = deployed_branch_environments \\ {e}" in tla
    assert "deployed_branch_environments \\cup" in tla

    assert "BranchEnvironmentLifecycleAdapter" in plan
    assert "branchEnvironmentReset" in manifest
    assert "branchEnvironmentMergeDestroy" in manifest
    assert "TG-5F" in plan


def test_current_manifest_records_tg5g_deploy_cdc_issue_without_new_model_actions() -> None:
    tla = (SPEC_ROOT / "current/TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")
    plan = (SPEC_ROOT / "desired_program_model/ticket_plan.yaml").read_text(encoding="utf-8")
    issue_body = (SPEC_ROOT.parent / "references/tickets/tg5-deploy-cdc-environment-repository-issue.md").read_text(encoding="utf-8")

    assert "name: TG-5G" in manifest
    assert "https://github.com/haydenrear/deploy-cdc/issues/6" in manifest
    assert "status: done" in plan
    assert "references/tickets/tg5-deploy-cdc-environment-repository-issue.md" in plan
    assert "https://github.com/haydenrear/deploy-cdc/issues/6" in plan
    assert "./scripts/run.py deployCdcIssueContract --test-graph-root test_graph" in plan
    assert "Add deploy-helm environment repository templates for test graph branch environments" in issue_body
    assert "@command CreateDeployHelmIssue" not in tla
    assert "deploy_cdc_environment_repository_issue" not in tla
