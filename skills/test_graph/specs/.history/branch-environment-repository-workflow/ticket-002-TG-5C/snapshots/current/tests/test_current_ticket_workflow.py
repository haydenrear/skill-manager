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

    assert "name: TG-5C" in manifest
    assert "ConfigureEnvironmentRepository" in manifest
    assert "DeclareBranchEnvironment" in manifest
