----------------------------- MODULE Internal -----------------------------
EXTENDS Core

\* Accepted whole-program state machine for skill-manager, migrated from
\* the single-module SkillManager.tla program model. Domains covered:
\* - CLI store: install / sync / remove / bind / harness lifecycle plus
\*   effect-program semantics (halt, rollback journal, always-after).
\* - Virtual MCP gateway: catalog, global/session deployments, progressive
\*   tool disclosure and invocation.
\* - Registry server: authentication, publish, search.
\* - Project model: registration, dependency resolution, env/lib
\*   materialization, child homes, profiles, claiming-project sync.
\* - CLI progressive disclosure: help, workflow docs, agent context.
\*
\* Every command action X is split into XImpl (pure transition, semantics
\* identical to the pre-split accepted model) plus a wrapper X that also
\* records lastInternalAction for the External harness view.


VARIABLES
  cli_store_units,
  cli_doc_repos,
  cli_harness_templates,
  cli_harness_instances,
  cli_installed_records,
  cli_lock_units,
  cli_agent_projections,
  cli_bindings,
  cli_projection_rows,
  cli_managed_copies,
  cli_import_directives,
  cli_projection_conflicts,
  cli_tool_records,
  cli_cli_lock,
  cli_skill_scripts_run,
  cli_errors,
  cli_gateway_url_configured,
  cli_registry_url_configured,
  cli_gateway_mcp_snapshot,
  effect_status,
  effect_continuation,
  program_halted,
  always_after_ran,
  rollback_journal,
  gateway_catalog,
  gateway_dynamic_servers,
  gateway_global_deployments,
  gateway_session_deployments,
  gateway_tools,
  gateway_disclosures,
  gateway_errors,
  gateway_last_init,
  server_registry_units,
  server_versions,
  server_packages,
  server_authenticated_users,
  project_model,
  result,
  lastInternalAction

vars ==
  << cli_store_units, cli_doc_repos, cli_harness_templates,
     cli_harness_instances, cli_installed_records, cli_lock_units,
     cli_agent_projections, cli_bindings, cli_projection_rows,
     cli_managed_copies, cli_import_directives, cli_projection_conflicts,
     cli_tool_records, cli_cli_lock, cli_skill_scripts_run, cli_errors,
     cli_gateway_url_configured, cli_registry_url_configured,
     cli_gateway_mcp_snapshot, effect_status, effect_continuation,
     program_halted, always_after_ran, rollback_journal, gateway_catalog,
     gateway_dynamic_servers, gateway_global_deployments,
     gateway_session_deployments, gateway_tools, gateway_disclosures,
     gateway_errors, gateway_last_init, server_registry_units,
     server_versions, server_packages, server_authenticated_users,
     project_model, result, lastInternalAction >>

state_vars ==
  << cli_store_units, cli_doc_repos, cli_harness_templates,
     cli_harness_instances, cli_installed_records, cli_lock_units,
     cli_agent_projections, cli_bindings, cli_projection_rows,
     cli_managed_copies, cli_import_directives, cli_projection_conflicts,
     cli_tool_records, cli_cli_lock, cli_skill_scripts_run, cli_errors,
     cli_gateway_url_configured, cli_registry_url_configured,
     cli_gateway_mcp_snapshot, effect_status, effect_continuation,
     program_halted, always_after_ran, rollback_journal, gateway_catalog,
     gateway_dynamic_servers, gateway_global_deployments,
     gateway_session_deployments, gateway_tools, gateway_disclosures,
     gateway_errors, gateway_last_init, server_registry_units,
     server_versions, server_packages, server_authenticated_users >>

ProjectClaimedUnits ==
  {entry[2] : entry \in project_model.resolved_units}
    \cup {entry[2] : entry \in project_model.child_home_units}

ProjectLockedUnits(project) ==
  {entry[2] : entry \in {row \in project_model.resolved_units : row[1] = project}}

SessionServers ==
  {server \in Servers : \E session \in Sessions: <<session, server>> \in gateway_session_deployments}

DeployedServers ==
  gateway_global_deployments \cup SessionServers

VisibleTools ==
  ToolsFor(DeployedServers)

EffectOk ==
  /\ effect_status' = "OK"
  /\ effect_continuation' = "CONTINUE"
  /\ program_halted' = FALSE

EffectHalt(reason) ==
  /\ effect_status' = "HALTED"
  /\ effect_continuation' = "HALT"
  /\ program_halted' = TRUE
  /\ result' = Reject(reason)

Init ==
  /\ cli_store_units = {}
  /\ cli_doc_repos = {}
  /\ cli_harness_templates = {}
  /\ cli_harness_instances = {}
  /\ cli_installed_records = {}
  /\ cli_lock_units = {}
  /\ cli_agent_projections = {}
  /\ cli_bindings = {}
  /\ cli_projection_rows = {}
  /\ cli_managed_copies = {}
  /\ cli_import_directives = {}
  /\ cli_projection_conflicts = {}
  /\ cli_tool_records = {}
  /\ cli_cli_lock = {}
  /\ cli_skill_scripts_run = {}
  /\ cli_errors = {}
  /\ cli_gateway_url_configured = FALSE
  /\ cli_registry_url_configured = FALSE
  /\ cli_gateway_mcp_snapshot = {}
  /\ effect_status = "OK"
  /\ effect_continuation = "CONTINUE"
  /\ program_halted = FALSE
  /\ always_after_ran = FALSE
  /\ rollback_journal = {}
  /\ gateway_catalog = {}
  /\ gateway_dynamic_servers = {}
  /\ gateway_global_deployments = {}
  /\ gateway_session_deployments = {}
  /\ gateway_tools = {}
  /\ gateway_disclosures = {}
  /\ gateway_errors = {}
  /\ gateway_last_init = {}
  /\ server_registry_units = {}
  /\ server_versions = {}
  /\ server_packages = {}
  /\ server_authenticated_users = {}
  /\ project_model = ProjectModelInit
  /\ result = Ok
  /\ lastInternalAction = [name |-> "Init", params |-> NoParams]

MarkInternal(name, params) ==
  lastInternalAction' = [name |-> name, params |-> params]

ServerAuthenticateImpl(user) ==
  /\ server_authenticated_users' = server_authenticated_users \cup {user}
  /\ result' = Ok
  /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                  cli_harness_instances, cli_installed_records, cli_lock_units,
                  cli_agent_projections, cli_bindings, cli_projection_rows,
                  cli_managed_copies, cli_import_directives,
                  cli_projection_conflicts, cli_tool_records, cli_cli_lock,
                  cli_skill_scripts_run, cli_errors,
                  cli_gateway_url_configured, cli_registry_url_configured,
                  cli_gateway_mcp_snapshot, effect_status,
                  effect_continuation, program_halted, always_after_ran,
                  rollback_journal, gateway_catalog, gateway_dynamic_servers,
                  gateway_global_deployments, gateway_session_deployments,
                  gateway_tools, gateway_disclosures, gateway_errors,
                  gateway_last_init, server_registry_units, server_versions,
                  server_packages >>

\* @command ServerAuthenticate
\* @result ServerResult
\* @port SkillManagerServer.authenticate
ServerAuthenticate(user) ==
  /\ ServerAuthenticateImpl(user)
  /\ project_model' = project_model
  /\ MarkInternal("ServerAuthenticate", [user |-> user])

ServerPublishTarballImpl(user, unit, version) ==
  IF user \notin server_authenticated_users
  THEN
    /\ result' = Reject("AUTHENTICATION_REQUIRED")
    /\ UNCHANGED state_vars
  ELSE
    /\ server_registry_units' = server_registry_units \cup {unit}
    /\ server_versions' = server_versions \cup {<<unit, version>>}
    /\ server_packages' = server_packages \cup {<<unit, version>>}
    /\ result' = Ok
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_authenticated_users >>

\* @command ServerPublishTarball
\* @result ServerResult
\* @port SkillManagerServer.publish_tarball
ServerPublishTarball(user, unit, version) ==
  /\ ServerPublishTarballImpl(user, unit, version)
  /\ project_model' = project_model
  /\ MarkInternal("ServerPublishTarball", [user |-> user, unit |-> unit, version |-> version])

ServerSearchImpl ==
  /\ result' = Ok
  /\ UNCHANGED state_vars

\* @command ServerSearch
\* @result ServerResult
\* @port SkillManagerServer.search
ServerSearch ==
  /\ ServerSearchImpl
  /\ project_model' = project_model
  /\ MarkInternal("ServerSearch", NoParams)

ConfigureRegistryImpl ==
  /\ cli_registry_url_configured' = TRUE
  /\ result' = Ok
  /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                  cli_harness_instances, cli_installed_records, cli_lock_units,
                  cli_agent_projections, cli_bindings, cli_projection_rows,
                  cli_managed_copies, cli_import_directives,
                  cli_projection_conflicts, cli_tool_records, cli_cli_lock,
                  cli_skill_scripts_run, cli_errors,
                  cli_gateway_url_configured, cli_gateway_mcp_snapshot,
                  effect_status, effect_continuation, program_halted,
                  always_after_ran, rollback_journal, gateway_catalog,
                  gateway_dynamic_servers, gateway_global_deployments,
                  gateway_session_deployments, gateway_tools,
                  gateway_disclosures, gateway_errors, gateway_last_init,
                  server_registry_units, server_versions, server_packages,
                  server_authenticated_users >>

\* @command ConfigureRegistry
\* @result ConfigureResult
\* @port SkillManagerCli.configure_registry
ConfigureRegistry ==
  /\ ConfigureRegistryImpl
  /\ project_model' = project_model
  /\ MarkInternal("ConfigureRegistry", NoParams)

EnsureGatewayImpl ==
  /\ cli_gateway_url_configured' = TRUE
  /\ result' = Ok
  /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                  cli_harness_instances, cli_installed_records, cli_lock_units,
                  cli_agent_projections, cli_bindings, cli_projection_rows,
                  cli_managed_copies, cli_import_directives,
                  cli_projection_conflicts, cli_tool_records, cli_cli_lock,
                  cli_skill_scripts_run, cli_errors,
                  cli_registry_url_configured, cli_gateway_mcp_snapshot,
                  effect_status, effect_continuation, program_halted,
                  always_after_ran, rollback_journal, gateway_catalog,
                  gateway_dynamic_servers, gateway_global_deployments,
                  gateway_session_deployments, gateway_tools,
                  gateway_disclosures, gateway_errors, gateway_last_init,
                  server_registry_units, server_versions, server_packages,
                  server_authenticated_users >>

\* @command EnsureGateway
\* @result GatewayResult
\* @port SkillManagerCli.ensure_gateway
EnsureGateway ==
  /\ EnsureGatewayImpl
  /\ project_model' = project_model
  /\ MarkInternal("EnsureGateway", NoParams)

InstallUnitImpl(u) ==
  LET install_set == DependencyClosure({u}) IN
  IF u \in cli_store_units
  THEN
    /\ EffectHalt("ALREADY_INSTALLED")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>
  ELSE IF ~(install_set \subseteq server_registry_units)
  THEN
    /\ EffectHalt("RESOLVE_FAILED")
    /\ cli_errors' = cli_errors \cup {u}
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, always_after_ran,
                    rollback_journal, gateway_catalog, gateway_dynamic_servers,
                    gateway_global_deployments, gateway_session_deployments,
                    gateway_tools, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>
  ELSE
    /\ cli_store_units' = cli_store_units \cup install_set
    /\ cli_installed_records' = cli_installed_records \cup install_set
    /\ cli_lock_units' = cli_lock_units \cup install_set
    /\ cli_agent_projections' = cli_agent_projections \cup UnitProjections(install_set)
    /\ cli_bindings' = cli_bindings \cup install_set
    /\ cli_tool_records' = cli_tool_records \cup CliDepsFor(install_set)
    /\ cli_cli_lock' = cli_cli_lock \cup CliDepsFor(install_set)
    /\ cli_skill_scripts_run' =
        cli_skill_scripts_run \cup (ScriptsFor(install_set) \ cli_cli_lock)
    /\ gateway_catalog' = gateway_catalog \cup McpServersFor(install_set)
    /\ gateway_dynamic_servers' = gateway_dynamic_servers \cup McpServersFor(install_set)
    /\ rollback_journal' = {}
    /\ EffectOk
    /\ result' = Ok
	    /\ UNCHANGED << cli_doc_repos, cli_harness_templates,
	                    cli_harness_instances, cli_projection_rows,
	                    cli_managed_copies, cli_import_directives,
	                    cli_projection_conflicts, cli_errors,
	                    cli_gateway_url_configured, cli_registry_url_configured,
	                    cli_gateway_mcp_snapshot, always_after_ran,
	                    gateway_global_deployments, gateway_session_deployments,
	                    gateway_tools, gateway_disclosures, gateway_errors,
	                    gateway_last_init, server_registry_units, server_versions,
	                    server_packages, server_authenticated_users >>

