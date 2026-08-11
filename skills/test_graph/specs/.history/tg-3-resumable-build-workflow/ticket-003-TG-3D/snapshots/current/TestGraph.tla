----------------------------- MODULE TestGraph -----------------------------
EXTENDS Naturals, FiniteSets, Sequences, TLC

\* Accepted whole-program model for the test-graph skill. The product is a
\* validation DAG runner, so graph definitions, script metadata, DSL overlays,
\* dependency resolution, node execution, context flow, and report emission are
\* program semantics. Package paths and command recipes live in the manifest.

CONSTANTS
  Graphs,
  Nodes,
  Packages,
  SourceNodes,
  NoReason

VARIABLES
  scaffolded,
  declared_graphs,
  explicit_nodes,
  script_deps,
  described_nodes,
  dsl_deps,
  overlays,
  resolved_nodes,
  planned_graphs,
  plan_docs,
  active_graphs,
  passed_nodes,
  terminal_nodes,
  envelopes,
  context_items,
  input_contexts,
  rerunnable_nodes,
  resumed_nodes,
  single_node_reruns,
  run_reports,
  package_catalog,
  result

vars ==
  << scaffolded, declared_graphs, explicit_nodes, script_deps, described_nodes,
     dsl_deps, overlays, resolved_nodes, planned_graphs, plan_docs,
     active_graphs, passed_nodes, terminal_nodes, envelopes, context_items,
     input_contexts, rerunnable_nodes, resumed_nodes, single_node_reruns,
     run_reports, package_catalog, result >>

ticket_state_vars ==
  << input_contexts, rerunnable_nodes, resumed_nodes, single_node_reruns >>

AvailableFor(g) ==
  SourceNodes

ScriptDepsOf(n) ==
  script_deps[n]

DslDepsOf(g, n) ==
  dsl_deps[g][n]

MergedDeps(g, n) == ScriptDepsOf(n) \cup DslDepsOf(g, n)

DepsForGraph(g) ==
  [n \in SourceNodes |-> MergedDeps(g, n)]

BoundedPathsIn(ns) ==
  UNION {[1..len -> ns] : len \in 2..(Cardinality(ns) + 1)}

PathUsesDeps(deps, path) ==
  \A i \in 1..(Len(path) - 1):
    path[i + 1] \in deps[path[i]]

ReachableByDepsWithin(ns, deps, from, to) ==
  \E path \in BoundedPathsIn(ns):
    /\ path[1] = from
    /\ path[Len(path)] = to
    /\ PathUsesDeps(deps, path)

AcyclicDepsWithin(ns, deps) ==
  \A n \in ns:
    ~ReachableByDepsWithin(ns, deps, n, n)

GraphDependencyClosed(g, ns) ==
  /\ ns \subseteq SourceNodes
  /\ explicit_nodes[g] \subseteq ns
  /\ \A n \in ns:
      MergedDeps(g, n) \subseteq ns

Init ==
  /\ scaffolded = FALSE
  /\ declared_graphs = {}
  /\ explicit_nodes = [g \in Graphs |-> {}]
  /\ script_deps = [n \in SourceNodes |-> {}]
  /\ described_nodes = {}
  /\ dsl_deps = [g \in Graphs |-> [n \in SourceNodes |-> {}]]
  /\ overlays = [g \in Graphs |-> {}]
  /\ resolved_nodes = [g \in Graphs |-> {}]
  /\ planned_graphs = {}
  /\ plan_docs = {}
  /\ active_graphs = {}
  /\ passed_nodes = [g \in Graphs |-> {}]
  /\ terminal_nodes = [g \in Graphs |-> {}]
  /\ envelopes = [g \in Graphs |-> {}]
  /\ context_items = [g \in Graphs |-> {}]
  /\ input_contexts = [g \in Graphs |-> {}]
  /\ rerunnable_nodes = SourceNodes
  /\ resumed_nodes = [g \in Graphs |-> {}]
  /\ single_node_reruns = [g \in Graphs |-> {}]
  /\ run_reports = {}
  /\ package_catalog = {}
  /\ result = [accepted |-> TRUE, reason |-> NoReason]

