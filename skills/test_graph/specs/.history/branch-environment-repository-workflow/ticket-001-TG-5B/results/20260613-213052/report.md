# Validation report — 20260613-213052

**Overall**: PASSED  
**Nodes**: 4 (passed=4, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `tg5.environment.markers.present` | **PASS** | 46ms | [context/tg5.environment.markers.present.input.json](context/tg5.environment.markers.present.input.json) | [node-logs/tg5.environment.markers.present.stdout.log](node-logs/tg5.environment.markers.present.stdout.log) |
| `tg5.environment.provision` | **PASS** | 44ms | [context/tg5.environment.provision.input.json](context/tg5.environment.provision.input.json) | [node-logs/tg5.environment.provision.stdout.log](node-logs/tg5.environment.provision.stdout.log) |
| `tg5.environment.reset` | **PASS** | 46ms | [context/tg5.environment.reset.input.json](context/tg5.environment.reset.input.json) | [node-logs/tg5.environment.reset.stdout.log](node-logs/tg5.environment.reset.stdout.log) |
| `tg5.future.workflow.plan` | **PASS** | 45ms | [context/tg5.future.workflow.plan.input.json](context/tg5.future.workflow.plan.input.json) | [node-logs/tg5.future.workflow.plan.stdout.log](node-logs/tg5.future.workflow.plan.stdout.log) |

## `tg5.environment.markers.present` — **PASS**

executor start: `2026-06-13T21:30:52.946330Z`  
executor end: `2026-06-13T21:30:52.992955Z`  
spawn exit code: 0

**Input context**: [context/tg5.environment.markers.present.input.json](context/tg5.environment.markers.present.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_available_from_context | **PASS** |
| provisioned_marker_exists_after_provision | **PASS** |
| reset_marker_exists_after_reset | **PASS** |
| reset_kept_provisioned_marker | **PASS** |
| marker_environment_id_matches_context | **PASS** |
| provisioned_marker_is_from_this_run | **PASS** |

### Metrics

- `durationMs`: 0

### Artifacts

- `provisioned-marker` — [`/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-provisioning-state/provisioned/branchEnvironmentWorkflow__local__local-preview__local.json`](/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-provisioning-state/provisioned/branchEnvironmentWorkflow__local__local-preview__local.json)
- `reset-marker` — [`/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-provisioning-state/reset/branchEnvironmentWorkflow__local__local-preview__local__20260613-213052__tg5.environment.reset.json`](/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-provisioning-state/reset/branchEnvironmentWorkflow__local__local-preview__local__20260613-213052__tg5.environment.reset.json)

**Node-process stdout**: [node-logs/tg5.environment.markers.present.stdout.log](node-logs/tg5.environment.markers.present.stdout.log)

---

## `tg5.environment.provision` — **PASS**

executor start: `2026-06-13T21:30:52.853025Z`  
executor end: `2026-06-13T21:30:52.897858Z`  
spawn exit code: 0

**Input context**: [context/tg5.environment.provision.input.json](context/tg5.environment.provision.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_was_assigned | **PASS** |
| state_dir_was_assigned | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `environmentId`: `branchEnvironmentWorkflow__local__local-preview__local`
- `stateDir`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-provisioning-state`

### Provisioning state

- `environmentId`: `branchEnvironmentWorkflow__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `provision`
- `provisionedMarker`: [`../../testgraph-provisioning-state/provisioned/branchEnvironmentWorkflow__local__local-preview__local.json`](../../testgraph-provisioning-state/provisioned/branchEnvironmentWorkflow__local__local-preview__local.json)

**Node-process stdout**: [node-logs/tg5.environment.provision.stdout.log](node-logs/tg5.environment.provision.stdout.log)

---

## `tg5.environment.reset` — **PASS**

executor start: `2026-06-13T21:30:52.899496Z`  
executor end: `2026-06-13T21:30:52.945265Z`  
spawn exit code: 0

**Input context**: [context/tg5.environment.reset.input.json](context/tg5.environment.reset.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_was_assigned | **PASS** |
| reset_targets_same_environment | **PASS** |

### Metrics

- `durationMs`: 0

### Provisioning state

- `environmentId`: `branchEnvironmentWorkflow__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `reset`
- `resetMarker`: [`../../testgraph-provisioning-state/reset/branchEnvironmentWorkflow__local__local-preview__local__20260613-213052__tg5.environment.reset.json`](../../testgraph-provisioning-state/reset/branchEnvironmentWorkflow__local__local-preview__local__20260613-213052__tg5.environment.reset.json)

**Node-process stdout**: [node-logs/tg5.environment.reset.stdout.log](node-logs/tg5.environment.reset.stdout.log)

---

## `tg5.future.workflow.plan` — **PASS**

executor start: `2026-06-13T21:30:52.993944Z`  
executor end: `2026-06-13T21:30:53.038167Z`  
spawn exit code: 0

**Input context**: [context/tg5.future.workflow.plan.input.json](context/tg5.future.workflow.plan.input.json)

### Assertions

| Name | Status |
|---|---|
| TG-5C_is_planned | **PASS** |
| TG-5D_is_planned | **PASS** |
| TG-5E_is_planned | **PASS** |
| TG-5F_is_planned | **PASS** |
| TG-5G_is_planned | **PASS** |

### Metrics

- `durationMs`: 0

### Inline logs

```
Future TG-5 nodes are represented by the ticket plan and will be strengthened as each slice lands.
```

**Node-process stdout**: [node-logs/tg5.future.workflow.plan.stdout.log](node-logs/tg5.future.workflow.plan.stdout.log)

---

