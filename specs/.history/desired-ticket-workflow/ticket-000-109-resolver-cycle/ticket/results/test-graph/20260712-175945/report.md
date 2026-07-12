# Validation report — 20260712-175945

**Overall**: PASSED  
**Nodes**: 8 (passed=8, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `account.created` | **PASS** | 2210ms | [context/account.created.input.json](context/account.created.input.json) | [node-logs/account.created.stdout.log](node-logs/account.created.stdout.log) |
| `browser.authorized` | **PASS** | 4736ms | [context/browser.authorized.input.json](context/browser.authorized.input.json) | [node-logs/browser.authorized.stdout.log](node-logs/browser.authorized.stdout.log) |
| `env.prepared` | **PASS** | 588ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 612ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `postgres.down` | **PASS** | 780ms | [context/postgres.down.input.json](context/postgres.down.input.json) | [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log) |
| `postgres.up` | **PASS** | 1585ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `registry.up` | **PASS** | 4876ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `selenium.ready` | **PASS** | 5836ms | [context/selenium.ready.input.json](context/selenium.ready.input.json) | [node-logs/selenium.ready.stdout.log](node-logs/selenium.ready.stdout.log) |

## `account.created` — **PASS**

executor start: `2026-07-12T17:59:59.131987Z`  
executor end: `2026-07-12T18:00:01.341433Z`  
spawn exit code: 0

**Input context**: [context/account.created.input.json](context/account.created.input.json)

### Assertions

| Name | Status |
|---|---|
| row_exists | **PASS** |
| email_persisted | **PASS** |
| password_hash_is_bcrypt | **PASS** |

### Metrics

- `durationMs`: 1649

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| create-account | 0 | 1457ms | 81112 | [`node-logs/account.created.create-account.log`](node-logs/account.created.create-account.log) |  |

### Published context

- `username`: `graph-user`
- `password`: `graph-user-password-2026`
- `email`: `graph-user@skill-manager.test`

**Node-process stdout**: [node-logs/account.created.stdout.log](node-logs/account.created.stdout.log)

---

## `browser.authorized` — **PASS**

executor start: `2026-07-12T18:00:01.342483Z`  
executor end: `2026-07-12T18:00:06.078607Z`  
spawn exit code: 0

**Input context**: [context/browser.authorized.input.json](context/browser.authorized.input.json)

### Assertions

| Name | Status |
|---|---|
| cli_printed_authorize_url | **PASS** |
| login_form_submitted | **PASS** |
| cli_exit_zero | **PASS** |
| token_cached_on_disk | **PASS** |
| me_returns_expected_username | **PASS** |

### Metrics

- `cli_exit_code`: 0
- `durationMs`: 4179

**Node-process stdout**: [node-logs/browser.authorized.stdout.log](node-logs/browser.authorized.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T17:59:46.242741Z`  
executor end: `2026-07-12T17:59:46.830313Z`  
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

- `registryPort`: 64062
- `gatewayPort`: 64063
- `durationMs`: 27

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13487420078936789756`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13487420078936789756/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13487420078936789756/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13487420078936789756/agent-home/.gemini`
- `registryPort`: `64062`
- `gatewayPort`: `64063`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T17:59:45.629457Z`  
executor end: `2026-07-12T17:59:46.241812Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 44

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 37ms | 80802 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `postgres.down` — **PASS**

executor start: `2026-07-12T18:00:06.079352Z`  
executor end: `2026-07-12T18:00:06.859631Z`  
spawn exit code: 0

**Input context**: [context/postgres.down.input.json](context/postgres.down.input.json)

### Assertions

| Name | Status |
|---|---|
| stopped | **PASS** |

### Metrics

- `durationMs`: 208

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-stop | 0 | 202ms | 81309 | [`node-logs/postgres.down.compose-stop.log`](node-logs/postgres.down.compose-stop.log) |  |

**Node-process stdout**: [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T17:59:46.831209Z`  
executor end: `2026-07-12T17:59:48.416768Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 1042

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-up | 0 | 249ms | 80821 | [`node-logs/postgres.up.compose-up.log`](node-logs/postgres.up.compose-up.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T17:59:48.417713Z`  
executor end: `2026-07-12T17:59:53.293868Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 80835
- `port`: 64062
- `durationMs`: 3939

### Published context

- `baseUrl`: `http://127.0.0.1:64062`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `selenium.ready` — **PASS**

executor start: `2026-07-12T17:59:53.295252Z`  
executor end: `2026-07-12T17:59:59.131140Z`  
spawn exit code: 0

**Input context**: [context/selenium.ready.input.json](context/selenium.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| chromedriver_resolved | **PASS** |
| chrome_browser_resolved | **PASS** |
| chromedriver_started | **PASS** |

### Metrics

- `durationMs`: 5270

**Node-process stdout**: [node-logs/selenium.ready.stdout.log](node-logs/selenium.ready.stdout.log)

---

