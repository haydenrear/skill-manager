# Validation report — 20260628-164659

**Overall**: PASSED
**Nodes**: 6 (passed=6, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `tg6.lifecycle.java.delete-cluster` | **PASS** | 652ms | [context/tg6.lifecycle.java.delete-cluster.input.json](context/tg6.lifecycle.java.delete-cluster.input.json) | [node-logs/tg6.lifecycle.java.delete-cluster.stdout.log](node-logs/tg6.lifecycle.java.delete-cluster.stdout.log) |
| `tg6.lifecycle.java.deploy-cluster` | **PASS** | 659ms | [context/tg6.lifecycle.java.deploy-cluster.input.json](context/tg6.lifecycle.java.deploy-cluster.input.json) | [node-logs/tg6.lifecycle.java.deploy-cluster.stdout.log](node-logs/tg6.lifecycle.java.deploy-cluster.stdout.log) |
| `tg6.lifecycle.java.reset-node` | **PASS** | 675ms | [context/tg6.lifecycle.java.reset-node.input.json](context/tg6.lifecycle.java.reset-node.input.json) | [node-logs/tg6.lifecycle.java.reset-node.stdout.log](node-logs/tg6.lifecycle.java.reset-node.stdout.log) |
| `tg6.lifecycle.python.delete-cluster` | **PASS** | 57ms | [context/tg6.lifecycle.python.delete-cluster.input.json](context/tg6.lifecycle.python.delete-cluster.input.json) | [node-logs/tg6.lifecycle.python.delete-cluster.stdout.log](node-logs/tg6.lifecycle.python.delete-cluster.stdout.log) |
| `tg6.lifecycle.python.deploy-cluster` | **PASS** | 61ms | [context/tg6.lifecycle.python.deploy-cluster.input.json](context/tg6.lifecycle.python.deploy-cluster.input.json) | [node-logs/tg6.lifecycle.python.deploy-cluster.stdout.log](node-logs/tg6.lifecycle.python.deploy-cluster.stdout.log) |
| `tg6.lifecycle.python.reset-node` | **PASS** | 56ms | [context/tg6.lifecycle.python.reset-node.input.json](context/tg6.lifecycle.python.reset-node.input.json) | [node-logs/tg6.lifecycle.python.reset-node.stdout.log](node-logs/tg6.lifecycle.python.reset-node.stdout.log) |

## `tg6.lifecycle.java.delete-cluster` — **PASS**

executor start: `2026-06-28T16:47:00.562271Z`
executor end: `2026-06-28T16:47:01.214404Z`
spawn exit code: 0

**Input context**: [context/tg6.lifecycle.java.delete-cluster.input.json](context/tg6.lifecycle.java.delete-cluster.input.json)

### Assertions

| Name | Status |
|---|---|
| all_targets_covered | **PASS** |
| destroy_skips_without_intent | **PASS** |
| destroy_runs_with_intent | **PASS** |
| aws_destroy_requires_explicit_selection | **PASS** |

### Metrics

- `durationMs`: 3

### Published context

- `Runtime`: `jbang`
- `DeleteCases`: `6`
- `DeleteLifecycleCommand`: `delete_cluster`
- `DeleteLifecycleTarget`: `local-preview`
- `DeleteLifecycleBackend`: `local`
- `DeleteLifecycleDispatchKey`: `local`
- `DeleteLifecycleEnvironmentAction`: ``
- `DeleteLifecycleTofuCommand`: ``
- `DeleteLifecycleShouldRun`: `false`
- `DeleteLifecycleExpectedState`: `kept-active`
- `DeleteLifecycleSkipReason`: `destroy-not-requested`
- `DeleteLifecycleJustProvisioned`: `false`
- `DeleteLifecycleAlreadyReset`: `false`
- `DeleteLifecycleDestroyRequested`: `false`
- `DeleteLifecycleRequiresExplicitSelection`: `false`

**Node-process stdout**: [node-logs/tg6.lifecycle.java.delete-cluster.stdout.log](node-logs/tg6.lifecycle.java.delete-cluster.stdout.log)

---

## `tg6.lifecycle.java.deploy-cluster` — **PASS**

executor start: `2026-06-28T16:46:59.225566Z`
executor end: `2026-06-28T16:46:59.884416Z`
spawn exit code: 0

**Input context**: [context/tg6.lifecycle.java.deploy-cluster.input.json](context/tg6.lifecycle.java.deploy-cluster.input.json)

### Assertions

| Name | Status |
|---|---|
| all_targets_covered | **PASS** |
| deploy_uses_provision_action | **PASS** |
| deploy_uses_apply_command | **PASS** |
| missing_environment_is_provisioned | **PASS** |
| existing_environment_is_reused | **PASS** |
| dispatch_metadata_matches_target_matrix | **PASS** |
| aws_requires_explicit_selection | **PASS** |

### Metrics

- `durationMs`: 2

### Published context

- `Runtime`: `jbang`
- `DeployCases`: `6`
- `DeployLifecycleCommand`: `deploy_cluster`
- `DeployLifecycleTarget`: `local-preview`
- `DeployLifecycleBackend`: `local`
- `DeployLifecycleDispatchKey`: `local`
- `DeployLifecycleEnvironmentAction`: `provision`
- `DeployLifecycleTofuCommand`: `tofu apply -auto-approve`
- `DeployLifecycleShouldRun`: `true`
- `DeployLifecycleExpectedState`: `provision-missing`
- `DeployLifecycleSkipReason`: ``
- `DeployLifecycleJustProvisioned`: `false`
- `DeployLifecycleAlreadyReset`: `false`
- `DeployLifecycleDestroyRequested`: `false`
- `DeployLifecycleRequiresExplicitSelection`: `false`

**Node-process stdout**: [node-logs/tg6.lifecycle.java.deploy-cluster.stdout.log](node-logs/tg6.lifecycle.java.deploy-cluster.stdout.log)

---

## `tg6.lifecycle.java.reset-node` — **PASS**

executor start: `2026-06-28T16:46:59.885787Z`
executor end: `2026-06-28T16:47:00.560875Z`
spawn exit code: 0

**Input context**: [context/tg6.lifecycle.java.reset-node.input.json](context/tg6.lifecycle.java.reset-node.input.json)

### Assertions

| Name | Status |
|---|---|
| all_targets_covered | **PASS** |
| normal_reset_runs | **PASS** |
| just_provisioned_reset_skips | **PASS** |
| already_reset_skips | **PASS** |

### Metrics

- `durationMs`: 3

### Published context

- `Runtime`: `jbang`
- `ResetCases`: `9`
- `ResetLifecycleCommand`: `reset_node`
- `ResetLifecycleTarget`: `local-preview`
- `ResetLifecycleBackend`: `local`
- `ResetLifecycleDispatchKey`: `local`
- `ResetLifecycleEnvironmentAction`: ``
- `ResetLifecycleTofuCommand`: ``
- `ResetLifecycleShouldRun`: `false`
- `ResetLifecycleExpectedState`: `kept-active`
- `ResetLifecycleSkipReason`: `just-provisioned`
- `ResetLifecycleJustProvisioned`: `true`
- `ResetLifecycleAlreadyReset`: `false`
- `ResetLifecycleDestroyRequested`: `false`
- `ResetLifecycleRequiresExplicitSelection`: `false`

**Node-process stdout**: [node-logs/tg6.lifecycle.java.reset-node.stdout.log](node-logs/tg6.lifecycle.java.reset-node.stdout.log)

---

## `tg6.lifecycle.python.delete-cluster` — **PASS**

executor start: `2026-06-28T16:46:59.167578Z`
executor end: `2026-06-28T16:46:59.224477Z`
spawn exit code: 0

**Input context**: [context/tg6.lifecycle.python.delete-cluster.input.json](context/tg6.lifecycle.python.delete-cluster.input.json)

### Assertions

| Name | Status |
|---|---|
| all_targets_covered | **PASS** |
| destroy_skips_without_intent | **PASS** |
| skip_reason_records_missing_destroy_intent | **PASS** |
| skip_destroy_keeps_environment_active | **PASS** |
| destroy_runs_with_intent | **PASS** |
| destroy_uses_destroy_action | **PASS** |
| destroy_uses_destroy_command | **PASS** |
| destroy_expected_state_recorded | **PASS** |
| aws_destroy_requires_explicit_selection | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `Runtime`: `uv`
- `DeleteCases`: `6`
- `DeleteLifecycleCommand`: `delete_cluster`
- `DeleteLifecycleTarget`: `local-preview`
- `DeleteLifecycleBackend`: `local`
- `DeleteLifecycleDispatchKey`: `local`
- `DeleteLifecycleEnvironmentAction`: ``
- `DeleteLifecycleTofuCommand`: ``
- `DeleteLifecycleShouldRun`: `false`
- `DeleteLifecycleExpectedState`: `kept-active`
- `DeleteLifecycleSkipReason`: `destroy-not-requested`
- `DeleteLifecycleJustProvisioned`: `false`
- `DeleteLifecycleAlreadyReset`: `false`
- `DeleteLifecycleDestroyRequested`: `false`
- `DeleteLifecycleRequiresExplicitSelection`: `false`

**Node-process stdout**: [node-logs/tg6.lifecycle.python.delete-cluster.stdout.log](node-logs/tg6.lifecycle.python.delete-cluster.stdout.log)

---

## `tg6.lifecycle.python.deploy-cluster` — **PASS**

executor start: `2026-06-28T16:46:59.048399Z`
executor end: `2026-06-28T16:46:59.109251Z`
spawn exit code: 0

**Input context**: [context/tg6.lifecycle.python.deploy-cluster.input.json](context/tg6.lifecycle.python.deploy-cluster.input.json)

### Assertions

| Name | Status |
|---|---|
| all_targets_covered | **PASS** |
| deploy_uses_provision_action | **PASS** |
| deploy_uses_apply_command | **PASS** |
| missing_environment_is_provisioned | **PASS** |
| existing_environment_is_reused | **PASS** |
| dispatch_metadata_matches_target_matrix | **PASS** |
| aws_requires_explicit_selection | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `Runtime`: `uv`
- `DeployCases`: `6`
- `DeployLifecycleCommand`: `deploy_cluster`
- `DeployLifecycleTarget`: `local-preview`
- `DeployLifecycleBackend`: `local`
- `DeployLifecycleDispatchKey`: `local`
- `DeployLifecycleEnvironmentAction`: `provision`
- `DeployLifecycleTofuCommand`: `tofu apply -auto-approve`
- `DeployLifecycleShouldRun`: `true`
- `DeployLifecycleExpectedState`: `provision-missing`
- `DeployLifecycleSkipReason`: ``
- `DeployLifecycleJustProvisioned`: `false`
- `DeployLifecycleAlreadyReset`: `false`
- `DeployLifecycleDestroyRequested`: `false`
- `DeployLifecycleRequiresExplicitSelection`: `false`

**Node-process stdout**: [node-logs/tg6.lifecycle.python.deploy-cluster.stdout.log](node-logs/tg6.lifecycle.python.deploy-cluster.stdout.log)

---

## `tg6.lifecycle.python.reset-node` — **PASS**

executor start: `2026-06-28T16:46:59.110977Z`
executor end: `2026-06-28T16:46:59.166483Z`
spawn exit code: 0

**Input context**: [context/tg6.lifecycle.python.reset-node.input.json](context/tg6.lifecycle.python.reset-node.input.json)

### Assertions

| Name | Status |
|---|---|
| all_targets_covered | **PASS** |
| normal_reset_runs | **PASS** |
| normal_reset_uses_reset_action | **PASS** |
| normal_reset_uses_apply_command | **PASS** |
| just_provisioned_reset_skips | **PASS** |
| just_provisioned_reason_recorded | **PASS** |
| already_reset_skips | **PASS** |
| already_reset_reason_recorded | **PASS** |
| skip_keeps_environment_active | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `Runtime`: `uv`
- `ResetCases`: `9`
- `ResetLifecycleCommand`: `reset_node`
- `ResetLifecycleTarget`: `local-preview`
- `ResetLifecycleBackend`: `local`
- `ResetLifecycleDispatchKey`: `local`
- `ResetLifecycleEnvironmentAction`: ``
- `ResetLifecycleTofuCommand`: ``
- `ResetLifecycleShouldRun`: `false`
- `ResetLifecycleExpectedState`: `kept-active`
- `ResetLifecycleSkipReason`: `just-provisioned`
- `ResetLifecycleJustProvisioned`: `true`
- `ResetLifecycleAlreadyReset`: `false`
- `ResetLifecycleDestroyRequested`: `false`
- `ResetLifecycleRequiresExplicitSelection`: `false`

**Node-process stdout**: [node-logs/tg6.lifecycle.python.reset-node.stdout.log](node-logs/tg6.lifecycle.python.reset-node.stdout.log)

---
