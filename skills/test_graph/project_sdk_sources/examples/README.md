# Examples

The root `build.gradle.kts` wires a `smoke` test graph composed from the
four scripts under `sources/`:

- `app.running` (testbed, JBang) — HEAD-probes `baseUrl`. Publishes `baseUrl` downstream.
  *Not* listed in the DSL — pulled in transitively because other nodes declare `dependsOn("app.running")`.
- `user.seeded` (fixture, uv) — writes a synthetic user record, publishes `userId`.
- `network.pingable` (evidence, JBang) — its script declares **no** `dependsOn`. The DSL adds `.dependsOn("app.running")` so it sits between the testbed and the assertion.
- `login.smoke` (assertion, JBang) — script deps: `app.running`, `user.seeded`. DSL adds `.dependsOn("network.pingable")`. Reads `userId` across the runtime boundary via `ctx.get("user.seeded", "userId")`.

## Try it

```bash
./gradlew validationListGraphs
./gradlew validationPlanGraph --name=smoke
./gradlew smoke                         # or: test-graph-skill/scripts/run.py smoke
./gradlew validationReport
```

Per-node envelopes land at `build/validation-reports/<runId>/envelope/`; the unified summary is `summary.json` in the same dir.

## Expected shape

`validationListGraphs`:

```
graph: smoke
  - sources/user_seeded.py
  - sources/NetworkPingable.java
  - sources/LoginSmoke.java
```

`validationPlanGraph --name=smoke`:

```
plan for test graph 'smoke' (4 steps):
  #   id                kind        rt      entry
  1.  app.running       testbed     jbang   sources/AppRunning.java
  2.  user.seeded       fixture     uv      sources/user_seeded.py
  3.  network.pingable  evidence    jbang   sources/NetworkPingable.java
  4.  login.smoke       assertion   jbang   sources/LoginSmoke.java

dependencies:
  app.running       (root)
  user.seeded       <- app.running
  network.pingable  <- app.running
  login.smoke       <- app.running, user.seeded, network.pingable
```

`test-graph-skill/scripts/discover.py smoke` also writes `docs/smoke.dot` and renders `docs/smoke.png` (if graphviz is installed), so you can view the DAG visually.

`summary.json` after a successful run should include:

```json
"user.seeded":     { "published": { "userId": "u-xxxx" } }
"login.smoke":     { "published": { "attemptedAs": "u-xxxx" } }
```

— the same `u-xxxx` string, showing the accumulated context survived the uv → jbang boundary.