\* @command InstallUnit
\* @result InstallResult
\* @port SkillManagerCli.install_unit
InstallUnit(u) ==
  /\ InstallUnitImpl(u)
  /\ project_model' = project_model
  /\ MarkInternal("InstallUnit", [unit |-> u])

InstallUnitForceScriptsImpl(u) ==
  LET install_set == DependencyClosure({u}) IN
  IF u \in cli_store_units
  THEN
    /\ EffectHalt("ALREADY_INSTALLED")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>
  ELSE IF ~(install_set \subseteq server_registry_units)
  THEN
    /\ EffectHalt("RESOLVE_FAILED")
    /\ cli_errors' = cli_errors \cup {u}
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, always_after_ran,
                    rollback_journal, gateway_catalog, gateway_dynamic_servers,
                    gateway_global_deployments, gateway_session_deployments,
                    gateway_tools, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>
  ELSE
    /\ cli_store_units' = cli_store_units \cup install_set
    /\ cli_installed_records' = cli_installed_records \cup install_set
    /\ cli_lock_units' = cli_lock_units \cup install_set
    /\ cli_agent_projections' = cli_agent_projections \cup UnitProjections(install_set)
    /\ cli_bindings' = cli_bindings \cup install_set
    /\ cli_tool_records' = cli_tool_records \cup CliDepsFor(install_set)
    /\ cli_cli_lock' = cli_cli_lock \cup CliDepsFor(install_set)
    /\ cli_skill_scripts_run' = cli_skill_scripts_run \cup ScriptsFor(install_set)
    /\ gateway_catalog' = gateway_catalog \cup McpServersFor(install_set)
    /\ gateway_dynamic_servers' = gateway_dynamic_servers \cup McpServersFor(install_set)
    /\ rollback_journal' = {}
    /\ EffectOk
    /\ result' = OkForceScripts
    /\ UNCHANGED << cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_projection_rows,
                    cli_managed_copies, cli_import_directives,
                    cli_projection_conflicts, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, always_after_ran,
                    gateway_global_deployments, gateway_session_deployments,
                    gateway_tools, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>

\* @command InstallUnitForceScripts
\* @result InstallResult
\* @port SkillManagerCli.install_unit_force_scripts
InstallUnitForceScripts(u) ==
  /\ InstallUnitForceScriptsImpl(u)
  /\ project_model' = project_model
  /\ MarkInternal("InstallUnitForceScripts", [unit |-> u])

SyncUnitImpl(u) ==
  LET surfaced == DependencyClosure({u}) IN
  IF u \notin cli_store_units
  THEN
    /\ EffectHalt("NOT_INSTALLED")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>
  ELSE IF ~(surfaced \subseteq server_registry_units)
  THEN
    /\ effect_status' = "PARTIAL"
    /\ effect_continuation' = "CONTINUE"
    /\ program_halted' = FALSE
    /\ cli_errors' = cli_errors \cup {u}
    /\ result' = Reject("TRANSITIVE_RESOLVE_FAILED")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, always_after_ran,
                    rollback_journal, gateway_catalog, gateway_dynamic_servers,
                    gateway_global_deployments, gateway_session_deployments,
                    gateway_tools, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>
  ELSE
    /\ cli_store_units' = cli_store_units \cup surfaced
    /\ cli_installed_records' = cli_installed_records \cup surfaced
    /\ cli_lock_units' = cli_store_units'
    /\ cli_agent_projections' = cli_agent_projections \cup UnitProjections(surfaced)
    /\ cli_bindings' = cli_bindings \cup surfaced
    /\ cli_tool_records' = cli_tool_records \cup CliDepsFor(surfaced)
    /\ cli_cli_lock' = cli_cli_lock \cup CliDepsFor(surfaced)
    /\ cli_skill_scripts_run' =
        cli_skill_scripts_run \cup (ScriptsFor(surfaced) \ cli_cli_lock)
    /\ gateway_catalog' = gateway_catalog \cup McpServersFor(surfaced)
    /\ gateway_dynamic_servers' = gateway_dynamic_servers \cup McpServersFor(surfaced)
    /\ EffectOk
    /\ result' = Ok
    /\ UNCHANGED << cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_projection_rows,
                    cli_managed_copies, cli_import_directives,
                    cli_projection_conflicts, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, always_after_ran,
                    rollback_journal, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>

\* @command SyncUnit
\* @result SyncResult
\* @port SkillManagerCli.sync_unit
SyncUnit(u) ==
  /\ SyncUnitImpl(u)
  /\ project_model' = project_model
  /\ MarkInternal("SyncUnit", [unit |-> u])

SyncUnitForceScriptsImpl(u) ==
  LET surfaced == DependencyClosure({u}) IN
  IF u \notin cli_store_units
  THEN
    /\ EffectHalt("NOT_INSTALLED")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>
  ELSE IF ~(surfaced \subseteq server_registry_units)
  THEN
    /\ effect_status' = "PARTIAL"
    /\ effect_continuation' = "CONTINUE"
    /\ program_halted' = FALSE
    /\ cli_errors' = cli_errors \cup {u}
    /\ result' = Reject("TRANSITIVE_RESOLVE_FAILED")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, always_after_ran,
                    rollback_journal, gateway_catalog, gateway_dynamic_servers,
                    gateway_global_deployments, gateway_session_deployments,
                    gateway_tools, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>
  ELSE
    /\ cli_store_units' = cli_store_units \cup surfaced
    /\ cli_installed_records' = cli_installed_records \cup surfaced
    /\ cli_lock_units' = cli_store_units'
    /\ cli_agent_projections' = cli_agent_projections \cup UnitProjections(surfaced)
    /\ cli_bindings' = cli_bindings \cup surfaced
    /\ cli_tool_records' = cli_tool_records \cup CliDepsFor(surfaced)
    /\ cli_cli_lock' = cli_cli_lock \cup CliDepsFor(surfaced)
    /\ cli_skill_scripts_run' = cli_skill_scripts_run \cup ScriptsFor(surfaced)
    /\ gateway_catalog' = gateway_catalog \cup McpServersFor(surfaced)
    /\ gateway_dynamic_servers' = gateway_dynamic_servers \cup McpServersFor(surfaced)
    /\ EffectOk
    /\ result' = OkForceScripts
    /\ UNCHANGED << cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_projection_rows,
                    cli_managed_copies, cli_import_directives,
                    cli_projection_conflicts, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, always_after_ran,
                    rollback_journal, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>

\* @command SyncUnitForceScripts
\* @result SyncResult
\* @port SkillManagerCli.sync_unit_force_scripts
SyncUnitForceScripts(u) ==
  /\ SyncUnitForceScriptsImpl(u)
  /\ project_model' = project_model
  /\ MarkInternal("SyncUnitForceScripts", [unit |-> u])

