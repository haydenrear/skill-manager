---
name: test-graph
description: Work with the test-graph validation system — scaffold a test_graph project into a user repo, add JBang/uv nodes, compose test graphs in build.gradle.kts, run them, aggregate reports. Use whenever the user asks to set up validation nodes, compose a validation graph, or extend an existing test_graph project.
---

# test-graph skill

You are helping the user build or extend a **test_graph** project — a polyglot validation graph where each node is a small executable script (JBang or uv) that self-describes its metadata and returns a structured `NodeResult`. A Gradle plugin discovers, orders, and runs the graph; reports aggregate through a common envelope.

This whole repo IS the skill. The skill's scripts live in `scripts/`, templates in `templates/`, and the scaffold payload (what gets copied into the user's repo) lives in `project_sdk_sources/`. Read [`constitution.md`](constitution.md) (durable principles) and [`initial-spec.md`](initial-spec.md) (v0 design) for background. Full API / DSL / task reference: [`reference.md`](reference.md).

## Core mental model (60 seconds)

- A **node** is one small unit of validation work. It declares its own `NodeSpec` in-code (id, kind, runtime, `dependsOn`, tags, etc.) and returns a `NodeResult`.
- The **script is the source of truth**. No YAML sidecar. The plugin invokes each script with `--describe-out=<tmp>` to discover metadata, and with full context args to execute.
- A **test graph** is a named composition declared in the scaffolded project's `build.gradle.kts` via `testGraph("X") { ... }`. Each one is a Gradle task — `./gradlew X` runs it.
- Inside a graph, `node("path/to/script")` pulls in a script. Chain `.dependsOn(...)`, `.tags(...)`, `.timeout(...)` to **add rules from the DSL** on top of what the script self-declared. Both sources of edges feed the topo sort.
- Transitives — scripts not listed in the DSL but named in some node's `dependsOn` — are resolved from the registered `sourcesDir("...")`.
- Data flows downstream through an accumulated `Context[]` — each upstream node contributes one `ContextItem { nodeId, data }`. Publish with `NodeResult.publish(k, v)`; read with `ctx.get(upstreamId, key)`.
- Reports are one JSON envelope per node, aggregated into `summary.json` per run, all under `build/validation-reports/<runId>/` of the scaffolded project.

## Dependency nodes are the reuse seam

Anything that provisions or tears down infrastructure — Docker containers, language runtimes, registries, gateways, package-manager state, port allocations, fixture data — belongs in a **dependency node** (typically `kind=testbed` or `kind=fixture`). Action / assertion nodes then `dependsOn(...)` those, and the same dependency node is shared across every graph that needs it.

A node like `app.running` should be a single `sources/AppRunning.java` (or `.py`) that's reused unmodified by `smoke`, `regression`, `nightly`, etc. — not duplicated. That's the whole point of the topo-sorted DAG: any node, no matter how heavy its setup, runs once per execution and downstream nodes pick up its `Context` output.

Don't push infrastructure work into action / assertion nodes. If two graphs need the same testbed, they should both depend on the same testbed node. If you find yourself copy-pasting setup logic between nodes, extract a new dependency node and have both depend on it.

## Test contracts run against real code

Node scripts live next to the user's repo (`<user-repo>/test_graph/sources/`), so they can `//SOURCES ../../src/main/java/...` (JBang) or `[tool.uv.sources]` at `../..` (Python) to pull production sources directly into the validation runtime. That means:

- The test exercises the same classes / modules the production app does — no parallel "test-only" port of the contract.
- Refactors in production code surface as compile breaks in the node (JBang) or import errors (uv), not as silent test rot.
- Contracts are visible: a node's `//SOURCES` block lists exactly which production files it depends on.

Pull in only the specific files / packages a node needs (be specific with globs); see the **Importing user code into a node** section below for the patterns.

## `sdk/` and `build-logic/` are symlinks into this skill repo

When `scaffold.py` lays out a new `<repo>/test_graph/`, two of its subtrees are created as symlinks rather than copies:

