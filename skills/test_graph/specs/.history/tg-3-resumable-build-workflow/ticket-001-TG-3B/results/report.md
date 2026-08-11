# Validation report — 20260610-191451

**Overall**: PASSED  
**Nodes**: 6 (passed=6, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `app.running` | **PASS** | 1177ms | [context/app.running.input.json](context/app.running.input.json) | [node-logs/app.running.stdout.log](node-logs/app.running.stdout.log) |
| `context.snapshots.present` | **PASS** | 52ms | [context/context.snapshots.present.input.json](context/context.snapshots.present.input.json) | [node-logs/context.snapshots.present.stdout.log](node-logs/context.snapshots.present.stdout.log) |
| `login.smoke` | **PASS** | 1175ms | [context/login.smoke.input.json](context/login.smoke.input.json) | [node-logs/login.smoke.stdout.log](node-logs/login.smoke.stdout.log) |
| `network.pingable` | **PASS** | 573ms | [context/network.pingable.input.json](context/network.pingable.input.json) | [node-logs/network.pingable.stdout.log](node-logs/network.pingable.stdout.log) |
| `rerun.disabled.probe` | **PASS** | 49ms | [context/rerun.disabled.probe.input.json](context/rerun.disabled.probe.input.json) | [node-logs/rerun.disabled.probe.stdout.log](node-logs/rerun.disabled.probe.stdout.log) |
| `user.seeded` | **PASS** | 52ms | [context/user.seeded.input.json](context/user.seeded.input.json) | [node-logs/user.seeded.stdout.log](node-logs/user.seeded.stdout.log) |

## `app.running` — **PASS**

executor start: `2026-06-10T19:14:51.352934Z`  
executor end: `2026-06-10T19:14:52.529546Z`  
spawn exit code: 0

**Input context**: [context/app.running.input.json](context/app.running.input.json)

### Assertions

| Name | Status |
|---|---|
| ready | **PASS** |

### Metrics

- `statusCode`: 200
- `durationMs`: 175

### Published context

- `baseUrl`: `http://localhost:8080`

**Node-process stdout**: [node-logs/app.running.stdout.log](node-logs/app.running.stdout.log)

---

## `context.snapshots.present` — **PASS**

executor start: `2026-06-10T19:14:54.384893Z`  
executor end: `2026-06-10T19:14:54.436766Z`  
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
| rerun.disabled.probe.input_context_snapshot_exists | **PASS** |
| rerun.disabled.probe.input_context_snapshot_has_items_array | **PASS** |
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
| rerun.disabled.probe.envelope_has_input_context_file | **PASS** |
| rerun.disabled.probe.envelope_input_context_file_exists | **PASS** |

### Metrics

- `snapshotCount`: 6
- `durationMs`: 0

### Artifacts

- `input-context-dir` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-191451/context`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-191451/context)

### Published context

- `snapshotCount`: `6`

**Node-process stdout**: [node-logs/context.snapshots.present.stdout.log](node-logs/context.snapshots.present.stdout.log)

---

## `login.smoke` — **PASS**

executor start: `2026-06-10T19:14:53.158174Z`  
executor end: `2026-06-10T19:14:54.333751Z`  
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
- `durationMs`: 177

### Published context

- `baseUrl`: `http://localhost:8080`
- `attemptedAs`: `u-1caf5a97`
- `upstreamIds`: `app.running,user.seeded,network.pingable`
- `upstreamShape`: `app.running{baseUrl};user.seeded{userId,username};network.pingable{sawBaseUrl}`

**Node-process stdout**: [node-logs/login.smoke.stdout.log](node-logs/login.smoke.stdout.log)

---

## `network.pingable` — **PASS**

executor start: `2026-06-10T19:14:52.584521Z`  
executor end: `2026-06-10T19:14:53.157310Z`  
spawn exit code: 0

**Input context**: [context/network.pingable.input.json](context/network.pingable.input.json)

### Assertions

| Name | Status |
|---|---|
| ran_after_app_running | **PASS** |

### Metrics

- `durationMs`: 1

### Published context

- `sawBaseUrl`: `http://localhost:8080`

**Node-process stdout**: [node-logs/network.pingable.stdout.log](node-logs/network.pingable.stdout.log)

---

## `rerun.disabled.probe` — **PASS**

executor start: `2026-06-10T19:14:54.334935Z`  
executor end: `2026-06-10T19:14:54.383971Z`  
spawn exit code: 0

**Input context**: [context/rerun.disabled.probe.input.json](context/rerun.disabled.probe.input.json)

### Metrics

- `durationMs`: 0

### Published context

- `rerun`: `false`

**Node-process stdout**: [node-logs/rerun.disabled.probe.stdout.log](node-logs/rerun.disabled.probe.stdout.log)

---

## `user.seeded` — **PASS**

executor start: `2026-06-10T19:14:52.531616Z`  
executor end: `2026-06-10T19:14:52.583646Z`  
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

- `seed-record` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-191451/fixtures/u-1caf5a97.json`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260610-191451/fixtures/u-1caf5a97.json)

### Published context

- `userId`: `u-1caf5a97`
- `username`: `smoke-user`

**Node-process stdout**: [node-logs/user.seeded.stdout.log](node-logs/user.seeded.stdout.log)

---

