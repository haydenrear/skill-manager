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


def test_program_model_skill_docs_cover_modeled_workflows() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")
    adapter = _load_adapter_module()
    metadata = adapter.load_cli_metadata_source(ROOT)
    docs = adapter.load_cli_skill_docs_source(ROOT)

    # OUN-6: skill-manager-skill left this repository, so its surface is
    # external too and NOTHING is readable here. in_tree is empty by
    # construction, not by accident — assert that, or "coverage == in_tree"
    # becomes two empty sets agreeing about nothing.
    in_tree: set[tuple[str, str]] = set()
    manager = {
        ("skill-manager-skill", workflow)
        for workflow in _tla_set(tla, "SkillManagerSkillWorkflows")
    }
    # SI-18: the authoring surface is `unit-authoring`, a contained skill of
    # the tla-spec-dev plugin, and its pages are in another repository. The
    # model still names it and CliMetadata still points five workflows at it —
    # that relationship is this repo's to assert — but no reader here can open
    # the pages, so it is checked as an EXTERNAL set rather than as coverage.
    external = {
        ("unit-authoring", workflow)
        for workflow in _tla_set(tla, "UnitAuthoringWorkflows")
    }

    metadata_expected = {
        (surface, workflow)
        for workflow, surfaces in metadata.workflow_docs.items()
        for surface in surfaces
    }

    # CliMetadata still claims every pair the model declares.
    assert metadata_expected == manager | external
    # Nothing is readable here, so coverage is empty — and `missing` must be
    # empty too: an unreadable surface is EXTERNAL, never "undocumented".
    assert docs.coverage == in_tree == set()
    assert docs.help_routes == set()
    assert not docs.missing_workflow_docs
    assert not docs.missing_help_routes
    # THE WHOLE ASSERTION, now that no bytes can be read: every pair the model
    # declares is accounted for as external, exactly, and nothing has silently
    # dropped out of the catalogue. A workflow whose surface were misspelled
    # would land here and fail rather than vanish.
    assert docs.external == manager | external
    assert len(docs.external) == len(metadata_expected)
