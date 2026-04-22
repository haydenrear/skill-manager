# Reference

Full surface for the test-graph system: NodeSpec, NodeResult, the DSL,
the Gradle tasks, the wire format for `--describe-out` and `--context`,
and the on-disk report layout. Pair with [`SKILL.md`](SKILL.md) for the
workflow guidance.

## NodeSpec (script-declared metadata)

Each script builds a `NodeSpec` and passes it to the SDK runner. The
Gradle plugin calls the script with `--describe-out=<tmp>` and reads the
emitted JSON; no YAML sidecar.

| Field          | Type                                                                        | Required | Notes                                                          |
| -------------- | --------------------------------------------------------------------------- | -------- | -------------------------------------------------------------- |
| `id`           | string (dotted)                                                             | yes      | Stable semantic identifier. Treat as public API.               |
| `kind`         | `testbed` \| `fixture` \| `action` \| `assertion` \| `evidence` \| `report` | yes      | Exactly one.                                                   |
| `runtime`      | `jbang` \| `uv`                                                             | yes      | Fixed by the SDK language (Java → `jbang`, Python → `uv`).     |
| `dependsOn`    | list\<string\>                                                              | no       | Upstream node ids.                                             |
| `tags`         | list\<string\>                                                              | no       | Free-form labels.                                              |
| `timeout`      | duration (`30s`, `2m`)                                                      | no       | Default: `60s`.                                                |
| `cacheable`    | boolean                                                                     | no       | Default: false. Only true if the node is a pure function of its inputs. |
| `sideEffects`  | list\<string\>                                                              | no       | Free-form: `browser`, `db:writes`, `fs:tmp`, `net:external`.   |
| `inputs`       | map\<string, type\>                                                         | no       | Typed inputs the node reads from context.                      |
| `outputs`      | map\<string, type\>                                                         | no       | Typed outputs the node produces in its envelope.               |
| `reports`      | object                                                                      | no       | See below.                                                     |

