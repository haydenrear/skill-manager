from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1].parent


def test_current_ticket_workflow_scaffold_points_to_desired_plan() -> None:
    manifest = SPEC_ROOT / "current/spec_manifest.yaml"
    plan = SPEC_ROOT / "desired_program_model/ticket_plan.yaml"

    assert manifest.exists()
    assert plan.exists()
    assert "CLI-PROGDISC-001" in manifest.read_text(encoding="utf-8")
    assert "CLI-PROGDISC-001" in plan.read_text(encoding="utf-8")
