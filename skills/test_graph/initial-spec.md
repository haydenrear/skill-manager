# Initial Spec: Validation Graph Skill System v0

## Scope

This spec defines the first implementation of a validation graph system with:

- a testing skill directory containing executable node scripts
- SDKs for Java and Python
- Gradle plugin + Kotlin DSL
- unified reporting contract
- starter project repository
- agent-facing documentation for extending the system

## High-Level Architecture

The system has five layers:

1. **Node scripts**
   - executable Java and Python scripts
   - Java scripts run with JBang
   - Python scripts run with uv

2. **Runtime SDKs**
   - Java SDK for common node behavior
   - Python SDK for common node behavior

3. **Graph model**
   - node metadata
   - dependency model
   - reporting contract
   - runtime adapter contract

4. **Gradle plugin + DSL**
   - discovers each script's NodeSpec via `--describe-out`
   - composes scripts into named `testGraph("X") { ... }` blocks
   - registers one Gradle task per test graph (name == graph name)
   - exposes inspection tasks (list graphs, plan a graph, emit DOT)

5. **Starter project**
   - ready-to-clone repo
   - sample nodes
   - sample shared logic
   - skill docs for agents

## Repository Layout

```text
validation-graph-starter/
  constitution.md
  initial-spec.md
  README.md
  build.gradle.kts         # example test graphs (uses the plugin's DSL)
  settings.gradle.kts
  gradlew / gradle/        # Gradle wrapper

  sources/                 # node scripts — each self-declares its NodeSpec
    AppRunning.java        # jbang (testbed)
    NetworkPingable.java   # jbang (evidence)
    LoginSmoke.java        # jbang (assertion)
    user_seeded.py         # uv   (fixture)

  build-logic/
    settings.gradle.kts
    build.gradle.kts
    src/main/kotlin/
      com/hayden/testgraphsdk/
        ValidationGraphPlugin.kt        # plugin entry
        ValidationGraphExtension.kt     # DSL root (sourcesDir, testGraph)
        TestGraphSpec.kt                # per-graph spec + NodeOverlay
        NodeDescribeLoader.kt           # --describe-out invocation + parse
        GraphAssembler.kt               # per-graph describe + overlay + resolve
        GraphModel.kt                   # topo-sort helper
        MiniJson.kt                     # dep-free JSON parser
        ValidationNodeSpec.kt           # typed spec + ReportsSpec
        ValidationRuntime.kt            # runtime adapter sealed hierarchy
        exec/                           # executors + PlanExecutor + Context
        tasks/                          # RunTestGraphTask + inspection + report

  sdk/
    java/    # Node.run, NodeSpec, NodeResult, NodeContext, ContextItem, ContextSerde, Json
    python/  # @node, NodeSpec, NodeResult, NodeContext, ContextItem, ...

  test-graph-skill/        # agent-facing skill
    SKILL.md
    reference.md
    scripts/               # scaffold.py, new-jbang-node.py, new-uv-node.py, discover.py, run.py
    templates/             # jbang-node.java.template, uv-node.py.template

  examples/                # supplementary example docs
```

## Node Model

Each node has:

- `id`: stable semantic identifier
- `kind`: `testbed | fixture | action | assertion | evidence | report`
- `runtime`: `jbang | uv`
- `entry`
- `dependsOn`
- `tags`
- `timeout`
- `cacheable`
- `sideEffects`
- `inputs`
- `outputs`
- `reports`

### Example node metadata (declared in-script)

Nodes declare their spec inside the script file. The plugin discovers specs by invoking each script with `--describe-out=<tmp>` — the SDK serializes the spec to that path and exits without running the body.

```java
static final NodeSpec SPEC = NodeSpec.of("login.smoke")
        .kind(NodeSpec.Kind.ASSERTION)
        .dependsOn("app.running", "user.seeded")
        .tags("ui", "smoke")
        .timeout("120s")
        .sideEffects("browser")
        .input("baseUrl", "string")
        .output("success", "boolean")
        .output("screenshotPath", "string?")
        .junitXml()
        .cucumber();
```