\* @command ScaffoldProject
\* @result WorkflowResult
\* @port TestGraphProgramPort.scaffold_project
ScaffoldProject ==
  /\ scaffolded = FALSE
  /\ scaffolded' = TRUE
  /\ package_catalog' = Packages
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << declared_graphs, explicit_nodes, script_deps, described_nodes,
                  dsl_deps, overlays, resolved_nodes, planned_graphs,
                  plan_docs, active_graphs, passed_nodes, terminal_nodes,
                  envelopes, context_items, run_reports >>
  /\ UNCHANGED ticket_state_vars

\* @command RegisterGraph
\* @result WorkflowResult
\* @port TestGraphProgramPort.register_graph
RegisterGraph(g) ==
  /\ scaffolded
  /\ g \in Graphs
  /\ g \notin declared_graphs
  /\ declared_graphs' = declared_graphs \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, explicit_nodes, script_deps, described_nodes,
                  dsl_deps, overlays,
                  resolved_nodes, planned_graphs, plan_docs, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command AddExplicitNode
\* @result WorkflowResult
\* @port TestGraphProgramPort.add_explicit_node
AddExplicitNode(g, n) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ n \in AvailableFor(g)
  /\ explicit_nodes' = [explicit_nodes EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, script_deps, described_nodes,
                  dsl_deps, overlays, resolved_nodes, planned_graphs,
                  plan_docs, active_graphs, passed_nodes, terminal_nodes,
                  envelopes, context_items, run_reports, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command AddScriptDependency
\* @result WorkflowResult
\* @port TestGraphProgramPort.add_script_dependency
AddScriptDependency(n, d) ==
  /\ scaffolded
  /\ n \in SourceNodes
  /\ d \in SourceNodes
  /\ n /= d
  /\ n \notin described_nodes
  /\ script_deps' = [script_deps EXCEPT ![n] = @ \cup {d}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  dsl_deps, overlays, resolved_nodes, planned_graphs,
                  plan_docs, active_graphs, passed_nodes, terminal_nodes,
                  envelopes, context_items, run_reports, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command DescribeNode
\* @result WorkflowResult
\* @port TestGraphProgramPort.describe_node
DescribeNode(n) ==
  /\ scaffolded
  /\ n \in SourceNodes
  /\ described_nodes' = described_nodes \cup {n}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  dsl_deps, overlays, resolved_nodes, planned_graphs,
                  plan_docs, active_graphs, passed_nodes, terminal_nodes,
                  envelopes, context_items, run_reports, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command SetNodeRerunDisabled
\* @result WorkflowResult
\* @port TestGraphProgramPort.set_node_rerun_disabled
SetNodeRerunDisabled(n) ==
  /\ scaffolded
  /\ n \in SourceNodes
  /\ n \notin described_nodes
  /\ rerunnable_nodes' = rerunnable_nodes \ {n}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, envelopes, context_items, input_contexts,
                  resumed_nodes, single_node_reruns, run_reports,
                  package_catalog >>

\* @command ApplyDslOverlay
\* @result WorkflowResult
\* @port TestGraphProgramPort.apply_dsl_overlay
ApplyDslOverlay(g, n, d) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ n \in explicit_nodes[g]
  /\ d \in SourceNodes
  /\ n /= d
  /\ dsl_deps' = [dsl_deps EXCEPT ![g][n] = @ \cup {d}]
  /\ overlays' = [overlays EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, resolved_nodes, planned_graphs, plan_docs,
                  active_graphs, passed_nodes, terminal_nodes, envelopes,
                  context_items, run_reports, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command ResolveNode
\* @result WorkflowResult
\* @port TestGraphProgramPort.resolve_node
ResolveNode(g, n) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ n \in described_nodes
  /\ n \in SourceNodes
  /\ (n \in explicit_nodes[g]
      \/ \E m \in explicit_nodes[g] \cup resolved_nodes[g]:
           n \in MergedDeps(g, m))
  /\ resolved_nodes' = [resolved_nodes EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, planned_graphs,
                  plan_docs, active_graphs, passed_nodes, terminal_nodes,
                  envelopes, context_items, run_reports, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command PlanGraph
