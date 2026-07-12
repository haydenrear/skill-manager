# Validation report — 20260712-180102

**Overall**: PASSED  
**Nodes**: 12 (passed=12, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `account.created` | **PASS** | 2246ms | [context/account.created.input.json](context/account.created.input.json) | [node-logs/account.created.stdout.log](node-logs/account.created.stdout.log) |
| `env.prepared` | **PASS** | 588ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `final.login` | **PASS** | 4703ms | [context/final.login.input.json](context/final.login.input.json) | [node-logs/final.login.stdout.log](node-logs/final.login.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 610ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `initial.login` | **PASS** | 4732ms | [context/initial.login.input.json](context/initial.login.input.json) | [node-logs/initial.login.stdout.log](node-logs/initial.login.stdout.log) |
| `password.changed` | **PASS** | 1365ms | [context/password.changed.input.json](context/password.changed.input.json) | [node-logs/password.changed.stdout.log](node-logs/password.changed.stdout.log) |
| `postgres.down` | **PASS** | 868ms | [context/postgres.down.input.json](context/postgres.down.input.json) | [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log) |
| `postgres.up` | **PASS** | 1099ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `refresh.honored` | **PASS** | 2143ms | [context/refresh.honored.input.json](context/refresh.honored.input.json) | [node-logs/refresh.honored.stdout.log](node-logs/refresh.honored.stdout.log) |
| `registry.up` | **PASS** | 6445ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `reset.requested` | **PASS** | 3868ms | [context/reset.requested.input.json](context/reset.requested.input.json) | [node-logs/reset.requested.stdout.log](node-logs/reset.requested.stdout.log) |
| `selenium.ready` | **PASS** | 2602ms | [context/selenium.ready.input.json](context/selenium.ready.input.json) | [node-logs/selenium.ready.stdout.log](node-logs/selenium.ready.stdout.log) |

## `account.created` — **PASS**

executor start: `2026-07-12T18:01:13.492484Z`  
executor end: `2026-07-12T18:01:15.738901Z`  
spawn exit code: 0

**Input context**: [context/account.created.input.json](context/account.created.input.json)

### Assertions

| Name | Status |
|---|---|
| row_exists | **PASS** |
| email_persisted | **PASS** |
| password_hash_is_bcrypt | **PASS** |

### Metrics

- `durationMs`: 1695

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| create-account | 0 | 1501ms | 82517 | [`node-logs/account.created.create-account.log`](node-logs/account.created.create-account.log) |  |

### Published context

- `username`: `graph-user`
- `password`: `graph-user-password-2026`
- `email`: `graph-user@skill-manager.test`

**Node-process stdout**: [node-logs/account.created.stdout.log](node-logs/account.created.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:01:02.754982Z`  
executor end: `2026-07-12T18:01:03.342654Z`  
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

- `registryPort`: 64290
- `gatewayPort`: 64291
- `durationMs`: 29

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9838563766426131040`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9838563766426131040/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9838563766426131040/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9838563766426131040/agent-home/.gemini`
- `registryPort`: `64290`
- `gatewayPort`: `64291`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `final.login` — **PASS**

executor start: `2026-07-12T18:01:25.708110Z`  
executor end: `2026-07-12T18:01:30.411811Z`  
spawn exit code: 0

**Input context**: [context/final.login.input.json](context/final.login.input.json)

### Assertions

| Name | Status |
|---|---|
| new_password_accepted | **PASS** |

### Metrics

- `durationMs`: 4109

**Node-process stdout**: [node-logs/final.login.stdout.log](node-logs/final.login.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:01:02.144207Z`  
executor end: `2026-07-12T18:01:02.754169Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 37

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 30ms | 82216 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `initial.login` — **PASS**

executor start: `2026-07-12T18:01:15.740426Z`  
executor end: `2026-07-12T18:01:20.472271Z`  
spawn exit code: 0

**Input context**: [context/initial.login.input.json](context/initial.login.input.json)

### Assertions

| Name | Status |
|---|---|
| original_password_accepted | **PASS** |

### Metrics

- `durationMs`: 4139

**Node-process stdout**: [node-logs/initial.login.stdout.log](node-logs/initial.login.stdout.log)

---

## `password.changed` — **PASS**

executor start: `2026-07-12T18:01:24.342854Z`  
executor end: `2026-07-12T18:01:25.707094Z`  
spawn exit code: 0

**Input context**: [context/password.changed.input.json](context/password.changed.input.json)

### Assertions

| Name | Status |
|---|---|
| confirm_returned_200 | **PASS** |
| token_marked_used | **PASS** |
| password_hash_changed | **PASS** |

### Metrics

- `durationMs`: 429

### Published context

- `newPassword`: `graph-user-NEW-password-2026`

**Node-process stdout**: [node-logs/password.changed.stdout.log](node-logs/password.changed.stdout.log)

---

## `postgres.down` — **PASS**

executor start: `2026-07-12T18:01:32.556502Z`  
executor end: `2026-07-12T18:01:33.424292Z`  
spawn exit code: 0

**Input context**: [context/postgres.down.input.json](context/postgres.down.input.json)

### Assertions

| Name | Status |
|---|---|
| stopped | **PASS** |

### Metrics

- `durationMs`: 258

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-stop | 0 | 250ms | 82964 | [`node-logs/postgres.down.compose-stop.log`](node-logs/postgres.down.compose-stop.log) |  |

**Node-process stdout**: [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T18:01:03.343476Z`  
executor end: `2026-07-12T18:01:04.442995Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 546

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-up | 0 | 243ms | 82232 | [`node-logs/postgres.up.compose-up.log`](node-logs/postgres.up.compose-up.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `refresh.honored` — **PASS**

executor start: `2026-07-12T18:01:30.412667Z`  
executor end: `2026-07-12T18:01:32.555605Z`  
spawn exit code: 0

**Input context**: [context/refresh.honored.input.json](context/refresh.honored.input.json)

### Assertions

| Name | Status |
|---|---|
| refresh_token_was_persisted | **PASS** |
| cli_exit_zero | **PASS** |
| me_endpoint_succeeded | **PASS** |
| access_token_rotated_on_disk | **PASS** |

### Metrics

- `durationMs`: 1644

**Node-process stdout**: [node-logs/refresh.honored.stdout.log](node-logs/refresh.honored.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T18:01:04.443791Z`  
executor end: `2026-07-12T18:01:10.888534Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 82248
- `port`: 64290
- `durationMs`: 5475

### Published context

- `baseUrl`: `http://127.0.0.1:64290`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `reset.requested` — **PASS**

executor start: `2026-07-12T18:01:20.473384Z`  
executor end: `2026-07-12T18:01:24.341974Z`  
spawn exit code: 0

**Input context**: [context/reset.requested.input.json](context/reset.requested.input.json)

### Assertions

| Name | Status |
|---|---|
| cli_exit_zero | **PASS** |
| token_row_persisted | **PASS** |
| token_not_expired | **PASS** |

### Metrics

- `durationMs`: 3308

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| reset-password | 0 | 3098ms | 82694 | [`node-logs/reset.requested.reset-password.log`](node-logs/reset.requested.reset-password.log) |  |

### Published context

- `resetToken`: `3vyHcnr2E_pvvBEyF4I8b-JeQQALAATPzojlVUpptQ0`

**Node-process stdout**: [node-logs/reset.requested.stdout.log](node-logs/reset.requested.stdout.log)

---

## `selenium.ready` — **PASS**

executor start: `2026-07-12T18:01:10.889543Z`  
executor end: `2026-07-12T18:01:13.491703Z`  
spawn exit code: 0

**Input context**: [context/selenium.ready.input.json](context/selenium.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| chromedriver_resolved | **PASS** |
| chrome_browser_resolved | **PASS** |
| chromedriver_started | **PASS** |

### Metrics

- `durationMs`: 2009

**Node-process stdout**: [node-logs/selenium.ready.stdout.log](node-logs/selenium.ready.stdout.log)

---

