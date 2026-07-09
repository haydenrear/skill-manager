----------------------------- MODULE External -----------------------------
\* Public, harness-drivable view over the Internal skill-manager program
\* model. Every action here is something a user or Test Graph harness can
\* trigger from outside the modeled internals: skill-manager CLI commands
\* (install / sync / remove / bind / harness / publish / search / registry /
\* gateway / project), plus explicit negative CLI error paths. Hidden
\* internal progress (registry-side authentication and publication, gateway
\* catalog registration, effect-program rollback, claiming-child-home sync,
\* CLI disclosure rendering) advances through HiddenInternalProgress and is
\* asserted only through externally visible results.
EXTENDS Internal

VARIABLES serviceHealth, lastServiceRoute, lastExternalAction

Services == {
  "skill-manager-cli",
  "registry-server",
  "virtual-mcp-gateway",
  "store-filesystem",
  "agent-homes",
  "git-source"
}

\* Service routes: which production boundaries participate in each public
\* behavior. These are model data recorded into generated cases/evidence.
InstallRoute == <<"skill-manager-cli", "git-source", "store-filesystem", "agent-homes">>
SyncRoute == <<"skill-manager-cli", "git-source", "store-filesystem", "agent-homes">>
RemoveRoute == <<"skill-manager-cli", "store-filesystem", "agent-homes">>
DocBindRoute == <<"skill-manager-cli", "git-source", "store-filesystem", "agent-homes">>
HarnessRoute == <<"skill-manager-cli", "store-filesystem", "agent-homes">>
PublishRoute == <<"skill-manager-cli", "registry-server">>
SearchRoute == <<"skill-manager-cli", "registry-server">>
RegistryConfigRoute == <<"skill-manager-cli", "registry-server">>
GatewayConfigRoute == <<"skill-manager-cli", "virtual-mcp-gateway">>
GatewayDeployRoute == <<"skill-manager-cli", "virtual-mcp-gateway">>
GatewayInvokeRoute == <<"skill-manager-cli", "virtual-mcp-gateway">>
ProjectRegisterRoute == <<"skill-manager-cli", "store-filesystem">>
ProjectResolveRoute == <<"skill-manager-cli", "store-filesystem", "agent-homes">>
ProjectEnvRoute == <<"skill-manager-cli", "store-filesystem">>
ProjectChildHomeRoute == <<"skill-manager-cli", "store-filesystem", "agent-homes">>
VenvRoute == <<"skill-manager-cli", "store-filesystem", "agent-homes">>

SeqToSet(seq) == {seq[i] : i \in 1..Len(seq)}

ExternalVars == <<InternalVars, serviceHealth, lastServiceRoute, lastExternalAction>>

ExternalInit ==
  /\ InternalInit
  /\ serviceHealth = [service \in Services |-> "up"]
  /\ lastServiceRoute = [services |-> <<>>]
  /\ lastExternalAction = [name |-> "Init", params |-> NoParams]

ServicesAvailable(route) ==
  \A service \in SeqToSet(route) : serviceHealth[service] = "up"

MarkExternal(actionName, params, route) ==
  /\ serviceHealth' = serviceHealth
  /\ lastServiceRoute' = [services |-> route]
  /\ lastExternalAction' = [name |-> actionName, params |-> params]

RejectExternally(reason) ==
  /\ UNCHANGED state_vars
  /\ project_model' = project_model
  /\ result' = Reject(reason)

\* ---------------------------------------------------------------------------
\* Install / sync / remove / bind / harness (skill-manager CLI store surface)
\* ---------------------------------------------------------------------------

SubmitInstallUnit(u) ==
  /\ ServicesAvailable(InstallRoute)
  /\ u \notin cli_store_units
  /\ DependencyClosure({u}) \subseteq server_registry_units
  /\ InstallUnit(u)
  /\ MarkExternal("SubmitInstallUnit",
       [unit |-> u, route |-> InstallRoute], InstallRoute)

SubmitInstallUnitForceScripts(u) ==
  /\ ServicesAvailable(InstallRoute)
  /\ u \notin cli_store_units
  /\ DependencyClosure({u}) \subseteq server_registry_units
  /\ InstallUnitForceScripts(u)
  /\ MarkExternal("SubmitInstallUnitForceScripts",
       [unit |-> u, route |-> InstallRoute], InstallRoute)

SubmitSyncUnit(u) ==
  /\ ServicesAvailable(SyncRoute)
  /\ u \in cli_store_units
  /\ DependencyClosure({u}) \subseteq server_registry_units
  /\ SyncUnit(u)
  /\ MarkExternal("SubmitSyncUnit",
       [unit |-> u, route |-> SyncRoute], SyncRoute)

