# Validation report — 20260712-181209

**Overall**: PASSED  
**Nodes**: 15 (passed=15, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 612ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 635ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `gateway.up` | **PASS** | 3333ms | [context/gateway.up.input.json](context/gateway.up.input.json) | [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log) |
| `postgres.down` | **PASS** | 822ms | [context/postgres.down.input.json](context/postgres.down.input.json) | [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log) |
| `postgres.up` | **PASS** | 1195ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `registry.up` | **PASS** | 4634ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `resolver.cycles.verified` | **PASS** | 8641ms | [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json) | [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log) |
| `servers.down` | **PASS** | 3733ms | [context/servers.down.input.json](context/servers.down.input.json) | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |
| `skill-dev.conflict.resolved` | **PASS** | 9665ms | [context/skill-dev.conflict.resolved.input.json](context/skill-dev.conflict.resolved.input.json) | [node-logs/skill-dev.conflict.resolved.stdout.log](node-logs/skill-dev.conflict.resolved.stdout.log) |
| `skill-dev.edit.doc` | **PASS** | 5233ms | [context/skill-dev.edit.doc.input.json](context/skill-dev.edit.doc.input.json) | [node-logs/skill-dev.edit.doc.stdout.log](node-logs/skill-dev.edit.doc.stdout.log) |
| `skill-dev.edit.harness` | **PASS** | 5304ms | [context/skill-dev.edit.harness.input.json](context/skill-dev.edit.harness.input.json) | [node-logs/skill-dev.edit.harness.stdout.log](node-logs/skill-dev.edit.harness.stdout.log) |
| `skill-dev.edit.plugin` | **PASS** | 5306ms | [context/skill-dev.edit.plugin.input.json](context/skill-dev.edit.plugin.input.json) | [node-logs/skill-dev.edit.plugin.stdout.log](node-logs/skill-dev.edit.plugin.stdout.log) |
| `skill-dev.edit.skill` | **PASS** | 5564ms | [context/skill-dev.edit.skill.input.json](context/skill-dev.edit.skill.input.json) | [node-logs/skill-dev.edit.skill.stdout.log](node-logs/skill-dev.edit.skill.stdout.log) |
| `skill-dev.installed` | **PASS** | 10212ms | [context/skill-dev.installed.input.json](context/skill-dev.installed.input.json) | [node-logs/skill-dev.installed.stdout.log](node-logs/skill-dev.installed.stdout.log) |
| `skill-dev.units.installed` | **PASS** | 15490ms | [context/skill-dev.units.installed.input.json](context/skill-dev.units.installed.input.json) | [node-logs/skill-dev.units.installed.stdout.log](node-logs/skill-dev.units.installed.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:12:09.867875Z`  
executor end: `2026-07-12T18:12:10.479025Z`  
spawn exit code: 0

**Input context**: [context/env.prepared.input.json](context/env.prepared.input.json)

### Assertions

| Name | Status |
|---|---|
| home_created | **PASS** |
| agent_home_created | **PASS** |
| codex_home_created | **PASS** |
| gemini_home_created | **PASS** |
| ports_allocated | **PASS** |

### Metrics

- `registryPort`: 65426
- `gatewayPort`: 65427
- `durationMs`: 30

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/agent-home/.gemini`
- `registryPort`: `65426`
- `gatewayPort`: `65427`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:12:19.120939Z`  
executor end: `2026-07-12T18:12:19.755621Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 53

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 47ms | 89428 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `gateway.up` — **PASS**

executor start: `2026-07-12T18:12:25.586976Z`  
executor end: `2026-07-12T18:12:28.919828Z`  
spawn exit code: 0

**Input context**: [context/gateway.up.input.json](context/gateway.up.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_healthy | **PASS** |

### Metrics

- `port`: 65427
- `durationMs`: 2745

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-up | 0 | 2394ms | 89460 | [`node-logs/gateway.up.gateway-up.log`](node-logs/gateway.up.gateway-up.log) |  |

### Published context

- `baseUrl`: `http://127.0.0.1:65427`

**Node-process stdout**: [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log)

---

## `postgres.down` — **PASS**

executor start: `2026-07-12T18:13:29.432374Z`  
executor end: `2026-07-12T18:13:30.254152Z`  
spawn exit code: 0

**Input context**: [context/postgres.down.input.json](context/postgres.down.input.json)

### Assertions

| Name | Status |
|---|---|
| stopped | **PASS** |

### Metrics

- `durationMs`: 207

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-stop | 0 | 200ms | 90566 | [`node-logs/postgres.down.compose-stop.log`](node-logs/postgres.down.compose-stop.log) |  |

**Node-process stdout**: [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T18:12:19.756451Z`  
executor end: `2026-07-12T18:12:20.951928Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 627

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-restart | 0 | 324ms | 89438 | [`node-logs/postgres.up.compose-restart.log`](node-logs/postgres.up.compose-restart.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T18:12:20.952688Z`  
executor end: `2026-07-12T18:12:25.586127Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 89448
- `port`: 65426
- `durationMs`: 3662

### Published context

- `baseUrl`: `http://127.0.0.1:65426`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `resolver.cycles.verified` — **PASS**

executor start: `2026-07-12T18:12:10.479776Z`  
executor end: `2026-07-12T18:12:19.120006Z`  
spawn exit code: 0

**Input context**: [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json)

### Assertions

| Name | Status |
|---|---|
| self-cycle.terminated | **PASS** |
| self-cycle.install_exit_zero | **PASS** |
| self-cycle.cycle_path_reported | **PASS** |
| self-cycle.each_unit_installed_once | **PASS** |
| self-cycle.no_stage_dirs_leaked | **PASS** |
| two-skill-cycle.terminated | **PASS** |
| two-skill-cycle.install_exit_zero | **PASS** |
| two-skill-cycle.cycle_path_reported | **PASS** |
| two-skill-cycle.each_unit_installed_once | **PASS** |
| two-skill-cycle.no_stage_dirs_leaked | **PASS** |
| three-skill-cycle.terminated | **PASS** |
| three-skill-cycle.install_exit_zero | **PASS** |
| three-skill-cycle.cycle_path_reported | **PASS** |
| three-skill-cycle.each_unit_installed_once | **PASS** |
| three-skill-cycle.no_stage_dirs_leaked | **PASS** |
| skill-plugin-cycle.terminated | **PASS** |
| skill-plugin-cycle.install_exit_zero | **PASS** |
| skill-plugin-cycle.cycle_path_reported | **PASS** |
| skill-plugin-cycle.each_unit_installed_once | **PASS** |
| skill-plugin-cycle.no_stage_dirs_leaked | **PASS** |
| plugin-plugin-cycle.terminated | **PASS** |
| plugin-plugin-cycle.install_exit_zero | **PASS** |
| plugin-plugin-cycle.cycle_path_reported | **PASS** |
| plugin-plugin-cycle.each_unit_installed_once | **PASS** |
| plugin-plugin-cycle.no_stage_dirs_leaked | **PASS** |

### Metrics

- `cases`: 5
- `durationMs`: 8054

### Inline logs

```
self-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-self-unit -> semantic-self-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/self-cycle/repos/coord-self-repository | INSTALLED: semantic-self-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/self-cycle/home/skills/semantic-self-unit | ✓ units.lock.toml: wrote 1 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/self-cycle/home/units.lock.toml
two-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-alpha-unit -> semantic-beta-unit -> semantic-alpha-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/two-skill-cycle/repos/coord-alpha-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/two-skill-cycle/repos/coord-beta-repository | INSTALLED: semantic-alpha-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-alpha-unit | INSTALLED: semantic-beta-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-beta-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/two-skill-cycle/home/units.lock.toml
three-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-first-unit -> semantic-second-unit -> semantic-third-unit -> semantic-first-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/three-skill-cycle/repos/coord-first-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/three-skill-cycle/repos/coord-second-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/three-skill-cycle/repos/coord-third-repository | INSTALLED: semantic-first-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-first-unit | INSTALLED: semantic-second-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-second-unit | INSTALLED: semantic-third-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-third-unit | ✓ units.lock.toml: wrote 3 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/three-skill-cycle/home/units.lock.toml
skill-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-skill-unit -> semantic-plugin-unit -> semantic-skill-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-skill-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-plugin-repository | INSTALLED: semantic-skill-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/skill-plugin-cycle/home/skills/semantic-skill-unit | INSTALLED: semantic-plugin-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/skill-plugin-cycle/home/plugins/semantic-plugin-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/skill-plugin-cycle/home/units.lock.toml
plugin-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-left-plugin -> semantic-right-plugin -> semantic-left-plugin |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-left-plugin-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-right-plugin-repository | INSTALLED: semantic-left-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-left-plugin | INSTALLED: semantic-right-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-right-plugin | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/resolver-cycle-matrix/plugin-plugin-cycle/home/units.lock.toml
```

**Node-process stdout**: [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-07-12T18:13:25.698606Z`  
executor end: `2026-07-12T18:13:29.431637Z`  
spawn exit code: 0

**Input context**: [context/servers.down.input.json](context/servers.down.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 3169

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 1338ms | 90517 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

## `skill-dev.conflict.resolved` — **PASS**

executor start: `2026-07-12T18:13:16.032147Z`  
executor end: `2026-07-12T18:13:25.697841Z`  
spawn exit code: 0

**Input context**: [context/skill-dev.conflict.resolved.input.json](context/skill-dev.conflict.resolved.input.json)

### Assertions

| Name | Status |
|---|---|
| conflict_setup_ok | **PASS** |
| sync_reported_conflict | **PASS** |
| manual_resolution_committed_and_synced | **PASS** |

### Metrics

- `durationMs`: 9040

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| conflict-open | 0 | 163ms | 90350 | [`node-logs/skill-dev.conflict.resolved.conflict-open.log`](node-logs/skill-dev.conflict.resolved.conflict-open.log) |  |
| conflict-worktree-commit | 0 | 32ms | 90360 | [`node-logs/skill-dev.conflict.resolved.conflict-worktree-commit.log`](node-logs/skill-dev.conflict.resolved.conflict-worktree-commit.log) |  |
| conflict-store-commit | 0 | 34ms | 90365 | [`node-logs/skill-dev.conflict.resolved.conflict-store-commit.log`](node-logs/skill-dev.conflict.resolved.conflict-store-commit.log) |  |
| conflict-sync | 8 | 4279ms | 90368 | [`node-logs/skill-dev.conflict.resolved.conflict-sync.log`](node-logs/skill-dev.conflict.resolved.conflict-sync.log) |  |
| conflict-resolve-commit | 0 | 21ms | 90419 | [`node-logs/skill-dev.conflict.resolved.conflict-resolve-commit.log`](node-logs/skill-dev.conflict.resolved.conflict-resolve-commit.log) |  |
| conflict-merge-commit | 0 | 27ms | 90420 | [`node-logs/skill-dev.conflict.resolved.conflict-merge-commit.log`](node-logs/skill-dev.conflict.resolved.conflict-merge-commit.log) |  |
| conflict-sync-after-resolve | 0 | 4311ms | 90423 | [`node-logs/skill-dev.conflict.resolved.conflict-sync-after-resolve.log`](node-logs/skill-dev.conflict.resolved.conflict-sync-after-resolve.log) |  |
| conflict-close | 0 | 153ms | 90502 | [`node-logs/skill-dev.conflict.resolved.conflict-close.log`](node-logs/skill-dev.conflict.resolved.conflict-close.log) |  |

**Node-process stdout**: [node-logs/skill-dev.conflict.resolved.stdout.log](node-logs/skill-dev.conflict.resolved.stdout.log)

---

## `skill-dev.edit.doc` — **PASS**

executor start: `2026-07-12T18:13:05.494618Z`  
executor end: `2026-07-12T18:13:10.727183Z`  
spawn exit code: 0

**Input context**: [context/skill-dev.edit.doc.input.json](context/skill-dev.edit.doc.input.json)

### Assertions

| Name | Status |
|---|---|
| edit_cycle_ok | **PASS** |
| store_contains_marker | **PASS** |

### Metrics

- `durationMs`: 4599

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| skill-dev-edit-doc-open | 0 | 142ms | 90141 | [`node-logs/skill-dev.edit.doc.skill-dev-edit-doc-open.log`](node-logs/skill-dev.edit.doc.skill-dev-edit-doc-open.log) |  |
| skill-dev-edit-doc-commit | 0 | 30ms | 90149 | [`node-logs/skill-dev.edit.doc.skill-dev-edit-doc-commit.log`](node-logs/skill-dev.edit.doc.skill-dev-edit-doc-commit.log) |  |
| skill-dev-edit-doc-sync | 0 | 4256ms | 90152 | [`node-logs/skill-dev.edit.doc.skill-dev-edit-doc-sync.log`](node-logs/skill-dev.edit.doc.skill-dev-edit-doc-sync.log) |  |
| skill-dev-edit-doc-close | 0 | 152ms | 90235 | [`node-logs/skill-dev.edit.doc.skill-dev-edit-doc-close.log`](node-logs/skill-dev.edit.doc.skill-dev-edit-doc-close.log) |  |

**Node-process stdout**: [node-logs/skill-dev.edit.doc.stdout.log](node-logs/skill-dev.edit.doc.stdout.log)

---

## `skill-dev.edit.harness` — **PASS**

executor start: `2026-07-12T18:13:10.727856Z`  
executor end: `2026-07-12T18:13:16.031358Z`  
spawn exit code: 0

**Input context**: [context/skill-dev.edit.harness.input.json](context/skill-dev.edit.harness.input.json)

### Assertions

| Name | Status |
|---|---|
| edit_cycle_ok | **PASS** |
| store_contains_marker | **PASS** |

### Metrics

- `durationMs`: 4621

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| skill-dev-edit-harness-open | 0 | 147ms | 90266 | [`node-logs/skill-dev.edit.harness.skill-dev-edit-harness-open.log`](node-logs/skill-dev.edit.harness.skill-dev-edit-harness-open.log) |  |
| skill-dev-edit-harness-commit | 0 | 33ms | 90274 | [`node-logs/skill-dev.edit.harness.skill-dev-edit-harness-commit.log`](node-logs/skill-dev.edit.harness.skill-dev-edit-harness-commit.log) |  |
| skill-dev-edit-harness-sync | 0 | 4266ms | 90277 | [`node-logs/skill-dev.edit.harness.skill-dev-edit-harness-sync.log`](node-logs/skill-dev.edit.harness.skill-dev-edit-harness-sync.log) |  |
| skill-dev-edit-harness-close | 0 | 156ms | 90335 | [`node-logs/skill-dev.edit.harness.skill-dev-edit-harness-close.log`](node-logs/skill-dev.edit.harness.skill-dev-edit-harness-close.log) |  |

**Node-process stdout**: [node-logs/skill-dev.edit.harness.stdout.log](node-logs/skill-dev.edit.harness.stdout.log)

---

## `skill-dev.edit.plugin` — **PASS**

executor start: `2026-07-12T18:13:00.187714Z`  
executor end: `2026-07-12T18:13:05.493708Z`  
spawn exit code: 0

**Input context**: [context/skill-dev.edit.plugin.input.json](context/skill-dev.edit.plugin.input.json)

### Assertions

| Name | Status |
|---|---|
| edit_cycle_ok | **PASS** |
| store_contains_marker | **PASS** |

### Metrics

- `durationMs`: 4721

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| skill-dev-edit-plugin-open | 0 | 137ms | 90011 | [`node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-open.log`](node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-open.log) |  |
| skill-dev-edit-plugin-commit | 0 | 28ms | 90019 | [`node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-commit.log`](node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-commit.log) |  |
| skill-dev-edit-plugin-sync | 0 | 4338ms | 90022 | [`node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-sync.log`](node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-sync.log) |  |
| skill-dev-edit-plugin-close | 0 | 201ms | 90122 | [`node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-close.log`](node-logs/skill-dev.edit.plugin.skill-dev-edit-plugin-close.log) |  |

**Node-process stdout**: [node-logs/skill-dev.edit.plugin.stdout.log](node-logs/skill-dev.edit.plugin.stdout.log)

---

## `skill-dev.edit.skill` — **PASS**

executor start: `2026-07-12T18:12:54.623625Z`  
executor end: `2026-07-12T18:13:00.187011Z`  
spawn exit code: 0

**Input context**: [context/skill-dev.edit.skill.input.json](context/skill-dev.edit.skill.input.json)

### Assertions

| Name | Status |
|---|---|
| edit_cycle_ok | **PASS** |
| store_contains_marker | **PASS** |

### Metrics

- `durationMs`: 4955

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| skill-dev-edit-skill-open | 0 | 500ms | 89832 | [`node-logs/skill-dev.edit.skill.skill-dev-edit-skill-open.log`](node-logs/skill-dev.edit.skill.skill-dev-edit-skill-open.log) |  |
| skill-dev-edit-skill-commit | 0 | 29ms | 89856 | [`node-logs/skill-dev.edit.skill.skill-dev-edit-skill-commit.log`](node-logs/skill-dev.edit.skill.skill-dev-edit-skill-commit.log) |  |
| skill-dev-edit-skill-sync | 0 | 4244ms | 89861 | [`node-logs/skill-dev.edit.skill.skill-dev-edit-skill-sync.log`](node-logs/skill-dev.edit.skill.skill-dev-edit-skill-sync.log) |  |
| skill-dev-edit-skill-close | 0 | 163ms | 89994 | [`node-logs/skill-dev.edit.skill.skill-dev-edit-skill-close.log`](node-logs/skill-dev.edit.skill.skill-dev-edit-skill-close.log) |  |

**Node-process stdout**: [node-logs/skill-dev.edit.skill.stdout.log](node-logs/skill-dev.edit.skill.stdout.log)

---

## `skill-dev.installed` — **PASS**

executor start: `2026-07-12T18:12:28.920509Z`  
executor end: `2026-07-12T18:12:39.132320Z`  
spawn exit code: 0

**Input context**: [context/skill-dev.installed.input.json](context/skill-dev.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_ok | **PASS** |
| skill_dev_executable | **PASS** |

### Metrics

- `durationMs`: 9634

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install-skill-dev | 0 | 9620ms | 89479 | [`node-logs/skill-dev.installed.install-skill-dev.log`](node-logs/skill-dev.installed.install-skill-dev.log) |  |

### Published context

- `skillDev`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/bin/cli/skill-dev`

**Node-process stdout**: [node-logs/skill-dev.installed.stdout.log](node-logs/skill-dev.installed.stdout.log)

---

## `skill-dev.units.installed` — **PASS**

executor start: `2026-07-12T18:12:39.132987Z`  
executor end: `2026-07-12T18:12:54.622377Z`  
spawn exit code: 0

**Input context**: [context/skill-dev.units.installed.input.json](context/skill-dev.units.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| all_installs_exit_zero | **PASS** |
| skill_installed | **PASS** |
| plugin_installed | **PASS** |
| doc_repo_installed | **PASS** |
| harness_installed | **PASS** |
| conflict_skill_installed | **PASS** |

### Metrics

- `durationMs`: 14993

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install-skill-dev-edit-skill | 0 | 2508ms | 89615 | [`node-logs/skill-dev.units.installed.install-skill-dev-edit-skill.log`](node-logs/skill-dev.units.installed.install-skill-dev-edit-skill.log) |  |
| install-skill-dev-edit-plugin | 0 | 3955ms | 89641 | [`node-logs/skill-dev.units.installed.install-skill-dev-edit-plugin.log`](node-logs/skill-dev.units.installed.install-skill-dev-edit-plugin.log) |  |
| install-skill-dev-edit-doc | 0 | 2552ms | 89678 | [`node-logs/skill-dev.units.installed.install-skill-dev-edit-doc.log`](node-logs/skill-dev.units.installed.install-skill-dev-edit-doc.log) |  |
| install-skill-dev-edit-harness | 0 | 2632ms | 89725 | [`node-logs/skill-dev.units.installed.install-skill-dev-edit-harness.log`](node-logs/skill-dev.units.installed.install-skill-dev-edit-harness.log) |  |
| install-skill-dev-conflict-skill | 0 | 2814ms | 89781 | [`node-logs/skill-dev.units.installed.install-skill-dev-conflict-skill.log`](node-logs/skill-dev.units.installed.install-skill-dev-conflict-skill.log) |  |

### Published context

- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-16357367977424657776/skill-dev-project`

**Node-process stdout**: [node-logs/skill-dev.units.installed.stdout.log](node-logs/skill-dev.units.installed.stdout.log)

---

