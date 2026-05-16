# Workflows

Operational guide for agents using the test-graph skill. Pair with
[`reference.md`](reference.md) only when you need the dense API, DSL, task, or
SDK surface.

## Mental Model

- A node is one small unit of validation work. It declares its own `NodeSpec`
  in code and returns a structured `NodeResult`.
- The script is the source of truth. No YAML sidecar. Discovery invokes each
  script with `--describe-out=<tmp>`.
- A test graph is a named composition declared in a scaffolded project's
  `build.gradle.kts` via `testGraph("name") { ... }`.
- The DSL can add overlays with `.dependsOn(...)`, `.tags(...)`,
  `.timeout(...)`, `.cacheable(...)`, and `.sideEffects(...)`.
- Transitive dependencies are resolved from `sourcesDir("sources")` by matching
  script-declared node ids.
- Data flows downstream through `Context[]`. Publish with
  `NodeResult.publish(key, value)` and read with `ctx.get(upstreamId, key)`.
- Reports are written under `<test_graph>/build/validation-reports/<runId>/`.

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
        node("sources/user_seeded.py")
        node("sources/LoginSmoke.java")
            .dependsOn("user.seeded")
            .tags("regression")
        // app.running can be pulled transitively from script-declared deps
    }
}
```

Every `testGraph("name")` registers a graph. The script metadata and DSL
overlays are merged: collections are unioned, scalars are overridden by the DSL.
The DSL can add constraints; it should not be used to hide script-declared
dependencies.

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
  envelope/
    <node-id>.json
  node-logs/
    <node-id>.<label>.log
  context/
    step-NNN.json
  summary.json
  report.md
```

`<runId>` is timestamp-like, for example `20260428-184103`. Multiple runs
accumulate until `clean.py` or Gradle `clean` removes them. CI should upload
`build/validation-reports/` as a whole.

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

## Symlinked Shared Infrastructure

`scaffold.py` creates these as symlinks into this skill repo by default:

```text
<repo>/test_graph/sdk -> <skill>/project_sdk_sources/sdk
<repo>/test_graph/build-logic -> <skill>/project_sdk_sources/build-logic
```

Do not edit those paths from inside a consumer scaffold. Real SDK or plugin
changes belong in `project_sdk_sources/sdk/` and
`project_sdk_sources/build-logic/` in this repo.

Use `scaffold.py --copy-sdk` only when symlinks are not viable. That creates a
snapshot copy, so future upstream changes require re-scaffolding or manual sync.

## GitHub Actions

Use:

```bash
<skill>/scripts/github-action.py <repo-root>
```

The generated workflow installs the skill, resolves `sdk/` and `build-logic/`,
runs `discover.py`, runs `run.py --all` by default, and uploads
`test_graph/build/validation-reports/`. Read
[`github-actions.md`](github-actions.md) for options and symlink modes.

## Authoring Checklist

Before finalizing work that touches `sources/` or `build.gradle.kts`:

- Run `<skill>/scripts/discover.py`.
- Run `<skill>/scripts/discover.py <graph>` for each affected graph.
- Confirm node ids are dotted, stable, and intentional.
- Confirm every node has exactly one kind and one runtime.
- Confirm all dependencies are declared via script metadata or DSL overlays.
- Confirm downstream data is published through `NodeResult.publish(...)`.
- Run `<skill>/scripts/run.py <graph>` or `<skill>/scripts/run.py --all`.
- Check `build/validation-reports/<runId>/summary.json` and `report.md`.

## Anti-Patterns

- One giant script doing setup, action, assertion, and reporting.
- Hidden ordering through sleeps.
- Node ids renamed without checking downstream references.
- Plain stdout instead of structured `NodeResult`.
- Copy-pasted infrastructure setup instead of shared dependency nodes.
- Duplicating `sdk/` or `build-logic/` edits inside consumer scaffolds.