RemoveUnitImpl(u) ==
  IF u \notin cli_store_units
  THEN
    /\ result' = Reject("NOT_INSTALLED")
    /\ UNCHANGED state_vars
  ELSE IF \E dependent \in cli_store_units \ {u}: <<dependent, u>> \in ReferenceEdges
  THEN
    /\ result' = Reject("DEPENDENT_INSTALLED")
    /\ UNCHANGED state_vars
  ELSE IF u \in ProjectClaimedUnits
  THEN
    /\ result' = Reject("PROJECT_CLAIMED")
    /\ UNCHANGED state_vars
  ELSE
    /\ LET remaining == cli_store_units \ {u}
           orphan_servers == McpServersFor({u}) \ McpServersFor(remaining)
           orphan_cli_deps == CliDepsFor({u}) \ CliDepsFor(remaining)
           orphan_scripts == ScriptsFor({u}) \ ScriptsFor(remaining)
       IN
       /\ cli_store_units' = remaining
       /\ cli_installed_records' = cli_installed_records \ {u}
       /\ cli_lock_units' = cli_lock_units \ {u}
       /\ cli_agent_projections' =
           {projection \in cli_agent_projections : projection[2] # u}
       /\ cli_bindings' = cli_bindings \ {u}
       /\ cli_projection_rows' = cli_projection_rows \ {u}
       /\ cli_tool_records' = cli_tool_records \ orphan_cli_deps
       /\ cli_cli_lock' = cli_cli_lock \ orphan_cli_deps
       /\ cli_skill_scripts_run' = cli_skill_scripts_run \ orphan_scripts
       /\ gateway_catalog' = gateway_catalog \ orphan_servers
       /\ gateway_dynamic_servers' = gateway_dynamic_servers \ orphan_servers
       /\ gateway_global_deployments' = gateway_global_deployments \ orphan_servers
       /\ gateway_session_deployments' =
           {pair \in gateway_session_deployments : pair[2] \notin orphan_servers}
       /\ gateway_tools' = ToolsFor(gateway_global_deployments' \cup
           {server \in Servers : \E session \in Sessions: <<session, server>> \in gateway_session_deployments'})
    /\ result' = Ok
    /\ UNCHANGED << cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_managed_copies, cli_import_directives,
                    cli_projection_conflicts, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, effect_status,
                    effect_continuation, program_halted, always_after_ran,
                    rollback_journal, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>

\* @command RemoveUnit
\* @result RemoveResult
\* @port SkillManagerCli.remove_unit
RemoveUnit(u) ==
  /\ RemoveUnitImpl(u)
  /\ project_model' = project_model
  /\ MarkInternal("RemoveUnit", [unit |-> u])

BindDocRepoImpl(doc) ==
  /\ cli_doc_repos' = cli_doc_repos \cup {doc}
  /\ cli_bindings' = cli_bindings \cup {doc}
  /\ cli_projection_rows' = cli_projection_rows \cup {doc}
  /\ cli_managed_copies' = cli_managed_copies \cup {doc}
  /\ cli_import_directives' = cli_import_directives \cup {doc}
  /\ result' = Ok
  /\ UNCHANGED << cli_store_units, cli_harness_templates,
                  cli_harness_instances, cli_installed_records, cli_lock_units,
                  cli_agent_projections, cli_projection_conflicts,
                  cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                  cli_errors, cli_gateway_url_configured,
                  cli_registry_url_configured, cli_gateway_mcp_snapshot,
                  effect_status, effect_continuation, program_halted,
                  always_after_ran, rollback_journal, gateway_catalog,
                  gateway_dynamic_servers, gateway_global_deployments,
                  gateway_session_deployments, gateway_tools,
                  gateway_disclosures, gateway_errors, gateway_last_init,
                  server_registry_units, server_versions, server_packages,
                  server_authenticated_users >>

\* @command BindDocRepo
\* @result BindingResult
\* @port SkillManagerCli.bind_doc_repo
BindDocRepo(doc) ==
  /\ BindDocRepoImpl(doc)
  /\ project_model' = project_model
  /\ MarkInternal("BindDocRepo", [doc |-> doc])

SyncDocRepoImpl(doc) ==
  IF doc \notin cli_doc_repos
  THEN
    /\ result' = Reject("DOC_REPO_NOT_INSTALLED")
    /\ UNCHANGED state_vars
  ELSE
    /\ cli_managed_copies' = cli_managed_copies \cup {doc}
    /\ cli_import_directives' = cli_import_directives \cup {doc}
    /\ cli_projection_conflicts' = cli_projection_conflicts \ {doc}
    /\ result' = Ok
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_tool_records, cli_cli_lock,
                    cli_skill_scripts_run, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, effect_status,
                    effect_continuation, program_halted, always_after_ran,
                    rollback_journal, gateway_catalog, gateway_dynamic_servers,
                    gateway_global_deployments, gateway_session_deployments,
                    gateway_tools, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>

\* @command SyncDocRepo
\* @result BindingResult
\* @port SkillManagerCli.sync_doc_repo
SyncDocRepo(doc) ==
  /\ SyncDocRepoImpl(doc)
  /\ project_model' = project_model
  /\ MarkInternal("SyncDocRepo", [doc |-> doc])

SyncHarnessImpl(template, instance) ==
  LET needed == HarnessUnitsFor(template) IN
  IF ~(needed \subseteq cli_store_units)
  THEN
    /\ result' = Reject("HARNESS_REFERENCE_GAP")
    /\ UNCHANGED state_vars
  ELSE
    /\ cli_harness_templates' = cli_harness_templates \cup {template}
    /\ cli_harness_instances' = cli_harness_instances \cup {instance}
    /\ cli_bindings' = cli_bindings \cup needed
    /\ cli_projection_rows' = cli_projection_rows \cup needed
    /\ result' = Ok
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>

\* @command SyncHarness
\* @result HarnessResult
\* @port SkillManagerCli.sync_harness
SyncHarness(template, instance) ==
  /\ SyncHarnessImpl(template, instance)
  /\ project_model' = project_model
  /\ MarkInternal("SyncHarness", [template |-> template, instance |-> instance])

RunEffectProgramFailureImpl ==
  /\ LET rolled_back_units == rollback_journal
         rolled_back_servers == McpServersFor(rolled_back_units)
         rolled_back_cli_deps == CliDepsFor(rolled_back_units)
         rolled_back_scripts == ScriptsFor(rolled_back_units)
     IN
     /\ cli_store_units' = cli_store_units \ rolled_back_units
     /\ cli_installed_records' = cli_installed_records \ rolled_back_units
     /\ cli_lock_units' = cli_lock_units \ rolled_back_units
     /\ cli_agent_projections' =
         {projection \in cli_agent_projections : projection[2] \notin rolled_back_units}
     /\ cli_bindings' = cli_bindings \ rolled_back_units
     /\ cli_projection_rows' = cli_projection_rows \ rolled_back_units
     /\ cli_tool_records' = cli_tool_records \ rolled_back_cli_deps
     /\ cli_cli_lock' = cli_cli_lock \ rolled_back_cli_deps
     /\ cli_skill_scripts_run' = cli_skill_scripts_run \ rolled_back_scripts
     /\ gateway_catalog' = gateway_catalog \ rolled_back_servers
     /\ gateway_dynamic_servers' = gateway_dynamic_servers \ rolled_back_servers
     /\ gateway_global_deployments' = gateway_global_deployments \ rolled_back_servers
     /\ gateway_session_deployments' =
         {pair \in gateway_session_deployments : pair[2] \notin rolled_back_servers}
     /\ gateway_tools' = ToolsFor(gateway_global_deployments' \cup
         {server \in Servers : \E session \in Sessions: <<session, server>> \in gateway_session_deployments'})
     /\ rollback_journal' = {}
  /\ effect_status' = "FAILED"
  /\ effect_continuation' = "HALT"
  /\ program_halted' = TRUE
  /\ result' = Reject("ROLLED_BACK")
  /\ UNCHANGED << cli_doc_repos, cli_harness_templates,
                  cli_harness_instances, cli_managed_copies, cli_import_directives,
                  cli_projection_conflicts, cli_errors,
                  cli_gateway_url_configured, cli_registry_url_configured,
                  cli_gateway_mcp_snapshot, always_after_ran,
                  gateway_disclosures, gateway_errors, gateway_last_init,
                  server_registry_units, server_versions, server_packages,
                  server_authenticated_users >>

\* @command RunEffectProgramFailure
\* @result ProgramResult
\* @port SkillManagerCli.run_effect_program_failure
RunEffectProgramFailure ==
  /\ RunEffectProgramFailureImpl
  /\ project_model' = project_model
  /\ MarkInternal("RunEffectProgramFailure", NoParams)

RunAlwaysAfterCleanupImpl ==
  /\ always_after_ran' = TRUE
  /\ result' = Ok
  /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                  cli_harness_instances, cli_installed_records, cli_lock_units,
                  cli_agent_projections, cli_bindings, cli_projection_rows,
                  cli_managed_copies, cli_import_directives,
                  cli_projection_conflicts, cli_tool_records, cli_cli_lock,
                  cli_skill_scripts_run, cli_errors,
                  cli_gateway_url_configured, cli_registry_url_configured,
                  cli_gateway_mcp_snapshot, effect_status,
                  effect_continuation, program_halted, rollback_journal,
                  gateway_catalog, gateway_dynamic_servers,
                  gateway_global_deployments, gateway_session_deployments,
                  gateway_tools, gateway_disclosures, gateway_errors,
                  gateway_last_init, server_registry_units, server_versions,
                  server_packages, server_authenticated_users >>

\* @command RunAlwaysAfterCleanup
\* @result ProgramResult
\* @port SkillManagerCli.run_always_after_cleanup
RunAlwaysAfterCleanup ==
  /\ RunAlwaysAfterCleanupImpl
  /\ project_model' = project_model
  /\ MarkInternal("RunAlwaysAfterCleanup", NoParams)

RegisterGatewayServerImpl(server) ==
  /\ gateway_catalog' = gateway_catalog \cup {server}
  /\ gateway_dynamic_servers' = gateway_dynamic_servers \cup {server}
  /\ gateway_global_deployments' = gateway_global_deployments \ {server}
  /\ gateway_tools' =
      ToolsFor((gateway_global_deployments \ {server}) \cup SessionServers)
  /\ result' = Ok
  /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                  cli_harness_instances, cli_installed_records, cli_lock_units,
                  cli_agent_projections, cli_bindings, cli_projection_rows,
                  cli_managed_copies, cli_import_directives,
                  cli_projection_conflicts, cli_tool_records, cli_cli_lock,
                  cli_skill_scripts_run, cli_errors,
                  cli_gateway_url_configured, cli_registry_url_configured,
                  cli_gateway_mcp_snapshot, effect_status,
                  effect_continuation, program_halted, always_after_ran,
                  rollback_journal, gateway_session_deployments,
                  gateway_disclosures, gateway_errors, gateway_last_init,
                  server_registry_units, server_versions, server_packages,
                  server_authenticated_users >>

\* @command RegisterGatewayServer
\* @result GatewayResult
\* @port VirtualMcpGateway.register_server
RegisterGatewayServer(server) ==
  /\ RegisterGatewayServerImpl(server)
  /\ project_model' = project_model
  /\ MarkInternal("RegisterGatewayServer", [server |-> server])

DeployGatewayGlobalImpl(server) ==
  IF server \notin gateway_catalog
  THEN
    /\ gateway_errors' = gateway_errors \cup {server}
    /\ result' = Reject("UNKNOWN_SERVER")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>
  ELSE
    /\ gateway_global_deployments' = gateway_global_deployments \cup {server}
    /\ gateway_tools' = ToolsFor(DeployedServers \cup {server})
    /\ gateway_last_init' = gateway_last_init \cup {server}
    /\ gateway_errors' = gateway_errors \ {server}
    /\ result' = Ok
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_session_deployments,
                    gateway_disclosures, server_registry_units,
                    server_versions, server_packages,
                    server_authenticated_users >>

\* @command DeployGatewayGlobal
\* @result GatewayResult
\* @port VirtualMcpGateway.deploy_global
DeployGatewayGlobal(server) ==
  /\ DeployGatewayGlobalImpl(server)
  /\ project_model' = project_model
  /\ MarkInternal("DeployGatewayGlobal", [server |-> server])

DeployGatewaySessionImpl(session, server) ==
  IF server \notin gateway_catalog
  THEN
    /\ gateway_errors' = gateway_errors \cup {server}
    /\ result' = Reject("UNKNOWN_SERVER")
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>
  ELSE
    /\ gateway_session_deployments' = gateway_session_deployments \cup {<<session, server>>}
    /\ gateway_tools' = ToolsFor(DeployedServers \cup {server})
    /\ gateway_errors' = gateway_errors \ {server}
    /\ result' = Ok
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_disclosures, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>

\* @command DeployGatewaySession
\* @result GatewayResult
\* @port VirtualMcpGateway.deploy_session
DeployGatewaySession(session, server) ==
  /\ DeployGatewaySessionImpl(session, server)
  /\ project_model' = project_model
  /\ MarkInternal("DeployGatewaySession", [session |-> session, server |-> server])

DescribeGatewayToolImpl(session, tool) ==
  IF tool \notin VisibleTools
  THEN
    /\ result' = Reject("TOOL_NOT_FOUND")
    /\ UNCHANGED state_vars
  ELSE
    /\ gateway_disclosures' = gateway_disclosures \cup {<<session, tool>>}
    /\ result' = Ok
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_errors, gateway_last_init, server_registry_units,
                    server_versions, server_packages,
                    server_authenticated_users >>

\* @command DescribeGatewayTool
\* @result GatewayResult
\* @port VirtualMcpGateway.describe_tool
DescribeGatewayTool(session, tool) ==
  /\ DescribeGatewayToolImpl(session, tool)
  /\ project_model' = project_model
  /\ MarkInternal("DescribeGatewayTool", [session |-> session, tool |-> tool])

InvokeGatewayToolImpl(session, tool) ==
  IF <<session, tool>> \notin gateway_disclosures
  THEN
    /\ result' = Reject("TOOL_NOT_DISCLOSED")
    /\ UNCHANGED state_vars
  ELSE IF tool \notin VisibleTools
  THEN
    /\ result' = Reject("TOOL_NOT_ACTIVE")
    /\ UNCHANGED state_vars
  ELSE
    /\ result' = Ok
    /\ UNCHANGED << cli_store_units, cli_doc_repos, cli_harness_templates,
                    cli_harness_instances, cli_installed_records,
                    cli_lock_units, cli_agent_projections, cli_bindings,
                    cli_projection_rows, cli_managed_copies,
                    cli_import_directives, cli_projection_conflicts,
                    cli_tool_records, cli_cli_lock, cli_skill_scripts_run,
                    cli_errors, cli_gateway_url_configured,
                    cli_registry_url_configured, cli_gateway_mcp_snapshot,
                    effect_status, effect_continuation, program_halted,
                    always_after_ran, rollback_journal, gateway_catalog,
                    gateway_dynamic_servers, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>

\* @command InvokeGatewayTool
\* @result GatewayResult
\* @port VirtualMcpGateway.invoke_tool
InvokeGatewayTool(session, tool) ==
  /\ InvokeGatewayToolImpl(session, tool)
  /\ project_model' = project_model
  /\ MarkInternal("InvokeGatewayTool", [session |-> session, tool |-> tool])

RegisterProjectManifestImpl(project) ==
  /\ project \notin project_model.manifests
  /\ project_model' =
      [project_model EXCEPT
        !.manifests = @ \cup {project},
        !.registrations = @ \cup {project},
        !.env_specs = @ \cup ({project} \X ProjectEnvSpecs(project)),
        !.lib_specs = @ \cup ({project} \X ProjectLibSpecs(project)),
        !.profile_declarations = @ \cup ({project} \X ProjectProfiles(project)),
        !.profile_env_specs =
            @ \cup UNION {{<<project, profile, env>> :
                    env \in ProjectProfileEnvSpecs(project, profile)}
                : profile \in ProjectProfiles(project)},
        !.profile_lib_specs =
            @ \cup UNION {{<<project, profile, lib>> :
                    lib \in ProjectProfileLibSpecs(project, profile)}
                : profile \in ProjectProfiles(project)}]
  /\ result' = Ok
  /\ UNCHANGED state_vars

\* @command RegisterProjectManifest
\* @result ProjectResult
\* @port SkillManagerCli.register_project_manifest
RegisterProjectManifest(project) ==
  /\ RegisterProjectManifestImpl(project)
  /\ MarkInternal("RegisterProjectManifest", [project |-> project])

ResolveProjectDependenciesImpl(project) ==
  LET resolved_units == ProjectResolvedUnitClosure(project)
      docs == ProjectDocRepos(project)
      harnesses == ProjectHarnessTemplates(project)
  IN
  IF project \notin project_model.manifests
  THEN
    /\ result' = Reject("PROJECT_NOT_REGISTERED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ cli_store_units' = cli_store_units \cup resolved_units
    /\ cli_doc_repos' = cli_doc_repos \cup docs
    /\ cli_harness_templates' = cli_harness_templates \cup harnesses
    /\ cli_installed_records' = cli_installed_records \cup resolved_units
    /\ cli_lock_units' = cli_lock_units \cup resolved_units
    /\ cli_bindings' = cli_bindings \cup resolved_units \cup docs
    /\ cli_projection_rows' = cli_projection_rows \cup resolved_units \cup docs
    /\ cli_managed_copies' = cli_managed_copies \cup docs
    /\ cli_import_directives' = cli_import_directives \cup docs
    /\ cli_tool_records' = cli_tool_records \cup CliDepsFor(resolved_units)
    /\ cli_cli_lock' = cli_cli_lock \cup CliDepsFor(resolved_units)
    /\ cli_skill_scripts_run' = cli_skill_scripts_run \cup ScriptsFor(resolved_units)
    /\ gateway_catalog' = gateway_catalog \cup McpServersFor(resolved_units)
    /\ gateway_dynamic_servers' = gateway_dynamic_servers \cup McpServersFor(resolved_units)
    /\ project_model' =
        [project_model EXCEPT
          !.registrations = @ \cup {project},
          !.locks = @ \cup {project},
          !.resolved_units = @ \cup ({project} \X resolved_units),
          !.doc_bindings = @ \cup ({project} \X docs),
          !.harness_bindings = @ \cup ({project} \X harnesses),
          !.agent_configs = @ \cup ({project} \X Agents)]
    /\ result' = Ok
    /\ UNCHANGED << cli_harness_instances, cli_agent_projections,
                    cli_projection_conflicts, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, effect_status,
                    effect_continuation, program_halted, always_after_ran,
                    rollback_journal, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>

\* @command ResolveProjectDependencies
\* @result ProjectResult
\* @port SkillManagerCli.resolve_project_dependencies
ResolveProjectDependencies(project) ==
  /\ ResolveProjectDependenciesImpl(project)
  /\ MarkInternal("ResolveProjectDependencies", [project |-> project])

MaterializeProjectEnvImpl(project, env) ==
  IF project \notin project_model.registrations
     \/ <<project, env>> \notin project_model.env_specs
     \/ project \notin project_model.locks
  THEN
    /\ result' = Reject("PROJECT_ENV_NOT_READY")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.env_realizations = @ \cup {<<project, env>>},
          !.env_locks = @ \cup {<<project, env>>},
          !.skill_vendors = @ \cup ({project} \X ProjectLockedUnits(project)),
          !.tool_shims = @ \cup ({project} \X Tools),
          !.env_docs = @ \cup {<<project, env>>}]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command MaterializeProjectEnv
\* @result ProjectResult
\* @port SkillManagerCli.materialize_project_env
MaterializeProjectEnv(project, env) ==
  /\ MaterializeProjectEnvImpl(project, env)
  /\ MarkInternal("MaterializeProjectEnv", [project |-> project, env |-> env])

ResolveProjectLibsImpl(project) ==
  IF project \notin project_model.registrations
  THEN
    /\ result' = Reject("PROJECT_NOT_REGISTERED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.locks = @ \cup {project},
          !.lib_checkouts = @ \cup ({project} \X ProjectLibSpecs(project)),
          !.lib_locks = @ \cup ({project} \X ProjectLibSpecs(project))]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command ResolveProjectLibs
\* @result ProjectResult
\* @port SkillManagerCli.resolve_project_libs
ResolveProjectLibs(project) ==
  /\ ResolveProjectLibsImpl(project)
  /\ MarkInternal("ResolveProjectLibs", [project |-> project])

InstantiateChildHomeFromHarnessImpl(home, template) ==
  LET needed_units == DependencyClosure(HarnessUnitsFor(template))
      child_units == needed_units \cup {template}
      child_servers == McpServersFor(needed_units)
      child_tools == ChildHomeShimsFor(needed_units)
  IN
  IF template \notin cli_harness_templates
     \/ ~(needed_units \subseteq cli_store_units)
  THEN
    /\ result' = Reject("CHILD_HOME_PARENT_GAP")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.child_homes = @ \cup {home},
          !.child_home_parents = @ \cup {<<ParentHomeA, home>>},
          !.child_home_harnesses = @ \cup {<<home, template>>},
          !.child_home_agent_configs = @ \cup ({home} \X Agents),
          !.child_home_units = @ \cup ({home} \X child_units),
          !.child_home_mcp_servers = @ \cup ({home} \X child_servers),
          !.child_home_tool_shims = @ \cup ({home} \X child_tools)]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command InstantiateChildHomeFromHarness
