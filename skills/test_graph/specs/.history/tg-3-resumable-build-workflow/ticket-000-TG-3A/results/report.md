# Validation report — 20260610-183929

**Overall**: PASSED  
**Nodes**: 5 (passed=5, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `app.running` | **PASS** | 1195ms | [context/app.running.input.json](context/app.running.input.json) | [node-logs/app.running.stdout.log](node-logs/app.running.stdout.log) |
| `context.snapshots.present` | **PASS** | 51ms | [context/context.snapshots.present.input.json](context/context.snapshots.present.input.json) | [node-logs/context.snapshots.present.stdout.log](node-logs/context.snapshots.present.stdout.log) |
| `login.smoke` | **PASS** | 1222ms | [context/login.smoke.input.json](context/login.smoke.input.json) | [node-logs/login.smoke.stdout.log](node-logs/login.smoke.stdout.log) |
| `network.pingable` | **PASS** | 598ms | [context/network.pingable.input.json](context/network.pingable.input.json) | [node-logs/network.pingable.stdout.log](node-logs/network.pingable.stdout.log) |
| `user.seeded` | **PASS** | 53ms | [context/user.seeded.input.json](context/user.seeded.input.json) | [node-logs/user.seeded.stdout.log](node-logs/user.seeded.stdout.log) |

## `app.running` — **PASS**

executor start: `2026-06-10T18:39:29.372766Z`  
executor end: `2026-06-10T18:39:30.567814Z`  
spawn exit code: 0

**Input context**: [context/app.running.input.json](context/app.running.input.json)

### Assertions

| Name | Status |
|---|---|
| ready | **PASS** |

### Metrics

- `statusCode`: 200
- `durationMs`: 181

### Published context

- `baseUrl`: `http://localhost:8080`

**Node-process stdout**: [node-logs/app.running.stdout.log](node-logs/app.running.stdout.log)

---

## `context.snapshots.present` — **PASS**

executor start: `2026-06-10T18:39:32.444302Z`  
executor end: `2026-06-10T18:39:32.495599Z`  
spawn exit code: 0

**Input context**: [context/context.snapshots.present.input.json](context/context.snapshots.present.input.json)

### Assertions

| Name | Status |
|---|---|
| app.running.input_context_snapshot_exists | **PASS** |
| app.running.input_context_snapshot_has_items_array | **PASS** |
| user.seeded.input_context_snapshot_exists | **PASS** |
| user.seeded.input_context_snapshot_has_items_array | **PASS** |
| network.pingable.input_context_snapshot_exists | **PASS** |
| network.pingable.input_context_snapshot_has_items_array | **PASS** |
| login.smoke.input_context_snapshot_exists | **PASS** |
| login.smoke.input_context_snapshot_has_items_array | **PASS** |
| context.snapshots.present.input_context_snapshot_exists | **PASS** |
| context.snapshots.present.input_context_snapshot_has_items_array | **PASS** |
| self_snapshot_contains_upstream_context | **PASS** |
| app.running.envelope_has_input_context_file | **PASS** |
| app.running.envelope_input_context_file_exists | **PASS** |
| user.seeded.envelope_has_input_context_file | **PASS** |
| user.seeded.envelope_input_context_file_exists | **PASS** |
| network.pingable.envelope_has_input_context_file | **PASS** |
| network.pingable.envelope_input_context_file_exists | **PASS** |
| login.smoke.envelope_has_input_context_file | **PASS** |
| login.smoke.envelope_input_context_file_exists | **PASS** |

### Metrics

- `snapshotCount`: 5
- `durationMs`: 0

### Artifacts

- `input-context-dir` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-183929/context`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-183929/context)

### Published context

- `snapshotCount`: `5`

**Node-process stdout**: [node-logs/context.snapshots.present.stdout.log](node-logs/context.snapshots.present.stdout.log)

---

## `login.smoke` — **PASS**

executor start: `2026-06-10T18:39:31.221450Z`  
executor end: `2026-06-10T18:39:32.443095Z`  
spawn exit code: 0

**Input context**: [context/login.smoke.input.json](context/login.smoke.input.json)

### Assertions

| Name | Status |
|---|---|
| login_endpoint_reachable | **PASS** |
| redirected_to_dashboard | **PASS** |
| saw_all_upstreams | **PASS** |

### Metrics

- `statusCode`: 302
- `upstreamCount`: 3
- `durationMs`: 184

### Published context

- `baseUrl`: `http://localhost:8080`
- `attemptedAs`: `u-31c0233a`
- `upstreamIds`: `app.running,user.seeded,network.pingable`
- `upstreamShape`: `app.running{baseUrl};user.seeded{username,userId};network.pingable{sawBaseUrl}`

**Node-process stdout**: [node-logs/login.smoke.stdout.log](node-logs/login.smoke.stdout.log)

---

## `network.pingable` — **PASS**

executor start: `2026-06-10T18:39:30.622898Z`  
executor end: `2026-06-10T18:39:31.220551Z`  
spawn exit code: 0

**Input context**: [context/network.pingable.input.json](context/network.pingable.input.json)

### Assertions

| Name | Status |
|---|---|
| ran_after_app_running | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `sawBaseUrl`: `http://localhost:8080`

**Node-process stdout**: [node-logs/network.pingable.stdout.log](node-logs/network.pingable.stdout.log)

---

## `user.seeded` — **PASS**

executor start: `2026-06-10T18:39:30.569523Z`  
executor end: `2026-06-10T18:39:30.622036Z`  
spawn exit code: 0

**Input context**: [context/user.seeded.input.json](context/user.seeded.input.json)

### Assertions

| Name | Status |
|---|---|
| record_written | **PASS** |

### Metrics

- `created_users`: 1
- `durationMs`: 0

### Artifacts

- `seed-record` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-183929/fixtures/u-31c0233a.json`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-183929/fixtures/u-31c0233a.json)

### Published context

- `userId`: `u-31c0233a`
- `username`: `smoke-user`

**Node-process stdout**: [node-logs/user.seeded.stdout.log](node-logs/user.seeded.stdout.log)

---

