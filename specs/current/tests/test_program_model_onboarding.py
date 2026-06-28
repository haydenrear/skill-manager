from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_ticket_workflow_scaffold_exists_for_active_ticket() -> None:
    assert (ROOT / "specs/program_model/SkillManager.tla").exists()
    assert (ROOT / "specs/program_model/spec_manifest.yaml").exists()
    assert (ROOT / "specs/current/SkillManager.tla").exists()
    assert (ROOT / "specs/desired_program_model/SkillManager.tla").exists()
    assert "CLI-PROGDISC-001" in (ROOT / "specs/current/spec_manifest.yaml").read_text()
    assert "CLI-PROGDISC-001" in (
        ROOT / "specs/desired_program_model/spec_manifest.yaml"
    ).read_text()


def test_program_model_tracks_parent_sync_refreshing_project_child_homes() -> None:
    tla = (ROOT / "specs/program_model/SkillManager.tla").read_text()
    manifest = (ROOT / "specs/program_model/spec_manifest.yaml").read_text()

    assert "SyncClaimingProjectChildHomes" in tla
    assert "@port SkillManagerCli.sync_claiming_project_child_homes" in tla
    assert "u \\in ProjectChildHomePayload(pair[1])" in tla
    assert "child_home_agent_configs = @ \\cup (homes \\X Agents)" in tla
    assert "child_home_tool_shims = @ \\cup (homes \\X child_tools)" in tla
    assert "ChildHomeShimsFor(payload)" in tla
    assert "ToolsFor(McpServersFor(payload)) \\cup PackagesFor(payload) \\cup ScriptsFor(payload)" in tla
    assert "PROJECT_CHILD_HOME_PARENT_GAP" in tla
    assert "Automatic parent sync refresh is non-destructive on project refresh failure." in tla
    assert "/\\ project_model' = project_model" in tla

    assert "SyncClaimingProjectChildHomes:" in manifest
    assert "sync_claiming_project_child_homes:" in manifest
    assert "ToolId | PackageId | ScriptId" in manifest


def test_program_model_tracks_force_scripts_and_cli_dep_cleanup() -> None:
    tla = (ROOT / "specs/program_model/SkillManager.tla").read_text()
    manifest = (ROOT / "specs/program_model/spec_manifest.yaml").read_text()

    assert "InstallUnitForceScripts" in tla
    assert "SyncUnitForceScripts" in tla
    assert "OkForceScripts" in tla
    assert "CliDepsFor(units)" in tla
    assert "orphan_cli_deps == CliDepsFor({u}) \\ CliDepsFor(remaining)" in tla
    assert "rolled_back_cli_deps == CliDepsFor(rolled_back_units)" in tla
    assert "cli_tool_records' = cli_tool_records \\cup CliDepsFor(resolved_units)" in tla
    assert "cli_cli_lock' = cli_cli_lock \\cup CliDepsFor(resolved_units)" in tla
    assert "CliCliArtifactsAreClaimed" in tla
    assert "CliCliLockRowsAreClaimed" in tla
    assert "SkillScriptRunsAreClaimed" in tla

    assert "install_unit_force_scripts:" in manifest
    assert "sync_unit_force_scripts:" in manifest
    assert "RemoveUnit:" in manifest
    assert "Force-scripts changes rerun eligibility only" in manifest


def test_project_smoke_graph_is_registered_for_ci_and_spec_validation() -> None:
    manifest = (ROOT / "specs/program_model/spec_manifest.yaml").read_text()
    graph = (ROOT / "test_graph/build.gradle.kts").read_text()
    ci = (ROOT / ".github/workflows/ci.yml").read_text()

    assert 'testGraph("project-smoke")' in graph
    assert "project-smoke" in manifest
    assert "project-smoke" in ci
    assert "max-parallel: 2" in ci