- `<repo>/test_graph/sdk/`        →  `<skill>/project_sdk_sources/sdk/`
- `<repo>/test_graph/build-logic/` → `<skill>/project_sdk_sources/build-logic/`

That's deliberate: the SDK helpers (Java + Python) and the Gradle plugin/tasks/executors are shared infrastructure that should stay byte-identical across every scaffold. Symlinking means an upstream upgrade (toolchain pinning, new task, plugin fix) lands in every consumer scaffold the next time they invoke Gradle — no rsync, no per-project drift.

The corollary: **don't edit anything under `sdk/` or `build-logic/` from inside a consumer scaffold**. The symlink is transparent — saving a file in `<repo>/test_graph/sdk/...` writes through to `<skill>/project_sdk_sources/sdk/...` and breaks every other consumer of this skill. Real changes belong in the skill repo and get committed there.

Everything else in the scaffold (`sources/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew`, `examples/`) stays as a copy because those are user-edited per-project.

Falling back to copies when symlinks aren't available (Windows without developer mode, FUSE mounts that reject symlinks): `scaffold.py --copy-sdk`. The scaffold then becomes a snapshot — upstream upgrades require re-scaffolding or manual rsync.

## Two roots the scripts care about

- **`<skill>`** — this repo (where SKILL.md / scripts/ / templates/ / project_sdk_sources/ live).
- **`<test_graph>`** — the scaffolded project the user is operating on (has a `settings.gradle.kts`). Typically lives at `<user-repo>/test_graph/`.

The scripts auto-detect `<test_graph>` so most invocations work without flags. Resolution order (highest precedence first):

1. `--test-graph-root` / `-R` flag.
2. `TEST_GRAPH_ROOT` env var.
3. Walk up from cwd looking for `settings.gradle.kts` — wins when you're inside the scaffold.
4. **Project repo root convenience** — fall back to `<cwd>/test_graph/` if it carries `settings.gradle.kts` and a `build.gradle.kts` that mentions `validationGraph`. So a user at `<user-repo>/` can run scripts directly without flags:
   ```bash
   cd <user-repo>                                            # NOT <user-repo>/test_graph
   <skill>/scripts/discover.py                               # → targets ./test_graph/
   <skill>/scripts/run.py smoke                              # → ./test_graph/
   <skill>/scripts/run.py --all                              # every graph, sequentially
   <skill>/scripts/clean.py                                  # wipes ./test_graph/build/
   ```
   The `validationGraph` substring check guards against picking up an unrelated `test_graph` directory.

## Workflows

### 1. Scaffold a new test_graph project

Use when: the user wants to start using this system in a codebase.

```bash
<skill>/scripts/scaffold.py <user-repo-root>
```

Copies the contents of `project_sdk_sources/` (SDKs, plugin, example nodes, Gradle wrapper) into `<user-repo-root>/test_graph/`. Errors if `test_graph/` already exists with content.

After scaffolding:

```bash
cd <user-repo-root>/test_graph
./gradlew validationListGraphs              # confirm the example graph is discovered
./gradlew validationPlanGraph --name=smoke  # dry-run the plan
./gradlew smoke                             # run the example graph
```

### 2. Create a new JBang (Java) node

Run from inside the scaffolded test_graph project.

```bash
<skill>/scripts/new-jbang-node.py <node-id> <kind>
# e.g. <skill>/scripts/new-jbang-node.py checkout.smoke assertion
```

Creates `sources/<ClassName>.java` in the active test_graph project from the skill's template. The class name is derived from the node id.

After creation: edit the file to fill in the body, then reference it from a `testGraph(...) { node("sources/<ClassName>.java") }` block in `build.gradle.kts`. Or leave it unlisted — it'll be pulled in transitively if another DSL-declared node depends on its id.

**Kinds**: `testbed | fixture | action | assertion | evidence | report` — see **Picking a kind** below.

### 3. Create a new uv (Python) node

```bash
<skill>/scripts/new-uv-node.py <node-id> <kind>
# e.g. <skill>/scripts/new-uv-node.py product.seeded fixture
```

