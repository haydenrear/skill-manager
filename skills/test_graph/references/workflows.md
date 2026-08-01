# Workflows

Operational guide for agents using the test-graph skill. Pair with
[`reference.md`](reference.md) only when you need the dense API, DSL, task, or
SDK surface.

## Mental Model

- A node is one small unit of validation work. It declares its own `NodeSpec`
  in code and returns a structured `NodeResult`. Node IDs match the portable
  lowercase grammar `[a-z0-9._-]{1,128}`.
- The script is the source of truth. No YAML sidecar. Discovery invokes each
  script with `--describe-out=<tmp>`.
- A test graph is a named composition declared in a scaffolded project's
  `build.gradle.kts` via `testGraph("name") { ... }`.
- The DSL can add overlays with `.dependsOn(...)`, `.tags(...)`,
  `.timeout(...)`, `.cacheable(...)`, and `.sideEffects(...)`.
- Side effects are a typed registry such as `browser`, `net:local`,
  `process:gradle`, `env:[KEY]`, and `environment:provision`; invalid forms
  fail during describe/plan validation before node execution starts.
- Branch-environment marker state lives under
  `build/testgraph-provisioning-state/`. Provision and reset markers are
  written only after successful nodes. Deploy writes application lifecycle
  state. Destroy requires `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` or
  `TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=true` and removes provisioned/deployed
  markers only after the destroy node passes.
- Environment repository metadata is declared through `NodeSpec` as a
  provider-neutral Git repository contract. TG-5C validates source, template,
  target/backend, branch scope, and required output keys; Git clone, OpenTofu,
  env propagation, reset, and guarded destroy execution are covered by the SDK
  contract fixture. AWS provisioning remains later adapter work.
- Script-level `NodeSpec.rerun(false)` opts out of future direct-rerun
  guidance when replaying from saved context is unsafe.
- Transitive dependencies are resolved from `sourcesDir("sources")` by matching
  script-declared node ids.
- Data flows downstream through `Context[]`. Publish with
  `NodeResult.publish(key, value)` and read with `ctx.get(upstreamId, key)`.
- Reports are written under `<test_graph>/build/validation-reports/<runId>/`.
- A resolved graph contains 1–10,000 nodes; larger or empty plans fail before
  node execution.

## Use Scripts First

The skill scripts are the primary agent interface. They auto-detect the active
scaffolded `test_graph` project and keep the common operations easy to find.

| Goal | Preferred command | Raw Gradle equivalent |
| --- | --- | --- |
| List graphs | `<skill>/scripts/discover.py` | `./gradlew validationListGraphs` |
| Plan/render one graph | `<skill>/scripts/discover.py <graph>` | `./gradlew validationPlanGraph --name=<graph>` plus `validationGraphDot` |
| Run one graph | `<skill>/scripts/run.py <graph>` | `./gradlew <graph>` |
| Run every graph | `<skill>/scripts/run.py --all` | `./gradlew validationRunAll` |
| Clean build output | `<skill>/scripts/clean.py` | `./gradlew clean` |

Use raw Gradle directly only when debugging the plugin or when the user
explicitly asks for the underlying task.

## Roots and Auto-Detection

There are two roots:

- `<skill>`: this repo, containing `SKILL.md`, `scripts/`, `templates/`,
  `references/`, and `project_sdk_sources/`.
- `<test_graph>`: the scaffolded project in a user repo, usually
  `<repo>/test_graph/`.

The scripts locate `<test_graph>` in this order:

1. `--test-graph-root` / `-R`
2. `TEST_GRAPH_ROOT`
3. Walk upward from the current directory until `settings.gradle.kts` is found.
4. From a user repo root, use `./test_graph/` if it has `settings.gradle.kts`
   and a `build.gradle.kts` containing `validationGraph`.

That means these work without flags:

```bash
cd <repo>
<skill>/scripts/discover.py
<skill>/scripts/run.py smoke
<skill>/scripts/run.py --all
<skill>/scripts/clean.py
```

and these also work:

