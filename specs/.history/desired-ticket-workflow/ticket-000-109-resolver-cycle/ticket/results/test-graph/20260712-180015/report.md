# Validation report — 20260712-180015

**Overall**: PASSED  
**Nodes**: 9 (passed=9, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `account.created` | **PASS** | 2248ms | [context/account.created.input.json](context/account.created.input.json) | [node-logs/account.created.stdout.log](node-logs/account.created.stdout.log) |
| `env.prepared` | **PASS** | 1347ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 1488ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `postgres.down` | **PASS** | 790ms | [context/postgres.down.input.json](context/postgres.down.input.json) | [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log) |
| `postgres.up` | **PASS** | 2134ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `refresh.on.expiry` | **PASS** | 11237ms | [context/refresh.on.expiry.input.json](context/refresh.on.expiry.input.json) | [node-logs/refresh.on.expiry.stdout.log](node-logs/refresh.on.expiry.stdout.log) |
| `registry.up` | **PASS** | 12195ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `selenium.ready` | **PASS** | 2521ms | [context/selenium.ready.input.json](context/selenium.ready.input.json) | [node-logs/selenium.ready.stdout.log](node-logs/selenium.ready.stdout.log) |
| `short.access.token.ttl` | **PASS** | 1258ms | [context/short.access.token.ttl.input.json](context/short.access.token.ttl.input.json) | [node-logs/short.access.token.ttl.stdout.log](node-logs/short.access.token.ttl.stdout.log) |

## `account.created` — **PASS**

executor start: `2026-07-12T18:00:36.133294Z`  
executor end: `2026-07-12T18:00:38.381702Z`  
spawn exit code: 0

**Input context**: [context/account.created.input.json](context/account.created.input.json)

### Assertions

| Name | Status |
|---|---|
| row_exists | **PASS** |
| email_persisted | **PASS** |
| password_hash_is_bcrypt | **PASS** |

### Metrics

- `durationMs`: 1684

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| create-account | 0 | 1492ms | 81894 | [`node-logs/account.created.create-account.log`](node-logs/account.created.create-account.log) |  |

### Published context

- `username`: `graph-user`
- `password`: `graph-user-password-2026`
- `email`: `graph-user@skill-manager.test`

**Node-process stdout**: [node-logs/account.created.stdout.log](node-logs/account.created.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:00:16.672269Z`  
executor end: `2026-07-12T18:00:18.019904Z`  
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

- `registryPort`: 64161
- `gatewayPort`: 64162
- `durationMs`: 47

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-18368182684762549071`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-18368182684762549071/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-18368182684762549071/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-18368182684762549071/agent-home/.gemini`
- `registryPort`: `64161`
- `gatewayPort`: `64162`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:00:15.183525Z`  
executor end: `2026-07-12T18:00:16.671358Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 77

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 67ms | 81489 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `postgres.down` — **PASS**

executor start: `2026-07-12T18:00:49.620244Z`  
executor end: `2026-07-12T18:00:50.410330Z`  
spawn exit code: 0

**Input context**: [context/postgres.down.input.json](context/postgres.down.input.json)

### Assertions

| Name | Status |
|---|---|
| stopped | **PASS** |

### Metrics

- `durationMs`: 199

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-stop | 0 | 192ms | 82099 | [`node-logs/postgres.down.compose-stop.log`](node-logs/postgres.down.compose-stop.log) |  |

**Node-process stdout**: [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T18:00:18.021696Z`  
executor end: `2026-07-12T18:00:20.155410Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 939

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-up | 0 | 416ms | 81508 | [`node-logs/postgres.up.compose-up.log`](node-logs/postgres.up.compose-up.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `refresh.on.expiry` — **PASS**

executor start: `2026-07-12T18:00:38.382617Z`  
executor end: `2026-07-12T18:00:49.619407Z`  
spawn exit code: 0

**Input context**: [context/refresh.on.expiry.input.json](context/refresh.on.expiry.input.json)

### Assertions

| Name | Status |
|---|---|
| original_token_is_valid_jwt | **PASS** |
| authed_command_succeeded | **PASS** |
| access_token_rotated | **PASS** |
| refresh_token_rotated | **PASS** |

### Metrics

- `durationMs`: 10739

**Node-process stdout**: [node-logs/refresh.on.expiry.stdout.log](node-logs/refresh.on.expiry.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T18:00:21.415822Z`  
executor end: `2026-07-12T18:00:33.610257Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 81540
- `port`: 64161
- `durationMs`: 10678

### Published context

- `baseUrl`: `http://127.0.0.1:64161`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `selenium.ready` — **PASS**

executor start: `2026-07-12T18:00:33.611193Z`  
executor end: `2026-07-12T18:00:36.132495Z`  
spawn exit code: 0

**Input context**: [context/selenium.ready.input.json](context/selenium.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| chromedriver_resolved | **PASS** |
| chrome_browser_resolved | **PASS** |
| chromedriver_started | **PASS** |

### Metrics

- `durationMs`: 1900

**Node-process stdout**: [node-logs/selenium.ready.stdout.log](node-logs/selenium.ready.stdout.log)

---

## `short.access.token.ttl` — **PASS**

executor start: `2026-07-12T18:00:20.156282Z`  
executor end: `2026-07-12T18:00:21.414907Z`  
spawn exit code: 0

**Input context**: [context/short.access.token.ttl.input.json](context/short.access.token.ttl.input.json)

### Assertions

| Name | Status |
|---|---|
| ttl_published | **PASS** |

### Metrics

- `durationMs`: 1

### Published context

- `seconds`: `3`
- `clockSkewSeconds`: `0`

**Node-process stdout**: [node-logs/short.access.token.ttl.stdout.log](node-logs/short.access.token.ttl.stdout.log)

---

