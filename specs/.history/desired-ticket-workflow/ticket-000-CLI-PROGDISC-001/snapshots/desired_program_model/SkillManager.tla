----------------------------- MODULE SkillManager -----------------------------
EXTENDS Naturals, FiniteSets, Sequences, TLC

CONSTANTS
  UnitA, UnitB,
  DocRepoA, HarnessA, InstanceA,
  ProjectA, EnvA, LibA,
  ProfileA,
  ClaudeAgent, CodexAgent, GeminiAgent,
  ServerA, ServerB,
  ToolA, ToolB,
  ScriptA, PackageA,
  SessionA, SessionB,
  UserA, VersionA,
  ParentHomeA, ChildHomeA,
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
Profiles == {ProfileA}
ChildHomes == {ChildHomeA}
SkillManagerHomes == {ParentHomeA, ChildHomeA}

ReferenceEdges == {<<UnitB, UnitA>>}
UnitMcpEdges == {<<UnitA, ServerA>>, <<UnitB, ServerB>>}
ServerToolEdges == {<<ServerA, ToolA>>, <<ServerB, ToolB>>}
UnitScriptEdges == {<<UnitA, ScriptA>>}
UnitPackageEdges == {<<UnitA, PackageA>>, <<UnitB, PackageA>>}
HarnessTemplateEdges == {<<HarnessA, UnitA>>}
ProjectUnitEdges == {<<ProjectA, UnitA>>, <<ProjectA, DocRepoA>>, <<ProjectA, HarnessA>>}
ProjectEnvSpecEdges == {<<ProjectA, EnvA>>}
ProjectLibSpecEdges == {<<ProjectA, LibA>>}
ProjectChildHomeEdges == {<<ProjectA, ChildHomeA>>}
ProjectProfileEdges == {<<ProjectA, ProfileA>>}
ProjectProfileUnitEdges == {<<ProjectA, ProfileA, UnitA>>}
ProjectProfileDocRepoEdges == {<<ProjectA, ProfileA, DocRepoA>>}
ProjectProfileHarnessEdges == {<<ProjectA, ProfileA, HarnessA>>}
ProjectProfileEnvSpecEdges == {<<ProjectA, ProfileA, EnvA>>}
ProjectProfileLibSpecEdges == {<<ProjectA, ProfileA, LibA>>}
ProjectProfileChildHomeEdges == {<<ProjectA, ProfileA, ChildHomeA>>}

CliRootCommand == "skill-manager"

CliTopLevelCommands ==
  {"ads", "bind", "bindings", "cli", "create", "create-account", "deps",
   "env", "gateway", "harness", "install", "list", "lock", "login",
   "onboard", "pm", "policy", "project", "publish", "registry", "rebind",
   "remove", "reset-password", "search", "show", "sync", "unbind",
   "uninstall", "upgrade"}

CliSubcommands ==
  {"ads list", "ads create", "ads delete",
   "bindings list",
   "cli list", "cli show", "cli path",
   "env sync", "env run",
   "gateway up", "gateway down", "gateway status", "gateway set",
   "harness instantiate", "harness rm", "harness list", "harness show",
   "lock status",
   "login logout", "login show",
   "pm install", "pm list", "pm which", "pm setup",
   "policy show", "policy init", "policy path",
   "project register", "project resolve", "project sync", "project remove",
   "project show", "project list", "project profiles", "project profiles list",
   "registry set", "registry status"}

CliCommandAliases ==
  {<<"rm", "remove">>, <<"un", "uninstall">>}

CliCommandCatalog ==
  {CliRootCommand} \cup CliTopLevelCommands \cup CliSubcommands

CliWorkflowCatalog ==
  {"account-auth", "ads-manage", "author-dependencies", "author-unit",
   "bind-projection", "cli-lock-inspect", "discover-installed-units",
   "force-skill-scripts", "gateway-lifecycle", "harness-instantiate",
   "harness-remove", "inspect-unit", "install-git-unit",
   "install-local-unit", "install-registry-unit", "onboard-default-skills",
   "package-manager-bootstrap", "policy-inspect", "project-env",
   "project-profile-resolve", "project-register", "project-resolve",
   "publish-unit", "rebind-projection", "refresh-lockfile",
   "registry-lifecycle", "remove-installed-unit", "skill-scripts",
   "sync-all-units", "sync-from-local-source", "sync-lockfile",
   "sync-one-unit", "unbind-projection", "upgrade-units"}