```bash
cd <repo>/test_graph
<skill>/scripts/discover.py smoke
<skill>/scripts/run.py smoke
```

## Scaffold a Project

Use when the user wants to add test-graph validation to an existing repo.

```bash
<skill>/scripts/scaffold.py <repo-root>
```

This creates `<repo-root>/test_graph/` from `project_sdk_sources/`. The target
must not already exist with content.

After scaffolding, validate with the wrapper scripts:

```bash
cd <repo-root>
<skill>/scripts/discover.py
<skill>/scripts/discover.py smoke
<skill>/scripts/run.py smoke
```

For all registered graphs:

```bash
<skill>/scripts/run.py --all
```

## Add Nodes

Run node generators from the user repo root or anywhere inside the scaffold.

```bash
<skill>/scripts/new-jbang-node.py checkout.smoke assertion
<skill>/scripts/new-uv-node.py product.seeded fixture
```

Generated files land in `<test_graph>/sources/`:

- JBang: `sources/<ClassName>.java`
- uv: `sources/<snake_name>.py`

Edit the body, keep the generated SDK imports and metadata shape, then wire the
node into `build.gradle.kts` or let it be pulled transitively by a dependency
from another node.

Kinds:

| Kind | Use for |
| --- | --- |
| `testbed` | Provisioning an environment, such as app up or db ready |
| `fixture` | Seeding data required by downstream nodes |
| `action` | Performing an operation such as an API call or UI flow |
| `assertion` | Checking an invariant |
| `evidence` | Collecting artifacts, logs, screenshots, or dumps |
| `report` | Aggregating results into a custom report shape |

Prefer the narrowest kind. Split setup from action and assertion.

## Compose a Graph

Edit the scaffolded project's `build.gradle.kts`:

```kotlin
validationGraph {
    sourcesDir("sources")

    testGraph("smoke") {
        standardNode("monitoring.cluster.assert.ready")
        node("sources/user_seeded.py")
        node("sources/LoginSmoke.java")
            .dependsOn("user.seeded")
            .tags("regression")
        // app.running can be pulled transitively from script-declared deps
    }
}
```

`standardNode("stable.dotted.id")` maps to the shipped
`standard-nodes/stable_dotted_id.py` script. The scaffold keeps that catalog as
a symlink into the installed skill, so consumers compose the contract without
copying provider code. Standard nodes are indexed before `sourcesDir(...)`
entries and therefore cannot be shadowed by a consumer script with the same id.

The shipped monitoring prerequisite is composed from its terminal assertion:

```kotlin
testGraph("monitoringReadiness") {
    standardNode("monitoring.cluster.assert.ready")
}
```

That resolves exactly `monitoring.cluster.ensure` followed by
`monitoring.cluster.assert.ready`. Ensure alone declares
`environment:provision` and a `360s` timeout; assert-ready has no side effects
and a `30s` timeout. They call the installed deploy-helm `monitoring` launcher
by deterministic absolute path. The pair has no `environmentRepository`
metadata because deploy-cdc's public monitoring CLI owns the complete
lifecycle; a repository prelude here would be a second deployment authority.

Coordinator graph-start telemetry happens before the first node. If monitoring
is cold, this first signal may be unavailable and is allowed to be lost. Export
and flush stay bounded and fail-open, the ensure node still launches, and the
coordinator never attempts to provision monitoring itself.

Every `testGraph("name")` registers a graph. The script metadata and DSL
overlays are merged: collections are unioned, scalars are overridden by the DSL.
The DSL can add constraints; it should not be used to hide script-declared
dependencies.

## Branch Environment Repository Contracts

For branch-scoped environments, node metadata can declare an
`environmentRepository` contract. Read
[`environment-repositories.md`](environment-repositories.md) for the
authoritative repository form, Git fixture policy, local k3d setup,
target/backend semantics, lifecycle behavior, and required test graph coverage
for local, GitHub Actions, and AWS targets.

```python
NodeSpec("preview.provision") \
    .kind("testbed") \
    .side_effects(SideEffect.environment("provision")) \
    .environment_repository(
        EnvironmentRepository.of(
            "git@github.com:example/environments.git",
            "templates/local-preview"))
```

