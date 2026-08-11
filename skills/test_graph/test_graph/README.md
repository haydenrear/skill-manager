# test_graph

Repo-local test-graph project for validating the test-graph skill itself.
See the upstream skill docs for the generic workflow, DSL reference, and
importing-user-code guidance.

This nested scaffold intentionally symlinks `sdk/` and `build-logic/` to
`../project_sdk_sources` so CI and local runs exercise this checkout's plugin
and SDK code.

## Quickstart

Prefer the upstream skill wrapper scripts. They work from your repo root or
from inside this `test_graph/` directory:

```bash
<skill>/scripts/discover.py                 # list available test graphs
<skill>/scripts/discover.py rerunGraphJbang # dry-run plan + render docs/rerunGraphJbang.png
<skill>/scripts/run.py rerunGraphJbang      # run one focused graph
<skill>/scripts/run.py --all                # run every registered graph serially
<skill>/scripts/clean.py                    # remove build/ outputs
```

The underlying Gradle tasks are documented in the upstream reference for plugin
debugging, but normal project work should start with the wrapper scripts.

## Layout

```
build.gradle.kts      repo-local rerun/resume graph wiring
settings.gradle.kts
gradlew, gradle/      Gradle wrapper (standalone; no global gradle needed)
build-logic/          Gradle plugin + Kotlin DSL (ValidationGraphPlugin)
sdk/java/             Java SDK: Node.run, NodeSpec, NodeResult, ContextItem
sdk/python/           Python SDK: @node, NodeSpec, NodeResult, ContextItem
sources/              node scripts (self-describing; .java = jbang, .py = uv)
examples/             supplementary example docs
```

## Adding nodes and composing graphs

Use the upstream skill's scripts from inside this directory:

```bash
<skill>/scripts/new-jbang-node.py checkout.smoke assertion
<skill>/scripts/new-uv-node.py product.seeded fixture
<skill>/scripts/discover.py rerunGraphJbang
<skill>/scripts/run.py rerunGraphJbang
```

## GitHub Actions

This repository's CI workflow lives at `.github/workflows/ci.yml`. It runs
unit tests, spec tests, TLC model checks, and this nested graph against the
local checkout.

## Importing your project's code

This directory lives at `<your-repo-root>/test_graph/`. From any node
script in `sources/`, `../..` reaches your repo root, so you can pull
in your Java classes via `//SOURCES ../../src/main/java/...` and your
Python packages via `[tool.uv.sources] path = "../.."`.

## Reports

Each run writes under `build/validation-reports/<runId>/`:

```
build/validation-reports/<runId>/
  execution-scope.json         no-replace expected nodes + replay digest provenance
  attempt-closure.json         closure-v2 exact evidence digest binding
  trace-context.json           persisted W3C carrier
  envelope/<nodeId>.json      per-node envelope
  context/<nodeId>.input.json exact input Context[] for that node attempt
  summary.json                unified summary (written inline at end of run)
  report.md                   markdown rollup (same)
```

Every `envelope/*.json` artifact uses the closed canonical
`envelopeVersion: 1` schema. The executor validates it before immutable
publication, and the same validator gates attempt closure, replay acquisition,
and report regeneration; malformed or semantically contradictory envelope
evidence is never replay-eligible or green.

Report regeneration also verifies that closure v2 still matches the current
scope, carrier, contexts, and envelopes. Missing or stale closure state is
`ERRORED`. Report retention is bounded to 16 MiB each of aggregate envelope and
context evidence plus 500,000 JSON structural tokens.

The rerun/resume self-graphs treat the baseline closure as the source integrity
anchor. They require a distinct fresh replay run, hash the source scope,
closure, carrier, envelopes, exact input-context snapshots, summary, and report
before and after replay, validate the target's v3 scope (including graph
identity and replay provenance), and verify exact expected/observed node and
context identities. Report integrity also reconstructs each node's ordered
published-data prefix and checks a replay's selected context against its source
snapshot. The self-graphs then run manual report regeneration to prove that
verdict, contextual gold, scope, and trace identity remain unchanged.
Replay sources are restricted to full, non-replay attempts whose complete
ordered plan equals the current graph; the selected source context must equal
the exact ordered prefix before the selected node.