\* @result WorkflowResult
\* @port TestGraphProgramPort.plan_graph
PlanGraph(g) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ explicit_nodes[g] /= {}
  /\ GraphDependencyClosed(g, resolved_nodes[g])
  /\ AcyclicDepsWithin(resolved_nodes[g], DepsForGraph(g))
  /\ \A n \in resolved_nodes[g]: n \in described_nodes
  /\ planned_graphs' = planned_graphs \cup {g}
  /\ plan_docs' = plan_docs \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  active_graphs, passed_nodes, terminal_nodes, envelopes,
                  context_items, run_reports, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command StartRun
\* @result WorkflowResult
\* @port TestGraphProgramPort.start_run
StartRun(g) ==
  /\ scaffolded
  /\ g \in planned_graphs
  /\ g \notin active_graphs
  /\ active_graphs' = active_graphs \cup {g}
  /\ passed_nodes' = [passed_nodes EXCEPT ![g] = {}]
  /\ terminal_nodes' = [terminal_nodes EXCEPT ![g] = {}]
  /\ envelopes' = [envelopes EXCEPT ![g] = {}]
  /\ context_items' = [context_items EXCEPT ![g] = {}]
  /\ input_contexts' = [input_contexts EXCEPT ![g] = {}]
  /\ run_reports' = run_reports \ {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, package_catalog,
                  rerunnable_nodes >>
  /\ resumed_nodes' = [resumed_nodes EXCEPT ![g] = {}]
  /\ single_node_reruns' = [single_node_reruns EXCEPT ![g] = {}]

\* @command ResumeRunFromBuild
\* @result WorkflowResult
\* @port TestGraphProgramPort.resume_run_from_build
ResumeRunFromBuild(g, n) ==
  /\ scaffolded
  /\ g \in planned_graphs
  /\ g \notin active_graphs
  /\ n \in resolved_nodes[g]
  /\ n \in input_contexts[g]
  /\ n \in rerunnable_nodes
  /\ (n \in terminal_nodes[g] \/ g \in run_reports)
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ MergedDeps(g, n) \subseteq context_items[g]
  /\ active_graphs' = active_graphs \cup {g}
  /\ resumed_nodes' = [resumed_nodes EXCEPT ![g] = input_contexts[g]]
  /\ run_reports' = run_reports \ {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, passed_nodes, terminal_nodes,
                  envelopes, context_items, input_contexts, rerunnable_nodes,
                  single_node_reruns,
                  package_catalog >>

\* @command RunOnlyNodeFromBuild
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_only_node_from_build
RunOnlyNodeFromBuild(g, n) ==
  /\ scaffolded
  /\ g \in planned_graphs
  /\ g \notin active_graphs
  /\ n \in resolved_nodes[g]
  /\ n \in input_contexts[g]
  /\ n \in rerunnable_nodes
  /\ (n \in terminal_nodes[g] \/ g \in run_reports)
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ MergedDeps(g, n) \subseteq context_items[g]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ input_contexts' = [input_contexts EXCEPT ![g] = @ \cup {n}]
  /\ single_node_reruns' = [single_node_reruns EXCEPT ![g] = @ \cup {n}]
  /\ run_reports' = run_reports \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, context_items, rerunnable_nodes,
                  resumed_nodes, package_catalog >>

\* @command RunNodePass
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_node_pass
RunNodePass(g, n) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ n \in resolved_nodes[g]
  /\ (n \notin envelopes[g] \/ n \in resumed_nodes[g])
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ passed_nodes' = [passed_nodes EXCEPT ![g] = @ \cup {n}]
  /\ terminal_nodes' = [terminal_nodes EXCEPT ![g] = @ \ {n}]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ context_items' = [context_items EXCEPT ![g] = @ \cup {n}]
  /\ input_contexts' = [input_contexts EXCEPT ![g] = @ \cup {n}]
  /\ resumed_nodes' = [resumed_nodes EXCEPT ![g] = @ \ {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs,
                  run_reports, package_catalog, rerunnable_nodes,
                  single_node_reruns >>

