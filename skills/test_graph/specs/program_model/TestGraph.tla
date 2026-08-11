----------------------------- MODULE TestGraph -----------------------------
EXTENDS Naturals, FiniteSets, Sequences, TLC

\* Accepted whole-program model for Test Graph. It carries graph definition,
\* dependency resolution, node execution, published context, reports,
\* environment-repository lifecycle behavior, and the fresh replay-attempt
\* refinement. A replay allocates a new attempt (never a new graph), selects a
\* tail or singleton execution scope, imports the selected node's saved source
\* context, continues the source carrier/trace, and writes only attempt-local
\* evidence while leaving source evidence immutable.

CONSTANTS
  Graphs,
  Nodes,
  Packages,
  SourceNodes,
  Branches,
  EnvironmentTargets,
  EnvironmentBackends,
  ContextKeys,
  RequiredEnvironmentContext,
  NodeRuntimes,
  RequiredEnvironmentRuntimes,
  LifecycleCommands,
  AwsTargets,
  AwsBackends,
  RunAttempts,
  PlanLength,
  PlanFirstNode,
  PlanSecondNode,
  PlanThirdNode,
  TraceIds,
  TraceCarriers,
  NoAttempt,
  NoGraph,
  NoNode,
  NoTrace,
  NoCarrier,
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
  rerun_guidance,
  resumed_nodes,
  single_node_reruns,
  run_reports,
  package_catalog,
  side_effect_runtime_configured,
  provisioning_state_configured,
  feature_branches,
  environment_repo_configured,
  branch_environment_specs,
  provisioned_branch_environments,
  reused_branch_environments,
  reset_branch_environments,
  deployed_branch_environments,
  propagated_environment_contexts,
  merge_destroy_requested,
  destroy_authorized_environments,
  destroyed_branch_environments,
  environment_context_keys,
  environment_repository_scaffolded,
  scaffolded_environment_templates,
  scaffolded_lifecycle_node_templates,
  runtime_environment_context_verified,
  aws_execution_guarded,
  skipped_reset_environments,
  skipped_destroy_environments,
  allocated_attempts,
  active_attempts,
  attempt_graph,
  attempt_mode,
  attempt_source,
  attempt_selected_node,
  attempt_plan,
  attempt_passed_nodes,
  attempt_terminal_nodes,
  attempt_envelopes,
  attempt_context_items,
  attempt_saved_contexts,
  attempt_input_context,
  attempt_initial_context,
  attempt_trace,
  attempt_carrier,
  attempt_envelope_trace,
  attempt_report_nodes,
  attempt_report_status,
  attempt_report_complete,
  attempt_report_writers,
  attempt_report_last_writer,
  attempt_closed,
  attempt_evidence_tampered,
  attempt_closure_fingerprint,
  acquired_replay_sources,
  acquired_replay_context,
  acquired_replay_trace,
  acquired_replay_carrier,
  acquired_replay_closure_fingerprint,
  result

vars ==
  << scaffolded, declared_graphs, explicit_nodes, script_deps, described_nodes,
     dsl_deps, overlays, resolved_nodes, planned_graphs, plan_docs,
     active_graphs, passed_nodes, terminal_nodes, envelopes, context_items,
     input_contexts, rerunnable_nodes, rerun_guidance, resumed_nodes,
     single_node_reruns, run_reports, package_catalog,
     side_effect_runtime_configured, provisioning_state_configured,
     feature_branches, environment_repo_configured,
     branch_environment_specs, provisioned_branch_environments,
     reused_branch_environments, reset_branch_environments,
     deployed_branch_environments, propagated_environment_contexts,
     merge_destroy_requested, destroy_authorized_environments,
     destroyed_branch_environments, environment_context_keys,
     environment_repository_scaffolded, scaffolded_environment_templates,
     scaffolded_lifecycle_node_templates, runtime_environment_context_verified,
     aws_execution_guarded, skipped_reset_environments,
     skipped_destroy_environments, allocated_attempts, active_attempts,
     attempt_graph, attempt_mode, attempt_source, attempt_selected_node,
     attempt_plan, attempt_passed_nodes, attempt_terminal_nodes,
     attempt_envelopes, attempt_context_items, attempt_saved_contexts,
     attempt_input_context, attempt_initial_context, attempt_trace,
     attempt_carrier, attempt_envelope_trace, attempt_report_nodes,
     attempt_report_status, attempt_report_complete, attempt_report_writers,
     attempt_report_last_writer, attempt_closed, attempt_evidence_tampered,
     attempt_closure_fingerprint, acquired_replay_sources,
     acquired_replay_context, acquired_replay_trace,
     acquired_replay_carrier, acquired_replay_closure_fingerprint,
     result >>

legacy_program_vars ==
  << scaffolded, declared_graphs, explicit_nodes, script_deps, described_nodes,
     dsl_deps, overlays, resolved_nodes, planned_graphs, plan_docs,
     active_graphs, passed_nodes, terminal_nodes, envelopes, context_items,
     input_contexts, rerunnable_nodes, rerun_guidance, resumed_nodes,
     single_node_reruns, run_reports, package_catalog,
     side_effect_runtime_configured, provisioning_state_configured,
     feature_branches, environment_repo_configured,
     branch_environment_specs, provisioned_branch_environments,
     reused_branch_environments, reset_branch_environments,
     deployed_branch_environments, propagated_environment_contexts,
     merge_destroy_requested, destroy_authorized_environments,
     destroyed_branch_environments, environment_context_keys >>

attempt_vars ==
  << allocated_attempts, active_attempts, attempt_graph, attempt_mode,
     attempt_source, attempt_selected_node, attempt_plan,
     attempt_passed_nodes, attempt_terminal_nodes, attempt_envelopes,
     attempt_context_items, attempt_saved_contexts, attempt_input_context,
     attempt_initial_context, attempt_trace, attempt_carrier,
     attempt_envelope_trace, attempt_report_nodes, attempt_report_status,
     attempt_report_complete, attempt_report_writers,
     attempt_report_last_writer, attempt_closed, attempt_evidence_tampered,
     attempt_closure_fingerprint, acquired_replay_sources,
     acquired_replay_context, acquired_replay_trace,
     acquired_replay_carrier, acquired_replay_closure_fingerprint >>

replay_integrity_vars ==
  << attempt_closed, attempt_evidence_tampered,
     attempt_closure_fingerprint, acquired_replay_sources,
     acquired_replay_context, acquired_replay_trace,
     acquired_replay_carrier, acquired_replay_closure_fingerprint >>

base_program_vars ==
  << legacy_program_vars, attempt_vars >>

resumption_vars ==
  << input_contexts, rerunnable_nodes, rerun_guidance, resumed_nodes,
     single_node_reruns >>

side_effect_vars ==
  << side_effect_runtime_configured, attempt_vars >>

provisioning_vars ==
  << provisioning_state_configured, feature_branches,
     environment_repo_configured, branch_environment_specs,
     provisioned_branch_environments,
     reused_branch_environments,
     reset_branch_environments, deployed_branch_environments,
     merge_destroy_requested, propagated_environment_contexts,
     destroy_authorized_environments, destroyed_branch_environments,
     environment_context_keys, environment_repository_scaffolded,
     scaffolded_environment_templates, scaffolded_lifecycle_node_templates,
     runtime_environment_context_verified, aws_execution_guarded,
     skipped_reset_environments, skipped_destroy_environments,
     attempt_vars >>

legacy_environment_scaffold_vars ==
  << environment_repository_scaffolded, scaffolded_environment_templates,
     scaffolded_lifecycle_node_templates, runtime_environment_context_verified,
     aws_execution_guarded, skipped_reset_environments,
     skipped_destroy_environments >>

environment_scaffold_vars ==
  << legacy_environment_scaffold_vars, attempt_vars >>

BranchEnvironment(g, b, target, backend) ==
  [graph |-> g, branch |-> b, target |-> target, backend |-> backend]

AllBranchEnvironments ==
  {BranchEnvironment(g, b, target, backend) :
    g \in Graphs, b \in Branches,
    target \in EnvironmentTargets, backend \in EnvironmentBackends}

EnvironmentTemplate(target, backend) ==
  [target |-> target, backend |-> backend]

AllEnvironmentTemplates ==
  {EnvironmentTemplate(target, backend) :
    target \in EnvironmentTargets, backend \in EnvironmentBackends}

AwsEnvironment(e) ==
  \/ e.target \in AwsTargets
  \/ e.backend \in AwsBackends

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

GraphPlan ==
  CASE PlanLength = 1 -> <<PlanFirstNode>>
    [] PlanLength = 2 -> <<PlanFirstNode, PlanSecondNode>>
    [] OTHER -> <<PlanFirstNode, PlanSecondNode, PlanThirdNode>>

SequenceNodes(sequence) ==
  {sequence[i] : i \in DOMAIN sequence}

UniqueNodeSequence(sequence) ==
  Cardinality(SequenceNodes(sequence)) = Len(sequence)

NodeIndex(sequence, node) ==
  CHOOSE i \in DOMAIN sequence: sequence[i] = node

SequenceTailFrom(sequence, node) ==
  SubSeq(sequence, NodeIndex(sequence, node), Len(sequence))

SequencePredecessors(sequence, node) ==
  LET index == NodeIndex(sequence, node)
  IN IF index = 1 THEN {} ELSE {sequence[i] : i \in 1..(index - 1)}

ContextItemFingerprint(node) ==
  [nodeId |-> node,
   publishedSha256 |-> [kind |-> "published", nodeId |-> node]]

AllContextItemFingerprints ==
  {ContextItemFingerprint(node) : node \in SourceNodes}

ContextNodeIds(context) ==
  {context[i].nodeId : i \in DOMAIN context}

AttemptEnvelopeStatus(attempt, node) ==
  IF node \in attempt_passed_nodes[attempt] THEN "passed" ELSE "terminal"

CanonicalEnvelopeFingerprint(attempt, node) ==
  [envelopeVersion |-> 1,
   nodeId |-> node,
   status |-> AttemptEnvelopeStatus(attempt, node),
   trace |-> attempt_envelope_trace[attempt][node],
   assertionsConsistentWithStatus |-> TRUE,
   publishedSha256 |-> [kind |-> "published", nodeId |-> node]]