CliWorkflowCommandLinks ==
  {<<"account-auth", "login">>,
   <<"ads-manage", "ads">>,
   <<"author-dependencies", "create">>,
   <<"author-unit", "create">>,
   <<"bind-projection", "bind">>,
   <<"cli-lock-inspect", "cli">>,
   <<"discover-installed-units", "list">>,
   <<"force-skill-scripts", "sync">>,
   <<"gateway-lifecycle", "gateway">>,
   <<"harness-instantiate", "harness instantiate">>,
   <<"harness-remove", "harness rm">>,
   <<"inspect-unit", "show">>,
   <<"install-git-unit", "install">>,
   <<"install-local-unit", "install">>,
   <<"install-registry-unit", "install">>,
   <<"onboard-default-skills", "onboard">>,
   <<"package-manager-bootstrap", "pm">>,
   <<"policy-inspect", "policy">>,
   <<"project-env", "env">>,
   <<"project-profile-resolve", "project profiles">>,
   <<"project-register", "project register">>,
   <<"project-resolve", "project resolve">>,
   <<"publish-unit", "publish">>,
   <<"rebind-projection", "rebind">>,
   <<"refresh-lockfile", "sync">>,
   <<"registry-lifecycle", "registry">>,
   <<"remove-installed-unit", "remove">>,
   <<"skill-scripts", "install">>,
   <<"sync-all-units", "sync">>,
   <<"sync-from-local-source", "sync">>,
   <<"sync-lockfile", "sync">>,
   <<"sync-one-unit", "sync">>,
   <<"unbind-projection", "unbind">>,
   <<"upgrade-units", "upgrade">>}

SkillDocSurfaces ==
  {"skill-manager-skill", "skill-publisher-skill", "skill-dev-skill"}

SkillManagerSkillWorkflows ==
  {"account-auth", "ads-manage", "bind-projection", "cli-lock-inspect",
   "discover-installed-units", "force-skill-scripts", "gateway-lifecycle",
   "harness-instantiate", "harness-remove", "inspect-unit",
   "install-git-unit", "install-local-unit", "install-registry-unit",
   "onboard-default-skills", "package-manager-bootstrap", "policy-inspect",
   "project-env", "project-profile-resolve", "project-register",
   "project-resolve", "publish-unit", "rebind-projection",
   "refresh-lockfile", "registry-lifecycle", "remove-installed-unit",
   "sync-all-units", "sync-from-local-source", "sync-lockfile",
   "sync-one-unit", "unbind-projection", "upgrade-units"}

SkillPublisherSkillWorkflows ==
  {"author-dependencies", "author-unit", "install-local-unit",
   "publish-unit", "skill-scripts"}

SkillDevSkillWorkflows ==
  {"force-skill-scripts", "install-local-unit", "project-env",
   "sync-from-local-source"}

ExpectedSkillDocCoverage ==
  ({"skill-manager-skill"} \X SkillManagerSkillWorkflows)
    \cup ({"skill-publisher-skill"} \X SkillPublisherSkillWorkflows)
    \cup ({"skill-dev-skill"} \X SkillDevSkillWorkflows)

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

CliDepsFor(units) ==
  PackagesFor(units) \cup ScriptsFor(units)

ChildHomeShimsFor(payload) ==
  ToolsFor(McpServersFor(payload)) \cup PackagesFor(payload) \cup ScriptsFor(payload)

HarnessUnitsFor(template) ==
  {u \in Units : <<template, u>> \in HarnessTemplateEdges}

ProjectEnvSpecs(project) ==
  {env \in Envs : <<project, env>> \in ProjectEnvSpecEdges}

ProjectLibSpecs(project) ==
  {lib \in Libs : <<project, lib>> \in ProjectLibSpecEdges}

ProjectDirectUnits(project) ==
  {u \in Units : <<project, u>> \in ProjectUnitEdges}

ProjectDocRepos(project) ==
  {doc \in DocRepos : <<project, doc>> \in ProjectUnitEdges}

