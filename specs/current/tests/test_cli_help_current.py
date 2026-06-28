import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
CURRENT = ROOT / "specs/current"


def test_current_help_model_has_production_help_hooks() -> None:
    tla = (CURRENT / "SkillManager.tla").read_text(encoding="utf-8")
    cli = (ROOT / "src/main/java/dev/skillmanager/cli/SkillManagerCli.java").read_text(encoding="utf-8")
    install = (ROOT / "src/main/java/dev/skillmanager/commands/InstallCommand.java").read_text(encoding="utf-8")
    sync = (ROOT / "src/main/java/dev/skillmanager/commands/SyncCommand.java").read_text(encoding="utf-8")

    assert "CliRootHelpStaysProgressive" in tla
    assert "CliCommandHelpCoversCatalog" in tla
    assert "usageHelp = true" in cli
    assert "versionHelp = true" in cli
    assert "scope = CommandLine.ScopeType.INHERIT" in cli
    assert "scope = CommandLine.ScopeType.LOCAL" in cli
    assert 'description = "Install a unit and its declared dependencies."' in install
    assert "What install does:" in install
    assert 'description = "Refresh installed units and re-run install side effects."' in sync
    assert "Sync modes:" in sync

    spec = importlib.util.spec_from_file_location(
        "current_production_adapters", CURRENT / "production_adapters.py"
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    snapshot = module.load_cli_help_source(ROOT)
    assert snapshot.inherited_help
    assert snapshot.root_version_local
    assert snapshot.install_footer
    assert snapshot.sync_footer