ReplayKey(source, selected) ==
  [source |-> source, selected |-> selected]

ReplayKeys ==
  {ReplayKey(source, selected) :
    source \in RunAttempts, selected \in SourceNodes}

NoEvidenceFingerprint == [version |-> 0]

CanonicalAttemptEvidenceFingerprint(attempt) ==
  [version |-> 2,
   scopeSha256 |->
     [graph |-> attempt_graph[attempt],
      mode |-> attempt_mode[attempt],
      source |-> attempt_source[attempt],
      selected |-> attempt_selected_node[attempt],
      exactPlan |-> attempt_plan[attempt]],
   carrierSha256 |->
     [trace |-> attempt_trace[attempt], carrier |-> attempt_carrier[attempt]],
   contextSha256 |->
     [node \in attempt_saved_contexts[attempt] |->
       [path |-> [directory |-> "context", nodeId |-> node],
        exactOrderedItems |-> attempt_input_context[attempt][node]]],
   envelopeSha256 |->
     [node \in attempt_envelopes[attempt] |->
       [path |-> [directory |-> "envelope", nodeId |-> node],
        exactCanonicalEnvelope |-> CanonicalEnvelopeFingerprint(attempt, node)]]]

CurrentAttemptEvidenceFingerprint(attempt) ==
  IF attempt \in attempt_evidence_tampered
  THEN [version |-> 2,
        tampered |-> TRUE,
        prior |-> CanonicalAttemptEvidenceFingerprint(attempt)]
  ELSE CanonicalAttemptEvidenceFingerprint(attempt)

ClosureMatchesCurrentEvidence(attempt) ==
  /\ attempt \in attempt_closed
  /\ attempt_closure_fingerprint[attempt] =
       CurrentAttemptEvidenceFingerprint(attempt)

ExpectedAttemptContext(attempt) ==
  LET passedCount == Cardinality(attempt_passed_nodes[attempt])
  IN attempt_initial_context[attempt] \o
       [i \in 1..passedCount |->
          ContextItemFingerprint(attempt_plan[attempt][i])]

ExpectedNodeInputContext(attempt, node) ==
  LET index == NodeIndex(attempt_plan[attempt], node)
  IN attempt_initial_context[attempt] \o
       [i \in 1..(index - 1) |->
          ContextItemFingerprint(attempt_plan[attempt][i])]

AttemptNodes(attempt) ==
  SequenceNodes(attempt_plan[attempt])

ReplayAttempts ==
  {attempt \in allocated_attempts: attempt_mode[attempt] /= "full"}

ReplaySourceAttempts ==
  {attempt_source[attempt] : attempt \in ReplayAttempts}

ReportIsComplete(attempt) ==
  /\ ClosureMatchesCurrentEvidence(attempt)
  /\ attempt_envelopes[attempt] = AttemptNodes(attempt)
  /\ attempt_saved_contexts[attempt] = AttemptNodes(attempt)

ComputedReportStatus(attempt) ==
  IF ~ReportIsComplete(attempt)
  THEN "errored"
  ELSE IF attempt_terminal_nodes[attempt] /= {}
       THEN "failed"
       ELSE IF attempt_passed_nodes[attempt] = AttemptNodes(attempt)
            THEN "passed"
            ELSE "errored"

InlineReportStatus(attempt) ==
  IF attempt_terminal_nodes[attempt] /= {}
  THEN "errored"
  ELSE ComputedReportStatus(attempt)

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
  /\ rerun_guidance = [g \in Graphs |-> {}]
  /\ resumed_nodes = [g \in Graphs |-> {}]
  /\ single_node_reruns = [g \in Graphs |-> {}]
  /\ run_reports = {}
  /\ package_catalog = {}
  /\ side_effect_runtime_configured = {}
  /\ provisioning_state_configured = {}
  /\ feature_branches = [g \in Graphs |-> {}]
  /\ environment_repo_configured = {}
  /\ branch_environment_specs = {}
  /\ provisioned_branch_environments = {}
  /\ reused_branch_environments = {}
  /\ reset_branch_environments = {}
  /\ deployed_branch_environments = {}
  /\ propagated_environment_contexts = {}
  /\ merge_destroy_requested = {}
  /\ destroy_authorized_environments = {}
  /\ destroyed_branch_environments = {}
  /\ environment_context_keys = [e \in AllBranchEnvironments |-> {}]
  /\ environment_repository_scaffolded = FALSE
  /\ scaffolded_environment_templates = {}
  /\ scaffolded_lifecycle_node_templates = {}
  /\ runtime_environment_context_verified = {}
  /\ aws_execution_guarded = {}
  /\ skipped_reset_environments = {}
  /\ skipped_destroy_environments = {}
  /\ allocated_attempts = {}
  /\ active_attempts = {}
  /\ attempt_graph = [attempt \in RunAttempts |-> NoGraph]
  /\ attempt_mode = [attempt \in RunAttempts |-> "none"]
  /\ attempt_source = [attempt \in RunAttempts |-> NoAttempt]
  /\ attempt_selected_node = [attempt \in RunAttempts |-> NoNode]
  /\ attempt_plan = [attempt \in RunAttempts |-> <<>>]
  /\ attempt_passed_nodes = [attempt \in RunAttempts |-> {}]
  /\ attempt_terminal_nodes = [attempt \in RunAttempts |-> {}]
  /\ attempt_envelopes = [attempt \in RunAttempts |-> {}]
  /\ attempt_context_items = [attempt \in RunAttempts |-> <<>>]
  /\ attempt_saved_contexts = [attempt \in RunAttempts |-> {}]
  /\ attempt_input_context =
      [attempt \in RunAttempts |-> [node \in SourceNodes |-> <<>>]]
  /\ attempt_initial_context = [attempt \in RunAttempts |-> <<>>]
  /\ attempt_trace = [attempt \in RunAttempts |-> NoTrace]
  /\ attempt_carrier = [attempt \in RunAttempts |-> NoCarrier]
  /\ attempt_envelope_trace =
      [attempt \in RunAttempts |-> [node \in SourceNodes |-> NoTrace]]
  /\ attempt_report_nodes = [attempt \in RunAttempts |-> {}]
  /\ attempt_report_status = [attempt \in RunAttempts |-> "none"]
  /\ attempt_report_complete = [attempt \in RunAttempts |-> FALSE]
  /\ attempt_report_writers = [attempt \in RunAttempts |-> {}]
  /\ attempt_report_last_writer = [attempt \in RunAttempts |-> "none"]
  /\ attempt_closed = {}
  /\ attempt_evidence_tampered = {}
  /\ attempt_closure_fingerprint =
      [attempt \in RunAttempts |-> NoEvidenceFingerprint]
  /\ acquired_replay_sources = {}
  /\ acquired_replay_context = [key \in ReplayKeys |-> <<>>]
  /\ acquired_replay_trace = [key \in ReplayKeys |-> NoTrace]
  /\ acquired_replay_carrier = [key \in ReplayKeys |-> NoCarrier]
  /\ acquired_replay_closure_fingerprint =
      [key \in ReplayKeys |-> NoEvidenceFingerprint]
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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
                  rerun_guidance, resumed_nodes, single_node_reruns,
                  run_reports, package_catalog,
                  side_effect_runtime_configured >>
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ rerun_guidance' = [rerun_guidance EXCEPT ![g] = {}]
  /\ resumed_nodes' = [resumed_nodes EXCEPT ![g] = {}]
  /\ single_node_reruns' = [single_node_reruns EXCEPT ![g] = {}]
  /\ run_reports' = run_reports \ {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, package_catalog,
                  rerunnable_nodes >>
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ active_graphs' = active_graphs \cup {g}
  /\ resumed_nodes' = [resumed_nodes EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, passed_nodes, terminal_nodes,
                  envelopes, context_items, input_contexts,
                  rerunnable_nodes, rerun_guidance, single_node_reruns,
                  run_reports, package_catalog >>
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

