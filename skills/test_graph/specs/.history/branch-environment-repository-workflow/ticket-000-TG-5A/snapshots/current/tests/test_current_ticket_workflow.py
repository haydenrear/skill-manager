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
    manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")

    assert "side_effect_runtime_configured" in tla
    assert "ConfigureSideEffectRuntime(g)" in tla
    assert "ConfigureProvisioningState(g)" not in tla
    assert "ConfigureEnvironmentRepository(g)" not in tla

    assert "name: TG-5A" in manifest
    assert "ConfigureSideEffectRuntime" in manifest