Same surface in Python:

```python
SPEC = (
    NodeSpec("login.smoke")
    .kind("assertion")
    .depends_on("app.running", "user.seeded")
    .tags("ui", "smoke")
    .timeout("120s")
    .side_effects("browser")
    .input("baseUrl", "string")
    .output("success", "boolean")
    .junit_xml()
    .cucumber()
)
```

## SDK Requirements

### Java SDK

The Java SDK must provide:

- node context parsing
- standard result envelope creation
- artifact registration
- structured assertion helpers
- error/failure helpers
- optional logging helpers
- optional HTTP/JSON/test utility helpers

Java nodes will run as JBang scripts. JBang is designed for self-contained Java source execution, and its Gradle plugin allows JBang scripts to be executed during a Gradle build.

#### Java node shape

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.hayden:testgraphsdk-java:0.1.0

import com.hayden.testgraphsdk.sdk.*;

public class LoginSmoke {
    static final NodeSpec SPEC = NodeSpec.of("login.smoke")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("app.running", "user.seeded")
            .tags("ui", "smoke");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> NodeResult.pass("login.smoke")
                .assertion("redirected_to_dashboard", true)
                .artifact("screenshot", "reports/screens/login.png"));
    }
}
```

### Python SDK

The Python SDK must provide:

- node context parsing
- standard result envelope creation
- artifact registration
- assertion helpers
- failure/error helpers
- shared utility layer

Python scripts will run via `uv run`, which supports inline script dependency metadata and isolated execution for scripts.

#### Python node shape

```python
# /// script
# dependencies = ["testgraphsdk==0.1.0", "httpx"]
# ///

from testgraphsdk import node, NodeResult, NodeSpec

SPEC = (
    NodeSpec("user.seeded")
    .kind("fixture")
    .depends_on("app.running")
)

@node(SPEC)
def main(ctx):
    return (
        NodeResult.pass_("user.seeded")
        .artifact("seed-record", "reports/fixtures/user.json")
        .metric("created_users", 1)
    )

if __name__ == "__main__":
    main()
