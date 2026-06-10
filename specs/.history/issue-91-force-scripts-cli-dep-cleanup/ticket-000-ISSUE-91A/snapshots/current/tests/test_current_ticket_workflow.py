from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1].parent


def test_current_ticket_workflow_scaffold_points_to_desired_plan() -> None:
    manifest = SPEC_ROOT / "current/spec_manifest.yaml"
    plan = SPEC_ROOT / "desired_program_model/ticket_plan.yaml"

    assert manifest.exists()
    assert plan.exists()
    assert "ISSUE-91" in manifest.read_text(encoding="utf-8")
    assert "ISSUE-91" in plan.read_text(encoding="utf-8")


def test_current_model_has_issue_91a_force_script_actions_only() -> None:
    current_tla = (SPEC_ROOT / "current/SkillManager.tla").read_text(encoding="utf-8")
    current_manifest = (SPEC_ROOT / "current/spec_manifest.yaml").read_text(encoding="utf-8")

    assert "InstallUnitForceScripts" in current_tla
    assert "SyncUnitForceScripts" in current_tla
    assert "OkForceScripts" in current_tla
    assert "orphan_cli_deps == CliDepsFor({u}) \\ CliDepsFor(remaining)" not in current_tla
    assert "ISSUE-91A" in current_manifest
    assert "ISSUE-91B" in current_manifest