SubmitSyncUnitForceScripts(u) ==
  /\ ServicesAvailable(SyncRoute)
  /\ u \in cli_store_units
  /\ DependencyClosure({u}) \subseteq server_registry_units
  /\ SyncUnitForceScripts(u)
  /\ MarkExternal("SubmitSyncUnitForceScripts",
       [unit |-> u, route |-> SyncRoute], SyncRoute)

SubmitSyncUninstalledUnit(u) ==
  /\ ServicesAvailable(SyncRoute)
  /\ u \notin cli_store_units
  /\ SyncUnit(u)
  /\ MarkExternal("SubmitSyncUninstalledUnit",
       [unit |-> u, route |-> SyncRoute], SyncRoute)

SubmitRemoveUnit(u) ==
  /\ ServicesAvailable(RemoveRoute)
  /\ u \in cli_store_units
  /\ ~(\E dependent \in cli_store_units \ {u}: <<dependent, u>> \in ReferenceEdges)
  /\ u \notin ProjectClaimedUnits
  /\ RemoveUnit(u)
  /\ MarkExternal("SubmitRemoveUnit",
       [unit |-> u, route |-> RemoveRoute], RemoveRoute)

SubmitRemoveUnknownUnit(u) ==
  /\ ServicesAvailable(RemoveRoute)
  /\ u \notin cli_store_units
  /\ RemoveUnit(u)
  /\ MarkExternal("SubmitRemoveUnknownUnit",
       [unit |-> u, route |-> RemoveRoute], RemoveRoute)

SubmitBindDocRepo(doc) ==
  /\ ServicesAvailable(DocBindRoute)
  /\ BindDocRepo(doc)
  /\ MarkExternal("SubmitBindDocRepo",
       [doc |-> doc, route |-> DocBindRoute], DocBindRoute)

SubmitSyncDocRepo(doc) ==
  /\ ServicesAvailable(DocBindRoute)
  /\ doc \in cli_doc_repos
  /\ SyncDocRepo(doc)
  /\ MarkExternal("SubmitSyncDocRepo",
       [doc |-> doc, route |-> DocBindRoute], DocBindRoute)

SubmitSyncUnknownDocRepo(doc) ==
  /\ ServicesAvailable(DocBindRoute)
  /\ doc \notin cli_doc_repos
  /\ SyncDocRepo(doc)
  /\ MarkExternal("SubmitSyncUnknownDocRepo",
       [doc |-> doc, route |-> DocBindRoute], DocBindRoute)

SubmitSyncHarness(template, instance) ==
  /\ ServicesAvailable(HarnessRoute)
  /\ HarnessUnitsFor(template) \subseteq cli_store_units
  /\ SyncHarness(template, instance)
  /\ MarkExternal("SubmitSyncHarness",
       [template |-> template, instance |-> instance, route |-> HarnessRoute],
       HarnessRoute)

\* ---------------------------------------------------------------------------
\* Registry server surface (publish / search / configure)
\* ---------------------------------------------------------------------------

SubmitPublishTarball(user, unit, version) ==
  /\ ServicesAvailable(PublishRoute)
  /\ user \in server_authenticated_users
  /\ ServerPublishTarball(user, unit, version)
  /\ MarkExternal("SubmitPublishTarball",
       [user |-> user, unit |-> unit, version |-> version, route |-> PublishRoute],
       PublishRoute)

SubmitPublishUnauthenticated(user, unit, version) ==
  /\ ServicesAvailable(PublishRoute)
  /\ user \notin server_authenticated_users
  /\ RejectExternally("AUTHENTICATION_REQUIRED")
  /\ MarkInternal("SubmitPublishUnauthenticated",
       [user |-> user, unit |-> unit, version |-> version])
  /\ MarkExternal("SubmitPublishUnauthenticated",
       [user |-> user, unit |-> unit, version |-> version, route |-> PublishRoute],
       PublishRoute)

SubmitSearchRegistry ==
  /\ ServicesAvailable(SearchRoute)
  /\ ServerSearch
  /\ MarkExternal("SubmitSearchRegistry",
       [route |-> SearchRoute], SearchRoute)

SubmitConfigureRegistry ==
  /\ ServicesAvailable(RegistryConfigRoute)
  /\ ConfigureRegistry
  /\ MarkExternal("SubmitConfigureRegistry",
       [route |-> RegistryConfigRoute], RegistryConfigRoute)

\* ---------------------------------------------------------------------------
\* Virtual MCP gateway surface (configure / deploy / disclose / invoke)
\* ---------------------------------------------------------------------------