Creates `sources/<snake_name>.py` in the active test_graph project. Script name snake-cases the node id.

### 4. Compose a test graph in `build.gradle.kts`

Edit the scaffolded project's `build.gradle.kts`:

```kotlin
validationGraph {
    sourcesDir("sources")

    testGraph("smoke") {
        node("sources/user_seeded.py")                      // explicit
        node("sources/LoginSmoke.java")
            .dependsOn("user.seeded")                       // DSL adds an edge
            .tags("regression")                             // DSL adds tags
        // app.running pulled in transitively from script-declared deps
    }
}
```

Every `testGraph(...)` registers a Gradle task with the same name. Multiple graphs are allowed per project.

### 5. Discover graphs and plans

```bash
<skill>/scripts/discover.py                     # list all registered test graphs
<skill>/scripts/discover.py <graph-name>        # plan + adjacency + render DAG to docs/
# equivalent raw Gradle (from inside the scaffolded project):
./gradlew validationListGraphs
./gradlew validationPlanGraph --name=<graph>
./gradlew validationGraphDot  --name=<graph>    # DOT only (pipe-friendly)
```

`discover.py <graph>` writes `<test_graph>/docs/<graph>.dot` and — if graphviz's `dot` is on PATH — renders `<test_graph>/docs/<graph>.png`.

Discovery invokes `--describe-out=<tmp>` on every script referenced by a `testGraph(...)` or reachable through `sourcesDir(...)`. To debug a failing describe:

```bash
jbang sources/MyNode.java --describe-out=/tmp/spec.json
uv run sources/my_node.py --describe-out=/tmp/spec.json
cat /tmp/spec.json
```

### 6. Run a graph

```bash
<skill>/scripts/run.py <graph-name>
# or directly, from inside the scaffolded project:
./gradlew <graph-name>
```

Output lives at `<test_graph>/build/validation-reports/<runId>/` — one envelope per node under `envelope/`, plus a unified `summary.json` and `report.md` written inline by the graph task at the end of plan execution. Run `./gradlew validationReport` to re-render those rollups for every existing run dir (e.g. after editing an envelope by hand).

### 7. Run every registered graph

```bash
<skill>/scripts/run.py --all
# or directly:
./gradlew validationRunAll
```

`validationRunAll` fans out to every `testGraph(...)` declared in `build.gradle.kts` and chains them serially in declaration order — so testbed nodes don't compete for shared local resources when Gradle's worker pool is wider than 1. Each per-graph task writes its own `summary.json` + `report.md` inline; `validationRunAll` doesn't need a finalizer (a shared finalizer would dedupe across graph tasks and leave some run dirs without a report).

### 8. Clean the build directory

```bash
./gradlew clean
```

Plain `clean` from Gradle's `base` plugin (which the validation plugin auto-applies). Removes `build/` — including `build/validation-reports/`, so prior runs are wiped. Use before a fresh `validationRunAll` if you want a clean baseline.

## Where validation reports live

Every run writes under the scaffolded project's `build/` directory:

```
<test_graph>/build/validation-reports/<runId>/
  envelope/
    <node-id>.json              # one per executed node — status, assertions, metrics, published, logs[]
  node-logs/                    # subprocess stdout+stderr captured per (node, label)
    <node-id>.<label>.log       # e.g. ci.logged.in.login.log
  summary.json                  # aggregate (written inline at the end of the graph run)
  report.md                     # human-readable rollup (same)
  docs/<graph>.dot              # graphviz source from `discover.py <graph>`
  docs/<graph>.png              # rendered if graphviz `dot` is on PATH
```

`<runId>` is a timestamp like `20260428-184103`, so multiple runs accumulate side-by-side until `clean` (or `clean.py`) wipes them.

The structured `envelope/<node-id>.json` carries everything a CI artifact uploader needs — including absolute paths to any captured subprocess logs, so a downstream artifact bundle round-trips losslessly. CI workflows should upload `build/validation-reports/` wholesale.

## Importing user code into a node

The scaffolded project lives at `<user-repo>/test_graph/`, so `../..` from any node script reaches the user's repo root. That gives node scripts physical access to the user's domain models and helpers via relative paths, without a separate publish step.

