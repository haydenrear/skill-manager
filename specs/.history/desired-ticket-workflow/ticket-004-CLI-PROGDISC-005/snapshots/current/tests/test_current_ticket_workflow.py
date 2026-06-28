from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1].parent


def test_current_ticket_workflow_scaffold_points_to_desired_plan() -> None:
    manifest = SPEC_ROOT / "current/spec_manifest.yaml"
    plan = SPEC_ROOT / "desired_program_model/ticket_plan.yaml"

    assert manifest.exists()
    assert plan.exists()
    manifest_text = manifest.read_text(encoding="utf-8")
    plan_text = plan.read_text(encoding="utf-8")
    assert "CLI-PROGDISC-005" in manifest_text
    assert "CLI-PROGDISC-001" in plan_text
    assert "CLI-PROGDISC-002" in plan_text
    assert "CLI-PROGDISC-003" in plan_text
    assert "CLI-PROGDISC-004" in plan_text
    assert "CLI-PROGDISC-005" in plan_text
    assert "CliDisclosureSpec" in (SPEC_ROOT / "current/MC.cfg").read_text(encoding="utf-8")
    assert "CliCommandCatalog" in (SPEC_ROOT / "current/SkillManager.tla").read_text(encoding="utf-8")