\* @result ProjectResult
\* @port SkillManagerCli.instantiate_child_home_from_harness
InstantiateChildHomeFromHarness(home, template) ==
  /\ InstantiateChildHomeFromHarnessImpl(home, template)
  /\ MarkInternal("InstantiateChildHomeFromHarness", [home |-> home, template |-> template])

ScaffoldProjectChildHomeImpl(project, home, parent_home) ==
  LET needed_units == ProjectChildHomePayload(project)
      child_servers == McpServersFor(needed_units)
      child_tools == ChildHomeShimsFor(needed_units)
  IN
  IF project \notin project_model.registrations
     \/ <<project, home>> \notin ProjectChildHomeEdges
     \/ parent_home \notin SkillManagerHomes
     \/ parent_home = home
     \/ ~(ProjectResolvedUnitClosure(project) \subseteq cli_store_units)
     \/ ~(ProjectDocRepos(project) \subseteq cli_doc_repos)
     \/ ~(ProjectHarnessTemplates(project) \subseteq cli_harness_templates)
  THEN
    /\ result' = Reject("PROJECT_CHILD_HOME_PARENT_GAP")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.child_homes = @ \cup {home},
          !.project_child_homes = @ \cup {<<project, home>>},
          !.child_home_parents = @ \cup {<<parent_home, home>>},
          !.child_home_agent_configs = @ \cup ({home} \X Agents),
          !.child_home_units = @ \cup ({home} \X needed_units),
          !.child_home_mcp_servers = @ \cup ({home} \X child_servers),
          !.child_home_tool_shims = @ \cup ({home} \X child_tools)]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command ScaffoldProjectChildHome
\* @result ProjectResult
\* @port SkillManagerCli.scaffold_project_child_home
ScaffoldProjectChildHome(project, home, parent_home) ==
  /\ ScaffoldProjectChildHomeImpl(project, home, parent_home)
  /\ MarkInternal("ScaffoldProjectChildHome", [project |-> project, home |-> home, parent_home |-> parent_home])

ResolveProjectProfileImpl(project, profile, home, parent_home) ==
  LET resolved_units == ProjectProfileResolvedUnitClosure(project, profile)
      docs == ProjectProfileDocRepos(project, profile)
      harnesses == ProjectProfileHarnessTemplates(project, profile)
      child_payload == ProjectProfileChildHomePayload(project, profile)
      child_servers == McpServersFor(child_payload)
      child_tools == ChildHomeShimsFor(child_payload)
  IN
  IF project \notin project_model.registrations
     \/ <<project, profile>> \notin project_model.profile_declarations
     \/ <<project, profile, home>> \notin ProjectProfileChildHomeEdges
     \/ parent_home \notin SkillManagerHomes
     \/ parent_home = home
  THEN
    /\ result' = Reject("PROJECT_PROFILE_NOT_READY")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ cli_store_units' = cli_store_units \cup resolved_units
    /\ cli_doc_repos' = cli_doc_repos \cup docs
    /\ cli_harness_templates' = cli_harness_templates \cup harnesses
    /\ cli_installed_records' = cli_installed_records \cup resolved_units
    /\ cli_lock_units' = cli_lock_units \cup resolved_units
    /\ cli_bindings' = cli_bindings \cup resolved_units \cup docs
    /\ cli_projection_rows' = cli_projection_rows \cup resolved_units \cup docs
    /\ cli_managed_copies' = cli_managed_copies \cup docs
    /\ cli_import_directives' = cli_import_directives \cup docs
    /\ cli_tool_records' = cli_tool_records \cup CliDepsFor(resolved_units)
    /\ cli_cli_lock' = cli_cli_lock \cup CliDepsFor(resolved_units)
    /\ cli_skill_scripts_run' = cli_skill_scripts_run \cup ScriptsFor(resolved_units)
    /\ gateway_catalog' = gateway_catalog \cup McpServersFor(resolved_units)
    /\ gateway_dynamic_servers' = gateway_dynamic_servers \cup McpServersFor(resolved_units)
    /\ project_model' =
        [project_model EXCEPT
          !.profile_locks = @ \cup {<<project, profile>>},
          !.profile_resolved_units =
              @ \cup {<<project, profile, unit>> : unit \in resolved_units},
          !.profile_child_homes = @ \cup {<<project, profile, home>>},
          !.child_homes = @ \cup {home},
          !.child_home_parents = @ \cup {<<parent_home, home>>},
          !.child_home_agent_configs = @ \cup ({home} \X Agents),
          !.child_home_units = @ \cup ({home} \X child_payload),
          !.child_home_mcp_servers = @ \cup ({home} \X child_servers),
          !.child_home_tool_shims = @ \cup ({home} \X child_tools)]
    /\ result' = Ok
    /\ UNCHANGED << cli_harness_instances, cli_agent_projections,
                    cli_projection_conflicts, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, effect_status,
                    effect_continuation, program_halted, always_after_ran,
                    rollback_journal, gateway_global_deployments,
                    gateway_session_deployments, gateway_tools,
                    gateway_disclosures, gateway_errors, gateway_last_init,
                    server_registry_units, server_versions, server_packages,
                    server_authenticated_users >>

\* @command ResolveProjectProfile
\* @result ProjectResult
\* @port SkillManagerCli.resolve_project_profile
ResolveProjectProfile(project, profile, home, parent_home) ==
  /\ ResolveProjectProfileImpl(project, profile, home, parent_home)
  /\ MarkInternal("ResolveProjectProfile", [project |-> project, profile |-> profile, home |-> home, parent_home |-> parent_home])

SyncClaimingProjectChildHomesImpl(u) ==
  LET claiming_pairs ==
        {pair \in project_model.project_child_homes :
          u \in ProjectChildHomePayload(pair[1])}
      homes == {pair[2] : pair \in claiming_pairs}
      payload ==
        UNION {ProjectChildHomePayload(pair[1]) : pair \in claiming_pairs}
      child_servers == McpServersFor(payload)
      child_tools == ChildHomeShimsFor(payload)
  IN
  IF u \notin cli_store_units
  THEN
    /\ result' = Reject("NOT_INSTALLED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE IF claiming_pairs = {}
  THEN
    /\ result' = Ok
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE IF ~(payload \subseteq (cli_store_units \cup cli_doc_repos \cup cli_harness_templates))
  THEN
    \* Automatic parent sync refresh is non-destructive on project refresh failure.
    /\ result' = Reject("PROJECT_CHILD_HOME_PARENT_GAP")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.child_home_agent_configs = @ \cup (homes \X Agents),
          !.child_home_units = @ \cup (homes \X payload),
          !.child_home_mcp_servers = @ \cup (homes \X child_servers),
          !.child_home_tool_shims = @ \cup (homes \X child_tools)]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command SyncClaimingProjectChildHomes
\* @result ProjectResult
\* @port SkillManagerCli.sync_claiming_project_child_homes
SyncClaimingProjectChildHomes(u) ==
  /\ SyncClaimingProjectChildHomesImpl(u)
  /\ MarkInternal("SyncClaimingProjectChildHomes", [unit |-> u])