```

## Unified Result Envelope

All nodes must emit a common structured result envelope.

### JSON schema shape

```json
{
  "nodeId": "login.smoke",
  "status": "passed",
  "startedAt": "2026-04-16T20:00:00Z",
  "endedAt": "2026-04-16T20:00:12Z",
  "assertions": [
    {
      "name": "redirected_to_dashboard",
      "status": "passed"
    }
  ],
  "artifacts": [
    {
      "type": "screenshot",
      "path": "build/reports/validation/login-smoke.png"
    }
  ],
  "metrics": {
    "durationMs": 12450
  },
  "logs": [],
  "published": { "attemptedAs": "u-1a2b3c4d" }
}
```

This envelope is the canonical reporting handoff.

## Gradle Plugin

The Gradle side should be implemented as a custom plugin rather than as ad hoc build script logic. The plugin encapsulates reusable build logic, uses custom task types, and registers tasks lazily.

### Plugin responsibilities

- expose a Kotlin DSL for composing test graphs from scripts
- discover each script's metadata via `--describe-out`
- resolve transitive deps from `sourcesDir(...)` pools
- register one Gradle task per `testGraph(...)` declaration
- expose graph inspection tasks (list / plan)
- drive per-node execution via runtime-specific executors
- aggregate result envelopes into a unified summary

### Plugin tasks

- `<graphName>` — each `testGraph("X") { ... }` registers a Gradle task named `X`
- `validationListGraphs` — list registered graphs
- `validationPlanGraph --name=<graph>` — topo-ordered plan with entry paths
- `validationReport` — aggregate latest run's envelopes into summary.json

## Gradle DSL

```kotlin
validationGraph {
    sourcesDir("sources")                       // pool for transitive deps

    testGraph("smoke") {
        node("sources/user_seeded.py")

        node("sources/NetworkPingable.java")
            .dependsOn("app.running")           // DSL-added dep (script has none)

        node("sources/LoginSmoke.java")
            .dependsOn("network.pingable")      // DSL-added dep on top of script's
            .tags("regression")

        // app.running pulled in transitively via sourcesDir
    }

    // Multiple graphs allowed — each becomes its own Gradle task.
    // testGraph("quick") { node("sources/user_seeded.py") }
}
```

`node(path)` returns a chainable `NodeOverlay`; every setter (`dependsOn`, `tags`, `sideEffects`, `timeout`, `cacheable`) layers on top of what the script self-declared. Collections are unioned; scalars override. The script is always the floor — the DSL can only add edges or tighten constraints, never hide a dep the script declared.

## Reporting

The reporting layer must unify results across runtimes.

### Reporting responsibilities

- ingest per-node JSON envelopes
- aggregate by graph run
- expose machine-readable summary
- optionally emit human-friendly reports
- optionally bridge to existing Gradle/JVM reporting infrastructure

The plugin should integrate with an existing reporting mechanism where practical, but the graph system's own structured JSON envelope remains canonical.

## Starter Project

The starter project exists to make extension fast for both humans and agents.

### Starter project contents

- sample Java (JBang) and Python (uv) node scripts in `sources/`
- self-describing NodeSpec declarations in each script
- Gradle plugin under `build-logic/` + example `build.gradle.kts`
- a sample `testGraph("smoke") { ... }` demonstrating `node().dependsOn(...)`
- Gradle wrapper (`gradlew`, `gradle/`)
- `constitution.md` + `initial-spec.md`
- `test-graph-skill/SKILL.md` + `test-graph-skill/reference.md`
- helper scripts under `test-graph-skill/scripts/`

### Starter project goals

- clone into a repo and start adding nodes immediately
- teach composition over duplication
- show shared utility extraction
- show cross-language graphs
- show unified reporting

## Agent-Facing Skill Documentation

The skill documentation must explicitly teach agents how to extend the graph safely.

### Required guidance in `SKILL.md`

- how to scaffold a new test-graph project
- how to add a node script (JBang or uv)
- how to declare a `NodeSpec` in-script
- how to compose scripts into a `testGraph(...)` in `build.gradle.kts`
- how to use `node(path).dependsOn(...)` to add graph-level edges
- how to choose Java vs Python
- how to emit structured report data
- how to publish and read cumulative context
- how to dry-run a graph (`validationPlanGraph --name=<graph>`)
- how to run a graph and aggregate reports

### Required authoring guidance

- prefer small nodes
- prefer reusable fixtures/testbeds
- do not duplicate shared utilities
- keep node ids stable and descriptive
- declare all dependencies explicitly
- return structured result envelopes
- compose workflows from nodes instead of writing one large script

## Initial Deliverables

### Deliverable 1: Java SDK

- context parser
- result envelope builder
- artifact/assertion helpers
- one example JBang node

### Deliverable 2: Python SDK

- context parser
- result envelope builder
- artifact/assertion helpers
- one example uv node

### Deliverable 3: Gradle plugin

- typed model
- DSL
- task materialization
- graph rendering
- node execution
- report aggregation

### Deliverable 4: Starter project

- one sample `testGraph("smoke") { ... }`
- four sample scripts (3 JBang + 1 uv) exercising cross-runtime context flow
- a node with NO script-declared deps, exercised via DSL overlay
- Gradle wrapper included
- agent documentation under `test-graph-skill/`

## Non-Goals for v0

Not required in the first version:

- distributed remote execution
- graph database backing
- IDE plugin support
- full CI orchestration
- advanced cache remoting
- dynamic runtime provisioning beyond declared local tools
