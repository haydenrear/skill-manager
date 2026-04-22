# test_graph

Scaffolded test-graph project. See the upstream skill for full docs
(workflow, DSL reference, importing user code, etc).

## Quickstart

```bash
./gradlew validationListGraphs              # list available test graphs
./gradlew validationPlanGraph --name=smoke  # dry-run the topo-ordered plan
./gradlew smoke                             # run the smoke graph
./gradlew validationReport                  # aggregate envelopes into summary.json
```

## Layout

```
build.gradle.kts      example `testGraph("smoke") { ... }` wiring
settings.gradle.kts
gradlew, gradle/      Gradle wrapper (standalone — no global gradle needed)
build-logic/          Gradle plugin + Kotlin DSL (ValidationGraphPlugin)
sdk/java/             Java SDK — Node.run, NodeSpec, NodeResult, ContextItem
sdk/python/           Python SDK — @node, NodeSpec, NodeResult, ContextItem
sources/              node scripts (self-describing; .java = jbang, .py = uv)
examples/             supplementary example docs
```

## Adding nodes and composing graphs

Use the upstream skill's scripts from inside this directory:

```bash
<skill>/scripts/new-jbang-node.py checkout.smoke assertion
<skill>/scripts/new-uv-node.py product.seeded fixture
<skill>/scripts/discover.py smoke           # plan + render docs/smoke.png
<skill>/scripts/run.py smoke                # execute + aggregate
```

Or go straight to Gradle tasks (listed above).

## Importing your project's code

This directory lives at `<your-repo-root>/test_graph/`. From any node
script in `sources/`, `../..` reaches your repo root — so you can pull
in your Java classes via `//SOURCES ../../src/main/java/...` and your
Python packages via `[tool.uv.sources] path = "../.."`. Commented
examples are already in every `sources/*.java` and `sources/*.py`.

## Reports

Each run writes under `build/validation-reports/<runId>/`:

```
build/validation-reports/<runId>/
  envelope/<nodeId>.json    per-node envelope
  summary.json              unified summary (after validationReport)
```