\* ---------------------------------------------------------------------------
\* skill-manager venv: content-addressed skill store, per-project version
\* pins with the ancestry-or-fail collision rule, venv activation as a
\* recursive parent-child env overlay, agent-CLI shims, and tool-call hook
\* materialization (ticket/env-overlay.md + ticket/versioning.md).
\* ---------------------------------------------------------------------------

\* Install-time write into the content-addressed store skills/<name>/<sha>/.
\* Store entries are an immutable cache: they are never removed by RemoveUnit,
\* and the store_latest pointer (the "global latest" surfaced in
\* SKILL_MANAGER_HOME) always moves to the most recently stored sha.
StoreUnitVersionImpl(u, sha) ==
  IF u \notin cli_store_units
  THEN
    /\ result' = Reject("UNIT_NOT_INSTALLED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.store_versions = @ \cup {<<u, sha>>},
          !.store_latest =
              {entry \in @ : entry[1] # u} \cup {<<u, sha>>}]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command StoreUnitVersion
\* @result VenvResult
\* @port SkillManagerCli.store_unit_version
StoreUnitVersion(u, sha) ==
  /\ StoreUnitVersionImpl(u, sha)
  /\ MarkInternal("StoreUnitVersion", [unit |-> u, sha |-> sha])

\* Per-project pin (skill-project.toml). Ancestry-or-fail: a pin is rejected
\* and recorded as a conflict when any other project pins the same unit to a
\* sha that is not ancestry-related. Re-pinning within one project replaces
\* the project's own pin.
PinProjectUnitVersionImpl(project, u, sha) ==
  IF project \notin project_model.registrations
  THEN
    /\ result' = Reject("PROJECT_NOT_REGISTERED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE IF <<u, sha>> \notin project_model.store_versions
  THEN
    /\ result' = Reject("VERSION_NOT_STORED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE IF \E pin \in project_model.version_pins:
            /\ pin[2] = u
            /\ pin[1] # project
            /\ ~AncestryRelated(pin[3], sha)
  THEN
    /\ project_model' =
        [project_model EXCEPT
          !.pin_conflicts = @ \cup {<<project, u>>}]
    /\ result' = Reject("PIN_ANCESTRY_CONFLICT")
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.version_pins =
              {pin \in @ : ~(pin[1] = project /\ pin[2] = u)}
                \cup {<<project, u, sha>>},
          !.pin_conflicts = @ \ {<<project, u>>}]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command PinProjectUnitVersion
\* @result VenvResult
\* @port SkillManagerCli.pin_project_unit_version
PinProjectUnitVersion(project, u, sha) ==
  /\ PinProjectUnitVersionImpl(project, u, sha)
  /\ MarkInternal("PinProjectUnitVersion",
       [project |-> project, unit |-> u, sha |-> sha])

\* Activate the project-local venv overlay: the project's .skill-manager home
\* overrides the parent home pip-venv style (previous bin retained through
\* the recorded overlay parent). Distinct from harness/profile child homes:
\* a venv activation never copies units; it reroutes resolution.
ActivateProjectVenvImpl(project, home, parent_home) ==
  IF \/ project \notin project_model.registrations
     \/ <<project, home>> \notin ProjectChildHomeEdges
     \/ parent_home \notin SkillManagerHomes
     \/ parent_home = home
  THEN
    /\ result' = Reject("VENV_PROJECT_NOT_READY")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.venv_activations = @ \cup {<<project, home>>},
          !.venv_overlay_parents = @ \cup {<<parent_home, home>>}]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command ActivateProjectVenv
\* @result VenvResult
\* @port SkillManagerCli.activate_project_venv
ActivateProjectVenv(project, home, parent_home) ==
  /\ ActivateProjectVenvImpl(project, home, parent_home)
  /\ MarkInternal("ActivateProjectVenv",
       [project |-> project, home |-> home, parent_home |-> parent_home])

\* Materialize the agent-CLI shims (claude / codex / gemini) into an
\* activated venv. Each shim reroutes the agent CLI with
\* SKILL_MANAGER_HOME / CLAUDE_CONFIG_DIR / CODEX_HOME pointed at the venv
\* home so the pinned skill versions win over the global config.
MaterializeVenvAgentShimsImpl(home) ==
  IF ~(\E project \in Projects: <<project, home>> \in project_model.venv_activations)
  THEN
    /\ result' = Reject("VENV_NOT_ACTIVATED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.venv_agent_shims = @ \cup ({home} \X Agents)]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command MaterializeVenvAgentShims
\* @result VenvResult
\* @port SkillManagerCli.materialize_venv_agent_shims
MaterializeVenvAgentShims(home) ==
  /\ MaterializeVenvAgentShimsImpl(home)
  /\ MarkInternal("MaterializeVenvAgentShims", [home |-> home])

\* Materialize tool-call hooks into an activated venv from the units pinned
\* by the activating projects. Hook actions run on intercepted CLI calls and
\* stay visible to the agent (push model over pull).
MaterializeVenvHooksImpl(home) ==
  LET activating == {project \in Projects :
                       <<project, home>> \in project_model.venv_activations}
      pinned == {pin[2] : pin \in {p \in project_model.version_pins :
                                     p[1] \in activating}}
  IN
  IF activating = {}
  THEN
    /\ result' = Reject("VENV_NOT_ACTIVATED")
    /\ project_model' = project_model
    /\ UNCHANGED state_vars
  ELSE
    /\ project_model' =
        [project_model EXCEPT
          !.venv_hook_materializations = @ \cup ({home} \X HooksFor(pinned))]
    /\ result' = Ok
    /\ UNCHANGED state_vars

\* @command MaterializeVenvHooks
\* @result VenvResult
\* @port SkillManagerCli.materialize_venv_hooks
MaterializeVenvHooks(home) ==
  /\ MaterializeVenvHooksImpl(home)
  /\ MarkInternal("MaterializeVenvHooks", [home |-> home])

RenderProgressiveRootHelpImpl ==
  /\ project_model.cli_root_help_topics = CliTopLevelCommands
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command RenderProgressiveRootHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_progressive_root_help
RenderProgressiveRootHelp ==
  /\ RenderProgressiveRootHelpImpl
  /\ MarkInternal("RenderProgressiveRootHelp", NoParams)

RenderInstallCommandHelpImpl ==
  /\ "install" \in project_model.cli_command_catalog
  /\ "install" \in project_model.cli_command_help_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command RenderInstallCommandHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_command_help
RenderInstallCommandHelp ==
  /\ RenderInstallCommandHelpImpl
  /\ MarkInternal("RenderInstallCommandHelp", NoParams)

RenderSyncCommandHelpImpl ==
  /\ "sync" \in project_model.cli_command_catalog
  /\ "sync" \in project_model.cli_command_help_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command RenderSyncCommandHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_command_help
RenderSyncCommandHelp ==
  /\ RenderSyncCommandHelpImpl
  /\ MarkInternal("RenderSyncCommandHelp", NoParams)

RenderProjectProfilesListHelpImpl ==
  /\ "project profiles list" \in project_model.cli_command_catalog
  /\ "project profiles list" \in project_model.cli_command_help_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command RenderProjectProfilesListHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_command_help
RenderProjectProfilesListHelp ==
  /\ RenderProjectProfilesListHelpImpl
  /\ MarkInternal("RenderProjectProfilesListHelp", NoParams)

ExposeInstallLocalUnitWorkflowDocsImpl ==
  /\ "install-local-unit" \in project_model.cli_workflow_catalog
  /\ <<"skill-manager-skill", "install-local-unit">> \in project_model.cli_skill_doc_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command ExposeInstallLocalUnitWorkflowDocs
\* @result CliDisclosureResult
\* @port SkillManagerCli.expose_skill_workflow_docs
ExposeInstallLocalUnitWorkflowDocs ==
  /\ ExposeInstallLocalUnitWorkflowDocsImpl
  /\ MarkInternal("ExposeInstallLocalUnitWorkflowDocs", NoParams)

ExposeSkillScriptsWorkflowDocsImpl ==
  /\ "skill-scripts" \in project_model.cli_workflow_catalog
  /\ <<"skill-publisher-skill", "skill-scripts">> \in project_model.cli_skill_doc_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command ExposeSkillScriptsWorkflowDocs
\* @result CliDisclosureResult
\* @port SkillManagerCli.expose_skill_workflow_docs
ExposeSkillScriptsWorkflowDocs ==
  /\ ExposeSkillScriptsWorkflowDocsImpl
  /\ MarkInternal("ExposeSkillScriptsWorkflowDocs", NoParams)

ExposeProjectEnvWorkflowDocsImpl ==
  /\ "project-env" \in project_model.cli_workflow_catalog
  /\ <<"skill-manager-skill", "project-env">> \in project_model.cli_skill_doc_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command ExposeProjectEnvWorkflowDocs
\* @result CliDisclosureResult
\* @port SkillManagerCli.expose_skill_workflow_docs
ExposeProjectEnvWorkflowDocs ==
  /\ ExposeProjectEnvWorkflowDocsImpl
  /\ MarkInternal("ExposeProjectEnvWorkflowDocs", NoParams)

EmitSyncOneUnitAgentContextImpl ==
  /\ "sync-one-unit" \in project_model.cli_workflow_catalog
  /\ "sync-one-unit" \in project_model.cli_agent_context_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command EmitSyncOneUnitAgentContext
\* @result CliDisclosureResult
\* @port SkillManagerCli.emit_agent_workflow_context
EmitSyncOneUnitAgentContext ==
  /\ EmitSyncOneUnitAgentContextImpl
  /\ MarkInternal("EmitSyncOneUnitAgentContext", NoParams)

EmitProjectEnvAgentContextImpl ==
  /\ "project-env" \in project_model.cli_workflow_catalog
  /\ "project-env" \in project_model.cli_agent_context_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command EmitProjectEnvAgentContext
\* @result CliDisclosureResult
\* @port SkillManagerCli.emit_agent_workflow_context
EmitProjectEnvAgentContext ==
  /\ EmitProjectEnvAgentContextImpl
  /\ MarkInternal("EmitProjectEnvAgentContext", NoParams)

CoreNext ==
  \/ \E user \in Users: ServerAuthenticate(user)
  \/ \E user \in Users, unit \in Units, version \in Versions:
      ServerPublishTarball(user, unit, version)
  \/ ServerSearch
  \/ ConfigureRegistry
  \/ EnsureGateway
  \/ \E u \in Units: InstallUnit(u)
  \/ \E u \in Units: InstallUnitForceScripts(u)
  \/ \E u \in Units: SyncUnit(u)
  \/ \E u \in Units: SyncUnitForceScripts(u)
  \/ \E u \in Units: RemoveUnit(u)
  \/ \E doc \in DocRepos: BindDocRepo(doc)
  \/ \E doc \in DocRepos: SyncDocRepo(doc)
  \/ \E template \in HarnessTemplates, instance \in HarnessInstances:
      SyncHarness(template, instance)
  \/ RunEffectProgramFailure
  \/ RunAlwaysAfterCleanup
  \/ \E server \in Servers: RegisterGatewayServer(server)
  \/ \E server \in Servers: DeployGatewayGlobal(server)
  \/ \E session \in Sessions, server \in Servers:
      DeployGatewaySession(session, server)
  \/ \E session \in Sessions, tool \in Tools:
      DescribeGatewayTool(session, tool)
  \/ \E session \in Sessions, tool \in Tools:
      InvokeGatewayTool(session, tool)

CliDisclosureNext ==
  \/ RenderProgressiveRootHelp
  \/ RenderInstallCommandHelp
  \/ RenderSyncCommandHelp
  \/ RenderProjectProfilesListHelp
  \/ ExposeInstallLocalUnitWorkflowDocs
  \/ ExposeSkillScriptsWorkflowDocs
  \/ ExposeProjectEnvWorkflowDocs
  \/ EmitSyncOneUnitAgentContext
  \/ EmitProjectEnvAgentContext