ProjectHarnessTemplates(project) ==
  {template \in HarnessTemplates : <<project, template>> \in ProjectUnitEdges}

ProjectResolvedUnitClosure(project) ==
  DependencyClosure(ProjectDirectUnits(project) \cup
    UNION {HarnessUnitsFor(template) : template \in ProjectHarnessTemplates(project)})

ProjectChildHomePayload(project) ==
  ProjectResolvedUnitClosure(project) \cup
    ProjectDocRepos(project) \cup ProjectHarnessTemplates(project)

ProjectProfiles(project) ==
  {profile \in Profiles : <<project, profile>> \in ProjectProfileEdges}

ProjectProfileEnvSpecs(project, profile) ==
  {env \in Envs : <<project, profile, env>> \in ProjectProfileEnvSpecEdges}

ProjectProfileLibSpecs(project, profile) ==
  {lib \in Libs : <<project, profile, lib>> \in ProjectProfileLibSpecEdges}

ProjectProfileDirectUnits(project, profile) ==
  {u \in Units : <<project, profile, u>> \in ProjectProfileUnitEdges}

ProjectProfileDocRepos(project, profile) ==
  {doc \in DocRepos : <<project, profile, doc>> \in ProjectProfileDocRepoEdges}

ProjectProfileHarnessTemplates(project, profile) ==
  {template \in HarnessTemplates : <<project, profile, template>> \in ProjectProfileHarnessEdges}

ProjectProfileResolvedUnitClosure(project, profile) ==
  DependencyClosure(ProjectProfileDirectUnits(project, profile) \cup
    UNION {HarnessUnitsFor(template) : template \in ProjectProfileHarnessTemplates(project, profile)})

ProjectProfileChildHomePayload(project, profile) ==
  ProjectProfileResolvedUnitClosure(project, profile) \cup
    ProjectProfileDocRepos(project, profile) \cup ProjectProfileHarnessTemplates(project, profile)

ProjectClaimedUnits ==
  {entry[2] : entry \in project_model.resolved_units}
    \cup {entry[2] : entry \in project_model.child_home_units}

ProjectLockedUnits(project) ==
  {entry[2] : entry \in {row \in project_model.resolved_units : row[1] = project}}

ProjectModelInit ==
  [manifests |-> {},
   registrations |-> {},
   locks |-> {},
   resolved_units |-> {},
   doc_bindings |-> {},
   harness_bindings |-> {},
   agent_configs |-> {},
   env_realizations |-> {},
   env_locks |-> {},
   tool_shims |-> {},
   skill_vendors |-> {},
   env_docs |-> {},
   env_specs |-> {},
   lib_specs |-> {},
   profile_declarations |-> {},
   profile_locks |-> {},
   profile_resolved_units |-> {},
   profile_env_specs |-> {},
   profile_lib_specs |-> {},
   profile_child_homes |-> {},
   lib_checkouts |-> {},
   lib_locks |-> {},
   child_homes |-> {},
   project_child_homes |-> {},
   child_home_parents |-> {},
   child_home_harnesses |-> {},
   child_home_agent_configs |-> {},
   child_home_units |-> {},
   child_home_mcp_servers |-> {},
   child_home_tool_shims |-> {},
   cli_command_catalog |-> CliCommandCatalog,
   cli_command_aliases |-> CliCommandAliases,
   cli_workflow_catalog |-> CliWorkflowCatalog,
   cli_workflow_command_links |-> CliWorkflowCommandLinks,
   cli_root_help_topics |-> CliTopLevelCommands,
   cli_command_help_topics |-> CliCommandCatalog,
   cli_skill_doc_topics |-> ExpectedSkillDocCoverage,
   cli_agent_context_topics |-> CliWorkflowCatalog]

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

OkForceScripts ==
  [accepted |-> TRUE, reason |-> "FORCE_SCRIPTS_RERUN"]

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

\* @command InstallUnitForceScripts
\* @result InstallResult
\* @port SkillManagerCli.install_unit_force_scripts
InstallUnitForceScripts(u) ==
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

\* @command SyncUnitForceScripts
\* @result SyncResult
\* @port SkillManagerCli.sync_unit_force_scripts
SyncUnitForceScripts(u) ==
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