SubmitEnsureGateway ==
  /\ ServicesAvailable(GatewayConfigRoute)
  /\ EnsureGateway
  /\ MarkExternal("SubmitEnsureGateway",
       [route |-> GatewayConfigRoute], GatewayConfigRoute)

SubmitDeployGatewayGlobal(server) ==
  /\ ServicesAvailable(GatewayDeployRoute)
  /\ server \in gateway_catalog
  /\ DeployGatewayGlobal(server)
  /\ MarkExternal("SubmitDeployGatewayGlobal",
       [server |-> server, route |-> GatewayDeployRoute], GatewayDeployRoute)

SubmitDeployGatewaySession(session, server) ==
  /\ ServicesAvailable(GatewayDeployRoute)
  /\ server \in gateway_catalog
  /\ DeployGatewaySession(session, server)
  /\ MarkExternal("SubmitDeployGatewaySession",
       [session |-> session, server |-> server, route |-> GatewayDeployRoute],
       GatewayDeployRoute)

SubmitDescribeGatewayTool(session, tool) ==
  /\ ServicesAvailable(GatewayInvokeRoute)
  /\ tool \in VisibleTools
  /\ DescribeGatewayTool(session, tool)
  /\ MarkExternal("SubmitDescribeGatewayTool",
       [session |-> session, tool |-> tool, route |-> GatewayInvokeRoute],
       GatewayInvokeRoute)

SubmitInvokeGatewayTool(session, tool) ==
  /\ ServicesAvailable(GatewayInvokeRoute)
  /\ <<session, tool>> \in gateway_disclosures
  /\ tool \in VisibleTools
  /\ InvokeGatewayTool(session, tool)
  /\ MarkExternal("SubmitInvokeGatewayTool",
       [session |-> session, tool |-> tool, route |-> GatewayInvokeRoute],
       GatewayInvokeRoute)

SubmitInvokeUndisclosedTool(session, tool) ==
  /\ ServicesAvailable(GatewayInvokeRoute)
  /\ <<session, tool>> \notin gateway_disclosures
  /\ InvokeGatewayTool(session, tool)
  /\ MarkExternal("SubmitInvokeUndisclosedTool",
       [session |-> session, tool |-> tool, route |-> GatewayInvokeRoute],
       GatewayInvokeRoute)

\* ---------------------------------------------------------------------------
\* Project lifecycle CLI (skill-manager project / env)
\* ---------------------------------------------------------------------------

RunProjectRegister(project) ==
  /\ ServicesAvailable(ProjectRegisterRoute)
  /\ project \notin project_model.manifests
  /\ RegisterProjectManifest(project)
  /\ MarkExternal("RunProjectRegister",
       [project |-> project, route |-> ProjectRegisterRoute], ProjectRegisterRoute)

RunProjectResolve(project) ==
  /\ ServicesAvailable(ProjectResolveRoute)
  /\ project \in project_model.manifests
  /\ ResolveProjectDependencies(project)
  /\ MarkExternal("RunProjectResolve",
       [project |-> project, route |-> ProjectResolveRoute], ProjectResolveRoute)

RunProjectEnvMaterialize(project, env) ==
  /\ ServicesAvailable(ProjectEnvRoute)
  /\ project \in project_model.registrations
  /\ <<project, env>> \in project_model.env_specs
  /\ project \in project_model.locks
  /\ MaterializeProjectEnv(project, env)
  /\ MarkExternal("RunProjectEnvMaterialize",
       [project |-> project, env |-> env, route |-> ProjectEnvRoute], ProjectEnvRoute)

RunProjectLibsResolve(project) ==
  /\ ServicesAvailable(ProjectResolveRoute)
  /\ project \in project_model.registrations
  \* Production has no libs-only resolve: `project resolve --resolve-libs`
  \* always re-runs the full dependency resolution before materializing
  \* libs. The harness can therefore only drive libs resolution on a
  \* project whose dependency payload is already resolved and surfaced,
  \* where the re-resolve is a visible no-op and only libs change.
  /\ project \in project_model.locks
  /\ ProjectResolvedUnitClosure(project) \subseteq cli_store_units
  /\ ProjectDocRepos(project) \subseteq cli_doc_repos
  /\ ProjectHarnessTemplates(project) \subseteq cli_harness_templates
  /\ ResolveProjectLibs(project)
  /\ MarkExternal("RunProjectLibsResolve",
       [project |-> project, route |-> ProjectResolveRoute], ProjectResolveRoute)