Next ==
  \/ CoreNext
  \/ \E project \in Projects: RegisterProjectManifest(project)
  \/ \E project \in Projects: ResolveProjectDependencies(project)
  \/ \E project \in Projects, env \in Envs: MaterializeProjectEnv(project, env)
  \/ \E project \in Projects: ResolveProjectLibs(project)
  \/ \E home \in ChildHomes, template \in HarnessTemplates:
      InstantiateChildHomeFromHarness(home, template)
  \/ \E project \in Projects, home \in ChildHomes, parent_home \in SkillManagerHomes:
      ScaffoldProjectChildHome(project, home, parent_home)
  \/ \E project \in Projects, profile \in Profiles, home \in ChildHomes,
        parent_home \in SkillManagerHomes:
      ResolveProjectProfile(project, profile, home, parent_home)
  \/ \E u \in Units: SyncClaimingProjectChildHomes(u)
  \/ \E u \in Units, sha \in Shas: StoreUnitVersion(u, sha)
  \/ \E project \in Projects, u \in Units, sha \in Shas:
      PinProjectUnitVersion(project, u, sha)
  \/ \E project \in Projects, home \in ChildHomes, parent_home \in SkillManagerHomes:
      ActivateProjectVenv(project, home, parent_home)
  \/ \E home \in ChildHomes: MaterializeVenvAgentShims(home)
  \/ \E home \in ChildHomes: MaterializeVenvHooks(home)
  \/ CliDisclosureNext

\* @invariant CliInstalledRecordsTrackStore
CliInstalledRecordsTrackStore ==
  cli_installed_records = cli_store_units

\* @invariant CliLockTracksStore
CliLockTracksStore ==
  cli_lock_units = cli_store_units

\* @invariant CliReferencesClosed
CliReferencesClosed ==
  Closed(cli_store_units)

\* @invariant CliProjectionsHaveInstalledUnits
CliProjectionsHaveInstalledUnits ==
  cli_agent_projections \subseteq (Agents \X cli_store_units)

\* @invariant CliBindingsReferenceInstalledOrContentUnits
CliBindingsReferenceInstalledOrContentUnits ==
  cli_bindings \subseteq (cli_store_units \cup cli_doc_repos)

\* @invariant ProjectionRowsHaveBindings
ProjectionRowsHaveBindings ==
  cli_projection_rows \subseteq cli_bindings

\* @invariant ManagedCopiesHaveDocRepo
ManagedCopiesHaveDocRepo ==
  cli_managed_copies \subseteq cli_doc_repos

\* @invariant ImportDirectivesHaveDocRepo
ImportDirectivesHaveDocRepo ==
  cli_import_directives \subseteq cli_doc_repos

\* @invariant CliCliLockTracksInstalledPackages
CliCliLockTracksInstalledPackages ==
  cli_cli_lock = cli_tool_records

\* @invariant CliCliArtifactsAreClaimed
CliCliArtifactsAreClaimed ==
  cli_tool_records \subseteq CliDepsFor(cli_store_units)

\* @invariant CliCliLockRowsAreClaimed
CliCliLockRowsAreClaimed ==
  cli_cli_lock \subseteq CliDepsFor(cli_store_units)

\* @invariant SkillScriptsAreKnownScripts
SkillScriptsAreKnownScripts ==
  cli_skill_scripts_run \subseteq Scripts

\* @invariant SkillScriptRunsAreClaimed
SkillScriptRunsAreClaimed ==
  cli_skill_scripts_run \subseteq ScriptsFor(cli_store_units)

\* @invariant CliCommandCatalogCoversAllCommands
CliCommandCatalogCoversAllCommands ==
  /\ project_model.cli_command_catalog = CliCommandCatalog
  /\ project_model.cli_command_aliases = CliCommandAliases

\* @invariant CliRootHelpStaysProgressive
CliRootHelpStaysProgressive ==
  /\ project_model.cli_root_help_topics = CliTopLevelCommands
  /\ project_model.cli_root_help_topics \cap CliSubcommands = {}
  /\ CliRootCommand \notin project_model.cli_root_help_topics

\* @invariant CliCommandHelpCoversCatalog
CliCommandHelpCoversCatalog ==
  project_model.cli_command_help_topics = CliCommandCatalog

\* @invariant CliWorkflowCatalogCoversDesiredWorkflows
CliWorkflowCatalogCoversDesiredWorkflows ==
  project_model.cli_workflow_catalog = CliWorkflowCatalog

\* @invariant CliWorkflowsReferenceCatalogCommands
CliWorkflowsReferenceCatalogCommands ==
  /\ project_model.cli_workflow_command_links = CliWorkflowCommandLinks
  /\ {link[1] : link \in project_model.cli_workflow_command_links} = CliWorkflowCatalog
  /\ {link[2] : link \in project_model.cli_workflow_command_links} \subseteq CliCommandCatalog

\* @invariant CliSkillDocsCoverWorkflowCatalog
CliSkillDocsCoverWorkflowCatalog ==
  /\ project_model.cli_skill_doc_topics = ExpectedSkillDocCoverage
  /\ \A workflow \in CliWorkflowCatalog:
       \E doc \in SkillDocSurfaces:
         <<doc, workflow>> \in project_model.cli_skill_doc_topics

\* @invariant CliAgentContextCoversWorkflowCatalog
CliAgentContextCoversWorkflowCatalog ==
  project_model.cli_agent_context_topics = CliWorkflowCatalog

\* @invariant ProjectRegistrationsHaveManifests
ProjectRegistrationsHaveManifests ==
  project_model.registrations \subseteq project_model.manifests

\* @invariant ProjectEnvSpecsHaveManifest
ProjectEnvSpecsHaveManifest ==
  \A entry \in project_model.env_specs:
    entry[1] \in project_model.manifests

\* @invariant ProjectLibSpecsHaveManifest
ProjectLibSpecsHaveManifest ==
  \A entry \in project_model.lib_specs:
    entry[1] \in project_model.manifests

\* @invariant ProjectLocksHaveManifests
ProjectLocksHaveManifests ==
  project_model.locks \subseteq project_model.manifests

\* @invariant ProjectResolvedUnitsHaveLocksAndInstalledUnits
ProjectResolvedUnitsHaveLocksAndInstalledUnits ==
  \A entry \in project_model.resolved_units:
    /\ entry[1] \in project_model.locks
    /\ entry[2] \in cli_store_units

\* @invariant ProjectDocBindingsHaveLocksAndDocRepos
ProjectDocBindingsHaveLocksAndDocRepos ==
  \A entry \in project_model.doc_bindings:
    /\ entry[1] \in project_model.locks
    /\ entry[2] \in cli_doc_repos

\* @invariant ProjectHarnessBindingsHaveLocksAndHarnessTemplates
ProjectHarnessBindingsHaveLocksAndHarnessTemplates ==
  \A entry \in project_model.harness_bindings:
    /\ entry[1] \in project_model.locks
    /\ entry[2] \in cli_harness_templates

\* @invariant ProjectAgentConfigsHaveLocks
ProjectAgentConfigsHaveLocks ==
  \A entry \in project_model.agent_configs:
    entry[1] \in project_model.locks

\* @invariant ProjectEnvRealizationsHaveLocks
ProjectEnvRealizationsHaveLocks ==
  /\ project_model.env_realizations \subseteq project_model.env_specs
  /\ project_model.env_locks = project_model.env_realizations
  /\ \A entry \in project_model.env_realizations:
       entry[1] \in project_model.locks

\* @invariant ProjectEnvDocsHaveRealizedEnv
ProjectEnvDocsHaveRealizedEnv ==
  project_model.env_docs \subseteq project_model.env_realizations

\* @invariant ProjectToolShimsAreKnownTools
ProjectToolShimsAreKnownTools ==
  \A entry \in project_model.tool_shims:
    /\ entry[1] \in project_model.locks
    /\ entry[2] \in Tools

\* @invariant ProjectSkillVendorsAreInstalled
ProjectSkillVendorsAreInstalled ==
  \A entry \in project_model.skill_vendors:
    /\ entry[1] \in project_model.locks
    /\ entry[2] \in cli_store_units

\* @invariant ProjectLibLocksTrackCheckouts
ProjectLibLocksTrackCheckouts ==
  /\ project_model.lib_checkouts \subseteq project_model.lib_specs
  /\ project_model.lib_locks = project_model.lib_checkouts
  /\ \A entry \in project_model.lib_checkouts:
       entry[1] \in project_model.locks

\* @invariant ProjectProfileDeclarationsHaveManifests
ProjectProfileDeclarationsHaveManifests ==
  \A entry \in project_model.profile_declarations:
    entry[1] \in project_model.manifests

\* @invariant ProjectProfileSpecsHaveDeclarations
ProjectProfileSpecsHaveDeclarations ==
  /\ \A entry \in project_model.profile_env_specs:
       <<entry[1], entry[2]>> \in project_model.profile_declarations
  /\ \A entry \in project_model.profile_lib_specs:
       <<entry[1], entry[2]>> \in project_model.profile_declarations

\* @invariant ProjectProfileLocksHaveDeclarations
ProjectProfileLocksHaveDeclarations ==
  project_model.profile_locks \subseteq project_model.profile_declarations

\* @invariant ProjectProfileResolvedUnitsHaveLocksAndInstalledUnits
ProjectProfileResolvedUnitsHaveLocksAndInstalledUnits ==
  \A entry \in project_model.profile_resolved_units:
    /\ <<entry[1], entry[2]>> \in project_model.profile_locks
    /\ entry[3] \in cli_store_units

\* @invariant ProjectProfileChildHomesHaveLocks
ProjectProfileChildHomesHaveLocks ==
  \A entry \in project_model.profile_child_homes:
    /\ <<entry[1], entry[2]>> \in project_model.profile_locks
    /\ entry[3] \in project_model.child_homes

\* @invariant ChildHomesHaveHarnesses
ChildHomesHaveHarnesses ==
  \A home \in project_model.child_homes:
    \/ \E template \in HarnessTemplates:
        <<home, template>> \in project_model.child_home_harnesses
    \/ \E project \in Projects:
        <<project, home>> \in project_model.project_child_homes
    \/ \E project \in Projects, profile \in Profiles:
        <<project, profile, home>> \in project_model.profile_child_homes

\* @invariant ProjectChildHomesHaveRegistrations
ProjectChildHomesHaveRegistrations ==
  \A pair \in project_model.project_child_homes:
    /\ pair[1] \in project_model.registrations
    /\ pair[2] \in project_model.child_homes

\* @invariant ChildHomesHaveParents
ChildHomesHaveParents ==
  \A home \in project_model.child_homes:
    \E parent \in SkillManagerHomes:
      <<parent, home>> \in project_model.child_home_parents

\* @invariant ChildHomeParentsAreKnownAndNotSelf
ChildHomeParentsAreKnownAndNotSelf ==
  \A pair \in project_model.child_home_parents:
    /\ pair[1] \in SkillManagerHomes
    /\ pair[2] \in project_model.child_homes
    /\ pair[1] # pair[2]

\* @invariant ChildHomeUnitsComeFromParent
ChildHomeUnitsComeFromParent ==
  \A pair \in project_model.child_home_units:
    /\ pair[1] \in project_model.child_homes
    /\ pair[2] \in (cli_store_units \cup cli_doc_repos \cup cli_harness_templates)

\* @invariant ProjectChildHomeUnitsComeFromProject
ProjectChildHomeUnitsComeFromProject ==
  \A project_home \in project_model.project_child_homes:
    \A unit_pair \in project_model.child_home_units:
      unit_pair[1] = project_home[2] =>
        unit_pair[2] \in ProjectChildHomePayload(project_home[1])

\* @invariant ProjectProfileChildHomeUnitsComeFromProfile
ProjectProfileChildHomeUnitsComeFromProfile ==
  \A profile_home \in project_model.profile_child_homes:
    \A unit_pair \in project_model.child_home_units:
      unit_pair[1] = profile_home[3] =>
        unit_pair[2] \in ProjectProfileChildHomePayload(profile_home[1], profile_home[2])

\* @invariant ChildHomeAgentConfigsAreKnown
ChildHomeAgentConfigsAreKnown ==
  \A pair \in project_model.child_home_agent_configs:
    /\ pair[1] \in project_model.child_homes
    /\ pair[2] \in Agents