**Concrete commented examples are already in the scaffold payload** — copy the patterns:

- [`project_sdk_sources/sources/AppRunning.java`](project_sdk_sources/sources/AppRunning.java) — JBang `//SOURCES` + `//DEPS` block in a header comment
- [`project_sdk_sources/sources/LoginSmoke.java`](project_sdk_sources/sources/LoginSmoke.java) — same, oriented toward a downstream user HTTP client
- [`project_sdk_sources/sources/NetworkPingable.java`](project_sdk_sources/sources/NetworkPingable.java) — minimal variant for a no-deps node
- [`project_sdk_sources/sources/user_seeded.py`](project_sdk_sources/sources/user_seeded.py) — uv `[tool.uv.sources]` block with a user package
- [`templates/jbang-node.java.template`](templates/jbang-node.java.template) and [`templates/uv-node.py.template`](templates/uv-node.py.template) carry the same examples, so `new-jbang-node.py` / `new-uv-node.py` seed every new script with the pointers already in place.
- [`project_sdk_sources/build.gradle.kts`](project_sdk_sources/build.gradle.kts) — commented `testGraph("regression") { ... }` showing how a second graph can reuse scripts with tighter overlays.

Assumed layout (after scaffolding):

```
<user-repo>/
  src/main/java/...         <user project Java sources>
  src/main/python/... or <pkg>/     <user project Python>
  pyproject.toml             (if the user's project is a Python package)
  test_graph/                <- the scaffold lives here
    sources/
      MyNode.java            <- node scripts
      my_node.py
    sdk/java/...
    sdk/python/...
    build.gradle.kts
    gradlew, gradle/
    ...
```

From `test_graph/sources/`, the user repo root is `../..` (up from `sources/` → `test_graph/` → user repo).

### Java (JBang) — use `//SOURCES` + optional `//DEPS`

`//SOURCES` paths in a JBang script are resolved relative to the script file. To pull in the user's Java sources, point at files under `<user-repo>/src/main/java/`:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../src/main/java/com/acme/domain/User.java
//SOURCES ../../src/main/java/com/acme/api/*.java

import com.acme.domain.User;
import com.acme.api.UserClient;
// ... normal Node.run(args, SPEC, ...) body ...
```

Rules of thumb:

- Be specific. Pull in just the files or packages the node needs, not the whole tree.
- Globs are fine: `//SOURCES ../../src/main/java/com/acme/api/*.java`.
- For external JVM libraries (Jackson, OkHttp, Selenium, …) use `//DEPS`:
  ```java
  //DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
  //DEPS org.seleniumhq.selenium:selenium-java:4.21.0
  ```
  JBang resolves them from Maven Central. No Gradle dependency wiring needed.

### Python (uv) — use `[tool.uv.sources]` + inline deps

For Python nodes, add your package to the inline script metadata and point `[tool.uv.sources]` at its local path. uv will install it editable into the script's isolated environment:

```python
# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk", "acme-domain"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# acme-domain  = { path = "../..",         editable = true }
# ///

from testgraphsdk import NodeResult, NodeSpec, node
from acme_domain import User, UserClient
```

This assumes `<user-repo>/pyproject.toml` declares the package name (e.g. `name = "acme-domain"`). If the user's project uses a `src/` layout, point at the directory containing `pyproject.toml` — usually still `../..`.

For ad-hoc imports without a packaged project, fall back to `sys.path`:

```python
import sys
from pathlib import Path

# <user-repo>/src/main/python reached from test_graph/sources/<this>.py
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src" / "main" / "python"))

from acme.domain import User
```

Prefer the `[tool.uv.sources]` form — it gives you dependency isolation, editable reinstalls, and cleaner failure modes.

### Keeping node scripts portable

Whether you import user code via `//SOURCES` or `[tool.uv.sources]`, the relative path from `test_graph/sources/<script>` to the user-repo root is always `../..`. Keep the depth at two levels and document any deviation in the script's file header.

