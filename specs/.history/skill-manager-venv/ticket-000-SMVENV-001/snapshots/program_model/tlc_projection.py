"""Projection helpers between TLC-dumped states and adapter-visible state.

TLA+ (Core/Internal/External) is the source of truth. This module projects
raw TLC states into the abstract program state compared by generated cases,
and projects adapter results back into the same shape. The normalization is
shared with ``spec_case_adapters`` so both sides of every comparison use one
canonical encoding.
"""

from __future__ import annotations

from typing import Any

NO_REASON = "NoReason"

# Trace/response variables that exist for case generation but are not part of
# the abstract program state compared between model and production.
_TRACE_VARIABLES = {
    "result",
    "lastInternalAction",
    "lastExternalAction",
    "serviceHealth",
    "lastServiceRoute",
}

# Sets of atomic model values (unit / doc / server / tool / script ids).
_SET_FIELDS = [
    "cli_store_units",
    "cli_doc_repos",
    "cli_harness_templates",
    "cli_harness_instances",
    "cli_installed_records",
    "cli_lock_units",
    "cli_bindings",
    "cli_projection_rows",
    "cli_managed_copies",
    "cli_import_directives",
    "cli_projection_conflicts",
    "cli_tool_records",
    "cli_cli_lock",
    "cli_skill_scripts_run",
    "cli_errors",
    "cli_gateway_mcp_snapshot",
    "rollback_journal",
    "gateway_catalog",
    "gateway_dynamic_servers",
    "gateway_global_deployments",
    "gateway_tools",
    "gateway_errors",
    "gateway_last_init",
    "server_registry_units",
    "server_authenticated_users",
]

# Sets of tuples (agent projections, session deployments, disclosures,
# registry versions/packages).
_PAIR_SET_FIELDS = [
    "cli_agent_projections",
    "gateway_session_deployments",
    "gateway_disclosures",
    "server_versions",
    "server_packages",
]

_BOOL_FIELDS = [
    "cli_gateway_url_configured",
    "cli_registry_url_configured",
    "program_halted",
    "always_after_ran",
]

_STRING_FIELDS = [
    "effect_status",
    "effect_continuation",
]

# project_model is one nested record; every field is a set of atoms or a set
# of tuples (pairs/triples), normalized recursively.
PROJECT_MODEL_FIELDS = [
    "manifests",
    "registrations",
    "locks",
    "resolved_units",
    "doc_bindings",
    "harness_bindings",
    "agent_configs",
    "env_realizations",
    "env_locks",
    "tool_shims",
    "skill_vendors",
    "env_docs",
    "env_specs",
    "lib_specs",
    "profile_declarations",
    "profile_locks",
    "profile_resolved_units",
    "profile_env_specs",
    "profile_lib_specs",
    "profile_child_homes",
    "lib_checkouts",
    "lib_locks",
    "child_homes",
    "project_child_homes",
    "child_home_parents",
    "child_home_harnesses",
    "child_home_agent_configs",
    "child_home_units",
    "child_home_mcp_servers",
    "child_home_tool_shims",
]

# Constant-valued CLI disclosure catalogs (pinned to the Core.tla constants by
# the CliCommandCatalogCoversAllCommands / CliRootHelpStaysProgressive /
# CliWorkflowCatalogCoversDesiredWorkflows / CliSkillDocsCoverWorkflowCatalog /
# CliAgentContextCoversWorkflowCatalog invariants on every TLC run). They are
# dropped from the projected program state so generated cases stay compact;
# the CLI-disclosure production adapters assert the production catalogs
# directly against source.
PROJECT_MODEL_CONSTANT_FIELDS = [
    "cli_command_catalog",
    "cli_command_aliases",
    "cli_workflow_catalog",
    "cli_workflow_command_links",
    "cli_root_help_topics",
    "cli_command_help_topics",
    "cli_skill_doc_topics",
    "cli_agent_context_topics",
]

STATE_FIELDS = (
    _SET_FIELDS + _PAIR_SET_FIELDS + _BOOL_FIELDS + _STRING_FIELDS + ["project_model"]
)