\* @invariant ChildHomeMcpServersComeFromUnits
ChildHomeMcpServersComeFromUnits ==
  \A pair \in project_model.child_home_mcp_servers:
    /\ pair[1] \in project_model.child_homes
    /\ \E unit_pair \in project_model.child_home_units:
        /\ unit_pair[1] = pair[1]
        /\ <<unit_pair[2], pair[2]>> \in UnitMcpEdges

\* @invariant ChildHomeToolShimsComeFromMcpServers
ChildHomeToolShimsComeFromMcpServers ==
  \A pair \in project_model.child_home_tool_shims:
    /\ pair[1] \in project_model.child_homes
    /\ \/ \E server_pair \in project_model.child_home_mcp_servers:
            /\ server_pair[1] = pair[1]
            /\ <<server_pair[2], pair[2]>> \in ServerToolEdges
       \/ \E unit_pair \in project_model.child_home_units:
            /\ unit_pair[1] = pair[1]
            /\ <<unit_pair[2], pair[2]>> \in UnitPackageEdges
       \/ \E unit_pair \in project_model.child_home_units:
            /\ unit_pair[1] = pair[1]
            /\ <<unit_pair[2], pair[2]>> \in UnitScriptEdges

\* @invariant VenvStoreVersionsAreContentAddressed
VenvStoreVersionsAreContentAddressed ==
  project_model.store_versions \subseteq (Units \X Shas)

\* @invariant VenvStoreLatestIsStored
VenvStoreLatestIsStored ==
  project_model.store_latest \subseteq project_model.store_versions

\* @invariant VenvStoreLatestUniquePerUnit
VenvStoreLatestUniquePerUnit ==
  \A e1 \in project_model.store_latest:
    \A e2 \in project_model.store_latest:
      e1[1] = e2[1] => e1 = e2

\* @invariant VenvPinsAreStoredAndRegistered
VenvPinsAreStoredAndRegistered ==
  \A pin \in project_model.version_pins:
    /\ pin[1] \in project_model.registrations
    /\ <<pin[2], pin[3]>> \in project_model.store_versions

\* @invariant VenvPinsUniquePerProjectUnit
VenvPinsUniquePerProjectUnit ==
  \A p1 \in project_model.version_pins:
    \A p2 \in project_model.version_pins:
      (p1[1] = p2[1] /\ p1[2] = p2[2]) => p1 = p2

\* Ancestry-or-fail: cross-project pins of one unit stay ancestry-related.
\* @invariant VenvPinsAncestryCoherent
VenvPinsAncestryCoherent ==
  \A p1 \in project_model.version_pins:
    \A p2 \in project_model.version_pins:
      p1[2] = p2[2] => AncestryRelated(p1[3], p2[3])

\* @invariant VenvPinConflictsAreDeclaredProjects
VenvPinConflictsAreDeclaredProjects ==
  project_model.pin_conflicts \subseteq (Projects \X Units)

\* @invariant VenvActivationsAreDeclaredAndRegistered
VenvActivationsAreDeclaredAndRegistered ==
  \A activation \in project_model.venv_activations:
    /\ activation \in ProjectChildHomeEdges
    /\ activation[1] \in project_model.registrations

\* @invariant VenvOverlayParentsAreKnownAndNotSelf
VenvOverlayParentsAreKnownAndNotSelf ==
  \A pair \in project_model.venv_overlay_parents:
    /\ pair[1] \in SkillManagerHomes
    /\ pair[2] \in ChildHomes
    /\ pair[1] # pair[2]

\* @invariant VenvAgentShimsComeFromActivations
VenvAgentShimsComeFromActivations ==
  \A shim \in project_model.venv_agent_shims:
    /\ shim[2] \in Agents
    /\ \E project \in Projects:
        <<project, shim[1]>> \in project_model.venv_activations

\* @invariant VenvHooksComeFromPinnedUnits
VenvHooksComeFromPinnedUnits ==
  \A entry \in project_model.venv_hook_materializations:
    \E pin \in project_model.version_pins:
      /\ <<pin[1], entry[1]>> \in project_model.venv_activations
      /\ <<pin[2], entry[2]>> \in UnitHookEdges

\* @invariant HaltImpliesHaltContinuation
HaltImpliesHaltContinuation ==
  program_halted => effect_continuation = "HALT"

\* @invariant CompletedSuccessfulProgramsClearRollbackJournal
CompletedSuccessfulProgramsClearRollbackJournal ==
  /\ effect_status = "OK"
  /\ effect_continuation = "CONTINUE"
  /\ ~program_halted
  => rollback_journal = {}

\* @invariant GatewayDynamicServersAreCataloged
GatewayDynamicServersAreCataloged ==
  gateway_dynamic_servers \subseteq gateway_catalog

\* @invariant GatewayDeploymentsAreCataloged
GatewayDeploymentsAreCataloged ==
  DeployedServers \subseteq gateway_catalog

\* @invariant GatewayToolsComeFromDeployments
GatewayToolsComeFromDeployments ==
  gateway_tools = VisibleTools

\* @invariant GatewayDisclosuresReferenceKnownTools
GatewayDisclosuresReferenceKnownTools ==
  \A disclosure \in gateway_disclosures:
    disclosure[2] \in Tools

\* @invariant ServerVersionsHaveRegistryUnit
ServerVersionsHaveRegistryUnit ==
  \A version_entry \in server_versions:
    version_entry[1] \in server_registry_units

\* @invariant ServerPackagesHaveVersion
ServerPackagesHaveVersion ==
  server_packages \subseteq server_versions

\* The accepted pre-split transition relation over the unmarked Impl
\* actions. External.tla uses this for hidden internal progress so
\* hidden steps do not multiply command-marker variety during case
\* generation. Mirrors Next exactly, including the project_model frame
\* on the CoreNext-family disjunct.
CoreImplNext ==
  \/ \E user \in Users: ServerAuthenticateImpl(user)
  \/ \E user \in Users, unit \in Units, version \in Versions:
      ServerPublishTarballImpl(user, unit, version)
  \/ ServerSearchImpl
  \/ ConfigureRegistryImpl
  \/ EnsureGatewayImpl
  \/ \E u \in Units: InstallUnitImpl(u)
  \/ \E u \in Units: InstallUnitForceScriptsImpl(u)
  \/ \E u \in Units: SyncUnitImpl(u)
  \/ \E u \in Units: SyncUnitForceScriptsImpl(u)
  \/ \E u \in Units: RemoveUnitImpl(u)
  \/ \E doc \in DocRepos: BindDocRepoImpl(doc)
  \/ \E doc \in DocRepos: SyncDocRepoImpl(doc)
  \/ \E template \in HarnessTemplates, instance \in HarnessInstances:
      SyncHarnessImpl(template, instance)
  \/ RunEffectProgramFailureImpl
  \/ RunAlwaysAfterCleanupImpl
  \/ \E server \in Servers: RegisterGatewayServerImpl(server)
  \/ \E server \in Servers: DeployGatewayGlobalImpl(server)
  \/ \E session \in Sessions, server \in Servers:
      DeployGatewaySessionImpl(session, server)
  \/ \E session \in Sessions, tool \in Tools:
      DescribeGatewayToolImpl(session, tool)
  \/ \E session \in Sessions, tool \in Tools:
      InvokeGatewayToolImpl(session, tool)

CliDisclosureImplNext ==
  \/ RenderProgressiveRootHelpImpl
  \/ RenderInstallCommandHelpImpl
  \/ RenderSyncCommandHelpImpl
  \/ RenderProjectProfilesListHelpImpl
  \/ ExposeInstallLocalUnitWorkflowDocsImpl
  \/ ExposeSkillScriptsWorkflowDocsImpl
  \/ ExposeProjectEnvWorkflowDocsImpl
  \/ EmitSyncOneUnitAgentContextImpl
  \/ EmitProjectEnvAgentContextImpl

InternalImplNext ==
  \/ CoreImplNext /\ project_model' = project_model
  \/ \E project \in Projects: RegisterProjectManifestImpl(project)
  \/ \E project \in Projects: ResolveProjectDependenciesImpl(project)
  \/ \E project \in Projects, env \in Envs: MaterializeProjectEnvImpl(project, env)
  \/ \E project \in Projects: ResolveProjectLibsImpl(project)
  \/ \E home \in ChildHomes, template \in HarnessTemplates:
      InstantiateChildHomeFromHarnessImpl(home, template)
  \/ \E project \in Projects, home \in ChildHomes, parent_home \in SkillManagerHomes:
      ScaffoldProjectChildHomeImpl(project, home, parent_home)
  \/ \E project \in Projects, profile \in Profiles, home \in ChildHomes,
        parent_home \in SkillManagerHomes:
      ResolveProjectProfileImpl(project, profile, home, parent_home)
  \/ \E u \in Units: SyncClaimingProjectChildHomesImpl(u)
  \/ \E u \in Units, sha \in Shas: StoreUnitVersionImpl(u, sha)
  \/ \E project \in Projects, u \in Units, sha \in Shas:
      PinProjectUnitVersionImpl(project, u, sha)
  \/ \E project \in Projects, home \in ChildHomes, parent_home \in SkillManagerHomes:
      ActivateProjectVenvImpl(project, home, parent_home)
  \/ \E home \in ChildHomes: MaterializeVenvAgentShimsImpl(home)
  \/ \E home \in ChildHomes: MaterializeVenvHooksImpl(home)
  \/ CliDisclosureImplNext

InternalVars == vars

InternalInit == Init

InternalNext == Next

InternalInvariant ==
  /\ CliInstalledRecordsTrackStore
  /\ CliLockTracksStore
  /\ CliReferencesClosed
  /\ CliProjectionsHaveInstalledUnits
  /\ CliBindingsReferenceInstalledOrContentUnits
  /\ ProjectionRowsHaveBindings
  /\ ManagedCopiesHaveDocRepo
  /\ ImportDirectivesHaveDocRepo
  /\ CliCliLockTracksInstalledPackages
  /\ CliCliArtifactsAreClaimed
  /\ CliCliLockRowsAreClaimed
  /\ SkillScriptsAreKnownScripts
  /\ SkillScriptRunsAreClaimed
  /\ CliCommandCatalogCoversAllCommands
  /\ CliRootHelpStaysProgressive
  /\ CliCommandHelpCoversCatalog
  /\ CliWorkflowCatalogCoversDesiredWorkflows
  /\ CliWorkflowsReferenceCatalogCommands
  /\ CliSkillDocsCoverWorkflowCatalog
  /\ CliAgentContextCoversWorkflowCatalog
  /\ ProjectRegistrationsHaveManifests
  /\ ProjectEnvSpecsHaveManifest
  /\ ProjectLibSpecsHaveManifest
  /\ ProjectLocksHaveManifests
  /\ ProjectResolvedUnitsHaveLocksAndInstalledUnits
  /\ ProjectDocBindingsHaveLocksAndDocRepos
  /\ ProjectHarnessBindingsHaveLocksAndHarnessTemplates
  /\ ProjectAgentConfigsHaveLocks
  /\ ProjectEnvRealizationsHaveLocks
  /\ ProjectEnvDocsHaveRealizedEnv
  /\ ProjectToolShimsAreKnownTools
  /\ ProjectSkillVendorsAreInstalled
  /\ ProjectLibLocksTrackCheckouts
  /\ ProjectProfileDeclarationsHaveManifests
  /\ ProjectProfileSpecsHaveDeclarations
  /\ ProjectProfileLocksHaveDeclarations
  /\ ProjectProfileResolvedUnitsHaveLocksAndInstalledUnits
  /\ ProjectProfileChildHomesHaveLocks
  /\ ChildHomesHaveHarnesses
  /\ ProjectChildHomesHaveRegistrations
  /\ ChildHomesHaveParents
  /\ ChildHomeParentsAreKnownAndNotSelf
  /\ ChildHomeUnitsComeFromParent
  /\ ProjectChildHomeUnitsComeFromProject
  /\ ProjectProfileChildHomeUnitsComeFromProfile
  /\ ChildHomeAgentConfigsAreKnown
  /\ ChildHomeMcpServersComeFromUnits
  /\ ChildHomeToolShimsComeFromMcpServers
  /\ VenvStoreVersionsAreContentAddressed
  /\ VenvStoreLatestIsStored
  /\ VenvStoreLatestUniquePerUnit
  /\ VenvPinsAreStoredAndRegistered
  /\ VenvPinsUniquePerProjectUnit
  /\ VenvPinsAncestryCoherent
  /\ VenvPinConflictsAreDeclaredProjects
  /\ VenvActivationsAreDeclaredAndRegistered
  /\ VenvOverlayParentsAreKnownAndNotSelf
  /\ VenvAgentShimsComeFromActivations
  /\ VenvHooksComeFromPinnedUnits
  /\ HaltImpliesHaltContinuation
  /\ CompletedSuccessfulProgramsClearRollbackJournal
  /\ GatewayDynamicServersAreCataloged
  /\ GatewayDeploymentsAreCataloged
  /\ GatewayToolsComeFromDeployments
  /\ GatewayDisclosuresReferenceKnownTools
  /\ ServerVersionsHaveRegistryUnit
  /\ ServerPackagesHaveVersion

