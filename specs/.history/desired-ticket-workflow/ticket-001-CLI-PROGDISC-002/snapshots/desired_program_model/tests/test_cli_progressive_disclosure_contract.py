from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DESIRED = ROOT / "specs/desired_program_model"


TOP_LEVEL_COMMANDS = {
    "ads",
    "bind",
    "bindings",
    "cli",
    "create",
    "create-account",
    "deps",
    "env",
    "gateway",
    "harness",
    "install",
    "list",
    "lock",
    "login",
    "onboard",
    "pm",
    "policy",
    "project",
    "publish",
    "registry",
    "rebind",
    "remove",
    "reset-password",
    "search",
    "show",
    "sync",
    "unbind",
    "uninstall",
    "upgrade",
}

REPRESENTATIVE_SUBCOMMANDS = {
    "ads create",
    "bindings show",
    "cli path",
    "env sync",
    "gateway up",
    "harness instantiate",
    "lock status",
    "login logout",
    "pm setup",
    "policy show",
    "project profiles list",
    "registry status",
}

WORKFLOWS = {
    "author-unit",
    "author-dependencies",
    "bind-projection",
    "cli-lock-inspect",
    "force-skill-scripts",
    "gateway-lifecycle",
    "harness-instantiate",
    "install-local-unit",
    "install-registry-unit",
    "project-env",
    "project-profile-resolve",
    "publish-unit",
    "skill-scripts",
    "sync-all-units",
    "sync-from-local-source",
    "sync-lockfile",
}


def test_desired_tla_models_cli_command_and_workflow_catalogs() -> None:
    tla = (DESIRED / "SkillManager.tla").read_text()

    assert "CliTopLevelCommands ==" in tla
    assert "CliSubcommands ==" in tla
    assert "CliWorkflowCatalog ==" in tla
    assert '"skill-manager"' in tla
    assert '<<"ls", "list">>' in tla
    assert '<<"rm", "remove">>' in tla
    assert '<<"un", "uninstall">>' in tla

    for command in TOP_LEVEL_COMMANDS | REPRESENTATIVE_SUBCOMMANDS:
        assert f'"{command}"' in tla

    for workflow in WORKFLOWS:
        assert f'"{workflow}"' in tla


def test_desired_tla_models_progressive_disclosure_actions_and_invariants() -> None:
    tla = (DESIRED / "SkillManager.tla").read_text()
    cfg = (DESIRED / "MC.cfg").read_text()

    assert "SPECIFICATION CliDisclosureSpec" in cfg
    assert "CHECK_DEADLOCK FALSE" in cfg

    for action in (
        "RenderProgressiveRootHelp",
        "RenderInstallCommandHelp",
        "RenderSyncCommandHelp",
        "RenderProjectProfilesListHelp",
        "ExposeInstallLocalUnitWorkflowDocs",
        "ExposeSkillScriptsWorkflowDocs",
        "ExposeProjectEnvWorkflowDocs",
        "EmitSyncOneUnitAgentContext",
        "EmitProjectEnvAgentContext",
    ):
        assert f"\\* @command {action}" in tla
        assert action in tla

    for action in (
        "\\* @command RenderCommandHelp",
        "\\* @command ExposeSkillWorkflowDocs",
        "\\* @command EmitAgentWorkflowContext",
        "RenderCommandHelp(command)",
        "ExposeSkillWorkflowDocs(workflow)",
        "EmitAgentWorkflowContext(workflow)",
    ):
        assert action not in tla

    for invariant in (
        "CliCommandCatalogCoversAllCommands",
        "CliRootHelpStaysProgressive",
        "CliCommandHelpCoversCatalog",
        "CliWorkflowCatalogCoversDesiredWorkflows",
        "CliWorkflowsReferenceCatalogCommands",
        "CliSkillDocsCoverWorkflowCatalog",
        "CliAgentContextCoversWorkflowCatalog",
    ):
        assert f"\\* @invariant {invariant}" in tla
        assert invariant in cfg


def test_ticket_plan_keeps_skill_docs_and_cli_sources_in_scope() -> None:
    plan = (DESIRED / "ticket_plan.yaml").read_text()
    manifest = (DESIRED / "spec_manifest.yaml").read_text()

    assert "MC.cfg" in plan
    assert "MC_program_promotion.cfg" in plan
    assert "two minutes or less" in plan

    for path in (
        "src/main/java/dev/skillmanager/cli/SkillManagerCli.java",
        "src/main/java/dev/skillmanager/commands",
        "skill-manager-skill",
        "skill-publisher-skill",
        "skill-dev-skill",
    ):
        assert path in plan or path in manifest

    assert "Root help exposes top-level commands" in plan
    assert "Agent-context adapter" in plan
