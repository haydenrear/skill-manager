from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_program_model_onboarding_scaffold_has_no_ticket_workflow_dirs() -> None:
    assert (ROOT / "specs/program_model/SkillManager.tla").exists()
    assert (ROOT / "specs/program_model/spec_manifest.yaml").exists()
    assert not (ROOT / "specs/current").exists()
    assert not (ROOT / "specs/desired_program_model").exists()