InternalSpec ==
  InternalInit /\ [][InternalNext]_InternalVars

\* Compatibility spec for the existing bounded CLI-disclosure loop.
CliDisclosureSpec ==
  InternalInit /\ [][CliDisclosureNext]_InternalVars

\* Fingerprint view that quotients out the result payload and the command
\* marker so full-model checking keeps the pre-split accepted state count.
MarkerlessView ==
  << cli_store_units, cli_doc_repos, cli_harness_templates,
     cli_harness_instances, cli_installed_records, cli_lock_units,
     cli_agent_projections, cli_bindings, cli_projection_rows,
     cli_managed_copies, cli_import_directives, cli_projection_conflicts,
     cli_tool_records, cli_cli_lock, cli_skill_scripts_run, cli_errors,
     cli_gateway_url_configured, cli_registry_url_configured,
     cli_gateway_mcp_snapshot, effect_status, effect_continuation,
     program_halted, always_after_ran, rollback_journal, gateway_catalog,
     gateway_dynamic_servers, gateway_global_deployments,
     gateway_session_deployments, gateway_tools, gateway_disclosures,
     gateway_errors, gateway_last_init, server_registry_units,
     server_versions, server_packages, server_authenticated_users,
     project_model >>

\* Bounded case-generation envelopes. The whole-program model is too large
\* for quick exhaustive TLC sweeps, so TLC state-graph cases are generated
\* from feature-slice envelopes of the one program spec. Each slice keeps
\* every action of its boundary reachable while freezing the orthogonal
\* subsystems at their Init values.

\* Install / sync / remove / bind-doc / harness boundary. The registry
\* server stays free because install resolution depends on published
\* units; the gateway deployment surface and the project model are frozen
\* at Init (gateway_catalog / gateway_dynamic_servers stay free because
\* install and remove write them).
CliStoreCaseEnvelope ==
  /\ gateway_global_deployments = {}
  /\ gateway_session_deployments = {}
  /\ gateway_tools = {}
  /\ gateway_disclosures = {}
  /\ gateway_errors = {}
  /\ gateway_last_init = {}
  /\ ~cli_gateway_url_configured
  /\ ~cli_registry_url_configured
  /\ project_model = ProjectModelInit

\* Gateway boundary: register / deploy (global + session) / disclose /
\* invoke plus EnsureGateway. RegisterGatewayServer seeds the catalog
\* directly, so the CLI store, effect program, registry server, and
\* project model are frozen at Init.
GatewayCaseEnvelope ==
  /\ cli_store_units = {}
  /\ cli_doc_repos = {}
  /\ cli_harness_templates = {}
  /\ cli_harness_instances = {}
  /\ cli_installed_records = {}
  /\ cli_lock_units = {}
  /\ cli_agent_projections = {}
  /\ cli_bindings = {}
  /\ cli_projection_rows = {}
  /\ cli_managed_copies = {}
  /\ cli_import_directives = {}
  /\ cli_tool_records = {}
  /\ cli_cli_lock = {}
  /\ cli_skill_scripts_run = {}
  /\ cli_errors = {}
  /\ ~cli_registry_url_configured
  /\ effect_status = "OK"
  /\ effect_continuation = "CONTINUE"
  /\ ~program_halted
  /\ ~always_after_ran
  /\ rollback_journal = {}
  /\ server_registry_units = {}
  /\ server_versions = {}
  /\ server_packages = {}
  /\ server_authenticated_users = {}
  /\ project_model = ProjectModelInit

\* Registry-server boundary: authenticate / publish / search plus
\* ConfigureRegistry. Everything downstream of the registry (CLI store,
\* gateway, project model) is frozen at Init.
ServerRegistryCaseEnvelope ==
  /\ cli_store_units = {}
  /\ cli_doc_repos = {}
  /\ cli_harness_templates = {}
  /\ cli_harness_instances = {}
  /\ cli_installed_records = {}
  /\ cli_lock_units = {}
  /\ cli_agent_projections = {}
  /\ cli_bindings = {}
  /\ cli_projection_rows = {}
  /\ cli_managed_copies = {}
  /\ cli_import_directives = {}
  /\ cli_tool_records = {}
  /\ cli_cli_lock = {}
  /\ cli_skill_scripts_run = {}
  /\ cli_errors = {}
  /\ ~cli_gateway_url_configured
  /\ effect_status = "OK"
  /\ effect_continuation = "CONTINUE"
  /\ ~program_halted
  /\ ~always_after_ran
  /\ rollback_journal = {}
  /\ gateway_catalog = {}
  /\ gateway_dynamic_servers = {}
  /\ gateway_global_deployments = {}
  /\ gateway_session_deployments = {}
  /\ gateway_tools = {}
  /\ gateway_disclosures = {}
  /\ gateway_errors = {}
  /\ gateway_last_init = {}
  /\ project_model = ProjectModelInit

\* Project / child-home / profile / env / lib boundary. The registry
\* server and CLI store stay free because project resolution installs
\* units, docs, and harnesses; the gateway deployment surface, error
\* paths, and the effect-failure program are frozen at Init.
ProjectCaseEnvelope ==
  /\ gateway_global_deployments = {}
  /\ gateway_session_deployments = {}
  /\ gateway_tools = {}
  /\ gateway_disclosures = {}
  /\ gateway_errors = {}
  /\ gateway_last_init = {}
  /\ ~cli_gateway_url_configured
  /\ ~cli_registry_url_configured
  /\ effect_status = "OK"
  /\ effect_continuation = "CONTINUE"
  /\ ~program_halted
  /\ ~always_after_ran
  /\ rollback_journal = {}
  /\ cli_errors = {}
  /\ project_model.store_versions = {}
  /\ project_model.store_latest = {}
  /\ project_model.version_pins = {}
  /\ project_model.pin_conflicts = {}
  /\ project_model.venv_activations = {}
  /\ project_model.venv_overlay_parents = {}
  /\ project_model.venv_agent_shims = {}
  /\ project_model.venv_hook_materializations = {}

\* CLI progressive-disclosure boundary: the nine help / workflow-doc /
\* agent-context actions. Freezes every variable except result and the
\* marker, and every project_model field except the constant-valued
\* cli_* catalog fields the disclosure actions read.
CliDisclosureCaseEnvelope ==
  /\ cli_store_units = {}
  /\ cli_doc_repos = {}
  /\ cli_harness_templates = {}
  /\ cli_harness_instances = {}
  /\ cli_installed_records = {}
  /\ cli_lock_units = {}
  /\ cli_agent_projections = {}
  /\ cli_bindings = {}
  /\ cli_projection_rows = {}
  /\ cli_managed_copies = {}
  /\ cli_import_directives = {}
  /\ cli_projection_conflicts = {}
  /\ cli_tool_records = {}
  /\ cli_cli_lock = {}
  /\ cli_skill_scripts_run = {}
  /\ cli_errors = {}
  /\ ~cli_gateway_url_configured
  /\ ~cli_registry_url_configured
  /\ cli_gateway_mcp_snapshot = {}
  /\ effect_status = "OK"
  /\ effect_continuation = "CONTINUE"
  /\ ~program_halted
  /\ ~always_after_ran
  /\ rollback_journal = {}
  /\ gateway_catalog = {}
  /\ gateway_dynamic_servers = {}
  /\ gateway_global_deployments = {}
  /\ gateway_session_deployments = {}
  /\ gateway_tools = {}
  /\ gateway_disclosures = {}
  /\ gateway_errors = {}
  /\ gateway_last_init = {}
  /\ server_registry_units = {}
  /\ server_versions = {}
  /\ server_packages = {}
  /\ server_authenticated_users = {}
  /\ project_model.manifests = {}
  /\ project_model.registrations = {}
  /\ project_model.locks = {}
  /\ project_model.resolved_units = {}
  /\ project_model.doc_bindings = {}
  /\ project_model.harness_bindings = {}
  /\ project_model.agent_configs = {}
  /\ project_model.env_realizations = {}
  /\ project_model.env_locks = {}
  /\ project_model.tool_shims = {}
  /\ project_model.skill_vendors = {}
  /\ project_model.env_docs = {}
  /\ project_model.env_specs = {}
  /\ project_model.lib_specs = {}
  /\ project_model.profile_declarations = {}
  /\ project_model.profile_locks = {}
  /\ project_model.profile_resolved_units = {}
  /\ project_model.profile_env_specs = {}
  /\ project_model.profile_lib_specs = {}
  /\ project_model.profile_child_homes = {}
  /\ project_model.lib_checkouts = {}
  /\ project_model.lib_locks = {}
  /\ project_model.child_homes = {}
  /\ project_model.project_child_homes = {}
  /\ project_model.child_home_parents = {}
  /\ project_model.child_home_harnesses = {}
  /\ project_model.child_home_agent_configs = {}
  /\ project_model.child_home_units = {}
  /\ project_model.child_home_mcp_servers = {}
  /\ project_model.child_home_tool_shims = {}
  /\ project_model.store_versions = {}
  /\ project_model.store_latest = {}
  /\ project_model.version_pins = {}
  /\ project_model.pin_conflicts = {}
  /\ project_model.venv_activations = {}
  /\ project_model.venv_overlay_parents = {}
  /\ project_model.venv_agent_shims = {}
  /\ project_model.venv_hook_materializations = {}

\* Venv boundary: content-addressed store writes, per-project version pins
\* (ancestry-or-fail), venv activation, agent shims, and hook
\* materialization. The registry server and CLI store stay free because
\* StoreUnitVersion requires an installed unit and pinning requires a
\* registered project; the gateway deployment surface, effect-failure
\* program, harness instances, profile resolution, and copied child homes
\* are frozen at Init.
VenvCaseEnvelope ==
  /\ gateway_global_deployments = {}
  /\ gateway_session_deployments = {}
  /\ gateway_tools = {}
  /\ gateway_disclosures = {}
  /\ gateway_errors = {}
  /\ gateway_last_init = {}
  /\ ~cli_gateway_url_configured
  /\ ~cli_registry_url_configured
  /\ effect_status = "OK"
  /\ effect_continuation = "CONTINUE"
  /\ ~program_halted
  /\ ~always_after_ran
  /\ rollback_journal = {}
  /\ cli_errors = {}
  /\ cli_harness_instances = {}
  /\ project_model.profile_locks = {}
  /\ project_model.profile_resolved_units = {}
  /\ project_model.profile_child_homes = {}
  /\ project_model.env_realizations = {}
  /\ project_model.env_locks = {}
  /\ project_model.env_docs = {}
  /\ project_model.lib_checkouts = {}
  /\ project_model.lib_locks = {}
  /\ project_model.child_homes = {}
  /\ project_model.project_child_homes = {}
  /\ project_model.child_home_parents = {}
  /\ project_model.child_home_harnesses = {}
  /\ project_model.child_home_agent_configs = {}
  /\ project_model.child_home_units = {}
  /\ project_model.child_home_mcp_servers = {}
  /\ project_model.child_home_tool_shims = {}

=============================================================================
