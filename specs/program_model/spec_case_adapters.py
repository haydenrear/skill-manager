"""Spec-unit case adapters for TLC-generated internal cases.

Each generated ``StateGraphCase`` carries one action-labeled TLC edge: a
projected ``before`` state, the command marker (action + params), the model
result, and the projected ``after`` state.

``StoreModelCaseAdapter`` replays non-disclosure internal cases against a
small in-memory executable double of the skill-manager store semantics.
The double is a faithful Python transcription of the Internal.tla
transitions over the same collapsed finite model the slice configs use
(UnitB = UnitA, ServerB = ServerA, ToolB = ToolA, SessionB = SessionA).
It loads ``case.before``, applies its own transition implementation for the
action, and returns the observed result plus the re-projected program state
so the generated validators compare the transition delta structurally —
never an echo of ``case.after``.

The nine CLI progressive-disclosure actions keep their production source
adapters (``production_adapters:CliHelpAdapter`` /
``CliSkillDocsAdapter`` / ``CliAgentContextAdapter``) via
``case_adapters.toml``.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

_SPEC_DIR = Path(__file__).resolve().parent
if str(_SPEC_DIR) not in sys.path:
    sys.path.insert(0, str(_SPEC_DIR))

import tlc_projection

# ---------------------------------------------------------------------------
# Collapsed finite model (mirrors Core.tla constants + the slice cfg
# substitutions UnitB=UnitA, ServerB=ServerA, ToolB=ToolA, SessionB=SessionA).
# ---------------------------------------------------------------------------

UNIT_A = "UnitA"
DOC_REPO_A = "DocRepoA"
HARNESS_A = "HarnessA"
PARENT_HOME_A = "ParentHomeA"

UNITS = frozenset({UNIT_A})
DOC_REPOS = frozenset({DOC_REPO_A})
HARNESS_TEMPLATES = frozenset({HARNESS_A})
AGENTS = frozenset({"ClaudeAgent", "CodexAgent", "GeminiAgent"})
SERVERS = frozenset({"ServerA"})
TOOLS = frozenset({"ToolA"})
SCRIPTS = frozenset({"ScriptA"})
PACKAGES = frozenset({"PackageA"})
SESSIONS = frozenset({"SessionA"})
PROJECTS = frozenset({"ProjectA"})
ENVS = frozenset({"EnvA"})
LIBS = frozenset({"LibA"})
PROFILES = frozenset({"ProfileA"})
CHILD_HOMES = frozenset({"ChildHomeA"})
SKILL_MANAGER_HOMES = frozenset({"ParentHomeA", "ChildHomeA"})

REFERENCE_EDGES = frozenset({(UNIT_A, UNIT_A)})
UNIT_MCP_EDGES = frozenset({(UNIT_A, "ServerA")})
SERVER_TOOL_EDGES = frozenset({("ServerA", "ToolA")})
UNIT_SCRIPT_EDGES = frozenset({(UNIT_A, "ScriptA")})
UNIT_PACKAGE_EDGES = frozenset({(UNIT_A, "PackageA")})
HARNESS_TEMPLATE_EDGES = frozenset({(HARNESS_A, UNIT_A)})
PROJECT_UNIT_EDGES = frozenset(
    {("ProjectA", UNIT_A), ("ProjectA", DOC_REPO_A), ("ProjectA", HARNESS_A)}
)
PROJECT_ENV_SPEC_EDGES = frozenset({("ProjectA", "EnvA")})
PROJECT_LIB_SPEC_EDGES = frozenset({("ProjectA", "LibA")})
PROJECT_CHILD_HOME_EDGES = frozenset({("ProjectA", "ChildHomeA")})
PROJECT_PROFILE_EDGES = frozenset({("ProjectA", "ProfileA")})
PROJECT_PROFILE_UNIT_EDGES = frozenset({("ProjectA", "ProfileA", UNIT_A)})
PROJECT_PROFILE_DOC_REPO_EDGES = frozenset({("ProjectA", "ProfileA", DOC_REPO_A)})
PROJECT_PROFILE_HARNESS_EDGES = frozenset({("ProjectA", "ProfileA", HARNESS_A)})
PROJECT_PROFILE_ENV_SPEC_EDGES = frozenset({("ProjectA", "ProfileA", "EnvA")})
PROJECT_PROFILE_LIB_SPEC_EDGES = frozenset({("ProjectA", "ProfileA", "LibA")})
PROJECT_PROFILE_CHILD_HOME_EDGES = frozenset({("ProjectA", "ProfileA", "ChildHomeA")})


def refs_for(units: frozenset[str]) -> frozenset[str]:
    return frozenset(ref for ref in UNITS if any((u, ref) in REFERENCE_EDGES for u in units))


def dependency_closure(units: frozenset[str]) -> frozenset[str]:
    return units | refs_for(units) | refs_for(refs_for(units))


def mcp_servers_for(units: frozenset[str]) -> frozenset[str]:
    return frozenset(s for s in SERVERS if any((u, s) in UNIT_MCP_EDGES for u in units))


def tools_for(servers: frozenset[str]) -> frozenset[str]:
    return frozenset(t for t in TOOLS if any((s, t) in SERVER_TOOL_EDGES for s in servers))


def scripts_for(units: frozenset[str]) -> frozenset[str]:
    return frozenset(s for s in SCRIPTS if any((u, s) in UNIT_SCRIPT_EDGES for u in units))


def packages_for(units: frozenset[str]) -> frozenset[str]:
    return frozenset(p for p in PACKAGES if any((u, p) in UNIT_PACKAGE_EDGES for u in units))


def cli_deps_for(units: frozenset[str]) -> frozenset[str]:
    return packages_for(units) | scripts_for(units)


def child_home_shims_for(payload: frozenset[str]) -> frozenset[str]:
    return tools_for(mcp_servers_for(payload)) | packages_for(payload) | scripts_for(payload)


def harness_units_for(template: str) -> frozenset[str]:
    return frozenset(u for u in UNITS if (template, u) in HARNESS_TEMPLATE_EDGES)


def project_env_specs(project: str) -> frozenset[str]:
    return frozenset(e for e in ENVS if (project, e) in PROJECT_ENV_SPEC_EDGES)


def project_lib_specs(project: str) -> frozenset[str]:
    return frozenset(l for l in LIBS if (project, l) in PROJECT_LIB_SPEC_EDGES)


def project_direct_units(project: str) -> frozenset[str]:
    return frozenset(u for u in UNITS if (project, u) in PROJECT_UNIT_EDGES)


def project_doc_repos(project: str) -> frozenset[str]:
    return frozenset(d for d in DOC_REPOS if (project, d) in PROJECT_UNIT_EDGES)


def project_harness_templates(project: str) -> frozenset[str]:
    return frozenset(t for t in HARNESS_TEMPLATES if (project, t) in PROJECT_UNIT_EDGES)


def project_resolved_unit_closure(project: str) -> frozenset[str]:
    seed = project_direct_units(project)
    for template in project_harness_templates(project):
        seed |= harness_units_for(template)
    return dependency_closure(seed)


def project_child_home_payload(project: str) -> frozenset[str]:
    return (
        project_resolved_unit_closure(project)
        | project_doc_repos(project)
        | project_harness_templates(project)
    )


def project_profiles(project: str) -> frozenset[str]:
    return frozenset(p for p in PROFILES if (project, p) in PROJECT_PROFILE_EDGES)


def project_profile_env_specs(project: str, profile: str) -> frozenset[str]:
    return frozenset(e for e in ENVS if (project, profile, e) in PROJECT_PROFILE_ENV_SPEC_EDGES)


def project_profile_lib_specs(project: str, profile: str) -> frozenset[str]:
    return frozenset(l for l in LIBS if (project, profile, l) in PROJECT_PROFILE_LIB_SPEC_EDGES)


def project_profile_direct_units(project: str, profile: str) -> frozenset[str]:
    return frozenset(u for u in UNITS if (project, profile, u) in PROJECT_PROFILE_UNIT_EDGES)


def project_profile_doc_repos(project: str, profile: str) -> frozenset[str]:
    return frozenset(d for d in DOC_REPOS if (project, profile, d) in PROJECT_PROFILE_DOC_REPO_EDGES)


def project_profile_harness_templates(project: str, profile: str) -> frozenset[str]:
    return frozenset(t for t in HARNESS_TEMPLATES if (project, profile, t) in PROJECT_PROFILE_HARNESS_EDGES)


def project_profile_resolved_unit_closure(project: str, profile: str) -> frozenset[str]:
    seed = project_profile_direct_units(project, profile)
    for template in project_profile_harness_templates(project, profile):
        seed |= harness_units_for(template)
    return dependency_closure(seed)


def project_profile_child_home_payload(project: str, profile: str) -> frozenset[str]:
    return (
        project_profile_resolved_unit_closure(project, profile)
        | project_profile_doc_repos(project, profile)
        | project_profile_harness_templates(project, profile)
    )


def unit_projections(units: frozenset[str]) -> frozenset[tuple[str, str]]:
    return frozenset((agent, unit) for agent in AGENTS for unit in units)


def ok() -> dict[str, Any]:
    return tlc_projection.normalize_result(True, None)


def ok_force_scripts() -> dict[str, Any]:
    return tlc_projection.normalize_result(True, "FORCE_SCRIPTS_RERUN")


def reject(reason: str) -> dict[str, Any]:
    return tlc_projection.normalize_result(False, reason)


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


class SkillManagerStoreModel:
    """In-memory executable double of the Internal.tla store transitions."""

    def __init__(self) -> None:
        for name in _SET_FIELDS:
            setattr(self, name, frozenset())
        for name in _PAIR_SET_FIELDS:
            setattr(self, name, frozenset())
        for name in _BOOL_FIELDS:
            setattr(self, name, False)
        self.effect_status = "OK"
        self.effect_continuation = "CONTINUE"
        self.project_model: dict[str, frozenset] = {
            name: frozenset() for name in tlc_projection.PROJECT_MODEL_FIELDS
        }

    # -- state loading / snapshotting ------------------------------------

    @classmethod
    def from_projected(cls, before: dict[str, Any]) -> "SkillManagerStoreModel":
        model = cls()
        for name in _SET_FIELDS:
            setattr(model, name, frozenset(str(item) for item in before.get(name, [])))
        for name in _PAIR_SET_FIELDS:
            setattr(
                model,
                name,
                frozenset(tuple(str(part) for part in pair) for pair in before.get(name, [])),
            )
        for name in _BOOL_FIELDS:
            setattr(model, name, bool(before.get(name, False)))
        model.effect_status = str(before.get("effect_status", "OK"))
        model.effect_continuation = str(before.get("effect_continuation", "CONTINUE"))
        raw_project = dict(before.get("project_model", {}))
        for field in tlc_projection.PROJECT_MODEL_FIELDS:
            members = set()
            for item in raw_project.get(field, []):
                if isinstance(item, (list, tuple)):
                    members.add(tuple(str(part) for part in item))
                else:
                    members.add(str(item))
            model.project_model[field] = frozenset(members)
        return model

    def snapshot(self) -> dict[str, Any]:
        raw: dict[str, Any] = {}
        for name in _SET_FIELDS + _PAIR_SET_FIELDS + _BOOL_FIELDS:
            raw[name] = getattr(self, name)
        raw["effect_status"] = self.effect_status
        raw["effect_continuation"] = self.effect_continuation
        raw["project_model"] = dict(self.project_model)
        return tlc_projection.normalize_program_state(raw)

    # -- derived state ----------------------------------------------------

    def project_claimed_units(self) -> frozenset[str]:
        resolved = {entry[1] for entry in self.project_model["resolved_units"]}
        child_units = {entry[1] for entry in self.project_model["child_home_units"]}
        return frozenset(resolved | child_units)

    def project_locked_units(self, project: str) -> frozenset[str]:
        return frozenset(
            entry[1] for entry in self.project_model["resolved_units"] if entry[0] == project
        )

    def session_servers(self) -> frozenset[str]:
        return frozenset(pair[1] for pair in self.gateway_session_deployments)

    def deployed_servers(self) -> frozenset[str]:
        return self.gateway_global_deployments | self.session_servers()

    def visible_tools(self) -> frozenset[str]:
        return tools_for(self.deployed_servers())

    # -- effect helpers ---------------------------------------------------

    def _effect_ok(self) -> None:
        self.effect_status = "OK"
        self.effect_continuation = "CONTINUE"
        self.program_halted = False

    def _effect_halt(self, reason: str) -> dict[str, Any]:
        self.effect_status = "HALTED"
        self.effect_continuation = "HALT"
        self.program_halted = True
        return reject(reason)

    def _pm_union(self, field: str, members) -> None:
        self.project_model[field] = self.project_model[field] | frozenset(members)

    # -- command dispatch ---------------------------------------------------

    def apply(self, action: str, params: dict[str, Any]) -> dict[str, Any]:
        handler = getattr(self, f"_do_{_snake(action)}", None)
        if handler is None:
            raise ValueError(f"store model does not implement action {action}")
        return handler(params)

    # -- registry server ---------------------------------------------------

    def _do_server_authenticate(self, p: dict[str, Any]) -> dict[str, Any]:
        self.server_authenticated_users = self.server_authenticated_users | {str(p["user"])}
        return ok()

    def _do_server_publish_tarball(self, p: dict[str, Any]) -> dict[str, Any]:
        user, unit, version = str(p["user"]), str(p["unit"]), str(p["version"])
        if user not in self.server_authenticated_users:
            return reject("AUTHENTICATION_REQUIRED")
        self.server_registry_units = self.server_registry_units | {unit}
        self.server_versions = self.server_versions | {(unit, version)}
        self.server_packages = self.server_packages | {(unit, version)}
        return ok()

    def _do_server_search(self, p: dict[str, Any]) -> dict[str, Any]:
        return ok()

    def _do_configure_registry(self, p: dict[str, Any]) -> dict[str, Any]:
        self.cli_registry_url_configured = True
        return ok()

    def _do_ensure_gateway(self, p: dict[str, Any]) -> dict[str, Any]:
        self.cli_gateway_url_configured = True
        return ok()

    # -- install / sync / remove -------------------------------------------

    def _surface(self, units: frozenset[str], force_scripts: bool) -> None:
        old_cli_lock = self.cli_cli_lock
        self.cli_store_units = self.cli_store_units | units
        self.cli_installed_records = self.cli_installed_records | units
        self.cli_agent_projections = self.cli_agent_projections | unit_projections(units)
        self.cli_bindings = self.cli_bindings | units
        self.cli_tool_records = self.cli_tool_records | cli_deps_for(units)
        self.cli_cli_lock = self.cli_cli_lock | cli_deps_for(units)
        if force_scripts:
            self.cli_skill_scripts_run = self.cli_skill_scripts_run | scripts_for(units)
        else:
            self.cli_skill_scripts_run = self.cli_skill_scripts_run | (
                scripts_for(units) - old_cli_lock
            )
        self.gateway_catalog = self.gateway_catalog | mcp_servers_for(units)
        self.gateway_dynamic_servers = self.gateway_dynamic_servers | mcp_servers_for(units)

    def _install(self, unit: str, force_scripts: bool) -> dict[str, Any]:
        install_set = dependency_closure(frozenset({unit}))
        if unit in self.cli_store_units:
            return self._effect_halt("ALREADY_INSTALLED")
        if not install_set <= self.server_registry_units:
            self.cli_errors = self.cli_errors | {unit}
            return self._effect_halt("RESOLVE_FAILED")
        self._surface(install_set, force_scripts)
        self.cli_lock_units = self.cli_lock_units | install_set
        self.rollback_journal = frozenset()
        self._effect_ok()
        return ok_force_scripts() if force_scripts else ok()

    def _do_install_unit(self, p: dict[str, Any]) -> dict[str, Any]:
        return self._install(str(p["unit"]), force_scripts=False)

    def _do_install_unit_force_scripts(self, p: dict[str, Any]) -> dict[str, Any]:
        return self._install(str(p["unit"]), force_scripts=True)

    def _sync(self, unit: str, force_scripts: bool) -> dict[str, Any]:
        surfaced = dependency_closure(frozenset({unit}))
        if unit not in self.cli_store_units:
            return self._effect_halt("NOT_INSTALLED")
        if not surfaced <= self.server_registry_units:
            self.effect_status = "PARTIAL"
            self.effect_continuation = "CONTINUE"
            self.program_halted = False
            self.cli_errors = self.cli_errors | {unit}
            return reject("TRANSITIVE_RESOLVE_FAILED")
        self._surface(surfaced, force_scripts)
        self.cli_lock_units = self.cli_store_units
        self._effect_ok()
        return ok_force_scripts() if force_scripts else ok()

    def _do_sync_unit(self, p: dict[str, Any]) -> dict[str, Any]:
        return self._sync(str(p["unit"]), force_scripts=False)

    def _do_sync_unit_force_scripts(self, p: dict[str, Any]) -> dict[str, Any]:
        return self._sync(str(p["unit"]), force_scripts=True)

    def _do_remove_unit(self, p: dict[str, Any]) -> dict[str, Any]:
        unit = str(p["unit"])
        if unit not in self.cli_store_units:
            return reject("NOT_INSTALLED")
        if any(
            (dependent, unit) in REFERENCE_EDGES
            for dependent in self.cli_store_units - {unit}
        ):
            return reject("DEPENDENT_INSTALLED")
        if unit in self.project_claimed_units():
            return reject("PROJECT_CLAIMED")
        remaining = self.cli_store_units - {unit}
        orphan_servers = mcp_servers_for(frozenset({unit})) - mcp_servers_for(remaining)
        orphan_cli_deps = cli_deps_for(frozenset({unit})) - cli_deps_for(remaining)
        orphan_scripts = scripts_for(frozenset({unit})) - scripts_for(remaining)
        self.cli_store_units = remaining
        self.cli_installed_records = self.cli_installed_records - {unit}
        self.cli_lock_units = self.cli_lock_units - {unit}
        self.cli_agent_projections = frozenset(
            pair for pair in self.cli_agent_projections if pair[1] != unit
        )
        self.cli_bindings = self.cli_bindings - {unit}
        self.cli_projection_rows = self.cli_projection_rows - {unit}
        self.cli_tool_records = self.cli_tool_records - orphan_cli_deps
        self.cli_cli_lock = self.cli_cli_lock - orphan_cli_deps
        self.cli_skill_scripts_run = self.cli_skill_scripts_run - orphan_scripts
        self.gateway_catalog = self.gateway_catalog - orphan_servers
        self.gateway_dynamic_servers = self.gateway_dynamic_servers - orphan_servers
        self.gateway_global_deployments = self.gateway_global_deployments - orphan_servers
        self.gateway_session_deployments = frozenset(
            pair for pair in self.gateway_session_deployments if pair[1] not in orphan_servers
        )
        self.gateway_tools = tools_for(
            self.gateway_global_deployments
            | frozenset(pair[1] for pair in self.gateway_session_deployments)
        )
        return ok()

    # -- doc repos / harness -------------------------------------------------

    def _do_bind_doc_repo(self, p: dict[str, Any]) -> dict[str, Any]:
        doc = str(p["doc"])
        self.cli_doc_repos = self.cli_doc_repos | {doc}
        self.cli_bindings = self.cli_bindings | {doc}
        self.cli_projection_rows = self.cli_projection_rows | {doc}
        self.cli_managed_copies = self.cli_managed_copies | {doc}
        self.cli_import_directives = self.cli_import_directives | {doc}
        return ok()

    def _do_sync_doc_repo(self, p: dict[str, Any]) -> dict[str, Any]:
        doc = str(p["doc"])
        if doc not in self.cli_doc_repos:
            return reject("DOC_REPO_NOT_INSTALLED")
        self.cli_managed_copies = self.cli_managed_copies | {doc}
        self.cli_import_directives = self.cli_import_directives | {doc}
        self.cli_projection_conflicts = self.cli_projection_conflicts - {doc}
        return ok()

    def _do_sync_harness(self, p: dict[str, Any]) -> dict[str, Any]:
        template, instance = str(p["template"]), str(p["instance"])
        needed = harness_units_for(template)
        if not needed <= self.cli_store_units:
            return reject("HARNESS_REFERENCE_GAP")
        self.cli_harness_templates = self.cli_harness_templates | {template}
        self.cli_harness_instances = self.cli_harness_instances | {instance}
        self.cli_bindings = self.cli_bindings | needed
        self.cli_projection_rows = self.cli_projection_rows | needed
        return ok()

    # -- effect program --------------------------------------------------------

    def _do_run_effect_program_failure(self, p: dict[str, Any]) -> dict[str, Any]:
        rolled_back = self.rollback_journal
        rolled_servers = mcp_servers_for(rolled_back)
        rolled_cli_deps = cli_deps_for(rolled_back)
        rolled_scripts = scripts_for(rolled_back)
        self.cli_store_units = self.cli_store_units - rolled_back
        self.cli_installed_records = self.cli_installed_records - rolled_back
        self.cli_lock_units = self.cli_lock_units - rolled_back
        self.cli_agent_projections = frozenset(
            pair for pair in self.cli_agent_projections if pair[1] not in rolled_back
        )
        self.cli_bindings = self.cli_bindings - rolled_back
        self.cli_projection_rows = self.cli_projection_rows - rolled_back
        self.cli_tool_records = self.cli_tool_records - rolled_cli_deps
        self.cli_cli_lock = self.cli_cli_lock - rolled_cli_deps
        self.cli_skill_scripts_run = self.cli_skill_scripts_run - rolled_scripts
        self.gateway_catalog = self.gateway_catalog - rolled_servers
        self.gateway_dynamic_servers = self.gateway_dynamic_servers - rolled_servers
        self.gateway_global_deployments = self.gateway_global_deployments - rolled_servers
        self.gateway_session_deployments = frozenset(
            pair for pair in self.gateway_session_deployments if pair[1] not in rolled_servers
        )
        self.gateway_tools = tools_for(
            self.gateway_global_deployments
            | frozenset(pair[1] for pair in self.gateway_session_deployments)
        )
        self.rollback_journal = frozenset()
        self.effect_status = "FAILED"
        self.effect_continuation = "HALT"
        self.program_halted = True
        return reject("ROLLED_BACK")

    def _do_run_always_after_cleanup(self, p: dict[str, Any]) -> dict[str, Any]:
        self.always_after_ran = True
        return ok()

    # -- virtual MCP gateway -----------------------------------------------------

    def _do_register_gateway_server(self, p: dict[str, Any]) -> dict[str, Any]:
        server = str(p["server"])
        self.gateway_catalog = self.gateway_catalog | {server}
        self.gateway_dynamic_servers = self.gateway_dynamic_servers | {server}
        self.gateway_global_deployments = self.gateway_global_deployments - {server}
        self.gateway_tools = tools_for(
            self.gateway_global_deployments | self.session_servers()
        )
        return ok()

    def _do_deploy_gateway_global(self, p: dict[str, Any]) -> dict[str, Any]:
        server = str(p["server"])
        if server not in self.gateway_catalog:
            self.gateway_errors = self.gateway_errors | {server}
            return reject("UNKNOWN_SERVER")
        self.gateway_global_deployments = self.gateway_global_deployments | {server}
        self.gateway_tools = tools_for(self.deployed_servers())
        self.gateway_last_init = self.gateway_last_init | {server}
        self.gateway_errors = self.gateway_errors - {server}
        return ok()

    def _do_deploy_gateway_session(self, p: dict[str, Any]) -> dict[str, Any]:
        session, server = str(p["session"]), str(p["server"])
        if server not in self.gateway_catalog:
            self.gateway_errors = self.gateway_errors | {server}
            return reject("UNKNOWN_SERVER")
        self.gateway_session_deployments = self.gateway_session_deployments | {(session, server)}
        self.gateway_tools = tools_for(self.deployed_servers())
        self.gateway_errors = self.gateway_errors - {server}
        return ok()

    def _do_describe_gateway_tool(self, p: dict[str, Any]) -> dict[str, Any]:
        session, tool = str(p["session"]), str(p["tool"])
        if tool not in self.visible_tools():
            return reject("TOOL_NOT_FOUND")
        self.gateway_disclosures = self.gateway_disclosures | {(session, tool)}
        return ok()

    def _do_invoke_gateway_tool(self, p: dict[str, Any]) -> dict[str, Any]:
        session, tool = str(p["session"]), str(p["tool"])
        if (session, tool) not in self.gateway_disclosures:
            return reject("TOOL_NOT_DISCLOSED")
        if tool not in self.visible_tools():
            return reject("TOOL_NOT_ACTIVE")
        return ok()

    # -- project model ------------------------------------------------------------

    def _do_register_project_manifest(self, p: dict[str, Any]) -> dict[str, Any]:
        project = str(p["project"])
        if project in self.project_model["manifests"]:
            raise AssertionError(f"RegisterProjectManifest not enabled for {project}")
        self._pm_union("manifests", {project})
        self._pm_union("registrations", {project})
        self._pm_union("env_specs", {(project, env) for env in project_env_specs(project)})
        self._pm_union("lib_specs", {(project, lib) for lib in project_lib_specs(project)})
        self._pm_union(
            "profile_declarations",
            {(project, profile) for profile in project_profiles(project)},
        )
        self._pm_union(
            "profile_env_specs",
            {
                (project, profile, env)
                for profile in project_profiles(project)
                for env in project_profile_env_specs(project, profile)
            },
        )
        self._pm_union(
            "profile_lib_specs",
            {
                (project, profile, lib)
                for profile in project_profiles(project)
                for lib in project_profile_lib_specs(project, profile)
            },
        )
        return ok()

    def _do_resolve_project_dependencies(self, p: dict[str, Any]) -> dict[str, Any]:
        project = str(p["project"])
        if project not in self.project_model["manifests"]:
            return reject("PROJECT_NOT_REGISTERED")
        resolved_units = project_resolved_unit_closure(project)
        docs = project_doc_repos(project)
        harnesses = project_harness_templates(project)
        old_cli_lock = self.cli_cli_lock
        self.cli_store_units = self.cli_store_units | resolved_units
        self.cli_doc_repos = self.cli_doc_repos | docs
        self.cli_harness_templates = self.cli_harness_templates | harnesses
        self.cli_installed_records = self.cli_installed_records | resolved_units
        self.cli_lock_units = self.cli_lock_units | resolved_units
        self.cli_bindings = self.cli_bindings | resolved_units | docs
        self.cli_projection_rows = self.cli_projection_rows | resolved_units | docs
        self.cli_managed_copies = self.cli_managed_copies | docs
        self.cli_import_directives = self.cli_import_directives | docs
        self.cli_tool_records = self.cli_tool_records | cli_deps_for(resolved_units)
        self.cli_cli_lock = self.cli_cli_lock | cli_deps_for(resolved_units)
        self.cli_skill_scripts_run = self.cli_skill_scripts_run | scripts_for(resolved_units)
        self.gateway_catalog = self.gateway_catalog | mcp_servers_for(resolved_units)
        self.gateway_dynamic_servers = self.gateway_dynamic_servers | mcp_servers_for(resolved_units)
        self._pm_union("registrations", {project})
        self._pm_union("locks", {project})
        self._pm_union("resolved_units", {(project, u) for u in resolved_units})
        self._pm_union("doc_bindings", {(project, d) for d in docs})
        self._pm_union("harness_bindings", {(project, h) for h in harnesses})
        self._pm_union("agent_configs", {(project, agent) for agent in AGENTS})
        return ok()

    def _do_materialize_project_env(self, p: dict[str, Any]) -> dict[str, Any]:
        project, env = str(p["project"]), str(p["env"])
        if (
            project not in self.project_model["registrations"]
            or (project, env) not in self.project_model["env_specs"]
            or project not in self.project_model["locks"]
        ):
            return reject("PROJECT_ENV_NOT_READY")
        self._pm_union("env_realizations", {(project, env)})
        self._pm_union("env_locks", {(project, env)})
        self._pm_union(
            "skill_vendors", {(project, u) for u in self.project_locked_units(project)}
        )
        self._pm_union("tool_shims", {(project, tool) for tool in TOOLS})
        self._pm_union("env_docs", {(project, env)})
        return ok()

    def _do_resolve_project_libs(self, p: dict[str, Any]) -> dict[str, Any]:
        project = str(p["project"])
        if project not in self.project_model["registrations"]:
            return reject("PROJECT_NOT_REGISTERED")
        self._pm_union("locks", {project})
        self._pm_union("lib_checkouts", {(project, lib) for lib in project_lib_specs(project)})
        self._pm_union("lib_locks", {(project, lib) for lib in project_lib_specs(project)})
        return ok()

    def _do_instantiate_child_home_from_harness(self, p: dict[str, Any]) -> dict[str, Any]:
        home, template = str(p["home"]), str(p["template"])
        needed_units = dependency_closure(harness_units_for(template))
        child_units = needed_units | {template}
        child_servers = mcp_servers_for(needed_units)
        child_tools = child_home_shims_for(needed_units)
        if template not in self.cli_harness_templates or not needed_units <= self.cli_store_units:
            return reject("CHILD_HOME_PARENT_GAP")
        self._pm_union("child_homes", {home})
        self._pm_union("child_home_parents", {(PARENT_HOME_A, home)})
        self._pm_union("child_home_harnesses", {(home, template)})
        self._pm_union("child_home_agent_configs", {(home, agent) for agent in AGENTS})
        self._pm_union("child_home_units", {(home, u) for u in child_units})
        self._pm_union("child_home_mcp_servers", {(home, s) for s in child_servers})
        self._pm_union("child_home_tool_shims", {(home, t) for t in child_tools})
        return ok()

    def _do_scaffold_project_child_home(self, p: dict[str, Any]) -> dict[str, Any]:
        project, home, parent_home = str(p["project"]), str(p["home"]), str(p["parent_home"])
        needed_units = project_child_home_payload(project)
        child_servers = mcp_servers_for(needed_units)
        child_tools = child_home_shims_for(needed_units)
        if (
            project not in self.project_model["registrations"]
            or (project, home) not in PROJECT_CHILD_HOME_EDGES
            or parent_home not in SKILL_MANAGER_HOMES
            or parent_home == home
            or not project_resolved_unit_closure(project) <= self.cli_store_units
            or not project_doc_repos(project) <= self.cli_doc_repos
            or not project_harness_templates(project) <= self.cli_harness_templates
        ):
            return reject("PROJECT_CHILD_HOME_PARENT_GAP")
        self._pm_union("child_homes", {home})
        self._pm_union("project_child_homes", {(project, home)})
        self._pm_union("child_home_parents", {(parent_home, home)})
        self._pm_union("child_home_agent_configs", {(home, agent) for agent in AGENTS})
        self._pm_union("child_home_units", {(home, u) for u in needed_units})
        self._pm_union("child_home_mcp_servers", {(home, s) for s in child_servers})
        self._pm_union("child_home_tool_shims", {(home, t) for t in child_tools})
        return ok()

    def _do_resolve_project_profile(self, p: dict[str, Any]) -> dict[str, Any]:
        project, profile = str(p["project"]), str(p["profile"])
        home, parent_home = str(p["home"]), str(p["parent_home"])
        resolved_units = project_profile_resolved_unit_closure(project, profile)
        docs = project_profile_doc_repos(project, profile)
        harnesses = project_profile_harness_templates(project, profile)
        child_payload = project_profile_child_home_payload(project, profile)
        child_servers = mcp_servers_for(child_payload)
        child_tools = child_home_shims_for(child_payload)
        if (
            project not in self.project_model["registrations"]
            or (project, profile) not in self.project_model["profile_declarations"]
            or (project, profile, home) not in PROJECT_PROFILE_CHILD_HOME_EDGES
            or parent_home not in SKILL_MANAGER_HOMES
            or parent_home == home
        ):
            return reject("PROJECT_PROFILE_NOT_READY")
        self.cli_store_units = self.cli_store_units | resolved_units
        self.cli_doc_repos = self.cli_doc_repos | docs
        self.cli_harness_templates = self.cli_harness_templates | harnesses
        self.cli_installed_records = self.cli_installed_records | resolved_units
        self.cli_lock_units = self.cli_lock_units | resolved_units
        self.cli_bindings = self.cli_bindings | resolved_units | docs
        self.cli_projection_rows = self.cli_projection_rows | resolved_units | docs
        self.cli_managed_copies = self.cli_managed_copies | docs
        self.cli_import_directives = self.cli_import_directives | docs
        self.cli_tool_records = self.cli_tool_records | cli_deps_for(resolved_units)
        self.cli_cli_lock = self.cli_cli_lock | cli_deps_for(resolved_units)
        self.cli_skill_scripts_run = self.cli_skill_scripts_run | scripts_for(resolved_units)
        self.gateway_catalog = self.gateway_catalog | mcp_servers_for(resolved_units)
        self.gateway_dynamic_servers = self.gateway_dynamic_servers | mcp_servers_for(resolved_units)
        self._pm_union("profile_locks", {(project, profile)})
        self._pm_union(
            "profile_resolved_units", {(project, profile, u) for u in resolved_units}
        )
        self._pm_union("profile_child_homes", {(project, profile, home)})
        self._pm_union("child_homes", {home})
        self._pm_union("child_home_parents", {(parent_home, home)})
        self._pm_union("child_home_agent_configs", {(home, agent) for agent in AGENTS})
        self._pm_union("child_home_units", {(home, u) for u in child_payload})
        self._pm_union("child_home_mcp_servers", {(home, s) for s in child_servers})
        self._pm_union("child_home_tool_shims", {(home, t) for t in child_tools})
        return ok()

    def _do_sync_claiming_project_child_homes(self, p: dict[str, Any]) -> dict[str, Any]:
        unit = str(p["unit"])
        claiming_pairs = frozenset(
            pair
            for pair in self.project_model["project_child_homes"]
            if unit in project_child_home_payload(pair[0])
        )
        homes = frozenset(pair[1] for pair in claiming_pairs)
        payload: frozenset[str] = frozenset()
        for pair in claiming_pairs:
            payload |= project_child_home_payload(pair[0])
        child_servers = mcp_servers_for(payload)
        child_tools = child_home_shims_for(payload)
        if unit not in self.cli_store_units:
            return reject("NOT_INSTALLED")
        if not claiming_pairs:
            return ok()
        if not payload <= (
            self.cli_store_units | self.cli_doc_repos | self.cli_harness_templates
        ):
            return reject("PROJECT_CHILD_HOME_PARENT_GAP")
        self._pm_union("child_home_agent_configs", {(h, agent) for h in homes for agent in AGENTS})
        self._pm_union("child_home_units", {(h, u) for h in homes for u in payload})
        self._pm_union("child_home_mcp_servers", {(h, s) for h in homes for s in child_servers})
        self._pm_union("child_home_tool_shims", {(h, t) for h in homes for t in child_tools})
        return ok()


def _snake(name: str) -> str:
    import re

    return re.sub(r"(?<!^)([A-Z])", r"_\1", name).lower()


class StoreModelCaseAdapter:
    """Replays one internal TLC case against the executable store double."""

    def can_run(self, case: Any):
        action = str(case.input.action)
        if hasattr(SkillManagerStoreModel, f"_do_{_snake(action)}"):
            return True
        return (False, f"no store-model transition for action {action}")

    def validate(self, case: Any) -> None:
        action = str(case.input.action)
        if not hasattr(SkillManagerStoreModel, f"_do_{_snake(action)}"):
            raise ValueError(f"no store-model transition for action {action}")

    def run(self, case: Any, work_dir: Path | None = None) -> dict[str, Any]:
        model = SkillManagerStoreModel.from_projected(dict(case.before))
        output = model.apply(str(case.input.action), dict(case.input.params))
        return {"output": output, "after": model.snapshot()}


def empty_visible_state() -> dict[str, Any]:
    """Blank-state projection helper shared with the layout tests."""
    return SkillManagerStoreModel().snapshot()