Existing validation entry points:

```bash
./scripts/run.py generatedEnvironmentRepositoryFixture --test-graph-root test_graph
./scripts/run.py environmentRepositoryContract --test-graph-root test_graph
./scripts/run.py branchEnvironmentReset --test-graph-root test_graph
TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=1 ./scripts/run.py branchEnvironmentMergeDestroy --test-graph-root test_graph
./scripts/run.py environmentRepositoryDocumentation --test-graph-root test_graph
```

## Discover and Plan

Always discover before running a new or changed graph:

```bash
<skill>/scripts/discover.py
<skill>/scripts/discover.py <graph>
```

`discover.py` with no graph lists all registered graphs.

`discover.py <graph>` prints the topo plan and dependency adjacency, writes
`<test_graph>/docs/<graph>.dot`, and renders `<test_graph>/docs/<graph>.png` if
Graphviz `dot` is installed.

To debug a failing describe call:

```bash
jbang sources/MyNode.java --describe-out=/tmp/spec.json
uv run sources/my_node.py --describe-out=/tmp/spec.json
cat /tmp/spec.json
```

## Run Graphs

Run one graph:

```bash
<skill>/scripts/run.py <graph>
```

Run every registered graph sequentially:

```bash
<skill>/scripts/run.py --all
```

`run.py --all` maps to `validationRunAll`, which chains the graph tasks in
declaration order. This avoids multiple local testbeds competing for shared
resources when Gradle has a wider worker pool.

Each graph task writes its own `summary.json` and `report.md` inline at the end
of execution.

If a graph fails because Python, uv, `PATH`, virtualenv activation, or Gradle
daemon state looks inconsistent, see
[`debug-python-uv-env.md`](debug-python-uv-env.md). In agent or CI contexts,
prefer an explicit `python3` invocation for the skill wrapper and disable the
Gradle daemon while debugging:

```bash
GRADLE_OPTS='-Dorg.gradle.daemon=false' python3 $SKILL_MANAGER_HOME/skills/test-graph/scripts/run.py <graph>
```

### Resume a graph from a saved build

When a previous run already reached the node you need to retry, resume the graph
from that node instead of replaying earlier dependency steps. The selected node
uses its saved input context from the build directory:

```bash
<skill>/scripts/run.py <graph> \
  --resume-from-build <test_graph>/build/validation-reports/<runId> \
  --resume-from-node <node-id>
```

The node must have `rerun=true` in its script metadata. The source must be a
closed full execution (never another replay), its full ordered node plan must
exactly equal the current plan, and its saved `context/<node-id>.input.json`
must equal the exact ordered current-plan prefix before that node. The
executor skips earlier plan steps, runs the selected node, continues through the
remaining graph plan, and writes those selected-to-tail envelopes into a fresh
sibling report directory. Resume first validates closure v2 against the exact
source evidence file set, then captures the selected context and carrier once.
The fresh report continues that captured trace without later source-path reads.
The replay report records its mode, selected node, and source build, and
`execution.complete` covers only the selected-to-tail execution scope.

When a rerunnable node fails, Gradle output and `report.md` include rerun guidance
with both this resume-graph command and the run-only command below.
Nodes declared with `rerun(false)` do not emit those commands.

### Run only one node from a saved build

When you want to debug one node without replaying upstream setup or continuing
downstream graph nodes, run only the selected node from its saved input context:

```bash
<skill>/scripts/run.py <graph> \
  --resume-from-build <test_graph>/build/validation-reports/<runId> \
  --run-only-node <node-id>
```

The selected node must have `rerun=true`, must be in the graph plan, and must
have a saved `context/<node-id>.input.json` in the build directory. The executor
runs only that node and writes a fresh one-node replay report, leaving both the
source build and downstream graph nodes untouched. The report records
`execution.mode=run-only-node`, the selected node, and the source build;
`execution.complete` means that one-node replay scope is complete, not that the
source graph's unselected suffix ran again.