The entry path is not in the spec — the plugin knows it (it's the file it invoked).

### NodeSpec API — Java

```java
NodeSpec.of("login.smoke")
    .kind(NodeSpec.Kind.ASSERTION)
    .dependsOn("app.running", "user.seeded")
    .tags("smoke", "ui")
    .timeout("120s")
    .cacheable(false)
    .sideEffects("browser")
    .input("baseUrl", "string")
    .output("success", "boolean")
    .junitXml()
    .cucumber();
```

### NodeSpec API — Python

```python
NodeSpec("login.smoke") \
    .kind("assertion") \
    .depends_on("app.running", "user.seeded") \
    .tags("smoke", "ui") \
    .timeout("120s") \
    .cacheable(False) \
    .side_effects("browser") \
    .input("baseUrl", "string") \
    .output("success", "boolean") \
    .junit_xml() \
    .cucumber()
```

### Describe mode

Every script accepts `--describe-out=<path>`. In that mode the SDK writes
the spec JSON and exits 0 without running the body. Debug manually:

```bash
jbang sources/LoginSmoke.java  --describe-out=/tmp/spec.json
uv run sources/user_seeded.py  --describe-out=/tmp/spec.json
```

## Gradle DSL

```kotlin
validationGraph {
    sourcesDir("sources")                // pool for transitive dep resolution

    testGraph("smoke") {
        node("sources/user_seeded.py")   // explicit — describe runs at config time

        node("sources/LoginSmoke.java")
            .dependsOn("extra.upstream") // DSL-added dep (unioned with script's)
            .tags("regression")
            .timeout("120s")
            .cacheable(false)
            .sideEffects("net:external")
    }

    testGraph("quick") { … }             // registers another Gradle task
}
```

### NodeOverlay surface

`node(path)` returns a chainable overlay. Every setter returns `this`.

| Method                     | Behavior                                             |
| -------------------------- | ---------------------------------------------------- |
| `.dependsOn(vararg ids)`   | Appends to script's `dependsOn`. Union, not override. |
| `.tags(vararg t)`          | Appends to script's tags.                            |
| `.sideEffects(vararg s)`   | Appends to script's sideEffects.                    |
| `.timeout(v)`              | Overrides the scalar timeout.                        |
| `.cacheable(b)`            | Overrides the scalar cacheable flag.                |

**Merge policy**: collections are unioned; scalars override if set. The script is always the floor — the DSL can only add edges or tighten constraints, never hide a dep the script declared.

## Gradle tasks

| Task                                    | Purpose                                              |
| --------------------------------------- | ---------------------------------------------------- |
| `<graphName>`                           | Run the test graph with that name.                   |
| `validationListGraphs`                  | List all registered graphs + their explicit nodes.   |
| `validationPlanGraph --name=<graph>`    | Plan (topo table + dependency adjacency).            |
| `validationGraphDot --name=<graph>`     | Emit graphviz DOT only (pipe-friendly).              |
| `validationReport`                      | Aggregate latest run's envelopes into summary.json.  |

`discover.py <graph>` wraps `validationPlanGraph` (for the human console output) and `validationGraphDot` (for `docs/<graph>.dot`), and renders `docs/<graph>.png` if `dot` is on PATH.

## Unified result envelope

What each node writes to `build/validation-reports/<runId>/envelope/<nodeId>.json`:

```json
{
  "nodeId": "login.smoke",
  "status": "passed",
  "startedAt": "2026-04-21T22:06:57.043351Z",
  "endedAt":   "2026-04-21T22:06:57.216374Z",
  "assertions": [
    { "name": "login_endpoint_reachable", "status": "passed" }
  ],
  "artifacts": [
    { "type": "screenshot", "path": "build/validation-reports/20260421-220657/login.png" }
  ],
  "metrics": { "statusCode": 200, "durationMs": 173 },
  "logs": [],
  "published": { "attemptedAs": "u-1a2b3c4d" }
}
```

- `status`: `passed | failed | errored | skipped`.
- `published` is this node's contribution to the downstream `Context[]`.

## Context[] — the data wire between nodes

### Wire format

One CLI arg, two encodings:

- **Inline** (≤ 8 KB): `--context={"items":[{"nodeId":"user.seeded","data":{"userId":"u-1a2b"}}, ...]}`
- **File ref** (larger): `--context=@<abs-path>` — plugin writes JSON to `<reportDir>/context/step-NNN.json`.

### Shape

```json
{
  "items": [
    { "nodeId": "app.running", "data": { "baseUrl": "http://localhost:8080" } },
    { "nodeId": "user.seeded", "data": { "userId": "u-1a2b3c4d", "username": "smoke-user" } }
  ]
}
```

Order reflects plan execution order. Exactly one `ContextItem` per upstream node in the plan.

### Reading — Java

```java
String userId = ctx.get("user.seeded", "userId").orElseThrow();
ContextItem it = ctx.item("user.seeded").orElseThrow();
List<ContextItem> all = ctx.context();
```

### Reading — Python

```python
user_id = ctx.get("user.seeded", "userId")
item    = ctx.item("user.seeded")
for it in ctx.context:
    ...
```

### Publishing

Call `NodeResult.publish(key, value)`. Everything in `published` is forwarded automatically. `logs`, `metrics`, `artifacts` do NOT flow downstream — they're report-only. For upstream envelope access beyond primitives, use `ctx.upstream(id)`.

## Importing user code

Node scripts can pull in source files from the user's own codebase when
this scaffold lives at `<user-project>/test_graph/`. From
`test_graph/sources/<script>`, the user-project root is always `../..`.

### Java (JBang)

- `//SOURCES ../../src/main/java/com/acme/**/*.java` — compile user classes alongside the node.
- `//DEPS <group:artifact:version>` — resolve external JVM libraries from Maven Central.

Paths are relative to the node script file. Be specific to keep compilation fast.

### Python (uv)

Inline script metadata supports `[tool.uv.sources]` with path-based sources:

```
# dependencies = ["testgraphsdk", "acme-domain"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# acme-domain          = { path = "../..",          editable = true }
```

Assumes `<user-project>/pyproject.toml` declares the package. `sys.path` insertion at `Path(__file__).resolve().parents[2] / "src" / "main" / "python"` is the fallback for unpackaged user code.

See [`SKILL.md`](SKILL.md) → **Importing user code** for full examples.

## Report output

Each run writes under `build/validation-reports/<runId>/`:

```
build/validation-reports/<runId>/
  envelope/<nodeId>.json     # canonical per-node envelope
  context/step-NNN.json      # (optional) spilled context payloads
  summary.json               # aggregated summary (after validationReport)
```

`summary.json` is the machine-readable handoff for CI, dashboards, agents.

## Java SDK (`com.hayden.testgraphsdk.sdk`)

### Node.run

```java
Node.run(args, spec, ctx -> { … return NodeResult; });
```

- If `--describe-out=<path>` is present: serialize spec, exit 0.
- Otherwise: parse context, run body, write envelope, exit pass/fail.

### NodeContext members

| Member               | Returns                 | Purpose                                           |
| -------------------- | ----------------------- | ------------------------------------------------- |
| `nodeId()`           | `String`                | Currently executing node id.                      |
| `input(key)`         | `Optional<String>`      | Typed input from the graph model.                 |
| `reportDir()`        | `Path`                  | Where to write artifacts for this run.            |
| `runId()`            | `String`                | Id of the overall graph run.                      |
| `context()`          | `List<ContextItem>`     | Ordered upstream Context[] (execution order).     |
| `get(upId, key)`     | `Optional<String>`      | Lookup from an upstream node's published data.    |
| `item(upId)`         | `Optional<ContextItem>` | Full ContextItem for one upstream node.           |

### NodeResult methods

| Method                        | Purpose                                      |
| ----------------------------- | -------------------------------------------- |
| `NodeResult.pass(nodeId)`     | Start a passed envelope.                    |
| `NodeResult.fail(nodeId,msg)` | Start a failed envelope.                    |
| `NodeResult.error(nodeId,t)`  | Start an errored envelope from a throwable. |
| `.assertion(name, bool)`      | Record a named assertion.                    |
| `.artifact(type, path)`       | Register an artifact path.                  |
| `.metric(name, number)`       | Add a numeric metric.                       |
| `.log(line)`                  | Add a log line (report-only).               |
| `.publish(key, value)`        | Publish to downstream `Context[]`.          |
| `.toContextItem()`            | Project published map as ContextItem.       |

## Python SDK (`testgraphsdk`)

### @node decorator

```python
@node(spec)
def main(ctx):
    …
    return NodeResult.pass_(spec.id)

if __name__ == "__main__":
    main()
```

### NodeContext attributes

| Attribute               | Returns                | Purpose                                             |
| ----------------------- | ---------------------- | --------------------------------------------------- |
| `ctx.node_id`           | `str`                  | Currently executing node id.                        |
| `ctx.input(key)`        | `str \| None`          | Typed input.                                        |
| `ctx.report_dir`        | `pathlib.Path`         | Where to write artifacts.                           |
| `ctx.run_id`            | `str`                  | Overall run id.                                     |
| `ctx.context`           | `list[ContextItem]`    | Ordered upstream Context[].                         |
| `ctx.get(up_id, key)`   | `str \| None`          | Lookup upstream published data.                     |
| `ctx.item(up_id)`       | `ContextItem \| None`  | Full ContextItem for one upstream node.             |
| `ctx.upstream(node_id)` | `dict \| None`         | Full envelope JSON for an upstream node.            |

### NodeResult methods

| Method                             | Purpose                        |
| ---------------------------------- | ------------------------------ |
| `NodeResult.pass_(node_id)`        | Passed envelope.               |
| `NodeResult.fail(node_id, msg)`    | Failed envelope.               |
| `NodeResult.error(node_id, exc)`   | Errored envelope.              |
| `.assertion(name, ok)`             | Named assertion.               |
| `.artifact(kind, path)`            | File artifact.                 |
| `.metric(name, value)`             | Numeric metric.                |
| `.log(line)`                       | Log line (report-only).        |
| `.publish(key, value)`             | Publish to downstream.         |
| `.to_context_item()`               | Project published as ContextItem. |
