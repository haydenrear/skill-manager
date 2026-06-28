from __future__ import annotations

import importlib.util
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[3]
MODEL = ROOT / "specs/program_model"


def _load_adapter_module():
    spec = importlib.util.spec_from_file_location(
        "program_model_production_adapters", MODEL / "production_adapters.py"
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _tla_set(text: str, name: str) -> set[str]:
    match = re.search(rf"{name}\s*==\s*(.*?)(?:\n\n|$)", text, re.S)
    assert match, f"missing {name}"
    return set(re.findall(r'"([^"]+)"', match.group(1)))


def _tla_tuple_pairs(text: str, name: str) -> set[tuple[str, str]]:
    match = re.search(rf"{name}\s*==\s*(.*?)(?:\n\n|$)", text, re.S)
    assert match, f"missing {name}"
    return {
        (left, right)
        for left, right in re.findall(r'<<"([^"]+)",\s*"([^"]+)">>', match.group(1))
    }


def test_program_model_tla_cli_catalog_is_backed_by_production_metadata() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")
    adapter = _load_adapter_module()
    metadata = adapter.load_cli_metadata_source(ROOT)

    root_command = _tla_set(tla, "CliCommandCatalog") - _tla_set(tla, "CliTopLevelCommands") - _tla_set(tla, "CliSubcommands")
    if not root_command:
        root_command = {"skill-manager"}
    modeled_commands = {"skill-manager"} | _tla_set(tla, "CliTopLevelCommands") | _tla_set(tla, "CliSubcommands")
    modeled_aliases = _tla_tuple_pairs(tla, "CliCommandAliases")
    modeled_workflows = _tla_set(tla, "CliWorkflowCatalog")
    modeled_links = _tla_tuple_pairs(tla, "CliWorkflowCommandLinks")

    assert root_command == {"skill-manager"}
    assert "bindings show" in modeled_commands
    assert ("ls", "list") in modeled_aliases
    assert modeled_commands <= metadata.command_paths
    assert modeled_workflows == metadata.workflow_ids
    assert modeled_links == set(metadata.workflow_links.items())
    assert set(metadata.workflow_links.values()) <= metadata.command_paths