### Rerun only the failing node while debugging

Do not automatically rerun the whole graph after every small fix. If a late node
fails and its upstream dependencies already produced valid context, iterate on
that node directly. This is often much faster than repeatedly replaying the full
graph to reach the same failure point.

Use the failed run's report directory:

```text
<test_graph>/build/validation-reports/<runId>/
  envelope/<node-id>.json
  node-logs/
  context/<node-id>.input.json
```

Inspect `report.md`, `summary.json`, the failed node envelope, and its logs.
Then rerun the node script directly with the standard node args. Reuse the
failed node's saved input context. For Python:

```bash
uv run sources/my_node.py \
  --nodeId=<node-id> \
  --runId=<runId> \
  --reportDir=<test_graph>/build/validation-reports/<runId> \
  --result-out=<test_graph>/build/validation-reports/<runId>/.tmp-results/<node-id>.json \
  --context=@<test_graph>/build/validation-reports/<runId>/context/<node-id>.input.json
```

For Java/JBang:

```bash
jbang sources/MyNode.java \
  --nodeId=<node-id> \
  --runId=<runId> \
  --reportDir=<test_graph>/build/validation-reports/<runId> \
  --result-out=<test_graph>/build/validation-reports/<runId>/.tmp-results/<node-id>.json \
  --context=@<test_graph>/build/validation-reports/<runId>/context/<node-id>.input.json
```

Every attempted node writes an input-context snapshot, including root nodes
whose snapshot contains an empty `items` array. Omit `--context` only when
manually rerunning a root node with no dependencies.

After the targeted node passes, run the containing graph once from the beginning:

```bash
<skill>/scripts/run.py <graph>
```

Rerun from the beginning immediately if the fix changes upstream dependency
behavior, shared fixture/testbed state, graph composition, node ids, context keys
used by other nodes, or anything else that makes the previous run's context
stale.

## Clean

```bash
<skill>/scripts/clean.py
```

This wraps `./gradlew clean` in the scaffolded project and removes `build/`,
including `build/validation-reports/`.

## Reports

Every run writes under the scaffolded project's build directory:

```text
<test_graph>/build/validation-reports/<runId>/
  execution-scope.json
  attempt-closure.json
  trace-context.json
  envelope/
    <node-id>.json
  node-logs/
    <node-id>.<label>.log
  context/
    <node-id>.input.json
  summary.json
  report.md
```

`<runId>` is timestamp-like, for example `20260428-184103`. Multiple runs
accumulate until `clean.py` or Gradle `clean` removes them. CI should upload
`build/validation-reports/` as a whole.

`execution-scope.json` is no-replace run-plan evidence written before execution.
`attempt-closure.json` is no-replace terminal evidence written only after node
execution has ended. Closure v2 binds raw scope/carrier bytes and exact present
context/envelope path-to-SHA-256 maps. A run without a valid closure is not a
replay source, and report regeneration revalidates the closure against current
evidence; a missing or mismatched closure is reported `ERRORED`, never green.
`validationReport` reuses it and the persisted trace carrier, so regeneration
cannot widen a run-only replay to the full graph or turn a partial plan green.
It also reconstructs the exact ordered published-data prefix for every saved
node context and verifies replay input against its captured source snapshot or
the source-context digest persisted in target scope v3; provenance mismatches
are explicit summary errors. These digests detect ordinary/protocol mutation,
not an adversarial owner rewriting both evidence and closure.
Runs created before scope metadata existed are labeled `legacy-unknown` and
remain incomplete/errored when regenerated.

Treat report identity checks as validation evidence: each canonical envelope
filename must match its embedded node ID, and all traced envelopes in a run must
share the persisted run trace. Context/result/envelope JSON is limited to 16
MiB per document; aggregate report inventory is limited to 16 MiB each for
envelopes and contexts plus 500,000 JSON structural tokens. The trace carrier
is limited to 4 KiB. Report enumeration is streamed and capped at 10,000
envelopes.