\* @command RunNodeTerminal
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_node_terminal
RunNodeTerminal(g, n) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ n \in resolved_nodes[g]
  /\ (n \notin envelopes[g] \/ n \in resumed_nodes[g])
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ terminal_nodes' = [terminal_nodes EXCEPT ![g] = @ \cup {n}]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ input_contexts' = [input_contexts EXCEPT ![g] = @ \cup {n}]
  /\ resumed_nodes' = [resumed_nodes EXCEPT ![g] = @ \ {n}]
  /\ active_graphs' = active_graphs \ {g}
  /\ result' = [accepted |-> FALSE, reason |-> "NODE_NOT_PASSED"]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, passed_nodes, context_items,
                  run_reports, package_catalog, rerunnable_nodes,
                  single_node_reruns >>

\* @command WriteInlineReport
\* @result WorkflowResult
\* @port TestGraphProgramPort.write_inline_report
WriteInlineReport(g) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ resolved_nodes[g] /= {}
  /\ resolved_nodes[g] \subseteq passed_nodes[g]
  /\ run_reports' = run_reports \cup {g}
  /\ active_graphs' = active_graphs \ {g}
  /\ resumed_nodes' = [resumed_nodes EXCEPT ![g] = {}]
  /\ single_node_reruns' = [single_node_reruns EXCEPT ![g] = {}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, passed_nodes, terminal_nodes,
                  envelopes, context_items, input_contexts, rerunnable_nodes,
                  package_catalog >>

\* @command RebuildReport
\* @result WorkflowResult
\* @port TestGraphProgramPort.rebuild_report
RebuildReport(g) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ envelopes[g] /= {}
  /\ run_reports' = run_reports \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, envelopes, context_items, package_catalog >>
  /\ UNCHANGED ticket_state_vars

\* @command CleanBuild
\* @result WorkflowResult
\* @port TestGraphProgramPort.clean_build
CleanBuild ==
  /\ scaffolded
  /\ active_graphs' = {}
  /\ passed_nodes' = [g \in Graphs |-> {}]
  /\ terminal_nodes' = [g \in Graphs |-> {}]
  /\ envelopes' = [g \in Graphs |-> {}]
  /\ context_items' = [g \in Graphs |-> {}]
  /\ input_contexts' = [g \in Graphs |-> {}]
  /\ resumed_nodes' = [g \in Graphs |-> {}]
  /\ single_node_reruns' = [g \in Graphs |-> {}]
  /\ run_reports' = {}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, package_catalog,
                  rerunnable_nodes >>

NoOp ==
  UNCHANGED vars

Next ==
  \/ ScaffoldProject
  \/ \E g \in Graphs:
      RegisterGraph(g)
  \/ \E g \in Graphs, n \in Nodes:
      AddExplicitNode(g, n)
  \/ \E n \in Nodes, d \in Nodes:
      AddScriptDependency(n, d)
  \/ \E n \in Nodes:
      DescribeNode(n)
  \/ \E n \in Nodes:
      SetNodeRerunDisabled(n)
  \/ \E g \in Graphs, n \in Nodes, d \in Nodes:
      ApplyDslOverlay(g, n, d)
  \/ \E g \in Graphs, n \in Nodes:
      ResolveNode(g, n)
  \/ \E g \in Graphs:
      PlanGraph(g)
  \/ \E g \in Graphs:
      StartRun(g)
  \/ \E g \in Graphs, n \in Nodes:
      ResumeRunFromBuild(g, n)
  \/ \E g \in Graphs, n \in Nodes:
      RunOnlyNodeFromBuild(g, n)
  \/ \E g \in Graphs, n \in Nodes:
      RunNodePass(g, n)
  \/ \E g \in Graphs, n \in Nodes:
      RunNodeTerminal(g, n)
  \/ \E g \in Graphs:
      WriteInlineReport(g)
  \/ \E g \in Graphs:
      RebuildReport(g)
  \/ CleanBuild
  \/ NoOp