def project_visible_state(state: dict[str, Any]) -> dict[str, Any]:
    """Project a raw TLC state into the canonical abstract program state."""
    visible: dict[str, Any] = {}
    for name in _SET_FIELDS:
        visible[name] = _string_set(state.get(name))
    for name in _PAIR_SET_FIELDS:
        visible[name] = _tuple_set(state.get(name))
    for name in _BOOL_FIELDS:
        visible[name] = _bool(state.get(name))
    for name in _STRING_FIELDS:
        visible[name] = str(state.get(name)) if state.get(name) is not None else ""
    visible["project_model"] = _project_model(state.get("project_model"))
    return visible


# External (Test Graph) view: the subset of the program state the external
# harness can honestly observe through the isolated SKILL_MANAGER_HOME
# filesystem (store dirs, units.lock.toml, installed records, registry /
# gateway config, project registrations). External cases are generated and
# deduped under this projection; every full-model field outside it is
# recorded per case as a coverage gap by the assertion role, and the full
# projection remains checked at the internal spec-unit layer.
EXTERNAL_SET_FIELDS = [
    "cli_store_units",
    "cli_doc_repos",
    "cli_harness_templates",
    "cli_harness_instances",
]

EXTERNAL_BOOL_FIELDS = [
    "cli_registry_url_configured",
    "cli_gateway_url_configured",
]

EXTERNAL_PROJECT_MODEL_FIELDS = [
    "manifests",
    "registrations",
]


def project_external_visible_state(state: dict[str, Any]) -> dict[str, Any]:
    """Project a raw TLC state into the externally observable program state."""
    visible: dict[str, Any] = {}
    for name in EXTERNAL_SET_FIELDS:
        visible[name] = _string_set(state.get(name))
    for name in EXTERNAL_BOOL_FIELDS:
        visible[name] = _bool(state.get(name))
    project_model = _as_dict(state.get("project_model"))
    visible["project_model"] = {
        name: _mixed_set(project_model.get(name)) for name in EXTERNAL_PROJECT_MODEL_FIELDS
    }
    return visible


def normalize_external_state(raw: dict[str, Any]) -> dict[str, Any]:
    """Normalize an adapter-side observation into the external state shape."""
    return project_external_visible_state(raw)


def project_adapter_output(
    *,
    after: dict[str, Any],
    projected_before: dict[str, Any],
    action: str,
    params: dict[str, Any],
    view: str,
    **_kwargs: Any,
) -> dict[str, Any]:
    """The command result recorded by the model for this transition."""
    result = _as_dict(after.get("result"))
    return normalize_result(result.get("accepted"), result.get("reason"))


def normalize_result(accepted: Any, reason: Any) -> dict[str, Any]:
    reason_value = None if reason in (None, NO_REASON, "") else str(reason)
    return {"accepted": _bool(accepted), "reason": reason_value}


def normalize_program_state(raw: dict[str, Any]) -> dict[str, Any]:
    """Normalize an adapter-side state dict into the same canonical shape."""
    return project_visible_state(raw)


def _project_model(value: Any) -> dict[str, Any]:
    record = _as_dict(value)
    return {name: _mixed_set(record.get(name)) for name in PROJECT_MODEL_FIELDS}


def _mixed_set(value: Any) -> list[Any]:
    """Normalize a set whose members are atoms or tuples of atoms."""
    members = []
    for item in _as_iterable(value):
        if isinstance(item, (list, tuple)):
            members.append([str(part) for part in item])
        else:
            members.append(str(item))
    return sorted(members, key=lambda member: (isinstance(member, list), repr(member)))


def _string_set(value: Any) -> list[str]:
    return sorted(str(item) for item in _as_iterable(value))


def _tuple_set(value: Any) -> list[list[str]]:
    tuples = []
    for item in _as_iterable(value):
        parts = list(_as_iterable(item)) if not isinstance(item, str) else [item]
        tuples.append([str(part) for part in parts])
    return sorted(tuples)


def _bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().upper() == "TRUE"
    return bool(value)


def _as_iterable(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return list(value)
    if isinstance(value, (set, frozenset)):
        return sorted(value, key=repr)
    if isinstance(value, dict):
        raise TypeError(f"expected TLC collection, got mapping {value!r}")
    return [value]


def _as_dict(value: Any) -> dict[Any, Any]:
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    raise TypeError(f"expected TLC mapping, got {value!r}")
