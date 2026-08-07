import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
MODEL = ROOT / "specs/program_model"


def test_program_model_agent_context_model_has_production_context_hooks() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")
    cli = (ROOT / "src/main/java/dev/skillmanager/cli/SkillManagerCli.java").read_text(encoding="utf-8")
    context = (ROOT / "src/main/java/dev/skillmanager/cli/CliAgentContext.java").read_text(encoding="utf-8")
    metadata = (ROOT / "src/main/java/dev/skillmanager/cli/CliMetadata.java").read_text(encoding="utf-8")

    assert "EmitSyncOneUnitAgentContext" in tla
    assert "EmitProjectEnvAgentContext" in tla
    assert "CliAgentContextCoversWorkflowCatalog" in tla
    assert "--agent-context" in cli
    assert "SKILL_MANAGER_AGENT_CONTEXT" in cli
    assert "setExecutionExceptionHandler(SkillManagerCli::handleExecutionException)" in cli
    assert "completeExecution(rootCommand(pr), pr" in cli
    assert "CliAgentContext.emit" in cli
    assert "SKILL_MANAGER_AGENT_CONTEXT_BEGIN" in context
    assert "SKILL_MANAGER_AGENT_CONTEXT_END" in context
    assert "workflow_state: command_completed" in context
    assert '"sync-one-unit", "sync"' in metadata
    assert '"project-env", "env sync"' in metadata

    spec = importlib.util.spec_from_file_location(
        "program_model_production_adapters", MODEL / "production_adapters.py"
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    snapshot = module.load_cli_agent_context_source(ROOT)
    assert snapshot.inherited_option
    assert snapshot.env_enabled
    assert snapshot.renderer_delimited
    assert snapshot.handled_exception_context
    assert snapshot.sync_context
    assert snapshot.project_env_context