\* @command RunOnlyNodeFromBuild
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_only_node_from_build
RunOnlyNodeFromBuild(g, n) ==
  /\ scaffolded
  /\ g \in planned_graphs
  /\ n \in resolved_nodes[g]
  /\ n \in input_contexts[g]
  /\ n \in rerunnable_nodes
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ single_node_reruns' = [single_node_reruns EXCEPT ![g] = @ \cup {n}]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ rerun_guidance' = [rerun_guidance EXCEPT ![g] = @ \ {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, context_items, input_contexts,
                  rerunnable_nodes, resumed_nodes, run_reports,
                  package_catalog >>
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

\* @command RunNodePass
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_node_pass
RunNodePass(g, n) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ n \in resolved_nodes[g]
  /\ n \notin passed_nodes[g]
  /\ (n \notin envelopes[g] \/ n \in terminal_nodes[g])
  /\ (n \notin terminal_nodes[g] \/ n \in rerunnable_nodes)
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ passed_nodes' = [passed_nodes EXCEPT ![g] = @ \cup {n}]
  /\ terminal_nodes' = [terminal_nodes EXCEPT ![g] = @ \ {n}]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ context_items' = [context_items EXCEPT ![g] = @ \cup {n}]
  /\ input_contexts' = [input_contexts EXCEPT ![g] = @ \cup {n}]
  /\ rerun_guidance' = [rerun_guidance EXCEPT ![g] = @ \ {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, run_reports,
                  package_catalog, rerunnable_nodes, resumed_nodes,
                  single_node_reruns >>
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

\* @command RunNodeTerminal
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_node_terminal
RunNodeTerminal(g, n) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ n \in resolved_nodes[g]
  /\ n \notin passed_nodes[g]
  /\ (n \notin envelopes[g] \/ n \in terminal_nodes[g])
  /\ (n \notin terminal_nodes[g] \/ n \in rerunnable_nodes)
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ terminal_nodes' = [terminal_nodes EXCEPT ![g] = @ \cup {n}]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ input_contexts' = [input_contexts EXCEPT ![g] = @ \cup {n}]
  /\ rerun_guidance' =
      [rerun_guidance EXCEPT ![g] =
        IF n \in rerunnable_nodes THEN @ \cup {n} ELSE @ \ {n}]
  /\ active_graphs' = active_graphs \ {g}
  /\ result' = [accepted |-> FALSE, reason |-> "NODE_NOT_PASSED"]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, passed_nodes, context_items,
                  run_reports, package_catalog, rerunnable_nodes,
                  resumed_nodes, single_node_reruns >>
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, passed_nodes, terminal_nodes,
                  envelopes, context_items, package_catalog >>
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

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
  /\ rerun_guidance' = [g \in Graphs |-> {}]
  /\ resumed_nodes' = [g \in Graphs |-> {}]
  /\ single_node_reruns' = [g \in Graphs |-> {}]
  /\ run_reports' = {}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, package_catalog,
                  rerunnable_nodes >>
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

\* @command ConfigureSideEffectRuntime
\* @result WorkflowResult
\* @port TestGraphProgramPort.configure_side_effect_runtime
ConfigureSideEffectRuntime(g) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin side_effect_runtime_configured
  /\ side_effect_runtime_configured' = side_effect_runtime_configured \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, envelopes, context_items, input_contexts,
                  rerunnable_nodes, rerun_guidance, resumed_nodes,
                  single_node_reruns, run_reports, package_catalog >>
  /\ UNCHANGED provisioning_vars

\* @command ConfigureProvisioningState
\* @result WorkflowResult
\* @port TestGraphProgramPort.configure_provisioning_state
ConfigureProvisioningState(g) ==
  /\ g \in side_effect_runtime_configured
  /\ g \notin provisioning_state_configured
  /\ provisioning_state_configured' = provisioning_state_configured \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, envelopes, context_items, input_contexts,
                  rerunnable_nodes, rerun_guidance, resumed_nodes,
                  single_node_reruns, run_reports, package_catalog,
                  side_effect_runtime_configured,
                  feature_branches, environment_repo_configured,
                  branch_environment_specs,
                  provisioned_branch_environments, reused_branch_environments,
                  reset_branch_environments, deployed_branch_environments,
                  propagated_environment_contexts, merge_destroy_requested,
                  destroy_authorized_environments,
                  destroyed_branch_environments, environment_context_keys >>
  /\ UNCHANGED environment_scaffold_vars

\* @command RegisterFeatureBranch
\* @result WorkflowResult
\* @port TestGraphProgramPort.register_feature_branch
RegisterFeatureBranch(g, b) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ b \in Branches
  /\ b \notin feature_branches[g]
  /\ feature_branches' = [feature_branches EXCEPT ![g] = @ \cup {b}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, envelopes, context_items, input_contexts,
                  rerunnable_nodes, rerun_guidance, resumed_nodes,
                  single_node_reruns, run_reports, package_catalog,
                  side_effect_runtime_configured,
                  provisioning_state_configured,
                  environment_repo_configured, branch_environment_specs,
                  provisioned_branch_environments, reused_branch_environments,
                  reset_branch_environments, deployed_branch_environments,
                  propagated_environment_contexts, merge_destroy_requested,
                  destroy_authorized_environments,
                  destroyed_branch_environments, environment_context_keys >>
  /\ UNCHANGED environment_scaffold_vars

\* @command ConfigureEnvironmentRepository
\* @result WorkflowResult
\* @port TestGraphProgramPort.configure_environment_repository
ConfigureEnvironmentRepository(g) ==
  /\ g \in provisioning_state_configured
  /\ g \notin environment_repo_configured
  /\ environment_repo_configured' = environment_repo_configured \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                  described_nodes, dsl_deps, overlays, resolved_nodes,
                  planned_graphs, plan_docs, active_graphs, passed_nodes,
                  terminal_nodes, envelopes, context_items, input_contexts,
                  rerunnable_nodes, rerun_guidance, resumed_nodes,
                  single_node_reruns, run_reports, package_catalog,
                  side_effect_runtime_configured,
                  provisioning_state_configured, feature_branches,
                  branch_environment_specs,
                  provisioned_branch_environments, reused_branch_environments,
                  reset_branch_environments, deployed_branch_environments,
                  propagated_environment_contexts, merge_destroy_requested,
                  destroy_authorized_environments,
                  destroyed_branch_environments, environment_context_keys >>
  /\ UNCHANGED environment_scaffold_vars

\* @command DeclareBranchEnvironment
\* @result WorkflowResult
\* @port TestGraphProgramPort.declare_branch_environment
DeclareBranchEnvironment(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ scaffolded
    /\ g \in environment_repo_configured
    /\ b \in feature_branches[g]
    /\ target \in EnvironmentTargets
    /\ backend \in EnvironmentBackends
    /\ e \notin branch_environment_specs
    /\ branch_environment_specs' = branch_environment_specs \cup {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured,
                    feature_branches, environment_repo_configured,
                    provisioned_branch_environments, reused_branch_environments,
                    reset_branch_environments, deployed_branch_environments,
                    propagated_environment_contexts, merge_destroy_requested,
                    destroy_authorized_environments,
                    destroyed_branch_environments, environment_context_keys >>
    /\ UNCHANGED environment_scaffold_vars

\* @command ProvisionBranchEnvironment
\* @result WorkflowResult
\* @port TestGraphProgramPort.provision_branch_environment
ProvisionBranchEnvironment(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in branch_environment_specs
    /\ e \notin provisioned_branch_environments
    /\ (AwsEnvironment(e) => e \in aws_execution_guarded)
    /\ provisioned_branch_environments' = provisioned_branch_environments \cup {e}
    /\ environment_context_keys' =
        [environment_context_keys EXCEPT ![e] = RequiredEnvironmentContext]
    /\ destroyed_branch_environments' = destroyed_branch_environments \ {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured,
                    feature_branches, environment_repo_configured,
                    branch_environment_specs, reused_branch_environments,
                    reset_branch_environments, deployed_branch_environments,
                    propagated_environment_contexts, merge_destroy_requested,
                    destroy_authorized_environments >>
    /\ UNCHANGED environment_scaffold_vars

\* @command ReuseBranchEnvironment
\* @result WorkflowResult
\* @port TestGraphProgramPort.reuse_branch_environment
ReuseBranchEnvironment(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ e \notin merge_destroy_requested
    /\ reused_branch_environments' = reused_branch_environments \cup {e}
    /\ environment_context_keys' =
        [environment_context_keys EXCEPT ![e] =
          environment_context_keys[e] \cup RequiredEnvironmentContext]
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured,
                    feature_branches, environment_repo_configured,
                    branch_environment_specs, provisioned_branch_environments,
                    reset_branch_environments, deployed_branch_environments,
                    propagated_environment_contexts, merge_destroy_requested,
                    destroy_authorized_environments,
                    destroyed_branch_environments >>
    /\ UNCHANGED environment_scaffold_vars

\* @command DeployApplicationToBranchEnvironment
\* @result WorkflowResult
\* @port TestGraphProgramPort.deploy_application_to_branch_environment
DeployApplicationToBranchEnvironment(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ RequiredEnvironmentContext \subseteq environment_context_keys[e]
    /\ deployed_branch_environments' = deployed_branch_environments \cup {e}
    /\ reset_branch_environments' = reset_branch_environments \ {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured,
                    feature_branches, environment_repo_configured,
                    branch_environment_specs, provisioned_branch_environments,
                    reused_branch_environments, merge_destroy_requested,
                    propagated_environment_contexts,
                    destroy_authorized_environments,
                    destroyed_branch_environments, environment_context_keys >>
    /\ UNCHANGED environment_scaffold_vars

\* @command PropagateEnvironmentContext
\* @result WorkflowResult
\* @port TestGraphProgramPort.propagate_environment_context
PropagateEnvironmentContext(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ RequiredEnvironmentContext \subseteq environment_context_keys[e]
    /\ propagated_environment_contexts' = propagated_environment_contexts \cup {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured, feature_branches,
                    environment_repo_configured, branch_environment_specs,
                    provisioned_branch_environments, reused_branch_environments,
                    reset_branch_environments, deployed_branch_environments,
                    merge_destroy_requested, destroy_authorized_environments,
                    destroyed_branch_environments,
                    environment_context_keys >>
    /\ UNCHANGED environment_scaffold_vars

\* @command ResetBranchEnvironment
\* @result WorkflowResult
\* @port TestGraphProgramPort.reset_branch_environment
ResetBranchEnvironment(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ reset_branch_environments' = reset_branch_environments \cup {e}
    /\ deployed_branch_environments' = deployed_branch_environments \ {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured,
                    feature_branches, environment_repo_configured,
                    branch_environment_specs,
                    provisioned_branch_environments, reused_branch_environments,
                    merge_destroy_requested, propagated_environment_contexts,
                    destroy_authorized_environments,
                    destroyed_branch_environments, environment_context_keys >>
    /\ UNCHANGED environment_scaffold_vars

\* @command RequestMergedBranchDestroy
\* @result WorkflowResult
\* @port TestGraphProgramPort.request_merged_branch_destroy
RequestMergedBranchDestroy(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ e \notin skipped_destroy_environments
    /\ merge_destroy_requested' = merge_destroy_requested \cup {e}
    /\ destroy_authorized_environments' = destroy_authorized_environments \cup {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured,
                    feature_branches, environment_repo_configured,
                    branch_environment_specs,
                    provisioned_branch_environments, reused_branch_environments,
                    reset_branch_environments, deployed_branch_environments,
                    propagated_environment_contexts,
                    destroyed_branch_environments, environment_context_keys >>
    /\ UNCHANGED environment_scaffold_vars

\* @command DestroyMergedBranchEnvironment
\* @result WorkflowResult
\* @port TestGraphProgramPort.destroy_merged_branch_environment
DestroyMergedBranchEnvironment(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ e \in merge_destroy_requested
    /\ e \notin skipped_destroy_environments
    /\ provisioned_branch_environments' = provisioned_branch_environments \ {e}
    /\ reused_branch_environments' = reused_branch_environments \ {e}
    /\ reset_branch_environments' = reset_branch_environments \ {e}
    /\ deployed_branch_environments' = deployed_branch_environments \ {e}
    /\ propagated_environment_contexts' = propagated_environment_contexts \ {e}
    /\ merge_destroy_requested' = merge_destroy_requested \ {e}
    /\ destroyed_branch_environments' = destroyed_branch_environments \cup {e}
    /\ environment_context_keys' = [environment_context_keys EXCEPT ![e] = {}]
    /\ skipped_reset_environments' = skipped_reset_environments \ {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, script_deps,
                    described_nodes, dsl_deps, overlays, resolved_nodes,
                    planned_graphs, plan_docs, active_graphs, passed_nodes,
                    terminal_nodes, envelopes, context_items, input_contexts,
                    rerunnable_nodes, rerun_guidance, resumed_nodes,
                    single_node_reruns, run_reports, package_catalog,
                    side_effect_runtime_configured,
                    provisioning_state_configured,
                    feature_branches, environment_repo_configured,
                    branch_environment_specs,
                    destroy_authorized_environments >>
    /\ UNCHANGED attempt_vars
    /\ UNCHANGED << environment_repository_scaffolded,
                    scaffolded_environment_templates,
                    scaffolded_lifecycle_node_templates,
                    runtime_environment_context_verified,
                    aws_execution_guarded, skipped_destroy_environments >>

\* @command ScaffoldEnvironmentRepository
\* @result WorkflowResult
\* @port TestGraphProgramPort.scaffold_environment_repository
ScaffoldEnvironmentRepository ==
  /\ scaffolded
  /\ environment_repository_scaffolded = FALSE
  /\ environment_repository_scaffolded' = TRUE
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED base_program_vars
  /\ UNCHANGED << scaffolded_environment_templates,
                  scaffolded_lifecycle_node_templates,
                  runtime_environment_context_verified,
                  aws_execution_guarded, skipped_reset_environments,
                  skipped_destroy_environments >>

\* @command ScaffoldEnvironmentTemplate
\* @result WorkflowResult
\* @port TestGraphProgramPort.scaffold_environment_template
ScaffoldEnvironmentTemplate(target, backend) ==
  LET template == EnvironmentTemplate(target, backend)
  IN
    /\ environment_repository_scaffolded
    /\ target \in EnvironmentTargets
    /\ backend \in EnvironmentBackends
    /\ template \notin scaffolded_environment_templates
    /\ scaffolded_environment_templates' =
        scaffolded_environment_templates \cup {template}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED base_program_vars
    /\ UNCHANGED << environment_repository_scaffolded,
                    scaffolded_lifecycle_node_templates,
                    runtime_environment_context_verified,
                    aws_execution_guarded, skipped_reset_environments,
                    skipped_destroy_environments >>

\* @command ScaffoldLifecycleNodeTemplate
\* @result WorkflowResult
\* @port TestGraphProgramPort.scaffold_lifecycle_node_template
ScaffoldLifecycleNodeTemplate(command) ==
  /\ environment_repository_scaffolded
  /\ command \in LifecycleCommands
  /\ command \notin scaffolded_lifecycle_node_templates
  /\ scaffolded_lifecycle_node_templates' =
      scaffolded_lifecycle_node_templates \cup {command}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED base_program_vars
  /\ UNCHANGED << environment_repository_scaffolded,
                  scaffolded_environment_templates,
                  runtime_environment_context_verified,
                  aws_execution_guarded, skipped_reset_environments,
                  skipped_destroy_environments >>

\* @command VerifyEnvironmentContextRuntime
\* @result WorkflowResult
\* @port TestGraphProgramPort.verify_environment_context_runtime
VerifyEnvironmentContextRuntime(runtime) ==
  /\ runtime \in NodeRuntimes
  /\ runtime \notin runtime_environment_context_verified
  /\ runtime_environment_context_verified' =
      runtime_environment_context_verified \cup {runtime}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED base_program_vars
  /\ UNCHANGED << environment_repository_scaffolded,
                  scaffolded_environment_templates,
                  scaffolded_lifecycle_node_templates,
                  aws_execution_guarded, skipped_reset_environments,
                  skipped_destroy_environments >>

\* @command GuardAwsBranchEnvironment
\* @result WorkflowResult
\* @port TestGraphProgramPort.guard_aws_branch_environment
GuardAwsBranchEnvironment(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in branch_environment_specs
    /\ AwsEnvironment(e)
    /\ e \notin aws_execution_guarded
    /\ aws_execution_guarded' = aws_execution_guarded \cup {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED base_program_vars
    /\ UNCHANGED << environment_repository_scaffolded,
                    scaffolded_environment_templates,
                    scaffolded_lifecycle_node_templates,
                    runtime_environment_context_verified,
                    skipped_reset_environments, skipped_destroy_environments >>

\* @command SkipBranchEnvironmentReset
\* @result WorkflowResult
\* @port TestGraphProgramPort.skip_branch_environment_reset
SkipBranchEnvironmentReset(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ e \notin merge_destroy_requested
    /\ (e \notin deployed_branch_environments \/ e \in reset_branch_environments)
    /\ e \notin skipped_reset_environments
    /\ skipped_reset_environments' = skipped_reset_environments \cup {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED base_program_vars
    /\ UNCHANGED << environment_repository_scaffolded,
                    scaffolded_environment_templates,
                    scaffolded_lifecycle_node_templates,
                    runtime_environment_context_verified,
                    aws_execution_guarded, skipped_destroy_environments >>

\* @command SkipBranchEnvironmentDestroy
\* @result WorkflowResult
\* @port TestGraphProgramPort.skip_branch_environment_destroy
SkipBranchEnvironmentDestroy(g, b, target, backend) ==
  LET e == BranchEnvironment(g, b, target, backend)
  IN
    /\ e \in provisioned_branch_environments
    /\ e \notin merge_destroy_requested
    /\ e \notin skipped_destroy_environments
    /\ skipped_destroy_environments' = skipped_destroy_environments \cup {e}
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED base_program_vars
    /\ UNCHANGED << environment_repository_scaffolded,
                    scaffolded_environment_templates,
                    scaffolded_lifecycle_node_templates,
                    runtime_environment_context_verified,
                    aws_execution_guarded, skipped_reset_environments >>

\* @command PrepareReplayGraph
\* @result WorkflowResult
\* @port TestGraphProgramPort.prepare_replay_graph
PrepareReplayGraph(g) ==
  /\ ~scaffolded
  /\ g \in Graphs
  /\ GraphPlan /= <<>>
  /\ SequenceNodes(GraphPlan) \subseteq SourceNodes
  /\ UniqueNodeSequence(GraphPlan)
  /\ scaffolded' = TRUE
  /\ declared_graphs' = declared_graphs \cup {g}
  /\ explicit_nodes' = [explicit_nodes EXCEPT ![g] = SequenceNodes(GraphPlan)]
  /\ described_nodes' = described_nodes \cup SequenceNodes(GraphPlan)
  /\ resolved_nodes' = [resolved_nodes EXCEPT ![g] = SequenceNodes(GraphPlan)]
  /\ planned_graphs' = planned_graphs \cup {g}
  /\ plan_docs' = plan_docs \cup {g}
  /\ package_catalog' = Packages
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << script_deps, dsl_deps, overlays, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports >>
  /\ UNCHANGED resumption_vars
  /\ UNCHANGED side_effect_vars
  /\ UNCHANGED provisioning_vars

\* @command StartFullAttempt
\* @result WorkflowResult
\* @port TestGraphProgramPort.start_full_attempt
StartFullAttempt(g, attempt, trace, carrier) ==
  /\ scaffolded
  /\ g \in planned_graphs
  /\ resolved_nodes[g] = SequenceNodes(GraphPlan)
  /\ attempt \in RunAttempts \ allocated_attempts
  /\ trace \in TraceIds
  /\ carrier \in TraceCarriers
  /\ trace \notin {attempt_trace[a] : a \in allocated_attempts}
  /\ carrier \notin {attempt_carrier[a] : a \in allocated_attempts}
  /\ allocated_attempts' = allocated_attempts \cup {attempt}
  /\ active_attempts' = active_attempts \cup {attempt}
  /\ attempt_graph' = [attempt_graph EXCEPT ![attempt] = g]
  /\ attempt_mode' = [attempt_mode EXCEPT ![attempt] = "full"]
  /\ attempt_source' = [attempt_source EXCEPT ![attempt] = NoAttempt]
  /\ attempt_selected_node' = [attempt_selected_node EXCEPT ![attempt] = NoNode]
  /\ attempt_plan' = [attempt_plan EXCEPT ![attempt] = GraphPlan]
  /\ attempt_passed_nodes' = [attempt_passed_nodes EXCEPT ![attempt] = {}]
  /\ attempt_terminal_nodes' = [attempt_terminal_nodes EXCEPT ![attempt] = {}]
  /\ attempt_envelopes' = [attempt_envelopes EXCEPT ![attempt] = {}]
  /\ attempt_context_items' = [attempt_context_items EXCEPT ![attempt] = <<>>]
  /\ attempt_saved_contexts' = [attempt_saved_contexts EXCEPT ![attempt] = {}]
  /\ attempt_input_context' =
      [attempt_input_context EXCEPT
        ![attempt] = [node \in SourceNodes |-> <<>>]]
  /\ attempt_initial_context' = [attempt_initial_context EXCEPT ![attempt] = <<>>]
  /\ attempt_trace' = [attempt_trace EXCEPT ![attempt] = trace]
  /\ attempt_carrier' = [attempt_carrier EXCEPT ![attempt] = carrier]
  /\ attempt_envelope_trace' =
      [attempt_envelope_trace EXCEPT
        ![attempt] = [node \in SourceNodes |-> NoTrace]]
  /\ attempt_report_nodes' = [attempt_report_nodes EXCEPT ![attempt] = {}]
  /\ attempt_report_status' = [attempt_report_status EXCEPT ![attempt] = "none"]
  /\ attempt_report_complete' = [attempt_report_complete EXCEPT ![attempt] = FALSE]
  /\ attempt_report_writers' = [attempt_report_writers EXCEPT ![attempt] = {}]
  /\ attempt_report_last_writer' =
      [attempt_report_last_writer EXCEPT ![attempt] = "none"]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED replay_integrity_vars
  /\ UNCHANGED legacy_program_vars
  /\ UNCHANGED legacy_environment_scaffold_vars

\* @command StartReplayAttempt
\* @result WorkflowResult
\* @port TestGraphProgramPort.start_replay_attempt
StartReplayAttempt(source, attempt, mode, selected) ==
  LET key == ReplayKey(source, selected)
      sourceContext == acquired_replay_context[key]
      replayPlan == IF mode = "resume"
                    THEN SequenceTailFrom(GraphPlan, selected)
                    ELSE <<selected>>
  IN
    /\ key \in acquired_replay_sources
    /\ source \in attempt_closed
    /\ attempt_mode[source] = "full"
    /\ attempt_plan[source] = GraphPlan
    /\ attempt \in RunAttempts \ allocated_attempts
    /\ mode \in {"resume", "run-only"}
    /\ selected \in SequenceNodes(GraphPlan)
    /\ selected \in attempt_saved_contexts[source]
    /\ sourceContext = ExpectedNodeInputContext(source, selected)
    /\ allocated_attempts' = allocated_attempts \cup {attempt}
    /\ active_attempts' = active_attempts \cup {attempt}
    /\ attempt_graph' =
        [attempt_graph EXCEPT ![attempt] = attempt_graph[source]]
    /\ attempt_mode' = [attempt_mode EXCEPT ![attempt] = mode]
    /\ attempt_source' = [attempt_source EXCEPT ![attempt] = source]
    /\ attempt_selected_node' =
        [attempt_selected_node EXCEPT ![attempt] = selected]
    /\ attempt_plan' = [attempt_plan EXCEPT ![attempt] = replayPlan]
    /\ attempt_passed_nodes' = [attempt_passed_nodes EXCEPT ![attempt] = {}]
    /\ attempt_terminal_nodes' = [attempt_terminal_nodes EXCEPT ![attempt] = {}]
    /\ attempt_envelopes' = [attempt_envelopes EXCEPT ![attempt] = {}]
    /\ attempt_context_items' =
        [attempt_context_items EXCEPT ![attempt] = sourceContext]
    /\ attempt_saved_contexts' = [attempt_saved_contexts EXCEPT ![attempt] = {}]
    /\ attempt_input_context' =
        [attempt_input_context EXCEPT
          ![attempt] = [node \in SourceNodes |-> <<>>]]
    /\ attempt_initial_context' =
        [attempt_initial_context EXCEPT ![attempt] = sourceContext]
    /\ attempt_trace' =
        [attempt_trace EXCEPT ![attempt] = acquired_replay_trace[key]]
    /\ attempt_carrier' =
        [attempt_carrier EXCEPT ![attempt] = acquired_replay_carrier[key]]
    /\ attempt_envelope_trace' =
        [attempt_envelope_trace EXCEPT
          ![attempt] = [node \in SourceNodes |-> NoTrace]]
    /\ attempt_report_nodes' = [attempt_report_nodes EXCEPT ![attempt] = {}]
    /\ attempt_report_status' =
        [attempt_report_status EXCEPT ![attempt] = "none"]
    /\ attempt_report_complete' =
        [attempt_report_complete EXCEPT ![attempt] = FALSE]
    /\ attempt_report_writers' =
        [attempt_report_writers EXCEPT ![attempt] = {}]
    /\ attempt_report_last_writer' =
        [attempt_report_last_writer EXCEPT ![attempt] = "none"]
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED replay_integrity_vars
    /\ UNCHANGED legacy_program_vars
    /\ UNCHANGED legacy_environment_scaffold_vars

\* @command RunAttemptNodePass
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_attempt_node_pass
RunAttemptNodePass(attempt, node) ==
  /\ attempt \in active_attempts
  /\ node \in AttemptNodes(attempt) \ attempt_envelopes[attempt]
  /\ SequencePredecessors(attempt_plan[attempt], node) \subseteq
      attempt_passed_nodes[attempt]
  /\ attempt_passed_nodes' =
      [attempt_passed_nodes EXCEPT ![attempt] = @ \cup {node}]
  /\ attempt_envelopes' =
      [attempt_envelopes EXCEPT ![attempt] = @ \cup {node}]
  /\ attempt_context_items' =
      [attempt_context_items EXCEPT
        ![attempt] = Append(@, ContextItemFingerprint(node))]
  /\ attempt_saved_contexts' =
      [attempt_saved_contexts EXCEPT ![attempt] = @ \cup {node}]
  /\ attempt_input_context' =
      [attempt_input_context EXCEPT
        ![attempt][node] = attempt_context_items[attempt]]
  /\ attempt_envelope_trace' =
      [attempt_envelope_trace EXCEPT
        ![attempt][node] = attempt_trace[attempt]]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED replay_integrity_vars
  /\ UNCHANGED << allocated_attempts, active_attempts, attempt_graph,
                  attempt_mode, attempt_source, attempt_selected_node,
                  attempt_plan, attempt_terminal_nodes,
                  attempt_initial_context, attempt_trace, attempt_carrier,
                  attempt_report_nodes, attempt_report_status,
                  attempt_report_complete, attempt_report_writers,
                  attempt_report_last_writer >>
  /\ UNCHANGED legacy_program_vars
  /\ UNCHANGED legacy_environment_scaffold_vars

\* @command RunAttemptNodeTerminal
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_attempt_node_terminal
RunAttemptNodeTerminal(attempt, node) ==
  /\ attempt \in active_attempts
  /\ node \in AttemptNodes(attempt) \ attempt_envelopes[attempt]
  /\ SequencePredecessors(attempt_plan[attempt], node) \subseteq
      attempt_passed_nodes[attempt]
  /\ active_attempts' = active_attempts \ {attempt}
  /\ attempt_terminal_nodes' =
      [attempt_terminal_nodes EXCEPT ![attempt] = @ \cup {node}]
  /\ attempt_envelopes' =
      [attempt_envelopes EXCEPT ![attempt] = @ \cup {node}]
  /\ attempt_saved_contexts' =
      [attempt_saved_contexts EXCEPT ![attempt] = @ \cup {node}]
  /\ attempt_input_context' =
      [attempt_input_context EXCEPT
        ![attempt][node] = attempt_context_items[attempt]]
  /\ attempt_envelope_trace' =
      [attempt_envelope_trace EXCEPT
        ![attempt][node] = attempt_trace[attempt]]
  /\ result' = [accepted |-> FALSE, reason |-> "NODE_NOT_PASSED"]
  /\ UNCHANGED replay_integrity_vars
  /\ UNCHANGED << allocated_attempts, attempt_graph, attempt_mode,
                  attempt_source, attempt_selected_node, attempt_plan,
                  attempt_passed_nodes, attempt_context_items,
                  attempt_initial_context, attempt_trace, attempt_carrier,
                  attempt_report_nodes, attempt_report_status,
                  attempt_report_complete, attempt_report_writers,
                  attempt_report_last_writer >>
  /\ UNCHANGED legacy_program_vars
  /\ UNCHANGED legacy_environment_scaffold_vars

\* @command FinishAttemptSuccess
\* @result WorkflowResult
\* @port TestGraphProgramPort.finish_attempt_success
FinishAttemptSuccess(attempt) ==
  /\ attempt \in active_attempts
  /\ attempt_passed_nodes[attempt] = AttemptNodes(attempt)
  /\ active_attempts' = active_attempts \ {attempt}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED replay_integrity_vars
  /\ UNCHANGED << allocated_attempts, attempt_graph, attempt_mode,
                  attempt_source, attempt_selected_node, attempt_plan,
                  attempt_passed_nodes, attempt_terminal_nodes,
                  attempt_envelopes, attempt_context_items,
                  attempt_saved_contexts, attempt_input_context,
                  attempt_initial_context, attempt_trace, attempt_carrier,
                  attempt_envelope_trace, attempt_report_nodes,
                  attempt_report_status, attempt_report_complete,
                  attempt_report_writers, attempt_report_last_writer >>
  /\ UNCHANGED legacy_program_vars
  /\ UNCHANGED legacy_environment_scaffold_vars

\* @command PublishAttemptClosure
\* @result WorkflowResult
\* @port TestGraphProgramPort.publish_attempt_closure
PublishAttemptClosure(attempt) ==
  /\ attempt \in allocated_attempts \ active_attempts
  /\ attempt \notin attempt_closed
  /\ attempt_closed' = attempt_closed \cup {attempt}
  /\ attempt_closure_fingerprint' =
      [attempt_closure_fingerprint EXCEPT
        ![attempt] = CurrentAttemptEvidenceFingerprint(attempt)]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << legacy_program_vars, allocated_attempts, active_attempts,
                  attempt_graph, attempt_mode, attempt_source,
                  attempt_selected_node, attempt_plan,
                  attempt_passed_nodes, attempt_terminal_nodes,
                  attempt_envelopes, attempt_context_items,
                  attempt_saved_contexts, attempt_input_context,
                  attempt_initial_context, attempt_trace, attempt_carrier,
                  attempt_envelope_trace, attempt_report_nodes,
                  attempt_report_status, attempt_report_complete,
                  attempt_report_writers, attempt_report_last_writer,
                  attempt_evidence_tampered, acquired_replay_sources,
                  acquired_replay_context, acquired_replay_trace,
                  acquired_replay_carrier,
                  acquired_replay_closure_fingerprint >>
  /\ UNCHANGED legacy_environment_scaffold_vars

\* @command AcquireReplaySource
\* @result WorkflowResult
\* @port TestGraphProgramPort.acquire_replay_source
AcquireReplaySource(source, selected) ==
  LET key == ReplayKey(source, selected)
  IN
    /\ source \in attempt_closed
    /\ attempt_mode[source] = "full"
    /\ attempt_plan[source] = GraphPlan
    /\ selected \in attempt_saved_contexts[source]
    /\ attempt_input_context[source][selected] =
         ExpectedNodeInputContext(source, selected)
    /\ key \notin acquired_replay_sources
    /\ ClosureMatchesCurrentEvidence(source)
    /\ acquired_replay_sources' = acquired_replay_sources \cup {key}
    /\ acquired_replay_context' =
        [acquired_replay_context EXCEPT
          ![key] = attempt_input_context[source][selected]]
    /\ acquired_replay_trace' =
        [acquired_replay_trace EXCEPT ![key] = attempt_trace[source]]
    /\ acquired_replay_carrier' =
        [acquired_replay_carrier EXCEPT ![key] = attempt_carrier[source]]
    /\ acquired_replay_closure_fingerprint' =
        [acquired_replay_closure_fingerprint EXCEPT
          ![key] = attempt_closure_fingerprint[source]]
    /\ result' = [accepted |-> TRUE, reason |-> NoReason]
    /\ UNCHANGED << legacy_program_vars, allocated_attempts, active_attempts,
                    attempt_graph, attempt_mode, attempt_source,
                    attempt_selected_node, attempt_plan,
                    attempt_passed_nodes, attempt_terminal_nodes,
                    attempt_envelopes, attempt_context_items,
                    attempt_saved_contexts, attempt_input_context,
                    attempt_initial_context, attempt_trace, attempt_carrier,
                    attempt_envelope_trace, attempt_report_nodes,
                    attempt_report_status, attempt_report_complete,
                    attempt_report_writers, attempt_report_last_writer,
                    attempt_closed, attempt_evidence_tampered,
                    attempt_closure_fingerprint >>
    /\ UNCHANGED legacy_environment_scaffold_vars

\* @command TamperClosedAttemptEvidence
\* @result WorkflowResult
TamperClosedAttemptEvidence(source) ==
  /\ source \in attempt_closed \ attempt_evidence_tampered
  /\ attempt_evidence_tampered' = attempt_evidence_tampered \cup {source}
  \* A report consumer revalidates the current closure before trusting a
  \* derived status. Model that trust view directly: an existing report is
  \* immediately non-green once its evidence no longer matches its closure.
  /\ attempt_report_status' =
      [attempt_report_status EXCEPT
        ![source] = IF attempt_report_writers[source] = {}
                    THEN "none"
                    ELSE "errored"]
  /\ attempt_report_complete' =
      [attempt_report_complete EXCEPT ![source] = FALSE]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << legacy_program_vars, allocated_attempts, active_attempts,
                  attempt_graph, attempt_mode, attempt_source,
                  attempt_selected_node, attempt_plan,
                  attempt_passed_nodes, attempt_terminal_nodes,
                  attempt_envelopes, attempt_context_items,
                  attempt_saved_contexts, attempt_input_context,
                  attempt_initial_context, attempt_trace, attempt_carrier,
                  attempt_envelope_trace, attempt_report_nodes,
                  attempt_report_writers, attempt_report_last_writer,
                  attempt_closed, attempt_closure_fingerprint,
                  acquired_replay_sources, acquired_replay_context,
                  acquired_replay_trace, acquired_replay_carrier,
                  acquired_replay_closure_fingerprint >>
  /\ UNCHANGED legacy_environment_scaffold_vars

\* @command RejectTamperedReplaySource
\* @result WorkflowResult
RejectTamperedReplaySource(source, selected) ==
  LET key == ReplayKey(source, selected)
  IN
    /\ source \in attempt_closed
    /\ selected \in attempt_saved_contexts[source]
    /\ key \notin acquired_replay_sources
    /\ ~ClosureMatchesCurrentEvidence(source)
    /\ result' = [accepted |-> FALSE, reason |-> "SOURCE_EVIDENCE_TAMPERED"]
    /\ UNCHANGED base_program_vars
    /\ UNCHANGED legacy_environment_scaffold_vars

\* @command WriteInlineAttemptReport
\* @result WorkflowResult
\* @port TestGraphProgramPort.write_inline_attempt_report
WriteInlineAttemptReport(attempt) ==
  /\ attempt \in allocated_attempts \ active_attempts
  /\ attempt_envelopes[attempt] /= {}
  /\ attempt_report_writers[attempt] = {}
  /\ attempt_report_nodes' =
      [attempt_report_nodes EXCEPT ![attempt] = attempt_envelopes[attempt]]
  /\ attempt_report_status' =
      [attempt_report_status EXCEPT ![attempt] = InlineReportStatus(attempt)]
  /\ attempt_report_complete' =
      [attempt_report_complete EXCEPT ![attempt] = ReportIsComplete(attempt)]
  /\ attempt_report_writers' =
      [attempt_report_writers EXCEPT ![attempt] = @ \cup {"inline"}]
  /\ attempt_report_last_writer' =
      [attempt_report_last_writer EXCEPT ![attempt] = "inline"]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED replay_integrity_vars
  /\ UNCHANGED << allocated_attempts, active_attempts, attempt_graph,
                  attempt_mode, attempt_source, attempt_selected_node,
                  attempt_plan, attempt_passed_nodes,
                  attempt_terminal_nodes, attempt_envelopes,
                  attempt_context_items, attempt_saved_contexts,
                  attempt_input_context, attempt_initial_context,
                  attempt_trace, attempt_carrier, attempt_envelope_trace >>
  /\ UNCHANGED legacy_program_vars
  /\ UNCHANGED legacy_environment_scaffold_vars

\* @command RegenerateAttemptReport
\* @result WorkflowResult
\* @port TestGraphProgramPort.regenerate_attempt_report
RegenerateAttemptReport(attempt) ==
  /\ attempt \in allocated_attempts \ active_attempts
  /\ attempt_envelopes[attempt] /= {}
  /\ attempt_report_nodes' =
      [attempt_report_nodes EXCEPT ![attempt] = attempt_envelopes[attempt]]
  /\ attempt_report_status' =
      [attempt_report_status EXCEPT ![attempt] = ComputedReportStatus(attempt)]
  /\ attempt_report_complete' =
      [attempt_report_complete EXCEPT ![attempt] = ReportIsComplete(attempt)]
  /\ attempt_report_writers' =
      [attempt_report_writers EXCEPT ![attempt] = @ \cup {"manual"}]
  /\ attempt_report_last_writer' =
      [attempt_report_last_writer EXCEPT ![attempt] = "manual"]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED replay_integrity_vars
  /\ UNCHANGED << allocated_attempts, active_attempts, attempt_graph,
                  attempt_mode, attempt_source, attempt_selected_node,
                  attempt_plan, attempt_passed_nodes,
                  attempt_terminal_nodes, attempt_envelopes,
                  attempt_context_items, attempt_saved_contexts,
                  attempt_input_context, attempt_initial_context,
                  attempt_trace, attempt_carrier, attempt_envelope_trace >>
  /\ UNCHANGED legacy_program_vars
  /\ UNCHANGED legacy_environment_scaffold_vars

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
  \/ \E g \in Graphs:
      ConfigureSideEffectRuntime(g)
  \/ \E g \in Graphs:
      ConfigureProvisioningState(g)
  \/ \E g \in Graphs, b \in Branches:
      RegisterFeatureBranch(g, b)
  \/ \E g \in Graphs:
      ConfigureEnvironmentRepository(g)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      DeclareBranchEnvironment(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      ProvisionBranchEnvironment(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      ReuseBranchEnvironment(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      DeployApplicationToBranchEnvironment(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      PropagateEnvironmentContext(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      ResetBranchEnvironment(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      RequestMergedBranchDestroy(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      DestroyMergedBranchEnvironment(g, b, target, backend)
  \/ ScaffoldEnvironmentRepository
  \/ \E target \in EnvironmentTargets, backend \in EnvironmentBackends:
      ScaffoldEnvironmentTemplate(target, backend)
  \/ \E command \in LifecycleCommands:
      ScaffoldLifecycleNodeTemplate(command)
  \/ \E runtime \in NodeRuntimes:
      VerifyEnvironmentContextRuntime(runtime)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      GuardAwsBranchEnvironment(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      SkipBranchEnvironmentReset(g, b, target, backend)
  \/ \E g \in Graphs, b \in Branches,
        target \in EnvironmentTargets, backend \in EnvironmentBackends:
      SkipBranchEnvironmentDestroy(g, b, target, backend)
  \/ \E g \in Graphs, attempt \in RunAttempts,
        trace \in TraceIds, carrier \in TraceCarriers:
      StartFullAttempt(g, attempt, trace, carrier)
  \/ \E source \in RunAttempts, attempt \in RunAttempts,
        mode \in {"resume", "run-only"}, selected \in SourceNodes:
      StartReplayAttempt(source, attempt, mode, selected)
  \/ \E attempt \in RunAttempts, node \in SourceNodes:
      RunAttemptNodePass(attempt, node)
  \/ \E attempt \in RunAttempts, node \in SourceNodes:
      RunAttemptNodeTerminal(attempt, node)
  \/ \E attempt \in RunAttempts:
      FinishAttemptSuccess(attempt)
  \/ \E attempt \in RunAttempts:
      PublishAttemptClosure(attempt)
  \/ \E source \in RunAttempts, selected \in SourceNodes:
      AcquireReplaySource(source, selected)
  \/ \E source \in RunAttempts:
      TamperClosedAttemptEvidence(source)
  \/ \E source \in RunAttempts, selected \in SourceNodes:
      RejectTamperedReplaySource(source, selected)
  \/ \E attempt \in RunAttempts:
      WriteInlineAttemptReport(attempt)
  \/ \E attempt \in RunAttempts:
      RegenerateAttemptReport(attempt)
  \/ NoOp

\* Replay.cfg deliberately permits one full source plus one focused replay.
\* Keeping these restrictions in the transition relation, rather than a TLC
\* state constraint, ensures invalid acquisition/tamper post-states are still
\* explored and can violate the safety properties.
FullAttempts ==
  {attempt \in allocated_attempts : attempt_mode[attempt] = "full"}

StartResumeAttempt(source, attempt) ==
  StartReplayAttempt(source, attempt, "resume", PlanSecondNode)

StartRunOnlyAttempt(source, attempt) ==
  StartReplayAttempt(source, attempt, "run-only", PlanSecondNode)

ReplayNext ==
  \/ \E g \in Graphs:
      PrepareReplayGraph(g)
  \/ \E g \in Graphs, attempt \in RunAttempts,
        trace \in TraceIds, carrier \in TraceCarriers:
      StartFullAttempt(g, attempt, trace, carrier)
  \/ \E source \in FullAttempts, attempt \in RunAttempts:
      StartResumeAttempt(source, attempt)
  \/ \E source \in FullAttempts, attempt \in RunAttempts:
      StartRunOnlyAttempt(source, attempt)
  \/ \E attempt \in RunAttempts, node \in SourceNodes:
      RunAttemptNodePass(attempt, node)
  \/ \E attempt \in RunAttempts, node \in SourceNodes:
      RunAttemptNodeTerminal(attempt, node)
  \/ \E attempt \in RunAttempts:
      FinishAttemptSuccess(attempt)
  \/ \E attempt \in RunAttempts:
      PublishAttemptClosure(attempt)
  \/ \E source \in FullAttempts:
      AcquireReplaySource(source, PlanSecondNode)
  \/ \E source \in FullAttempts:
      TamperClosedAttemptEvidence(source)
  \/ \E source \in FullAttempts:
      RejectTamperedReplaySource(source, PlanSecondNode)
  \/ \E attempt \in RunAttempts:
      WriteInlineAttemptReport(attempt)
  \/ \E attempt \in RunAttempts:
      RegenerateAttemptReport(attempt)

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
  /\ rerun_guidance \in [Graphs -> SUBSET SourceNodes]
  /\ resumed_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ single_node_reruns \in [Graphs -> SUBSET SourceNodes]
  /\ run_reports \subseteq Graphs
  /\ package_catalog \subseteq Packages
  /\ side_effect_runtime_configured \subseteq Graphs
  /\ provisioning_state_configured \subseteq Graphs
  /\ feature_branches \in [Graphs -> SUBSET Branches]
  /\ environment_repo_configured \subseteq Graphs
  /\ branch_environment_specs \subseteq AllBranchEnvironments
  /\ provisioned_branch_environments \subseteq AllBranchEnvironments
  /\ reused_branch_environments \subseteq AllBranchEnvironments
  /\ reset_branch_environments \subseteq AllBranchEnvironments
  /\ deployed_branch_environments \subseteq AllBranchEnvironments
  /\ propagated_environment_contexts \subseteq AllBranchEnvironments
  /\ merge_destroy_requested \subseteq AllBranchEnvironments
  /\ destroy_authorized_environments \subseteq AllBranchEnvironments
  /\ destroyed_branch_environments \subseteq AllBranchEnvironments
  /\ environment_context_keys \in [AllBranchEnvironments -> SUBSET ContextKeys]
  /\ environment_repository_scaffolded \in BOOLEAN
  /\ scaffolded_environment_templates \subseteq AllEnvironmentTemplates
  /\ scaffolded_lifecycle_node_templates \subseteq LifecycleCommands
  /\ runtime_environment_context_verified \subseteq NodeRuntimes
  /\ aws_execution_guarded \subseteq AllBranchEnvironments
  /\ skipped_reset_environments \subseteq AllBranchEnvironments
  /\ skipped_destroy_environments \subseteq AllBranchEnvironments
  /\ RequiredEnvironmentContext \subseteq ContextKeys
  /\ RequiredEnvironmentRuntimes \subseteq NodeRuntimes
  /\ LifecycleCommands /= {}
  /\ AwsTargets \subseteq EnvironmentTargets
  /\ AwsBackends \subseteq EnvironmentBackends
  /\ RunAttempts \cap {NoAttempt} = {}
  /\ Graphs \cap {NoGraph} = {}
  /\ SourceNodes \cap {NoNode} = {}
  /\ TraceIds \cap {NoTrace} = {}
  /\ TraceCarriers \cap {NoCarrier} = {}
  /\ PlanLength \in 1..3
  /\ {PlanFirstNode, PlanSecondNode, PlanThirdNode} \subseteq SourceNodes
  /\ GraphPlan \in Seq(SourceNodes)
  /\ UniqueNodeSequence(GraphPlan)
  /\ allocated_attempts \subseteq RunAttempts
  /\ active_attempts \subseteq allocated_attempts
  /\ attempt_graph \in [RunAttempts -> Graphs \cup {NoGraph}]
  /\ attempt_mode \in
      [RunAttempts -> {"none", "full", "resume", "run-only"}]
  /\ attempt_source \in [RunAttempts -> RunAttempts \cup {NoAttempt}]
  /\ attempt_selected_node \in [RunAttempts -> SourceNodes \cup {NoNode}]
  /\ \A attempt \in RunAttempts:
      attempt_plan[attempt] \in Seq(SourceNodes)
  /\ attempt_passed_nodes \in [RunAttempts -> SUBSET SourceNodes]
  /\ attempt_terminal_nodes \in [RunAttempts -> SUBSET SourceNodes]
  /\ attempt_envelopes \in [RunAttempts -> SUBSET SourceNodes]
  /\ attempt_context_items \in [RunAttempts -> Seq(AllContextItemFingerprints)]
  /\ attempt_saved_contexts \in [RunAttempts -> SUBSET SourceNodes]
  /\ attempt_input_context \in
      [RunAttempts -> [SourceNodes -> Seq(AllContextItemFingerprints)]]
  /\ attempt_initial_context \in
      [RunAttempts -> Seq(AllContextItemFingerprints)]
  /\ attempt_trace \in [RunAttempts -> TraceIds \cup {NoTrace}]
  /\ attempt_carrier \in [RunAttempts -> TraceCarriers \cup {NoCarrier}]
  /\ attempt_envelope_trace \in
      [RunAttempts -> [SourceNodes -> TraceIds \cup {NoTrace}]]
  /\ attempt_report_nodes \in [RunAttempts -> SUBSET SourceNodes]
  /\ attempt_report_status \in
      [RunAttempts -> {"none", "passed", "failed", "errored"}]
  /\ attempt_report_complete \in [RunAttempts -> BOOLEAN]
  /\ attempt_report_writers \in
      [RunAttempts -> SUBSET {"inline", "manual"}]
  /\ attempt_report_last_writer \in
      [RunAttempts -> {"none", "inline", "manual"}]
  /\ attempt_closed \subseteq allocated_attempts
  /\ attempt_evidence_tampered \subseteq attempt_closed
  /\ DOMAIN attempt_closure_fingerprint = RunAttempts
  /\ \A attempt \in RunAttempts:
      attempt_closure_fingerprint[attempt] \in
        {NoEvidenceFingerprint, CanonicalAttemptEvidenceFingerprint(attempt)}
  /\ acquired_replay_sources \subseteq ReplayKeys
  /\ acquired_replay_context \in
      [ReplayKeys -> Seq(AllContextItemFingerprints)]
  /\ acquired_replay_trace \in [ReplayKeys -> TraceIds \cup {NoTrace}]
  /\ acquired_replay_carrier \in
      [ReplayKeys -> TraceCarriers \cup {NoCarrier}]
  /\ DOMAIN acquired_replay_closure_fingerprint = ReplayKeys
  /\ \A key \in ReplayKeys:
      acquired_replay_closure_fingerprint[key] \in
        {NoEvidenceFingerprint} \cup
        {attempt_closure_fingerprint[attempt] : attempt \in RunAttempts}
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
  \A attempt \in allocated_attempts:
    attempt_passed_nodes[attempt] \cup attempt_terminal_nodes[attempt] =
      attempt_envelopes[attempt]

\* @invariant EveryAttemptHasSavedInputContext
EveryAttemptHasSavedInputContext ==
  \A attempt \in allocated_attempts:
    attempt_envelopes[attempt] = attempt_saved_contexts[attempt]

\* @invariant RerunGuidanceOnlyForRerunnableFailures
RerunGuidanceOnlyForRerunnableFailures ==
  \A g \in Graphs:
    /\ rerun_guidance[g] \subseteq terminal_nodes[g]
    /\ rerun_guidance[g] \subseteq rerunnable_nodes

\* @invariant ResumptionsUseSavedInputContext
ResumptionsUseSavedInputContext ==
  \A attempt \in ReplayAttempts:
    LET source == attempt_source[attempt]
        selected == attempt_selected_node[attempt]
        key == ReplayKey(source, selected)
    IN
      /\ selected \in attempt_saved_contexts[source]
      /\ key \in acquired_replay_sources
      /\ attempt_initial_context[attempt] = acquired_replay_context[key]
      /\ acquired_replay_context[key] =
          attempt_input_context[source][selected]

\* @invariant BuildRerunsRespectDependencies
BuildRerunsRespectDependencies ==
  \A attempt \in ReplayAttempts:
    SequencePredecessors(GraphPlan, attempt_selected_node[attempt]) \subseteq
      ContextNodeIds(attempt_initial_context[attempt])

\* @invariant ReportsHaveEnvelopeEvidence
ReportsHaveEnvelopeEvidence ==
  \A g \in run_reports:
    envelopes[g] /= {}

\* @invariant AttemptIdentityIsGraphScoped
AttemptIdentityIsGraphScoped ==
  \A attempt \in allocated_attempts:
    /\ attempt_graph[attempt] \in Graphs
    /\ attempt_mode[attempt] \in {"full", "resume", "run-only"}
    /\ attempt_plan[attempt] /= <<>>
    /\ UniqueNodeSequence(attempt_plan[attempt])
    /\ AttemptNodes(attempt) \subseteq SequenceNodes(GraphPlan)
    /\ attempt_trace[attempt] \in TraceIds
    /\ attempt_carrier[attempt] \in TraceCarriers

\* @invariant ReplayScopeMatchesMode
ReplayScopeMatchesMode ==
  /\ \A attempt \in allocated_attempts:
      attempt_mode[attempt] = "full" =>
        /\ attempt_source[attempt] = NoAttempt
        /\ attempt_selected_node[attempt] = NoNode
        /\ attempt_plan[attempt] = GraphPlan
        /\ attempt_initial_context[attempt] = <<>>
  /\ \A attempt \in ReplayAttempts:
      LET source == attempt_source[attempt]
          selected == attempt_selected_node[attempt]
      IN
        /\ source \in allocated_attempts
        /\ source /= attempt
        /\ selected \in SequenceNodes(GraphPlan)
        /\ attempt_graph[attempt] = attempt_graph[source]
        /\ IF attempt_mode[attempt] = "resume"
           THEN attempt_plan[attempt] = SequenceTailFrom(GraphPlan, selected)
           ELSE attempt_plan[attempt] = <<selected>>

\* @invariant ReplaySourceAttemptsAreClosed
ReplaySourceAttemptsAreClosed ==
  /\ ReplaySourceAttempts \subseteq allocated_attempts
  /\ ReplaySourceAttempts \subseteq attempt_closed
  /\ ReplaySourceAttempts \cap active_attempts = {}

\* @invariant ReplayTraceCarrierContinuity
ReplayTraceCarrierContinuity ==
  \A attempt \in ReplayAttempts:
    LET source == attempt_source[attempt]
        selected == attempt_selected_node[attempt]
        key == ReplayKey(source, selected)
    IN
      /\ key \in acquired_replay_sources
      /\ attempt_trace[attempt] = acquired_replay_trace[key]
      /\ attempt_carrier[attempt] = acquired_replay_carrier[key]

\* @invariant CarrierIdentifiesOneTrace
CarrierIdentifiesOneTrace ==
  \A first \in allocated_attempts, second \in allocated_attempts:
    attempt_carrier[first] = attempt_carrier[second] =>
      attempt_trace[first] = attempt_trace[second]

\* @invariant FullAttemptsMintIndependentTraceCarriers
FullAttemptsMintIndependentTraceCarriers ==
  \A first \in allocated_attempts, second \in allocated_attempts:
    /\ first /= second
    /\ attempt_mode[first] = "full"
    /\ attempt_mode[second] = "full"
    => /\ attempt_carrier[first] /= attempt_carrier[second]
       /\ attempt_trace[first] /= attempt_trace[second]

\* @invariant AttemptEvidenceIsScoped
AttemptEvidenceIsScoped ==
  \A attempt \in allocated_attempts:
    /\ attempt_passed_nodes[attempt] \subseteq AttemptNodes(attempt)
    /\ attempt_terminal_nodes[attempt] \subseteq AttemptNodes(attempt)
    /\ attempt_envelopes[attempt] \subseteq AttemptNodes(attempt)
    /\ attempt_passed_nodes[attempt] \cap
        attempt_terminal_nodes[attempt] = {}

\* @invariant AttemptContextIsAttemptLocal
AttemptContextIsAttemptLocal ==
  \A attempt \in allocated_attempts:
    /\ attempt_context_items[attempt] = ExpectedAttemptContext(attempt)
    /\ \A node \in attempt_saved_contexts[attempt]:
        /\ attempt_input_context[attempt][node] =
            ExpectedNodeInputContext(attempt, node)
        /\ node \notin ContextNodeIds(attempt_input_context[attempt][node])

\* @invariant AttemptClosuresBindExactEvidence
AttemptClosuresBindExactEvidence ==
  \A attempt \in attempt_closed:
    /\ attempt_closure_fingerprint[attempt] =
         CanonicalAttemptEvidenceFingerprint(attempt)
    /\ (attempt \notin attempt_evidence_tampered =>
          ClosureMatchesCurrentEvidence(attempt))
    /\ (attempt \in attempt_evidence_tampered =>
          ~ClosureMatchesCurrentEvidence(attempt))

\* @invariant TamperedUnacquiredSourcesFailValidation
TamperedUnacquiredSourcesFailValidation ==
  \A source \in attempt_evidence_tampered:
    ~ClosureMatchesCurrentEvidence(source)

\* @invariant AcquiredReplaySnapshotsAreClosureBound
AcquiredReplaySnapshotsAreClosureBound ==
  \A key \in acquired_replay_sources:
    /\ key.source \in attempt_closed
    /\ key.selected \in attempt_saved_contexts[key.source]
    /\ acquired_replay_context[key] =
         attempt_input_context[key.source][key.selected]
    /\ acquired_replay_trace[key] = attempt_trace[key.source]
    /\ acquired_replay_carrier[key] = attempt_carrier[key.source]
    /\ acquired_replay_closure_fingerprint[key] =
         attempt_closure_fingerprint[key.source]

\* @invariant AttemptEnvelopeTraceContinuity
AttemptEnvelopeTraceContinuity ==
  \A attempt \in allocated_attempts, node \in SourceNodes:
    IF node \in attempt_envelopes[attempt]
    THEN attempt_envelope_trace[attempt][node] = attempt_trace[attempt]
    ELSE attempt_envelope_trace[attempt][node] = NoTrace

\* @invariant AttemptReportsAreTruthful
AttemptReportsAreTruthful ==
  \A attempt \in allocated_attempts:
    IF attempt_report_writers[attempt] = {}
    THEN /\ attempt_report_nodes[attempt] = {}
         /\ attempt_report_status[attempt] = "none"
         /\ attempt_report_complete[attempt] = FALSE
         /\ attempt_report_last_writer[attempt] = "none"
    ELSE LET currentStatus ==
               IF attempt_report_last_writer[attempt] = "inline"
               THEN InlineReportStatus(attempt)
               ELSE ComputedReportStatus(attempt)
         IN /\ attempt_report_nodes[attempt] = attempt_envelopes[attempt]
            /\ attempt_report_last_writer[attempt] \in
                attempt_report_writers[attempt]
            \* A report written before closure may remain conservatively
            \* ERRORED/incomplete until explicitly regenerated. It may never
            \* be more optimistic than current closure-validated evidence.
            /\ attempt_report_status[attempt] \in {"errored", currentStatus}
            /\ (attempt_report_complete[attempt] =>
                  ReportIsComplete(attempt))
            /\ (attempt_report_status[attempt] = "passed" =>
                  attempt_report_complete[attempt])

\* @invariant IncompleteAttemptReportsNeverPass
IncompleteAttemptReportsNeverPass ==
  \A attempt \in allocated_attempts:
    attempt_report_status[attempt] = "passed" =>
      /\ attempt_report_complete[attempt]
      /\ attempt_report_nodes[attempt] = AttemptNodes(attempt)

\* Closure fingerprints and already-acquired snapshots are immutable. Raw
\* source evidence may be externally changed, which is modeled explicitly by
\* TamperClosedAttemptEvidence; a change before acquisition fails validation,
\* while a change after acquisition cannot alter the captured values below.
\* @property ClosedEvidenceBindingsAndAcquiredSnapshotsAreImmutable
ReplayEvidenceBindingStep ==
  /\ \A source \in attempt_closed:
      UNCHANGED attempt_closure_fingerprint[source]
  /\ \A key \in acquired_replay_sources:
      UNCHANGED << acquired_replay_context[key],
                   acquired_replay_trace[key],
                   acquired_replay_carrier[key],
                   acquired_replay_closure_fingerprint[key] >>

ClosedEvidenceBindingsAndAcquiredSnapshotsAreImmutable ==
  [][ReplayEvidenceBindingStep]_vars

\* @invariant PackageCatalogIsCompleteAfterScaffold
PackageCatalogIsCompleteAfterScaffold ==
  scaffolded => package_catalog = Packages

\* @invariant ProvisioningStateRequiresSideEffectRuntime
ProvisioningStateRequiresSideEffectRuntime ==
  provisioning_state_configured \subseteq side_effect_runtime_configured

\* @invariant EnvironmentRepositoryRequiresProvisioningState
EnvironmentRepositoryRequiresProvisioningState ==
  environment_repo_configured \subseteq provisioning_state_configured

\* @invariant BranchEnvironmentsAreDeclaredForFeatureBranches
BranchEnvironmentsAreDeclaredForFeatureBranches ==
  \A e \in branch_environment_specs:
    /\ e.graph \in environment_repo_configured
    /\ e.branch \in feature_branches[e.graph]

\* @invariant ProvisionedEnvironmentsAreDeclared
ProvisionedEnvironmentsAreDeclared ==
  provisioned_branch_environments \subseteq branch_environment_specs

\* @invariant ReusedEnvironmentsAreProvisioned
ReusedEnvironmentsAreProvisioned ==
  reused_branch_environments \subseteq provisioned_branch_environments

\* @invariant ResetKeepsBranchEnvironmentProvisioned
ResetKeepsBranchEnvironmentProvisioned ==
  reset_branch_environments \subseteq provisioned_branch_environments

\* @invariant DeployedEnvironmentsHaveRequiredContext
DeployedEnvironmentsHaveRequiredContext ==
  \A e \in deployed_branch_environments:
    /\ e \in provisioned_branch_environments
    /\ RequiredEnvironmentContext \subseteq environment_context_keys[e]

\* @invariant PropagatedEnvironmentContextRequiresProvisionedContext
PropagatedEnvironmentContextRequiresProvisionedContext ==
  \A e \in propagated_environment_contexts:
    /\ e \in provisioned_branch_environments
    /\ RequiredEnvironmentContext \subseteq environment_context_keys[e]

\* @invariant MergeDestroyRequiresExplicitIntent
MergeDestroyRequiresExplicitIntent ==
  destroyed_branch_environments \subseteq destroy_authorized_environments

\* @invariant DestroyedEnvironmentsAreNotActive
DestroyedEnvironmentsAreNotActive ==
  destroyed_branch_environments \cap
    (provisioned_branch_environments \cup reused_branch_environments \cup
     reset_branch_environments \cup deployed_branch_environments \cup
     propagated_environment_contexts) = {}

\* @invariant EnvironmentTemplatesRequireRepositoryScaffold
EnvironmentTemplatesRequireRepositoryScaffold ==
  scaffolded_environment_templates /= {} => environment_repository_scaffolded

\* @invariant LifecycleNodeTemplatesRequireRepositoryScaffold
LifecycleNodeTemplatesRequireRepositoryScaffold ==
  scaffolded_lifecycle_node_templates /= {} => environment_repository_scaffolded

\* @invariant AwsProvisioningRequiresExplicitGuard
AwsProvisioningRequiresExplicitGuard ==
  \A e \in provisioned_branch_environments:
    AwsEnvironment(e) => e \in aws_execution_guarded

\* @invariant SkippedResetEnvironmentsRemainProvisioned
SkippedResetEnvironmentsRemainProvisioned ==
  skipped_reset_environments \subseteq provisioned_branch_environments

\* @invariant SkippedDestroyEnvironmentsRemainActive
SkippedDestroyEnvironmentsRemainActive ==
  /\ skipped_destroy_environments \subseteq provisioned_branch_environments
  /\ skipped_destroy_environments \cap destroyed_branch_environments = {}

\* @invariant RuntimeContextVerificationIsTyped
RuntimeContextVerificationIsTyped ==
  runtime_environment_context_verified \subseteq NodeRuntimes

Spec ==
  Init /\ [][Next]_vars

ReplaySpec ==
  Init /\ [][ReplayNext]_vars

=============================================================================
