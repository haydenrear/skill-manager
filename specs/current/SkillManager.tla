----------------------------- MODULE SkillManager -----------------------------
EXTENDS Naturals, FiniteSets, Sequences, TLC

CONSTANTS
  UnitA, UnitB,
  DocRepoA, HarnessA, InstanceA,
  ProjectA, EnvA, LibA,
  ClaudeAgent, CodexAgent, GeminiAgent,
  ServerA, ServerB,
  ToolA, ToolB,
  ScriptA, PackageA,
  SessionA, SessionB,
  UserA, VersionA,
  NoReason

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
  result

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
     project_model, result >>

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

Units == {UnitA, UnitB}
DocRepos == {DocRepoA}
HarnessTemplates == {HarnessA}
HarnessInstances == {InstanceA}
Agents == {ClaudeAgent, CodexAgent, GeminiAgent}
Servers == {ServerA, ServerB}
Tools == {ToolA, ToolB}
Scripts == {ScriptA}
Packages == {PackageA}
Sessions == {SessionA, SessionB}
Users == {UserA}
Versions == {VersionA}
Projects == {ProjectA}
Envs == {EnvA}
Libs == {LibA}

ReferenceEdges == {<<UnitB, UnitA>>}
UnitMcpEdges == {<<UnitA, ServerA>>, <<UnitB, ServerB>>}
ServerToolEdges == {<<ServerA, ToolA>>, <<ServerB, ToolB>>}
UnitScriptEdges == {<<UnitA, ScriptA>>}
UnitPackageEdges == {<<UnitA, PackageA>>, <<UnitB, PackageA>>}
HarnessTemplateEdges == {<<HarnessA, UnitA>>}
ProjectUnitEdges == {<<ProjectA, UnitA>>, <<ProjectA, DocRepoA>>, <<ProjectA, HarnessA>>}
ProjectEnvSpecEdges == {<<ProjectA, EnvA>>}
ProjectLibSpecEdges == {<<ProjectA, LibA>>}

RefsFor(units) ==
  {ref \in Units : \E u \in units: <<u, ref>> \in ReferenceEdges}

DependencyClosure(units) ==
  units \cup RefsFor(units) \cup RefsFor(RefsFor(units))

McpServersFor(units) ==
  {server \in Servers : \E u \in units: <<u, server>> \in UnitMcpEdges}

ToolsFor(servers) ==
  {tool \in Tools : \E server \in servers: <<server, tool>> \in ServerToolEdges}

ScriptsFor(units) ==
  {script \in Scripts : \E u \in units: <<u, script>> \in UnitScriptEdges}

PackagesFor(units) ==
  {pkg \in Packages : \E u \in units: <<u, pkg>> \in UnitPackageEdges}

HarnessUnitsFor(template) ==
  {u \in Units : <<template, u>> \in HarnessTemplateEdges}

ProjectEnvSpecs(project) ==
  {env \in Envs : <<project, env>> \in ProjectEnvSpecEdges}

ProjectLibSpecs(project) ==
  {lib \in Libs : <<project, lib>> \in ProjectLibSpecEdges}

ProjectModelInit ==
  [manifests |-> {},
   registrations |-> {},
   env_specs |-> {},
   lib_specs |-> {}]

UnitProjections(units) ==
  Agents \X units

Closed(units) ==
  RefsFor(units) \subseteq units

SessionServers ==
  {server \in Servers : \E session \in Sessions: <<session, server>> \in gateway_session_deployments}

DeployedServers ==
  gateway_global_deployments \cup SessionServers

VisibleTools ==
  ToolsFor(DeployedServers)

Ok ==
  [accepted |-> TRUE, reason |-> NoReason]

Reject(reason) ==
  [accepted |-> FALSE, reason |-> reason]

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

\* @command ServerAuthenticate
\* @result ServerResult
\* @port SkillManagerServer.authenticate
ServerAuthenticate(user) ==
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

\* @command ServerPublishTarball
\* @result ServerResult
\* @port SkillManagerServer.publish_tarball
ServerPublishTarball(user, unit, version) ==
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

\* @command ServerSearch
\* @result ServerResult
\* @port SkillManagerServer.search
ServerSearch ==
  /\ result' = Ok
  /\ UNCHANGED state_vars

\* @command ConfigureRegistry
\* @result ConfigureResult
\* @port SkillManagerCli.configure_registry
ConfigureRegistry ==
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

\* @command EnsureGateway
\* @result GatewayResult
\* @port SkillManagerCli.ensure_gateway
EnsureGateway ==
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

\* @command InstallUnit
\* @result InstallResult
\* @port SkillManagerCli.install_unit
InstallUnit(u) ==
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
    /\ cli_tool_records' = cli_tool_records \cup PackagesFor(install_set)
    /\ cli_cli_lock' = cli_cli_lock \cup PackagesFor(install_set)
    /\ cli_skill_scripts_run' = cli_skill_scripts_run \cup ScriptsFor(install_set)
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

\* @command SyncUnit
\* @result SyncResult
\* @port SkillManagerCli.sync_unit
SyncUnit(u) ==
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
    /\ cli_tool_records' = cli_tool_records \cup PackagesFor(surfaced)
    /\ cli_cli_lock' = cli_cli_lock \cup PackagesFor(surfaced)
    /\ cli_skill_scripts_run' = cli_skill_scripts_run \cup ScriptsFor(surfaced)
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

