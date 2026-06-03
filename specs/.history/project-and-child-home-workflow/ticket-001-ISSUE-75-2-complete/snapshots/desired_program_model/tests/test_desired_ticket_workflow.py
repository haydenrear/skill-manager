from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1].parent


def test_desired_ticket_workflow_names_project_and_child_boundaries() -> None:
    desired_tla = (SPEC_ROOT / "desired_program_model/SkillManager.tla").read_text(
        encoding="utf-8"
    )
    plan = (SPEC_ROOT / "desired_program_model/ticket_plan.yaml").read_text(
        encoding="utf-8"
    )

    assert "RegisterProjectManifest" in desired_tla
    assert "MaterializeProjectEnv" in desired_tla
    assert "InstantiateChildHomeFromHarness" in desired_tla
    assert "ScaffoldProjectChildHome" in desired_tla
    assert "skill-manager-project.toml" in plan
    assert "child Skill Manager" in plan
    assert "ProjectChildHomeScaffolderAdapter" in plan
