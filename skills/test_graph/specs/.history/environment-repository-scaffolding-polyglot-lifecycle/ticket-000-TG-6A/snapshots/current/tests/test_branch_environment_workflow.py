from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_program_model_contains_branch_environment_actions_and_invariants() -> None:
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
        "BranchEnvironmentsAreDeclaredForFeatureBranches",
        "ProvisionedEnvironmentsAreDeclared",
        "ResetKeepsBranchEnvironmentProvisioned",
        "DeployedEnvironmentsHaveRequiredContext",
        "PropagatedEnvironmentContextRequiresProvisionedContext",
        "MergeDestroyRequiresExplicitIntent",
        "DestroyedEnvironmentsAreNotActive",
    ]:
        assert f"@invariant {invariant}" in tla


def test_program_model_records_tg5_validation_evidence() -> None:
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")
    issue_body = (REPO_ROOT / "references/tickets/tg5-deploy-cdc-environment-repository-issue.md").read_text(encoding="utf-8")
    graph = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    assert "branch_environment_repository_workflow" in manifest
    assert "deployCdcIssueContract" in manifest
    assert "https://github.com/haydenrear/deploy-cdc/issues/6" in manifest
    assert "templates/local-preview" in issue_body
    assert "templates/aws-preview" in issue_body
    assert "KUBECONFIG" in issue_body
    assert "KUBECONTEXT" in issue_body
    assert "OpenTofu/CompuTeQ" in issue_body
    assert 'testGraph("deployCdcIssueContract")' in graph
