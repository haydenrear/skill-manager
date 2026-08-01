---
name: test-graph
description: Work with the test-graph validation system -- scaffold a test_graph project into a user repo, add JBang/uv nodes, compose graphs in build.gradle.kts, discover/plan them, run one graph or all graphs, and aggregate reports. Use whenever the user asks to set up validation nodes, compose a validation graph, run test_graph, or extend an existing test_graph project.
---

# test-graph skill

You are helping the user build or extend a **test_graph** project: a polyglot validation DAG where each node is a small JBang or uv script that self-describes metadata and returns a structured `NodeResult`.

This repo is the skill. The agent-facing entry point is this file. Durable details live under `references/`.

## Why Test Graph Exists

Test graph exists to eliminate degenerate testing behavior. Agents are often capable of finding the shortest path to a green test, and that path can be wrong: asserting the current broken behavior, mocking away the real failure, testing an implementation detail that the bug itself controls, or writing a narrow regression that proves the patch instead of proving the user-visible behavior.

Test graph moves validation onto the plane of behavior. A good graph exercises the system the way a user, operator, browser, client, or downstream service would exercise it. It can scale up real infrastructure, seed real fixtures, drive real workflows, and assert on externally meaningful outcomes. A node may start a service, allocate a port, seed a database, drive Selenium or Playwright through the UI, call the public HTTP API, invoke the CLI, inspect generated files, or collect logs and screenshots. The point is not to make tests heavier for their own sake; the point is to make the test hard to satisfy by exploiting the same bug it is supposed to catch.

Author graph nodes as reusable behavioral contracts. Once a graph can reproduce the real-world condition, keep iterating on that graph instead of creating one-off tests that mirror the current implementation. The graph should survive refactors because it is anchored to behavior, not to private code shape. When a fix is complete, the graph should answer: "Can the system now do the thing the user needed under realistic conditions?"

When in doubt, ask what evidence would convince a skeptical user that the behavior works. Encode that evidence as testbed, fixture, action, assertion, and evidence nodes with explicit dependencies.

## Start Here

Use the skill scripts first. They auto-detect the active scaffolded `<repo>/test_graph/` from either the repo root or anywhere inside the scaffold.

| Goal | Command |
| --- | --- |
| Scaffold into a repo | `<skill>/scripts/scaffold.py <repo-root>` |
| Prepare managed provider bindings | `<skill>/scripts/prepare-bindings.py` |
| Migrate legacy provider symlinks | `<skill>/scripts/migrate-bindings.py` |
| List registered graphs | `<skill>/scripts/discover.py` |
| Plan one graph and render `docs/<graph>.dot` / `.png` | `<skill>/scripts/discover.py <graph>` |
| Run one graph | `<skill>/scripts/run.py <graph>` |
| Run every graph serially | `<skill>/scripts/run.py --all` |
| Clean scaffold build outputs | `<skill>/scripts/clean.py` |
| Add a JBang node | `<skill>/scripts/new-jbang-node.py <node-id> <kind>` |
| Add a uv node | `<skill>/scripts/new-uv-node.py <node-id> <kind>` |
| Add GitHub Actions | `<skill>/scripts/github-action.py <repo-root>` |

Raw Gradle tasks exist, but treat them as lower-level equivalents. Prefer the scripts above in docs, CI, and agent instructions because they handle root detection and keep the common workflows discoverable.

## Python and uv Environment Debugging

If a graph fails in an agent command but passes when re-run manually, or if Python, uv, `PATH`, virtualenv activation, or Gradle daemon state looks inconsistent, read [`references/debug-python-uv-env.md`](references/debug-python-uv-env.md). The first thing to try for wrapper scripts is often running the exact command with `python3`, because `python` may only be an interactive shell alias.

## Progressive Disclosure

Open only the reference you need:

- [`references/workflows.md`](references/workflows.md): normal operating guide - mental model, root detection, scaffolding, `discover.py`, `run.py --all`, node creation, graph composition, reports, symlink behavior, imports from user code, and authoring checklist.
- [`references/reference.md`](references/reference.md): dense API/DSL/task reference - `NodeSpec`, `NodeResult`, context wire format, Gradle DSL, task names, toolchain properties, Java SDK, Python SDK.
- [`references/environment-repositories.md`](references/environment-repositories.md): branch environment repository contract, Git fixture policy, required OpenTofu outputs, local k3d setup, target/backend semantics, and required local/GitHub Actions/AWS test graph coverage.
- [`references/github-actions.md`](references/github-actions.md): generated GitHub Actions workflow, skill install, symlink repair/preserve modes, private installs, and options.
- [`references/debug-python-uv-env.md`](references/debug-python-uv-env.md): troubleshooting Python/uv/Gradle environment mismatches, including `python` alias vs `python3`, uv node execution, and daemon-related "fails then passes" behavior.
- [`references/tickets/`](references/tickets/): lightweight future-work notes.

If the user asks to run or debug a graph, start with `discover.py` before `run.py`. If they ask for all validation, use `run.py --all`. If they ask for CI, read `references/github-actions.md`.

## Smart Failure Loop

Do not blindly rerun a whole graph after every small fix. Test graph nodes are independently runnable scripts, and repeated full-graph runs can waste hours when the same late node fails for different reasons.

When a graph fails:

- Inspect the failed node's report first: `<test_graph>/build/validation-reports/<runId>/report.md`, `summary.json`, `envelope/<node-id>.json`, and any `node-logs/` entries.
- For rerunnable failed nodes, read the report or Gradle output's rerun guidance. It includes both a resume-graph command and a run-only command backed by the node's saved input context.
- Decide whether the failure is isolated to the failed node or invalidates upstream setup. If upstream dependencies still produced valid published context, prefer rerunning only the failed node while iterating.
- To resume graph execution from that node and continue downstream, use `<skill>/scripts/run.py <graph> --resume-from-build <test_graph>/build/validation-reports/<runId> --resume-from-node <node-id>`. The selected node must have `rerun=true`.
- To run only that node from its saved build context, use `<skill>/scripts/run.py <graph> --resume-from-build <test_graph>/build/validation-reports/<runId> --run-only-node <node-id>`. This writes a fresh replay report containing only the selected node without continuing downstream graph nodes.
- Resume and run-only commands acquire `--resume-from-build` once as a verified source snapshot. The source must be a real, non-symlink direct child of the configured `build/validation-reports/` root; cross-project and nested paths are rejected. It must also be a full, non-replay attempt with an exact full-plan match; the selected context must equal the exact ordered current-plan prefix. Closure v2 binds the raw scope and carrier plus the exact present context/envelope path-to-SHA-256 maps; changed, added, removed, or symlinked evidence fails before replay. The captured carrier and selected ordered context are then used without reopening source paths. A fresh sibling run records the source closure/context digests in execution scope v3. This detects accidental or protocol mutation while trusting the application-owned closure; authenticity against an owner able to rewrite the closure requires an external signature, MAC, or WORM anchor. A replay report's `execution.complete` remains scoped to selected-to-tail for resume or one node for run-only.
- Canonical node evidence uses the closed `envelopeVersion: 1` schema. One strict validator gates executor publication, attempt closure/replay acquisition, and report regeneration. Unknown extensions require a version bump, and contradictory evidence such as a passed node with a failed assertion is rejected rather than rendered green.
- Report regeneration verifies the current attempt closure before trusting derived evidence. Missing or mismatched closure state is diagnostic `ERRORED`; aggregate report retention is capped at 16 MiB each for envelopes and contexts plus 500,000 JSON structural tokens.
- Managed node process groups require macOS or Linux plus `perl` core modules `POSIX` and `Time::HiRes`. A reaped orphan group becomes typed, closable `ERRORED` evidence; cleanup uncertainty withholds closure.
- To invoke the node script directly, reuse the failed run's context for that node. Each attempted node writes its exact input `Context[]` under `context/<node-id>.input.json`; pass it back as `--context=@<path>`.
- Invoke the node script directly with the standard node args, writing to a scratch result path inside the same report directory:

```bash
uv run sources/my_node.py \
  --nodeId=<node-id> \
  --runId=<runId> \
  --reportDir=<test_graph>/build/validation-reports/<runId> \
  --result-out=<test_graph>/build/validation-reports/<runId>/.tmp-results/<node-id>.json \
  --context=@<test_graph>/build/validation-reports/<runId>/context/<node-id>.input.json

jbang sources/MyNode.java \
  --nodeId=<node-id> \
  --runId=<runId> \
  --reportDir=<test_graph>/build/validation-reports/<runId> \
  --result-out=<test_graph>/build/validation-reports/<runId>/.tmp-results/<node-id>.json \
  --context=@<test_graph>/build/validation-reports/<runId>/context/<node-id>.input.json
```

- Omit `--context` only for root nodes with no dependencies. Root nodes still write an empty input-context snapshot for auditing.
- Keep iterating on the failing node with direct node reruns until it passes or until you discover an upstream dependency must change.
- After the targeted node passes, rerun the containing graph once from the beginning with `<skill>/scripts/run.py <graph>` to validate dependency ordering, fresh context, reporting, and integration behavior.

Rerun from the beginning immediately when the fix changes a dependency node, shared fixture/testbed state, graph composition, node ids, context keys consumed by multiple downstream nodes, or any behavior that makes the previous run's context stale.

## Core Model

- A node is one validation unit with a stable dotted id, one kind, one runtime, optional dependencies, and a `NodeResult`.
- The script is the source of truth. It emits `NodeSpec` in `--describe-out=<path>` mode; there are no YAML sidecars.
- A graph is declared in the scaffolded `build.gradle.kts` with `testGraph("name") { ... }`.
- `node("sources/Foo.java")` or `node("sources/foo.py")` adds a project-owned node. `standardNode("stable.dotted.id")` composes a centrally shipped node from the scaffold's non-copied `standard-nodes/` catalog. `.dependsOn(...)`, `.tags(...)`, `.timeout(...)`, `.cacheable(...)`, and `.sideEffects(...)` overlay script metadata. Use script-level `NodeSpec.rerun(false)` only when direct replay from saved context is unsafe.
- Transitive dependencies are resolved from `sourcesDir("sources")` when a node depends on another node id that was not listed explicitly in the graph DSL.
- Data flows downstream through `Context[]`. Publish with `NodeResult.publish(key, value)` and read with `ctx.get(upstreamId, key)`.
- Reports live under `<test_graph>/build/validation-reports/<runId>/`.

## Node Shape

Use dependency nodes for reusable setup:

- `testbed`: app/server/container/runtime ready
- `fixture`: seeded data or filesystem state
- `action`: operation performed against the system
- `assertion`: invariant check
- `evidence`: logs, screenshots, dumps, measurements
- `report`: custom aggregation

Do not hide setup inside assertion nodes. If multiple graphs need the same app/database/user fixture, make one node and declare dependencies on it.

## Editing Rules

- In a consumer scaffold, do not edit `sdk/`, `build-logic/`, or `standard-nodes/`. Managed projects generate and ignore those runtime links from the committed `provider-bindings.json`; legacy projects keep supported symlinks until an explicit `migrate-bindings.py` run. Real changes belong in `project_sdk_sources/` in this skill repo and reach consumers through `skill-manager sync`.
- Node scripts live in `<repo>/test_graph/sources/` and can import the user's real project code with paths relative to that file. From `sources/`, the user repo root is `../..`.
- For Java nodes, use JBang `//SOURCES ../../src/main/java/...` and optional `//DEPS`.
- For Python nodes, prefer uv inline metadata with `[tool.uv.sources]` pointing the user package at `../..`.
- When touching `sources/` or `build.gradle.kts`, verify with `<skill>/scripts/discover.py` and `<skill>/scripts/discover.py <graph>` before running.
- Before finalizing a validation change, run `<skill>/scripts/run.py <graph>` for the affected graph or `<skill>/scripts/run.py --all` when graph coverage is broad.

## Avoid

- One giant script that does setup, action, and assertion.
- Tests that can pass by depending on the bug, mocked-away behavior, or private implementation details.
- Patch-shaped regressions that prove only the current fix instead of the user-visible behavior.
- Renaming node ids casually. Treat ids as public API.
- Ordering via sleeps instead of `dependsOn`.
- Plain stdout as the only result. Return a structured `NodeResult`.
- Leading users or agents to raw `./gradlew` commands when a skill wrapper script exists for the workflow.
