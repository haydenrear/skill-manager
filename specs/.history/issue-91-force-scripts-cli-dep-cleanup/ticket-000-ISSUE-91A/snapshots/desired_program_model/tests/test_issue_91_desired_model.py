from pathlib import Path


SPEC_DIR = Path(__file__).resolve().parents[1]


def test_issue_91_desired_tla_models_force_scripts_and_cli_cleanup() -> None:
    tla = (SPEC_DIR / "SkillManager.tla").read_text(encoding="utf-8")
    manifest = (SPEC_DIR / "spec_manifest.yaml").read_text(encoding="utf-8")
    plan = (SPEC_DIR / "ticket_plan.yaml").read_text(encoding="utf-8")

    assert "CliDepsFor(units)" in tla
    assert "InstallUnitForceScripts" in tla
    assert "SyncUnitForceScripts" in tla
    assert "orphan_cli_deps == CliDepsFor({u}) \\ CliDepsFor(remaining)" in tla
    assert "CliCliArtifactsAreClaimed" in tla
    assert "CliCliLockRowsAreClaimed" in tla
    assert "SkillScriptRunsAreClaimed" in tla

    assert "install_unit_force_scripts" in manifest
    assert "sync_unit_force_scripts" in manifest
    assert "Force-scripts changes rerun eligibility only" in manifest
    assert "ISSUE-91A" in plan
    assert "ISSUE-91B" in plan