Node process-group supervision is supported on macOS and Linux and requires
`perl` with its core `POSIX` and `Time::HiRes` modules. Supervisor exit 125
means an orphaned group was reaped and becomes closable `ERRORED` evidence;
exit 124 or descendant-inventory overflow means ownership cannot be proved, so
attempt closure is withheld.

Canonical envelopes use the closed `envelopeVersion: 1` schema. The executor
validates the SDK-authored NodeResult before adding executor-owned fields, then
validates the complete envelope before immutable publication. Closure
publication/acquisition and manual report regeneration call that same complete
validator. Unknown fields require a future version bump, and contradictory
evidence such as `status: passed` with a failed assertion can neither close an
attempt nor produce a green report.

## Dependency Nodes

Reusable infrastructure belongs in dependency nodes, usually `kind=testbed` or
`kind=fixture`.

Examples:

- Docker containers
- language runtimes
- registries
- gateways
- package-manager state
- port allocations
- fixture data
- app/server readiness

A node like `app.running` should be shared by `smoke`, `regression`, and
`nightly` rather than duplicated. Downstream action/assertion nodes should
declare `dependsOn("app.running")`.

## Import User Code

The scaffold lives at `<repo>/test_graph/`. From any node in
`test_graph/sources/`, the user repo root is `../..`.