\* @invariant TypeInvariant
TypeInvariant ==
  /\ scaffolded \in BOOLEAN
  /\ declared_graphs \subseteq Graphs
  /\ explicit_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ script_deps \in [SourceNodes -> SUBSET SourceNodes]
  /\ described_nodes \subseteq SourceNodes
  /\ dsl_deps \in [Graphs -> [SourceNodes -> SUBSET SourceNodes]]
  /\ overlays \in [Graphs -> SUBSET SourceNodes]
  /\ resolved_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ planned_graphs \subseteq declared_graphs
  /\ plan_docs \subseteq planned_graphs
  /\ active_graphs \subseteq planned_graphs
  /\ passed_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ terminal_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ envelopes \in [Graphs -> SUBSET SourceNodes]
  /\ context_items \in [Graphs -> SUBSET SourceNodes]
  /\ input_contexts \in [Graphs -> SUBSET SourceNodes]
  /\ rerunnable_nodes \subseteq SourceNodes
  /\ resumed_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ single_node_reruns \in [Graphs -> SUBSET SourceNodes]
  /\ run_reports \subseteq Graphs
  /\ package_catalog \subseteq Packages
  /\ result.accepted \in BOOLEAN

\* @invariant ExplicitNodesAreAvailable
ExplicitNodesAreAvailable ==
  \A g \in Graphs:
    explicit_nodes[g] \subseteq AvailableFor(g)

\* @invariant ScriptSpecsAreSourceOfTruth
ScriptSpecsAreSourceOfTruth ==
  described_nodes \subseteq SourceNodes

\* @invariant DependencyConstantsAreIndexed
DependencyConstantsAreIndexed ==
  /\ SourceNodes \subseteq Nodes
  /\ SourceNodes /= {}

\* @invariant DslOverlaysOnlyAddDependencies
DslOverlaysOnlyAddDependencies ==
  \A g \in Graphs, n \in SourceNodes:
    ScriptDepsOf(n) \subseteq MergedDeps(g, n)

\* @invariant PlannedGraphsAreDependencyClosed
PlannedGraphsAreDependencyClosed ==
  \A g \in planned_graphs:
    GraphDependencyClosed(g, resolved_nodes[g])

\* @invariant PlannedGraphsAreAcyclic
PlannedGraphsAreAcyclic ==
  \A g \in planned_graphs:
    AcyclicDepsWithin(resolved_nodes[g], DepsForGraph(g))

\* @invariant RunsRespectDependencies
RunsRespectDependencies ==
  \A g \in Graphs:
    \A n \in passed_nodes[g] \cup terminal_nodes[g]:
      MergedDeps(g, n) \subseteq passed_nodes[g]

\* @invariant ContextContainsOnlyPassedPublishedData
ContextContainsOnlyPassedPublishedData ==
  \A g \in Graphs:
    context_items[g] \subseteq passed_nodes[g]

\* @invariant EveryAttemptGetsOneEnvelope
EveryAttemptGetsOneEnvelope ==
  \A g \in Graphs:
    passed_nodes[g] \cup terminal_nodes[g] \subseteq envelopes[g]

\* @invariant EveryAttemptHasSavedInputContext
EveryAttemptHasSavedInputContext ==
  \A g \in Graphs:
    envelopes[g] \subseteq input_contexts[g]

\* @invariant ResumptionsUseSavedInputContext
ResumptionsUseSavedInputContext ==
  \A g \in Graphs:
    resumed_nodes[g] \cup single_node_reruns[g] \subseteq input_contexts[g]

\* @invariant BuildRerunsRespectDependencies
BuildRerunsRespectDependencies ==
  \A g \in Graphs:
    \A n \in resumed_nodes[g] \cup single_node_reruns[g]:
      /\ MergedDeps(g, n) \subseteq passed_nodes[g]
      /\ MergedDeps(g, n) \subseteq context_items[g]

\* @invariant ReportsHaveEnvelopeEvidence
ReportsHaveEnvelopeEvidence ==
  \A g \in run_reports:
    envelopes[g] /= {}

\* @invariant PackageCatalogIsCompleteAfterScaffold
PackageCatalogIsCompleteAfterScaffold ==
  scaffolded => package_catalog = Packages

Spec ==
  Init /\ [][Next]_vars

=============================================================================
