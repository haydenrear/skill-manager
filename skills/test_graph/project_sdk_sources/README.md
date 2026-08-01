# test_graph

Scaffolded test-graph project. See the upstream skill for full docs
(workflow, DSL reference, importing user code, etc).

## Quickstart

Prefer the upstream skill wrapper scripts. They work from your repo root or
from inside this `test_graph/` directory:

```bash
<skill>/scripts/discover.py                 # list available test graphs
<skill>/scripts/discover.py smoke           # dry-run plan + render docs/smoke.png
<skill>/scripts/run.py smoke                # run one graph
<skill>/scripts/run.py --all                # run every registered graph serially
<skill>/scripts/run.py smoke --resume-from-build build/validation-reports/<runId> --resume-from-node login.smoke
<skill>/scripts/run.py smoke --resume-from-build build/validation-reports/<runId> --run-only-node login.smoke
<skill>/scripts/clean.py                    # remove build/ outputs
```

The underlying Gradle tasks are documented in the upstream reference for plugin
debugging, but normal project work should start with the wrapper scripts.

## Layout

```
build.gradle.kts      example `testGraph("smoke") { ... }` wiring
settings.gradle.kts
gradlew, gradle/      Gradle wrapper (standalone; no global gradle needed)
build-logic/          Gradle plugin + Kotlin DSL (ValidationGraphPlugin)
sdk/java/             Java SDK: Node.run, NodeSpec, NodeResult, ContextItem
sdk/python/           Python SDK: @node, NodeSpec, NodeResult, ContextItem
standard-nodes/       centrally shipped nodes composed with `standardNode("dotted.id")`
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

Compose a shipped node by semantic id instead of copying its script:

```kotlin
testGraph("monitoringReadiness") {
    standardNode("monitoring.cluster.assert.ready")
}
```

The monitoring assertion pulls `monitoring.cluster.ensure` as its sole
transitive dependency. Ensure is the only mutating node
(`environment:provision`, timeout `360s`); assert-ready is a pure, fresh
`monitoring status --json --require-ready` observation (timeout `30s`). Both
invoke the installed deploy-helm `monitoring` launcher through a deterministic
Skill Manager path and preserve the established monitoring endpoint,
`KUBECONFIG`, `KUBECONTEXT`, and reuse outputs.

This pair intentionally has no `environmentRepository` metadata. The public
deploy-cdc monitoring CLI is the single lifecycle authority, so Test Graph must
not run a Git/OpenTofu environment prelude or reproduce Helm, k3d, storage, or
readiness logic.

Graph coordinator telemetry is emitted before the first node. On a genuinely
cold monitoring start, that initial export can therefore be unavailable and
lost; telemetry remains bounded and fail-open so the ensure node still runs.
The coordinator never deploys monitoring or provides a fallback deployment
path.

## GitHub Actions

From your repo root, scaffold a CI workflow with the upstream skill:

```bash
<skill>/scripts/github-action.py
```

The workflow installs the test-graph skill on the runner and materializes the
managed `sdk/`, `build-logic/`, and `standard-nodes/` runtime links before
Gradle runs. The committed `provider-bindings.json`, not literal symlink text,
is the portable source record.

## Importing your project's code

This directory lives at `<your-repo-root>/test_graph/`. From any node
script in `sources/`, `../..` reaches your repo root, so you can pull
in your Java classes via `//SOURCES ../../src/main/java/...` and your
Python packages via `[tool.uv.sources] path = "../.."`. Commented
examples are already in every `sources/*.java` and `sources/*.py`.

## Reports

Each run writes under `build/validation-reports/<runId>/`:

```
build/validation-reports/<runId>/
  envelope/<nodeId>.json      per-node envelope
  context/<nodeId>.input.json exact input Context[] for that node attempt
  summary.json                unified summary (written inline at end of run)
  report.md                   markdown rollup (same)
```
