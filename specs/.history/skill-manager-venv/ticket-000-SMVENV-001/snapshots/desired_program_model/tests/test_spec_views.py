# /// script
# requires-python = ">=3.11"
# dependencies = ["pytest", "pyyaml"]
# ///
"""Layout and adapter-contract tests for the Core/Internal/External views.

Run with: uv run specs/program_model/tests/test_spec_views.py  (or pytest
through ``tla-spec-dev --spec-root specs run spec-unit-tests``).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

MODEL_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODEL_DIR))

import spec_case_adapters  # noqa: E402
import tlc_projection  # noqa: E402

DISCLOSURE_ACTIONS = {
    "RenderProgressiveRootHelp",
    "RenderInstallCommandHelp",
    "RenderSyncCommandHelp",
    "RenderProjectProfilesListHelp",
    "ExposeInstallLocalUnitWorkflowDocs",
    "ExposeSkillScriptsWorkflowDocs",
    "ExposeProjectEnvWorkflowDocs",
    "EmitSyncOneUnitAgentContext",
    "EmitProjectEnvAgentContext",
}


def _load_simple_actions_yaml(path: Path) -> dict:
    """Parse the uniform two-level actions mapping without requiring PyYAML.

    Falls back to PyYAML when available so richer formatting stays valid.
    """
    try:
        import yaml

        return yaml.safe_load(path.read_text())["actions"]
    except ModuleNotFoundError:
        pass
    actions: dict[str, dict] = {}
    current: dict | None = None
    in_actions = False
    for raw in path.read_text().splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        if line == "actions:":
            in_actions = True
            continue
        if not in_actions:
            continue
        if line.startswith("  ") and not line.startswith("    ") and line.endswith(":"):
            current = {}
            actions[line.strip()[:-1]] = current
            continue
        if current is None:
            continue
        stripped = line.strip()
        if stripped.startswith("- "):
            current.setdefault("generates", []).append(stripped[2:].strip())
            continue
        if ":" in stripped:
            key, _, value = stripped.partition(":")
            value = value.strip()
            if value == "[]":
                current[key.strip()] = []
            elif value:
                current[key.strip()] = value
            else:
                current[key.strip()] = []
    return actions


def _actions_metadata() -> dict:
    return _load_simple_actions_yaml(MODEL_DIR / "actions.yml")


def _internal_actions() -> set[str]:
    return {
        name
        for name, spec in _actions_metadata().items()
        if spec.get("layer") == "internal" and spec.get("controllability") != "hidden"
    }


def test_view_layout_files_exist():
    for name in [
        "Core.tla",
        "Internal.tla",
        "External.tla",
        "Internal.cfg",
        "InternalCliStoreCases.cfg",
        "InternalGatewayCases.cfg",
        "InternalServerRegistryCases.cfg",
        "InternalProjectCases.cfg",
        "InternalCliDisclosureCases.cfg",
        "External.cfg",
        "actions.yml",
        "testgraph_bindings.yml",
        "case_adapters.toml",
        "tlc_projection.py",
        "spec_case_adapters.py",
        "adapters.py",
        "production_adapters.py",
        "spec_manifest.yaml",
    ]:
        assert (MODEL_DIR / name).is_file(), f"missing {name}"
    assert not (MODEL_DIR / "SkillManager.tla").exists(), "single-module layout should be gone"
    # MC.cfg is kept only as a compatibility alias for tla-spec-dev workflow
    # tooling; it must mirror the Internal specification.
    mc = MODEL_DIR / "MC.cfg"
    assert mc.is_file(), "MC.cfg compatibility alias missing"
    assert "SPECIFICATION InternalSpec" in mc.read_text()


def test_internal_module_preserves_accepted_actions():
    internal = (MODEL_DIR / "Internal.tla").read_text()
    for action in sorted(_internal_actions()):
        assert f"{action}Impl" in internal, f"missing Impl for {action}"
        assert f'MarkInternal("{action}"' in internal, f"missing marked wrapper for {action}"


def test_store_double_covers_every_non_disclosure_internal_action():
    for action in sorted(_internal_actions() - DISCLOSURE_ACTIONS):
        handler = f"_do_{spec_case_adapters._snake(action)}"
        assert hasattr(spec_case_adapters.SkillManagerStoreModel, handler), (
            f"store double missing transition for {action}"
        )


def test_case_adapter_mapping_covers_every_internal_action():
    mapping = (MODEL_DIR / "case_adapters.toml").read_text()
    for action in sorted(_internal_actions()):
        assert f"[adapters.{action}]" in mapping, f"case_adapters.toml missing {action}"


def test_external_actions_have_bindings_and_metadata():
    external = (MODEL_DIR / "External.tla").read_text()
    declared = set(re.findall(r'MarkExternal\("([A-Za-z]+)"', external))
    declared.discard("HiddenInternalProgress")

    bindings = _load_simple_actions_yaml(MODEL_DIR / "testgraph_bindings.yml")
    assert declared == set(bindings), (
        f"bindings drift: only-in-model={sorted(declared - set(bindings))}, "
        f"only-in-bindings={sorted(set(bindings) - declared)}"
    )

    actions_meta = _actions_metadata()
    for action in declared:
        assert actions_meta.get(action, {}).get("layer") == "external", (
            f"{action} missing external metadata"
        )
    metadata_external = {
        name for name, spec in actions_meta.items() if spec.get("layer") == "external"
    }
    assert declared == metadata_external, (
        f"actions.yml drift: only-in-model={sorted(declared - metadata_external)}, "
        f"only-in-metadata={sorted(metadata_external - declared)}"
    )

    from adapters import _EXTERNAL_DISPATCH

    assert declared == set(_EXTERNAL_DISPATCH), (
        f"adapter dispatch drift: only-in-model={sorted(declared - set(_EXTERNAL_DISPATCH))}, "
        f"only-in-dispatch={sorted(set(_EXTERNAL_DISPATCH) - declared)}"
    )


def test_internal_case_adapter_replays_install_unit():
    adapter = spec_case_adapters.StoreModelCaseAdapter()
    before = spec_case_adapters.empty_visible_state()
    before["server_registry_units"] = ["UnitA"]

    class Input:
        action = "InstallUnit"
        params = {"unit": "UnitA"}

    class Case:
        name = "synthetic_install_unit"
        input = Input()

    Case.before = before
    result = adapter.run(Case())
    assert result["output"] == {"accepted": True, "reason": None}
    assert result["after"]["cli_store_units"] == ["UnitA"]
    assert result["after"]["cli_lock_units"] == ["UnitA"]
    assert result["after"]["cli_skill_scripts_run"] == ["ScriptA"]
    assert result["after"]["gateway_catalog"] == ["ServerA"]


def test_internal_case_adapter_validates_transition_delta_not_echo():
    """The double must reject a case whose declared after-state is wrong."""
    adapter = spec_case_adapters.StoreModelCaseAdapter()
    before = spec_case_adapters.empty_visible_state()

    class Input:
        action = "RemoveUnit"
        params = {"unit": "UnitA"}

    class Case:
        name = "synthetic_remove_not_installed"
        input = Input()

    Case.before = before
    result = adapter.run(Case())
    assert result["output"] == {"accepted": False, "reason": "NOT_INSTALLED"}
    assert result["after"] == spec_case_adapters.empty_visible_state()


def test_projection_round_trip_smoke():
    state = spec_case_adapters.empty_visible_state()
    state["result"] = {"accepted": "TRUE", "reason": "NoReason"}
    state["lastInternalAction"] = {"name": "Init", "params": []}
    state["lastExternalAction"] = {"name": "Init", "params": []}
    visible = tlc_projection.project_visible_state(state)
    for trace_var in tlc_projection._TRACE_VARIABLES:
        assert trace_var not in visible
    # Round trip: normalizing the projection is idempotent.
    assert tlc_projection.normalize_program_state(visible) == visible
    assert tlc_projection.normalize_result("TRUE", "NoReason") == {
        "accepted": True,
        "reason": None,
    }
    external = tlc_projection.project_external_visible_state(state)
    assert set(external) == set(
        tlc_projection.EXTERNAL_SET_FIELDS + tlc_projection.EXTERNAL_BOOL_FIELDS
    ) | {"project_model"}
    assert tlc_projection.normalize_external_state(external) == external


def test_external_projection_fields_match_adapter_observation():
    import adapters

    assert list(adapters.OBSERVED_FIELDS) == (
        tlc_projection.EXTERNAL_SET_FIELDS + tlc_projection.EXTERNAL_BOOL_FIELDS
    )
    assert list(adapters.OBSERVED_PROJECT_MODEL_FIELDS) == (
        tlc_projection.EXTERNAL_PROJECT_MODEL_FIELDS
    )


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