RunScaffoldProjectChildHome(project, home, parent_home) ==
  /\ ServicesAvailable(ProjectChildHomeRoute)
  /\ project \in project_model.registrations
  /\ <<project, home>> \in ProjectChildHomeEdges
  /\ parent_home \in SkillManagerHomes
  /\ parent_home # home
  /\ ProjectResolvedUnitClosure(project) \subseteq cli_store_units
  /\ ProjectDocRepos(project) \subseteq cli_doc_repos
  /\ ProjectHarnessTemplates(project) \subseteq cli_harness_templates
  /\ ScaffoldProjectChildHome(project, home, parent_home)
  /\ MarkExternal("RunScaffoldProjectChildHome",
       [project |-> project, home |-> home, parent_home |-> parent_home,
        route |-> ProjectChildHomeRoute],
       ProjectChildHomeRoute)

RunProjectProfileResolve(project, profile, home, parent_home) ==
  /\ ServicesAvailable(ProjectChildHomeRoute)
  /\ project \in project_model.registrations
  /\ <<project, profile>> \in project_model.profile_declarations
  /\ <<project, profile, home>> \in ProjectProfileChildHomeEdges
  /\ parent_home \in SkillManagerHomes
  /\ parent_home # home
  /\ ResolveProjectProfile(project, profile, home, parent_home)
  /\ MarkExternal("RunProjectProfileResolve",
       [project |-> project, profile |-> profile, home |-> home,
        parent_home |-> parent_home, route |-> ProjectChildHomeRoute],
       ProjectChildHomeRoute)

\* ---------------------------------------------------------------------------
\* skill-manager venv surface: content-addressed store writes, version pins
\* (positive and ancestry-conflict paths), venv activation, agent shims, and
\* hook materialization.
\* ---------------------------------------------------------------------------

RunStoreUnitVersion(u, sha) ==
  /\ ServicesAvailable(VenvRoute)
  /\ u \in cli_store_units
  /\ StoreUnitVersion(u, sha)
  /\ MarkExternal("RunStoreUnitVersion",
       [unit |-> u, sha |-> sha, route |-> VenvRoute], VenvRoute)

RunPinUnitVersion(project, u, sha) ==
  /\ ServicesAvailable(VenvRoute)
  /\ project \in project_model.registrations
  /\ <<u, sha>> \in project_model.store_versions
  /\ ~(\E pin \in project_model.version_pins:
        /\ pin[2] = u
        /\ pin[1] # project
        /\ ~AncestryRelated(pin[3], sha))
  /\ PinProjectUnitVersion(project, u, sha)
  /\ MarkExternal("RunPinUnitVersion",
       [project |-> project, unit |-> u, sha |-> sha, route |-> VenvRoute],
       VenvRoute)

RunPinConflictingUnitVersion(project, u, sha) ==
  /\ ServicesAvailable(VenvRoute)
  /\ project \in project_model.registrations
  /\ <<u, sha>> \in project_model.store_versions
  /\ \E pin \in project_model.version_pins:
      /\ pin[2] = u
      /\ pin[1] # project
      /\ ~AncestryRelated(pin[3], sha)
  /\ PinProjectUnitVersion(project, u, sha)
  /\ MarkExternal("RunPinConflictingUnitVersion",
       [project |-> project, unit |-> u, sha |-> sha, route |-> VenvRoute],
       VenvRoute)

RunActivateProjectVenv(project, home, parent_home) ==
  /\ ServicesAvailable(VenvRoute)
  /\ project \in project_model.registrations
  /\ <<project, home>> \in ProjectChildHomeEdges
  /\ parent_home \in SkillManagerHomes
  /\ parent_home # home
  /\ ActivateProjectVenv(project, home, parent_home)
  /\ MarkExternal("RunActivateProjectVenv",
       [project |-> project, home |-> home, parent_home |-> parent_home,
        route |-> VenvRoute],
       VenvRoute)

RunMaterializeVenvAgentShims(home) ==
  /\ ServicesAvailable(VenvRoute)
  /\ \E project \in Projects: <<project, home>> \in project_model.venv_activations
  /\ MaterializeVenvAgentShims(home)
  /\ MarkExternal("RunMaterializeVenvAgentShims",
       [home |-> home, route |-> VenvRoute], VenvRoute)

RunMaterializeVenvHooks(home) ==
  /\ ServicesAvailable(VenvRoute)
  /\ \E project \in Projects: <<project, home>> \in project_model.venv_activations
  /\ MaterializeVenvHooks(home)
  /\ MarkExternal("RunMaterializeVenvHooks",
       [home |-> home, route |-> VenvRoute], VenvRoute)

