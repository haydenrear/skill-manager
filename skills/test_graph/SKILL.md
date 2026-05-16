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
| List registered graphs | `<skill>/scripts/discover.py` |
| Plan one graph and render `docs/<graph>.dot` / `.png` | `<skill>/scripts/discover.py <graph>` |
| Run one graph | `<skill>/scripts/run.py <graph>` |
| Run every graph serially | `<skill>/scripts/run.py --all` |
| Clean scaffold build outputs | `<skill>/scripts/clean.py` |
| Add a JBang node | `<skill>/scripts/new-jbang-node.py <node-id> <kind>` |
| Add a uv node | `<skill>/scripts/new-uv-node.py <node-id> <kind>` |
| Add GitHub Actions | `<skill>/scripts/github-action.py <repo-root>` |

Raw Gradle tasks exist, but treat them as lower-level equivalents. Prefer the scripts above in docs, CI, and agent instructions because they handle root detection and keep the common workflows discoverable.

## Progressive Disclosure

Open only the reference you need:

- [`references/workflows.md`](references/workflows.md): normal operating guide - mental model, root detection, scaffolding, `discover.py`, `run.py --all`, node creation, graph composition, reports, symlink behavior, imports from user code, and authoring checklist.
- [`references/reference.md`](references/reference.md): dense API/DSL/task reference - `NodeSpec`, `NodeResult`, context wire format, Gradle DSL, task names, toolchain properties, Java SDK, Python SDK.
- [`references/github-actions.md`](references/github-actions.md): generated GitHub Actions workflow, skill install, symlink repair/preserve modes, private installs, and options.
- [`references/tickets/`](references/tickets/): lightweight future-work notes.

If the user asks to run or debug a graph, start with `discover.py` before `run.py`. If they ask for all validation, use `run.py --all`. If they ask for CI, read `references/github-actions.md`.

## Core Model

- A node is one validation unit with a stable dotted id, one kind, one runtime, optional dependencies, and a `NodeResult`.
- The script is the source of truth. It emits `NodeSpec` in `--describe-out=<path>` mode; there are no YAML sidecars.
- A graph is declared in the scaffolded `build.gradle.kts` with `testGraph("name") { ... }`.
- `node("sources/Foo.java")` or `node("sources/foo.py")` adds an explicit node. `.dependsOn(...)`, `.tags(...)`, `.timeout(...)`, `.cacheable(...)`, and `.sideEffects(...)` overlay script metadata.
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

- In a consumer scaffold, do not edit `sdk/` or `build-logic/`. They are symlinks into `project_sdk_sources/` in this skill repo.
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
