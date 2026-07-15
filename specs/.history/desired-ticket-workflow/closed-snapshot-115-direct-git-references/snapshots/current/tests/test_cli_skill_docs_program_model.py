from __future__ import annotations

import importlib.util
from pathlib import Path
import re
import sys


ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent / "RunTests.java").is_file())
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


def test_program_model_skill_docs_cover_modeled_workflows() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")
    adapter = _load_adapter_module()
    metadata = adapter.load_cli_metadata_source(ROOT)
    docs = adapter.load_cli_skill_docs_source(ROOT)

    expected = {
        ("skill-manager-skill", workflow)
        for workflow in _tla_set(tla, "SkillManagerSkillWorkflows")
    } | {
        ("skill-publisher-skill", workflow)
        for workflow in _tla_set(tla, "SkillPublisherSkillWorkflows")
    } | {
        ("skill-dev-skill", workflow)
        for workflow in _tla_set(tla, "SkillDevSkillWorkflows")
    }

    metadata_expected = {
        (surface, workflow)
        for workflow, surfaces in metadata.workflow_docs.items()
        for surface in surfaces
    }

    assert metadata_expected == expected
    assert docs.coverage == expected
    assert docs.help_routes == expected
    assert not docs.missing_workflow_docs
    assert not docs.missing_help_routes
