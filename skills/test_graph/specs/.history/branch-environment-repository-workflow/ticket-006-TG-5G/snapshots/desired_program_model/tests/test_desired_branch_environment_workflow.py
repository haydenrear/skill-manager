from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]


def test_desired_ticket_plan_splits_environment_workflow() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")

    for ticket_id in ["TG-5A", "TG-5B", "TG-5C", "TG-5D", "TG-5E", "TG-5F", "TG-5G"]:
        assert f"id: {ticket_id}" in plan

    assert "Add SDK side-effect runtime foundation" in plan
    assert "Add provisioning state markers and lifecycle guardrails" in plan
    assert "Define the Git environment repository contract" in plan
    assert "Generate a local Git environment repository fixture" in plan
    assert "Create the deploy-helm repository implementation ticket" in plan
    assert "id: TG-5G" in plan and "status: done" in plan
    assert "https://github.com/haydenrear/deploy-cdc/issues/6" in plan
    assert "deploy_cdc_environment_repository_issue.md" in plan
    assert "test_graph/environment-repository-source/" in plan
    assert "git init/add/commit" in plan


def test_desired_model_contains_branch_environment_actions_and_invariants() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")

    for action in [
        "ConfigureSideEffectRuntime",
        "ConfigureProvisioningState",
        "RegisterFeatureBranch",
        "ConfigureEnvironmentRepository",
        "DeclareBranchEnvironment",
        "ProvisionBranchEnvironment",
        "ReuseBranchEnvironment",
        "DeployApplicationToBranchEnvironment",
        "PropagateEnvironmentContext",
        "ResetBranchEnvironment",
        "RequestMergedBranchDestroy",
        "DestroyMergedBranchEnvironment",
    ]:
        assert f"@command {action}" in tla

    for invariant in [
        "ProvisioningStateRequiresSideEffectRuntime",
        "EnvironmentRepositoryRequiresProvisioningState",
        "ProvisionedEnvironmentsAreDeclared",
        "ResetKeepsBranchEnvironmentProvisioned",
        "DeployedEnvironmentsHaveRequiredContext",
        "PropagatedEnvironmentContextRequiresProvisionedContext",
        "MergeDestroyRequiresExplicitIntent",
        "DestroyedEnvironmentsAreNotActive",
    ]:
        assert f"@invariant {invariant}" in tla


def test_desired_manifest_records_git_fixture_and_deploy_helm_issue_ticket() -> None:
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")
    desired_state = (SPEC_ROOT / "desired_state.yaml").read_text(encoding="utf-8")
    issue_body = (SPEC_ROOT / "deploy_cdc_environment_repository_issue.md").read_text(encoding="utf-8")

    assert "before a deploy-helm repository issue is created" in manifest
    assert "GeneratedGitEnvironmentRepositoryFixtureAdapter" in manifest
    assert "deploy_helm_issue: https://github.com/haydenrear/deploy-cdc/issues/6" in desired_state
    assert "no checked-in nested .git" in desired_state
    assert "templates/local-preview" in issue_body
    assert "templates/aws-preview" in issue_body
    assert "KUBECONFIG" in issue_body
    assert "KUBECONTEXT" in issue_body
    assert "OpenTofu/CompuTeQ" in issue_body