\* @command ResolveProjectDependencies
\* @result ProjectResult
\* @port SkillManagerCli.resolve_project_dependencies
ResolveProjectDependencies(project) ==
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

\* @command MaterializeProjectEnv
\* @result ProjectResult
\* @port SkillManagerCli.materialize_project_env
MaterializeProjectEnv(project, env) ==
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

\* @command ResolveProjectLibs
\* @result ProjectResult
\* @port SkillManagerCli.resolve_project_libs
ResolveProjectLibs(project) ==
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

\* @command InstantiateChildHomeFromHarness
\* @result ProjectResult
\* @port SkillManagerCli.instantiate_child_home_from_harness
InstantiateChildHomeFromHarness(home, template) ==
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

\* @command ScaffoldProjectChildHome
\* @result ProjectResult
\* @port SkillManagerCli.scaffold_project_child_home
ScaffoldProjectChildHome(project, home, parent_home) ==
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

\* @command ResolveProjectProfile
\* @result ProjectResult
\* @port SkillManagerCli.resolve_project_profile
ResolveProjectProfile(project, profile, home, parent_home) ==
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

\* @command SyncClaimingProjectChildHomes
\* @result ProjectResult
\* @port SkillManagerCli.sync_claiming_project_child_homes
SyncClaimingProjectChildHomes(u) ==
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

\* @command RenderProgressiveRootHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_progressive_root_help
RenderProgressiveRootHelp ==
  /\ project_model.cli_root_help_topics = CliTopLevelCommands
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command RenderInstallCommandHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_command_help
RenderInstallCommandHelp ==
  /\ "install" \in project_model.cli_command_catalog
  /\ "install" \in project_model.cli_command_help_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command RenderSyncCommandHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_command_help
RenderSyncCommandHelp ==
  /\ "sync" \in project_model.cli_command_catalog
  /\ "sync" \in project_model.cli_command_help_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command RenderProjectProfilesListHelp
\* @result CliDisclosureResult
\* @port SkillManagerCli.render_command_help
RenderProjectProfilesListHelp ==
  /\ "project profiles list" \in project_model.cli_command_catalog
  /\ "project profiles list" \in project_model.cli_command_help_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command ExposeInstallLocalUnitWorkflowDocs
\* @result CliDisclosureResult
\* @port SkillManagerCli.expose_skill_workflow_docs
ExposeInstallLocalUnitWorkflowDocs ==
  /\ "install-local-unit" \in project_model.cli_workflow_catalog
  /\ <<"skill-manager-skill", "install-local-unit">> \in project_model.cli_skill_doc_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command ExposeSkillScriptsWorkflowDocs
\* @result CliDisclosureResult
\* @port SkillManagerCli.expose_skill_workflow_docs
ExposeSkillScriptsWorkflowDocs ==
  /\ "skill-scripts" \in project_model.cli_workflow_catalog
  /\ <<"skill-publisher-skill", "skill-scripts">> \in project_model.cli_skill_doc_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command ExposeProjectEnvWorkflowDocs
\* @result CliDisclosureResult
\* @port SkillManagerCli.expose_skill_workflow_docs
ExposeProjectEnvWorkflowDocs ==
  /\ "project-env" \in project_model.cli_workflow_catalog
  /\ <<"skill-manager-skill", "project-env">> \in project_model.cli_skill_doc_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command EmitSyncOneUnitAgentContext
\* @result CliDisclosureResult
\* @port SkillManagerCli.emit_agent_workflow_context
EmitSyncOneUnitAgentContext ==
  /\ "sync-one-unit" \in project_model.cli_workflow_catalog
  /\ "sync-one-unit" \in project_model.cli_agent_context_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

\* @command EmitProjectEnvAgentContext
\* @result CliDisclosureResult
\* @port SkillManagerCli.emit_agent_workflow_context
EmitProjectEnvAgentContext ==
  /\ "project-env" \in project_model.cli_workflow_catalog
  /\ "project-env" \in project_model.cli_agent_context_topics
  /\ result' = Ok
  /\ project_model' = project_model
  /\ UNCHANGED state_vars

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

Next ==
  \/ CoreNext /\ project_model' = project_model
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

CliDisclosureSpec ==
  Init /\ [][CliDisclosureNext]_vars

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