## Toolchain (jbang + uv)

The plugin resolves both runtimes at task-execution time — using whatever's on `PATH` by default, and downloading to `<test_graph>/.bin/` only when a version override is requested that doesn't match PATH, or when the tool isn't installed. Every run logs its choice:

```
[toolchain] jbang=0.137.0 (path: /opt/homebrew/bin/jbang)
[toolchain] uv=0.7.2 (path: /opt/homebrew/bin/uv)
```

Pin a version with Gradle properties when you need reproducibility:

```bash
./gradlew smoke -Poverride-jbang-version=0.118.0 -Poverride-uv-version=0.6.0
```

The downloaded binaries live at `<test_graph>/.bin/jbang-<v>/bin/jbang` and `<test_graph>/.bin/uv-<v>/uv`. `.bin/` is gitignored. Full table in [`reference.md`](reference.md) → **Toolchain**.

## Picking a kind

| Kind        | Use for                                           |
| ----------- | ------------------------------------------------- |
| `testbed`   | provisioning an environment (app up, db ready)    |
| `fixture`   | seeding data required by downstream nodes        |
| `action`    | performing an operation (API call, UI click path) |
| `assertion` | checking an invariant holds                      |
| `evidence`  | collecting artifacts (logs, screenshots, dumps)   |
| `report`    | aggregating results into a report shape          |

Prefer the narrowest kind that fits. A single "do everything" `assertion` is almost always wrong — split it.

## Reading and writing context

Every node after the first receives a single `--context=<value>` arg carrying the ordered `List<ContextItem>` for every upstream node in the plan. Inline JSON when small (≤8KB), `@<path>` file reference otherwise — the SDKs handle both transparently.

```java
// upstream (user.seeded):
return NodeResult.pass("user.seeded").publish("userId", userId);

// downstream (login.smoke):
String user = ctx.get("user.seeded", "userId").orElseThrow();
```

```python
# upstream
return NodeResult.pass_("user.seeded").publish("userId", user_id)

# downstream
user = ctx.get("user.seeded", "userId")
```

The canonical record is still the envelope JSON. `ctx.upstream(id)` returns the full envelope if a node needs more than a published primitive (metrics, artifacts, etc).

## When NOT to use this skill

- You are editing the plugin, SDKs, or constitution/spec themselves. That's core-system work — follow the existing patterns in `project_sdk_sources/build-logic/` and `project_sdk_sources/sdk/`; don't use the node templates.
- The user wants a one-off shell command, not a durable validation node. Only graph-worthy work belongs here.

## Authoring rules (condensed)

Before merging work that touches `sources/` or `build.gradle.kts`, confirm:

- [ ] `./gradlew validationListGraphs` + `validationPlanGraph --name=<graph>` show the expected DAG.
- [ ] Node id is dotted and stable (treat like public API).
- [ ] Exactly one `kind` and one `runtime` per node.
- [ ] All dependencies are intentional — either self-declared in the script spec or added via `.dependsOn(...)` in the DSL for reasons you can articulate.
- [ ] Node returns a structured `NodeResult`. Use `.publish(...)` for values downstream should read.
- [ ] Report JSON lands cleanly under `build/validation-reports/<runId>/envelope/`.

## Key anti-patterns

- **One giant script** doing setup + action + assertion. Split into separate nodes.
- **Renaming node ids** without checking downstream impact — ids are public API.
- **Hidden ordering**: `Thread.sleep(...)` / `time.sleep(...)` as a substitute for `dependsOn`. If node B needs A to finish, declare it.
- **Plain stdout reporting** instead of `NodeResult`. The aggregator needs structure.
- **Duplicating `//SOURCES` / `tool.uv.sources` paths** across nodes. The scaffold uses `../sdk/{java,python}/...` for scripts in `sources/`; preserve that layout.

## Reference pointers

- [`constitution.md`](constitution.md) — durable principles and invariants.
- [`initial-spec.md`](initial-spec.md) — v0 design, repo layout, Gradle DSL.
- [`reference.md`](reference.md) — full metadata / SDK / DSL / task reference.