\* ---------------------------------------------------------------------------
\* Hidden internal progress: registry authentication and publication, gateway
\* catalog registration, effect-program rollback/cleanup, claiming child-home
\* sync, harness child homes, and CLI disclosure rendering. The harness never
\* drives these directly between external actions; it only observes their
\* externally visible results.
\* ---------------------------------------------------------------------------

HiddenInternalProgress ==
  /\ InternalImplNext
  /\ MarkInternal("HiddenInternalProgress", NoParams)
  /\ MarkExternal("HiddenInternalProgress", NoParams, <<>>)

ExternalNext ==
  \/ \E u \in Units: SubmitInstallUnit(u)
  \/ \E u \in Units: SubmitInstallUnitForceScripts(u)
  \/ \E u \in Units: SubmitSyncUnit(u)
  \/ \E u \in Units: SubmitSyncUnitForceScripts(u)
  \/ \E u \in Units: SubmitSyncUninstalledUnit(u)
  \/ \E u \in Units: SubmitRemoveUnit(u)
  \/ \E u \in Units: SubmitRemoveUnknownUnit(u)
  \/ \E doc \in DocRepos: SubmitBindDocRepo(doc)
  \/ \E doc \in DocRepos: SubmitSyncDocRepo(doc)
  \/ \E doc \in DocRepos: SubmitSyncUnknownDocRepo(doc)
  \/ \E template \in HarnessTemplates, instance \in HarnessInstances:
      SubmitSyncHarness(template, instance)
  \/ \E user \in Users, unit \in Units, version \in Versions:
      SubmitPublishTarball(user, unit, version)
  \/ \E user \in Users, unit \in Units, version \in Versions:
      SubmitPublishUnauthenticated(user, unit, version)
  \/ SubmitSearchRegistry
  \/ SubmitConfigureRegistry
  \/ SubmitEnsureGateway
  \/ \E server \in Servers: SubmitDeployGatewayGlobal(server)
  \/ \E session \in Sessions, server \in Servers:
      SubmitDeployGatewaySession(session, server)
  \/ \E session \in Sessions, tool \in Tools:
      SubmitDescribeGatewayTool(session, tool)
  \/ \E session \in Sessions, tool \in Tools:
      SubmitInvokeGatewayTool(session, tool)
  \/ \E session \in Sessions, tool \in Tools:
      SubmitInvokeUndisclosedTool(session, tool)
  \/ \E project \in Projects: RunProjectRegister(project)
  \/ \E project \in Projects: RunProjectResolve(project)
  \/ \E project \in Projects, env \in Envs: RunProjectEnvMaterialize(project, env)
  \/ \E project \in Projects: RunProjectLibsResolve(project)
  \/ \E project \in Projects, home \in ChildHomes, parent_home \in SkillManagerHomes:
      RunScaffoldProjectChildHome(project, home, parent_home)
  \/ \E project \in Projects, profile \in Profiles, home \in ChildHomes,
        parent_home \in SkillManagerHomes:
      RunProjectProfileResolve(project, profile, home, parent_home)
  \/ \E u \in Units, sha \in Shas: RunStoreUnitVersion(u, sha)
  \/ \E project \in Projects, u \in Units, sha \in Shas:
      RunPinUnitVersion(project, u, sha)
  \/ \E project \in Projects, u \in Units, sha \in Shas:
      RunPinConflictingUnitVersion(project, u, sha)
  \/ \E project \in Projects, home \in ChildHomes, parent_home \in SkillManagerHomes:
      RunActivateProjectVenv(project, home, parent_home)
  \/ \E home \in ChildHomes: RunMaterializeVenvAgentShims(home)
  \/ \E home \in ChildHomes: RunMaterializeVenvHooks(home)
  \/ HiddenInternalProgress

ExternalInvariant ==
  /\ InternalInvariant
  /\ \A service \in Services : serviceHealth[service] = "up"
  /\ \A service \in SeqToSet(lastServiceRoute.services) : service \in Services

\* Bounded case-generation envelope: union of the internal feature-slice
\* envelopes so every public action stays reachable while orthogonal deep
\* interleavings stay pruned.
ExternalCaseEnvelope ==
  \/ CliStoreCaseEnvelope
  \/ GatewayCaseEnvelope
  \/ ServerRegistryCaseEnvelope
  \/ ProjectCaseEnvelope
  \/ CliDisclosureCaseEnvelope
  \/ VenvCaseEnvelope

ExternalSpec == ExternalInit /\ [][ExternalNext]_ExternalVars
Invariant == ExternalInvariant

=============================================================================
