from pathlib import Path


ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent / "RunTests.java").is_file())
MODEL = Path(__file__).resolve().parents[1]


def test_program_model_preserves_project_child_home_and_force_script_boundaries() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")

    for needle in (
        "SyncClaimingProjectChildHomes",
        "@port SkillManagerCli.sync_claiming_project_child_homes",
        "u \\in ProjectChildHomePayload(pair[1])",
        "child_home_tool_shims = @ \\cup (homes \\X child_tools)",
        "InstallUnitForceScripts",
        "SyncUnitForceScripts",
        "OkForceScripts",
        "orphan_cli_deps == CliDepsFor({u}) \\ CliDepsFor(remaining)",
        "CliCliArtifactsAreClaimed",
        "CliCliLockRowsAreClaimed",
        "SkillScriptRunsAreClaimed",
    ):
        assert needle in tla


def test_program_model_accepts_bounded_cli_disclosure_case_surface() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")
    cfg = (MODEL / "MC.cfg").read_text(encoding="utf-8")
    manifest = (MODEL / "spec_manifest.yaml").read_text(encoding="utf-8")

    assert "SPECIFICATION CliDisclosureSpec" in cfg
    assert "CHECK_DEADLOCK FALSE" in cfg
    for needle in (
        "CliCommandCatalog ==",
        "CliWorkflowCatalog ==",
        "CliRootHelpStaysProgressive",
        "CliCommandHelpCoversCatalog",
        "CliSkillDocsCoverWorkflowCatalog",
        "CliAgentContextCoversWorkflowCatalog",
    ):
        assert needle in tla
        assert needle.replace(" ==", "") in manifest or needle in cfg


def test_program_model_validation_surfaces_remain_registered() -> None:
    graph = (ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    for graph_name in ("smoke", "doc-smoke", "plugin-smoke", "skill-dev-smoke"):
        assert f'testGraph("{graph_name}")' in graph

    for graph_name in ("smoke", "doc-smoke", "plugin-smoke"):
        assert graph_name in ci
