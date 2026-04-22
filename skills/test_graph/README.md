# test-graph skill

A polyglot validation graph system, shipped as an agent skill. Scaffold a new test_graph project into any repository, then compose and run validation graphs from `build.gradle.kts`.

- Nodes are small units of validation work (testbed, fixture, action, assertion, evidence, report).
- Each node's script self-describes its metadata via `--describe-out=<path>` — no YAML sidecar.
- Nodes run in multiple runtimes (Java via JBang, Python via uv) but share one graph model and one result envelope schema.
- A Gradle plugin + Kotlin DSL composes nodes into named **test graphs**, each registered as a Gradle task.
- A `Context[]` carries data downstream across runtimes, so a JBang assertion can read values a uv fixture published.

Read [`SKILL.md`](SKILL.md) for the agent workflow, [`reference.md`](reference.md) for the full API, [`constitution.md`](constitution.md) for durable principles, and [`initial-spec.md`](initial-spec.md) for the v0 design.

## Layout (this repo IS the skill)

```
SKILL.md                  skill-facing instructions for the agent
reference.md              full API / DSL / task reference
constitution.md           durable principles & invariants
initial-spec.md           v0 design notes
scripts/                  skill scripts (scaffold, new-*-node, discover, run)
templates/                node templates (jbang, uv)
project_sdk_sources/      THE SCAFFOLD PAYLOAD — copied verbatim on scaffold
  build.gradle.kts          example `testGraph("smoke") { ... }`
  settings.gradle.kts
  gradlew, gradle/          Gradle wrapper
  build-logic/              Gradle plugin + Kotlin DSL (ValidationGraphPlugin)
  sdk/java/, sdk/python/    the two SDKs (`testgraphsdk`)
  sources/                  example node scripts
  examples/                 supplementary example docs
work/                     scratch area for scaffolded test projects (gitignored)
```

## Scaffold a project

```bash
scripts/scaffold.py <repo-root>
```

Creates `<repo-root>/test_graph/` with a full Gradle-ready copy of `project_sdk_sources/`. The target must not already be a non-empty `test_graph/`.

```bash
scripts/scaffold.py ~/projects/myapp
# → ~/projects/myapp/test_graph/

cd ~/projects/myapp/test_graph
./gradlew validationListGraphs              # confirm discovery
./gradlew validationPlanGraph --name=smoke  # dry-run the plan
./gradlew smoke                             # execute the example graph
```

## Work on an existing scaffolded project

The other skill scripts detect the scaffolded project by walking up from cwd to find `settings.gradle.kts`. Run them from inside the scaffolded `test_graph/`:

```bash
cd <repo-root>/test_graph

<skill>/scripts/discover.py                 # list registered test graphs
<skill>/scripts/discover.py smoke           # plan + docs/smoke.png
<skill>/scripts/new-jbang-node.py checkout.smoke assertion
<skill>/scripts/new-uv-node.py product.seeded fixture
<skill>/scripts/run.py smoke                # execute + aggregate
```

`<skill>` here is the path to this repo.

## The DSL in one glance

```kotlin
validationGraph {
    sourcesDir("sources")                          // pool for transitive deps

    testGraph("smoke") {
        node("sources/user_seeded.py")             // explicit
        node("sources/NetworkPingable.java")
            .dependsOn("app.running")              // DSL-added dep (script has none)
        node("sources/LoginSmoke.java")
            .dependsOn("network.pingable")         // DSL-added dep on top of script's
            .tags("regression")
        // app.running is pulled in transitively via sourcesDir
    }
}
```

Each `testGraph("name") { ... }` registers a Gradle task with that name.

## Importing user code into a node

The scaffolded `test_graph/` typically sits at `<repo-root>/test_graph/`, so `../..` from any node script reaches the user's project root. Node scripts can import the user's Java classes via `//SOURCES ../../src/main/java/...` and `//DEPS <coords>`, and uv scripts can import the user's Python packages via `[tool.uv.sources]` with `path = "../.."`. See [`SKILL.md`](SKILL.md) → **Importing user code**.

## Reports

Each run writes under `<test_graph>/build/validation-reports/<runId>/`:

```
build/validation-reports/<runId>/
  envelope/<nodeId>.json    per-node envelope
  summary.json              unified summary (after validationReport)
```