\* @command RemoveUnit
\* @result RemoveResult
\* @port SkillManagerCli.remove_unit
RemoveUnit(u) ==
  IF u \notin cli_store_units
  THEN
    /\ result' = Reject("NOT_INSTALLED")
    /\ UNCHANGED state_vars
  ELSE IF \E dependent \in cli_store_units \ {u}: <<dependent, u>> \in ReferenceEdges
  THEN
    /\ result' = Reject("DEPENDENT_INSTALLED")
    /\ UNCHANGED state_vars
  ELSE
    /\ LET remaining == cli_store_units \ {u}
           orphan_servers == McpServersFor({u}) \ McpServersFor(remaining)
       IN
       /\ cli_store_units' = remaining
       /\ cli_installed_records' = cli_installed_records \ {u}
       /\ cli_lock_units' = cli_lock_units \ {u}
       /\ cli_agent_projections' =
           {projection \in cli_agent_projections : projection[2] # u}
       /\ cli_bindings' = cli_bindings \ {u}
       /\ cli_projection_rows' = cli_projection_rows \ {u}
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
                    cli_projection_conflicts, cli_tool_records, cli_cli_lock,
                    cli_skill_scripts_run, cli_errors,
                    cli_gateway_url_configured, cli_registry_url_configured,
                    cli_gateway_mcp_snapshot, effect_status,
                    effect_continuation, program_halted, always_after_ran,
                    rollback_journal, gateway_disclosures, gateway_errors,
                    gateway_last_init, server_registry_units, server_versions,
                    server_packages, server_authenticated_users >>

\* @command BindDocRepo
\* @result BindingResult
\* @port SkillManagerCli.bind_doc_repo
BindDocRepo(doc) ==
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

\* @command SyncDocRepo
\* @result BindingResult
\* @port SkillManagerCli.sync_doc_repo
SyncDocRepo(doc) ==
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

\* @command SyncHarness
\* @result HarnessResult
\* @port SkillManagerCli.sync_harness
SyncHarness(template, instance) ==
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

\* @command RunEffectProgramFailure
\* @result ProgramResult
\* @port SkillManagerCli.run_effect_program_failure
RunEffectProgramFailure ==
  /\ LET rolled_back_units == rollback_journal
         rolled_back_servers == McpServersFor(rolled_back_units)
         rolled_back_packages == PackagesFor(rolled_back_units)
         rolled_back_scripts == ScriptsFor(rolled_back_units)
     IN
     /\ cli_store_units' = cli_store_units \ rolled_back_units
     /\ cli_installed_records' = cli_installed_records \ rolled_back_units
     /\ cli_lock_units' = cli_lock_units \ rolled_back_units
     /\ cli_agent_projections' =
         {projection \in cli_agent_projections : projection[2] \notin rolled_back_units}
     /\ cli_bindings' = cli_bindings \ rolled_back_units
     /\ cli_projection_rows' = cli_projection_rows \ rolled_back_units
     /\ cli_tool_records' = cli_tool_records \ rolled_back_packages
     /\ cli_cli_lock' = cli_cli_lock \ rolled_back_packages
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

\* @command RunAlwaysAfterCleanup
\* @result ProgramResult
\* @port SkillManagerCli.run_always_after_cleanup
RunAlwaysAfterCleanup ==
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

\* @command RegisterGatewayServer
\* @result GatewayResult
\* @port VirtualMcpGateway.register_server
RegisterGatewayServer(server) ==
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

\* @command DeployGatewayGlobal
\* @result GatewayResult
\* @port VirtualMcpGateway.deploy_global
DeployGatewayGlobal(server) ==
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

\* @command DeployGatewaySession
\* @result GatewayResult
\* @port VirtualMcpGateway.deploy_session
DeployGatewaySession(session, server) ==
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

\* @command DescribeGatewayTool
\* @result GatewayResult
\* @port VirtualMcpGateway.describe_tool
DescribeGatewayTool(session, tool) ==
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

\* @command InvokeGatewayTool
\* @result GatewayResult
\* @port VirtualMcpGateway.invoke_tool
InvokeGatewayTool(session, tool) ==
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

\* @command RegisterProjectManifest
\* @result ProjectResult
\* @port SkillManagerCli.register_project_manifest
RegisterProjectManifest(project) ==
  /\ project \notin project_model.manifests
  /\ project_model' =
      [project_model EXCEPT
        !.manifests = @ \cup {project},
        !.registrations = @ \cup {project},
        !.env_specs = @ \cup ({project} \X ProjectEnvSpecs(project)),
        !.lib_specs = @ \cup ({project} \X ProjectLibSpecs(project))]
  /\ result' = Ok
  /\ UNCHANGED state_vars

CoreNext ==
  \/ \E user \in Users: ServerAuthenticate(user)
  \/ \E user \in Users, unit \in Units, version \in Versions:
      ServerPublishTarball(user, unit, version)
  \/ ServerSearch
  \/ ConfigureRegistry
  \/ EnsureGateway
  \/ \E u \in Units: InstallUnit(u)
  \/ \E u \in Units: SyncUnit(u)
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

Next ==
  \/ CoreNext /\ project_model' = project_model
  \/ \E project \in Projects: RegisterProjectManifest(project)

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

\* @invariant SkillScriptsAreKnownScripts
SkillScriptsAreKnownScripts ==
  cli_skill_scripts_run \subseteq Scripts

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

Spec ==
  Init /\ [][Next]_vars

=============================================================================
