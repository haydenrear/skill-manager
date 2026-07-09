------------------------------- MODULE Core -------------------------------
\* Core = shared constants and constant-level helpers for the skill-manager
\* program model.
\* Internal.tla holds the accepted whole-program state machine.
\* External.tla holds the public harness-drivable view over Internal
\* (added in a later phase).

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
  ShaA, ShaB, ShaC,
  NoReason

NoParams == <<>>

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

\* Content-addressed skill versions (git hashes) for the skill-manager venv
\* workflow. Ticket SMVENV-001 lands the store itself; ancestry edges and
\* hooks arrive with the later pin/hook tickets.
Shas == {ShaA, ShaB, ShaC}

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
   "remove", "reset-password", "search", "show", "store", "sync", "unbind",
   "uninstall", "upgrade"}

CliSubcommands ==
  {"ads list", "ads create", "ads delete",
   "bindings list", "bindings show",
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
   "registry set", "registry status",
   "store add"}

CliCommandAliases ==
  {<<"ls", "list">>, <<"rm", "remove">>, <<"un", "uninstall">>}

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
   <<"project-env", "env sync">>,
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
   store_versions |-> {},
   store_latest |-> {},
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

Ok ==
  [accepted |-> TRUE, reason |-> NoReason]

OkForceScripts ==
  [accepted |-> TRUE, reason |-> "FORCE_SCRIPTS_RERUN"]

Reject(reason) ==
  [accepted |-> FALSE, reason |-> reason]

=============================================================================
