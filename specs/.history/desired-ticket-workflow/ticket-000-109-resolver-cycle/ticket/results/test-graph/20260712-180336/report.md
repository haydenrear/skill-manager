# Validation report — 20260712-180336

**Overall**: PASSED  
**Nodes**: 13 (passed=13, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `ci.logged.in` | **PASS** | 2130ms | [context/ci.logged.in.input.json](context/ci.logged.in.input.json) | [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log) |
| `env.prepared` | **PASS** | 607ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 633ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `jwt.valid` | **PASS** | 1132ms | [context/jwt.valid.input.json](context/jwt.valid.input.json) | [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log) |
| `onboard.agent.configs.written` | **PASS** | 598ms | [context/onboard.agent.configs.written.input.json](context/onboard.agent.configs.written.input.json) | [node-logs/onboard.agent.configs.written.stdout.log](node-logs/onboard.agent.configs.written.stdout.log) |
| `onboard.completed` | **PASS** | 12308ms | [context/onboard.completed.input.json](context/onboard.completed.input.json) | [node-logs/onboard.completed.stdout.log](node-logs/onboard.completed.stdout.log) |
| `onboard.gateway.healthy` | **PASS** | 1119ms | [context/onboard.gateway.healthy.input.json](context/onboard.gateway.healthy.input.json) | [node-logs/onboard.gateway.healthy.stdout.log](node-logs/onboard.gateway.healthy.stdout.log) |
| `onboard.seeded.by.server` | **PASS** | 1185ms | [context/onboard.seeded.by.server.input.json](context/onboard.seeded.by.server.input.json) | [node-logs/onboard.seeded.by.server.stdout.log](node-logs/onboard.seeded.by.server.stdout.log) |
| `onboard.skills.installed` | **PASS** | 620ms | [context/onboard.skills.installed.input.json](context/onboard.skills.installed.input.json) | [node-logs/onboard.skills.installed.stdout.log](node-logs/onboard.skills.installed.stdout.log) |
| `postgres.down` | **PASS** | 797ms | [context/postgres.down.input.json](context/postgres.down.input.json) | [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log) |
| `postgres.up` | **PASS** | 1114ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `registry.up` | **PASS** | 4637ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `servers.down` | **PASS** | 3741ms | [context/servers.down.input.json](context/servers.down.input.json) | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |

## `ci.logged.in` — **PASS**

executor start: `2026-07-12T18:03:43.128457Z`  
executor end: `2026-07-12T18:03:45.258349Z`  
spawn exit code: 0

**Input context**: [context/ci.logged.in.input.json](context/ci.logged.in.input.json)

### Assertions

| Name | Status |
|---|---|
| token_cached | **PASS** |

### Metrics

- `durationMs`: 1512

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| login | 0 | 1503ms | 84081 | [`node-logs/ci.logged.in.login.log`](node-logs/ci.logged.in.login.log) |  |

### Published context

- `clientId`: `skill-manager-ci`

**Node-process stdout**: [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:03:36.133584Z`  
executor end: `2026-07-12T18:03:36.740773Z`  
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

- `registryPort`: 64577
- `gatewayPort`: 64578
- `durationMs`: 31

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-1383845597903256912`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-1383845597903256912/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-1383845597903256912/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-1383845597903256912/agent-home/.gemini`
- `registryPort`: `64577`
- `gatewayPort`: `64578`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:03:36.741744Z`  
executor end: `2026-07-12T18:03:37.374707Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 36

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 29ms | 83940 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `jwt.valid` — **PASS**

executor start: `2026-07-12T18:03:45.259154Z`  
executor end: `2026-07-12T18:03:46.391098Z`  
spawn exit code: 0

**Input context**: [context/jwt.valid.input.json](context/jwt.valid.input.json)

### Assertions

| Name | Status |
|---|---|
| authed_me_is_200 | **PASS** |
| identity_matches_expected_user | **PASS** |
| unauthed_publish_is_401 | **PASS** |

### Metrics

- `me_status`: 200
- `unauth_publish_status`: 401
- `durationMs`: 190

**Node-process stdout**: [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log)

---

## `onboard.agent.configs.written` — **PASS**

executor start: `2026-07-12T18:04:01.625753Z`  
executor end: `2026-07-12T18:04:02.223908Z`  
spawn exit code: 0

**Input context**: [context/onboard.agent.configs.written.input.json](context/onboard.agent.configs.written.input.json)

### Assertions

| Name | Status |
|---|---|
| claude_config_exists | **PASS** |
| claude_gateway_entry_present | **PASS** |
| claude_gateway_url_matches | **PASS** |
| codex_config_exists | **PASS** |
| codex_gateway_entry_present | **PASS** |
| codex_gateway_url_matches | **PASS** |
| real_home_untouched | **PASS** |

### Metrics

- `durationMs`: 30

**Node-process stdout**: [node-logs/onboard.agent.configs.written.stdout.log](node-logs/onboard.agent.configs.written.stdout.log)

---

## `onboard.completed` — **PASS**

executor start: `2026-07-12T18:03:47.577359Z`  
executor end: `2026-07-12T18:03:59.885128Z`  
spawn exit code: 0

**Input context**: [context/onboard.completed.input.json](context/onboard.completed.input.json)

### Assertions

| Name | Status |
|---|---|
| onboard_exit_zero | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 11725

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| onboard | 0 | 11716ms | 84112 | [`node-logs/onboard.completed.onboard.log`](node-logs/onboard.completed.onboard.log) |  |

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-1383845597903256912`
- `agentHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-1383845597903256912/agent-home`

**Node-process stdout**: [node-logs/onboard.completed.stdout.log](node-logs/onboard.completed.stdout.log)

---

## `onboard.gateway.healthy` — **PASS**

executor start: `2026-07-12T18:04:00.506532Z`  
executor end: `2026-07-12T18:04:01.625027Z`  
spawn exit code: 0

**Input context**: [context/onboard.gateway.healthy.input.json](context/onboard.gateway.healthy.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_health_reachable | **PASS** |

### Metrics

- `durationMs`: 172

**Node-process stdout**: [node-logs/onboard.gateway.healthy.stdout.log](node-logs/onboard.gateway.healthy.stdout.log)

---

## `onboard.seeded.by.server` — **PASS**

executor start: `2026-07-12T18:03:46.391908Z`  
executor end: `2026-07-12T18:03:47.576580Z`  
spawn exit code: 0

**Input context**: [context/onboard.seeded.by.server.input.json](context/onboard.seeded.by.server.input.json)

### Assertions

| Name | Status |
|---|---|
| skill_manager_seeded | **PASS** |
| skill_publisher_seeded | **PASS** |
| skill_dev_seeded | **PASS** |

### Metrics

- `durationMs`: 200

**Node-process stdout**: [node-logs/onboard.seeded.by.server.stdout.log](node-logs/onboard.seeded.by.server.stdout.log)

---

## `onboard.skills.installed` — **PASS**

executor start: `2026-07-12T18:03:59.885842Z`  
executor end: `2026-07-12T18:04:00.505795Z`  
spawn exit code: 0

**Input context**: [context/onboard.skills.installed.input.json](context/onboard.skills.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| skill_manager_dir_present | **PASS** |
| skill_manager_md_present | **PASS** |
| skill_publisher_dir_present | **PASS** |
| skill_publisher_md_present | **PASS** |
| skill_dev_dir_present | **PASS** |
| skill_dev_md_present | **PASS** |
| skill_manager_git_metadata_present | **PASS** |
| skill_publisher_git_metadata_present | **PASS** |
| skill_dev_git_metadata_present | **PASS** |
| skill_manager_origin_points_to_github | **PASS** |
| skill_publisher_origin_points_to_github | **PASS** |
| skill_dev_origin_points_to_github | **PASS** |

### Metrics

- `durationMs`: 48

**Node-process stdout**: [node-logs/onboard.skills.installed.stdout.log](node-logs/onboard.skills.installed.stdout.log)

---

## `postgres.down` — **PASS**

executor start: `2026-07-12T18:04:05.966823Z`  
executor end: `2026-07-12T18:04:06.763823Z`  
spawn exit code: 0

**Input context**: [context/postgres.down.input.json](context/postgres.down.input.json)

### Assertions

| Name | Status |
|---|---|
| stopped | **PASS** |

### Metrics

- `durationMs`: 198

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-stop | 0 | 192ms | 84268 | [`node-logs/postgres.down.compose-stop.log`](node-logs/postgres.down.compose-stop.log) |  |

**Node-process stdout**: [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T18:03:37.375455Z`  
executor end: `2026-07-12T18:03:38.489244Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 525

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-up | 0 | 220ms | 83950 | [`node-logs/postgres.up.compose-up.log`](node-logs/postgres.up.compose-up.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T18:03:38.490150Z`  
executor end: `2026-07-12T18:03:43.127486Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 83978
- `port`: 64577
- `durationMs`: 3641

### Published context

- `baseUrl`: `http://127.0.0.1:64577`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-07-12T18:04:02.224656Z`  
executor end: `2026-07-12T18:04:05.965373Z`  
spawn exit code: 0

**Input context**: [context/servers.down.input.json](context/servers.down.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 3175

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 1336ms | 84250 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

