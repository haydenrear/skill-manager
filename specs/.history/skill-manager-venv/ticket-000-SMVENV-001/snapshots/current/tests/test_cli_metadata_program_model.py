from __future__ import annotations

import importlib.util
from pathlib import Path
import re
import sys


ROOT = next(
    p for p in Path(__file__).resolve().parents
    if (p / "test_graph").is_dir() and (p / "specs").is_dir()
)
MODEL = Path(__file__).resolve().parents[1]


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
    # The CLI catalogs are constant-level helpers and live in Core.tla.
    tla = (MODEL / "Core.tla").read_text(encoding="utf-8")
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
    # Equality, not containment: a command that exists in production but not in
    # the model means the model no longer describes the CLI it claims to.
    assert modeled_commands == metadata.command_paths
    assert modeled_workflows == metadata.workflow_ids
    assert modeled_links == set(metadata.workflow_links.items())
    assert set(metadata.workflow_links.values()) <= metadata.command_paths
    assert metadata.workflow_examples["refresh-lockfile"] == frozenset({"skill-manager sync --refresh"})
    assert metadata.workflow_examples["sync-lockfile"] == frozenset({"skill-manager sync --lock units.lock.toml"})