Java nodes use JBang:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../src/main/java/com/acme/domain/User.java
//SOURCES ../../src/main/java/com/acme/api/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
```

Python nodes use uv inline metadata:

```python
# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk", "acme-domain"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# acme-domain  = { path = "../..", editable = true }
# ///
```

Concrete examples are in:

- `project_sdk_sources/sources/AppRunning.java`
- `project_sdk_sources/sources/LoginSmoke.java`
- `project_sdk_sources/sources/NetworkPingable.java`
- `project_sdk_sources/sources/user_seeded.py`
- `templates/jbang-node.java.template`
- `templates/uv-node.py.template`
- `project_sdk_sources/build.gradle.kts`

## Managed Shared Infrastructure

New scaffolds commit `test_graph/provider-bindings.json` and ignore these
generated runtime links:

```text
<repo>/test_graph/sdk
<repo>/test_graph/build-logic
<repo>/test_graph/standard-nodes
```

`discover.py` and `run.py` materialize the links before Gradle starts. A
`workspace-relative` provider candidate is tried first when present, followed
by the skill checkout running the wrapper. Workspace links are relative;
installed-skill links are absolute runtime details. The manifest and provider
content are durable. Literal symlink text is not.

Legacy committed symlinks remain supported and are never rewritten
automatically. The next `discover.py` or `run.py` invocation prints a migration
notice on stderr. Migrate explicitly from the consuming repository with:

```bash
<skill>/scripts/migrate-bindings.py
```

For an integration repository whose Test Graph provider is a sibling, add the
portable first choice relative to `<consumer>/test_graph/`:

```bash
<skill>/scripts/migrate-bindings.py --workspace-provider ../../test_graph
```

A `--workspace-provider` that is passed must resolve to a complete provider at
migration time; migration refuses instead of binding to the installed skill in
its place. The manifest is committed and read by every consumer of the
repository, so a path that resolves nowhere would leave all of them
materializing an absolute installed-skill link. Omit the flag to bind to the
installed skill deliberately. At run time the fallback still applies:
`prepare-bindings.py` and the wrappers move on to the next candidate when a
workspace provider is absent on that machine.

Migration is all or nothing. Whatever it cannot complete it does not begin, and
a failure after writing has started is rolled back - manifest, ignore block,
generated links and the staged index entries - so the project stays on the
working legacy path.

Migration refuses real copied directories, adds the manifest and ignore rules,
and stages removal of only the three generated links from Git's index. Review
and commit those changes. `prepare-bindings.py` is the explicit idempotent
materialization command; normal wrapper use does not require calling it.

Migration writes the **canonical three-binding set** even when the legacy
project tracked only two links, and prints which bindings it added — or, when it
added none, that it added none. The binding set names subtrees of the provider,
not of the consumer, so it does not vary per repository; a project that later
opens a `standardNode(...)` needs no second migration. `standard-nodes` is
optional at build time and the generated links are gitignored, so the extra link
has no effect on the migrated projects — though it is *conditional*, not inert:
once the directory exists the catalog is indexed and provider node ids take
precedence over consumer ones. The full reasoning, the measurement it was
decided on, and the two precedence paths to check are in
`migrate-bindings.py`'s module docstring under "Why migration normalizes".

Because it normalizes, migration also checks the generated bindings against the
project manifest before writing anything, and prints one of three
`vendored-agreement:` verdicts. Every verdict names the search ceiling it
stopped at.

| verdict | meaning | outcome |
| --- | --- | --- |
| `ok` | some manifest declares all three bindings | migrates |
| `partial` | a manifest declares some of them and not the rest | **refuses**, prints the `paths` list to paste back |
| `unclaimed` | no manifest declares any of them | migrates, reports that nothing validates them, prints the block to add |

`skill-manager project resolve` classifies declared `[[vendored]]` paths only,
so an undeclared generated link is not a lax check — it is no check, and a link
that resolves into a foreign home would report clean. `partial` is a genuine
disagreement between two records of the same fact and is always fixable by
extending a block that already reaches into the project, so it refuses.
`unclaimed` is the *absence* of the second record: it is reported rather than
refused, because an integration repository's root manifest declares nothing
about a constituent's internals by design and a refusal there could not be
cleared.

Manifests are searched nearest-first under both names
`SkillProjectParser.findManifest` accepts (`skill-project.toml`, then
`skill-manager-project.toml`), and a manifest counts only when it already
declares a path inside the project, compared by resolved physical path. The
ceiling is the nearest enclosing **integration root** (`integration.toml` or
`INTEGRATION.md`) if there is one, otherwise the nearest `.git`. It is
deliberately not `git rev-parse --show-toplevel`: a constituent carries `.git`
in an integration parent's main working tree and none in an outer worktree, so
that ceiling gave two different answers for identical bytes.

Do not edit generated binding paths from inside a consumer scaffold. Real SDK,
plugin, or standard-node changes belong in `project_sdk_sources/sdk/`,
`project_sdk_sources/build-logic/`, and `project_sdk_sources/standard-nodes/`
in the provider repository.

Use `scaffold.py --copy-sdk` only when managed symlinks are not viable. That creates a
snapshot copy, so future upstream changes require re-scaffolding or manual sync.

## GitHub Actions

Use:

```bash
<skill>/scripts/github-action.py <repo-root>
```

The generated workflow installs the skill, resolves `sdk/`, `build-logic/`, and
`standard-nodes/`, runs `discover.py`, runs `run.py --all` by default, and uploads
`test_graph/build/validation-reports/`. Read
[`github-actions.md`](github-actions.md) for options and symlink modes.

## Authoring Checklist

Before finalizing work that touches `sources/` or `build.gradle.kts`:

- Run `<skill>/scripts/discover.py`.
- Run `<skill>/scripts/discover.py <graph>` for each affected graph.
- Confirm node ids match `[a-z0-9._-]{1,128}`, are stable, and are intentional.
- Confirm the resolved graph contains 1–10,000 nodes.
- Confirm every node has exactly one kind and one runtime.
- Confirm all dependencies are declared via script metadata or DSL overlays.
- Confirm downstream data is published through `NodeResult.publish(...)`.
- Run `<skill>/scripts/run.py <graph>` or `<skill>/scripts/run.py --all`.
- Check `build/validation-reports/<runId>/summary.json` and `report.md`.
- Confirm the report is not flagging missing, invalid, or mismatched node/trace
  identities.

## Anti-Patterns

- One giant script doing setup, action, assertion, and reporting.
- Hidden ordering through sleeps.
- Node ids renamed without checking downstream references.
- Plain stdout instead of structured `NodeResult`.
- Copy-pasted infrastructure setup instead of shared dependency nodes.
- Duplicating `sdk/`, `build-logic/`, or `standard-nodes/` edits inside consumer scaffolds.
