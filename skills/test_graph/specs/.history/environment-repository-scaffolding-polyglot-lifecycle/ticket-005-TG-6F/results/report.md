# Validation report — 20260628-193335

**Overall**: PASSED  
**Nodes**: 10 (passed=10, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `tg6.aws.lifecycle.context.jbang` | **PASS** | 646ms | [context/tg6.aws.lifecycle.context.jbang.input.json](context/tg6.aws.lifecycle.context.jbang.input.json) | [node-logs/tg6.aws.lifecycle.context.jbang.stdout.log](node-logs/tg6.aws.lifecycle.context.jbang.stdout.log) |
| `tg6.aws.lifecycle.deploy-existing` | **PASS** | 55ms | [context/tg6.aws.lifecycle.deploy-existing.input.json](context/tg6.aws.lifecycle.deploy-existing.input.json) | [node-logs/tg6.aws.lifecycle.deploy-existing.stdout.log](node-logs/tg6.aws.lifecycle.deploy-existing.stdout.log) |
| `tg6.aws.lifecycle.deploy-markers` | **PASS** | 56ms | [context/tg6.aws.lifecycle.deploy-markers.input.json](context/tg6.aws.lifecycle.deploy-markers.input.json) | [node-logs/tg6.aws.lifecycle.deploy-markers.stdout.log](node-logs/tg6.aws.lifecycle.deploy-markers.stdout.log) |
| `tg6.aws.lifecycle.destroy-markers` | **PASS** | 58ms | [context/tg6.aws.lifecycle.destroy-markers.input.json](context/tg6.aws.lifecycle.destroy-markers.input.json) | [node-logs/tg6.aws.lifecycle.destroy-markers.stdout.log](node-logs/tg6.aws.lifecycle.destroy-markers.stdout.log) |
| `tg6.aws.lifecycle.destroy` | **PASS** | 62ms | [context/tg6.aws.lifecycle.destroy.input.json](context/tg6.aws.lifecycle.destroy.input.json) | [node-logs/tg6.aws.lifecycle.destroy.stdout.log](node-logs/tg6.aws.lifecycle.destroy.stdout.log) |
| `tg6.aws.lifecycle.guard` | **PASS** | 57ms | [context/tg6.aws.lifecycle.guard.input.json](context/tg6.aws.lifecycle.guard.input.json) | [node-logs/tg6.aws.lifecycle.guard.stdout.log](node-logs/tg6.aws.lifecycle.guard.stdout.log) |
| `tg6.aws.lifecycle.provision-missing` | **PASS** | 57ms | [context/tg6.aws.lifecycle.provision-missing.input.json](context/tg6.aws.lifecycle.provision-missing.input.json) | [node-logs/tg6.aws.lifecycle.provision-missing.stdout.log](node-logs/tg6.aws.lifecycle.provision-missing.stdout.log) |
| `tg6.aws.lifecycle.reset-markers` | **PASS** | 58ms | [context/tg6.aws.lifecycle.reset-markers.input.json](context/tg6.aws.lifecycle.reset-markers.input.json) | [node-logs/tg6.aws.lifecycle.reset-markers.stdout.log](node-logs/tg6.aws.lifecycle.reset-markers.stdout.log) |
| `tg6.aws.lifecycle.reset` | **PASS** | 56ms | [context/tg6.aws.lifecycle.reset.input.json](context/tg6.aws.lifecycle.reset.input.json) | [node-logs/tg6.aws.lifecycle.reset.stdout.log](node-logs/tg6.aws.lifecycle.reset.stdout.log) |
| `tg6.environment.repository.scaffold.aws` | **PASS** | 263ms | [context/tg6.environment.repository.scaffold.aws.input.json](context/tg6.environment.repository.scaffold.aws.input.json) | [node-logs/tg6.environment.repository.scaffold.aws.stdout.log](node-logs/tg6.environment.repository.scaffold.aws.stdout.log) |

## `tg6.aws.lifecycle.context.jbang` — **PASS**

executor start: `2026-06-28T19:33:36.318108Z`  
executor end: `2026-06-28T19:33:36.964839Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.context.jbang.input.json](context/tg6.aws.lifecycle.context.jbang.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_guard_context_available | **PASS** |
| aws_guard_reason_projected | **PASS** |

### Metrics

- `durationMs`: 1

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.context.jbang.stdout.log](node-logs/tg6.aws.lifecycle.context.jbang.stdout.log)

---

## `tg6.aws.lifecycle.deploy-existing` — **PASS**

executor start: `2026-06-28T19:33:36.965625Z`  
executor end: `2026-06-28T19:33:37.020585Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.deploy-existing.input.json](context/tg6.aws.lifecycle.deploy-existing.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_deploy_guarded_without_cloud | **PASS** |
| aws_deploy_did_not_receive_environment_outputs | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `awsGuardReason`: `aws-lifecycle-not-selected`

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.deploy-existing.stdout.log](node-logs/tg6.aws.lifecycle.deploy-existing.stdout.log)

---

## `tg6.aws.lifecycle.deploy-markers` — **PASS**

executor start: `2026-06-28T19:33:37.021330Z`  
executor end: `2026-06-28T19:33:37.077978Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.deploy-markers.input.json](context/tg6.aws.lifecycle.deploy-markers.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_marker_check_guarded_without_cloud | **PASS** |
| aws_provision_runtime_not_invoked_when_guarded | **PASS** |
| aws_deploy_runtime_not_invoked_when_guarded | **PASS** |

### Metrics

- `durationMs`: 0

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.deploy-markers.stdout.log](node-logs/tg6.aws.lifecycle.deploy-markers.stdout.log)

---

## `tg6.aws.lifecycle.destroy-markers` — **PASS**

executor start: `2026-06-28T19:33:37.258070Z`  
executor end: `2026-06-28T19:33:37.316651Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.destroy-markers.input.json](context/tg6.aws.lifecycle.destroy-markers.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_destroy_marker_check_guarded_without_cloud | **PASS** |
| aws_destroy_runtime_not_invoked_when_guarded | **PASS** |

### Metrics

- `durationMs`: 0

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.destroy-markers.stdout.log](node-logs/tg6.aws.lifecycle.destroy-markers.stdout.log)

---

## `tg6.aws.lifecycle.destroy` — **PASS**

executor start: `2026-06-28T19:33:37.194353Z`  
executor end: `2026-06-28T19:33:37.256961Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.destroy.input.json](context/tg6.aws.lifecycle.destroy.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_destroy_guarded_without_cloud | **PASS** |
| destroy_runtime_not_enabled_without_aws_guard | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `destroyRequested`: `false`
- `EnvironmentId`: ``
- `awsGuardReason`: `aws-lifecycle-not-selected`

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.destroy.stdout.log](node-logs/tg6.aws.lifecycle.destroy.stdout.log)

---

## `tg6.aws.lifecycle.guard` — **PASS**

executor start: `2026-06-28T19:33:36.202880Z`  
executor end: `2026-06-28T19:33:36.259097Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.guard.input.json](context/tg6.aws.lifecycle.guard.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_lifecycle_requires_explicit_selection | **PASS** |
| aws_lifecycle_requires_credentials | **PASS** |
| aws_guard_reason_matches_state | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `awsLifecycleSelected`: `false`
- `awsCredentialsPresent`: `false`
- `awsLifecycleEnabled`: `false`
- `awsGuardReason`: `aws-lifecycle-not-selected`

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.guard.stdout.log](node-logs/tg6.aws.lifecycle.guard.stdout.log)

---

## `tg6.aws.lifecycle.provision-missing` — **PASS**

executor start: `2026-06-28T19:33:36.259807Z`  
executor end: `2026-06-28T19:33:36.316991Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.provision-missing.input.json](context/tg6.aws.lifecycle.provision-missing.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_provision_guarded_without_cloud | **PASS** |
| aws_environment_outputs_absent_when_guarded | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `awsGuardReason`: `aws-lifecycle-not-selected`

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.provision-missing.stdout.log](node-logs/tg6.aws.lifecycle.provision-missing.stdout.log)

---

## `tg6.aws.lifecycle.reset-markers` — **PASS**

executor start: `2026-06-28T19:33:37.135589Z`  
executor end: `2026-06-28T19:33:37.193484Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.reset-markers.input.json](context/tg6.aws.lifecycle.reset-markers.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_reset_marker_check_guarded_without_cloud | **PASS** |
| aws_reset_runtime_not_invoked_when_guarded | **PASS** |

### Metrics

- `durationMs`: 0

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.reset-markers.stdout.log](node-logs/tg6.aws.lifecycle.reset-markers.stdout.log)

---

## `tg6.aws.lifecycle.reset` — **PASS**

executor start: `2026-06-28T19:33:37.078661Z`  
executor end: `2026-06-28T19:33:37.134679Z`  
spawn exit code: 0

**Input context**: [context/tg6.aws.lifecycle.reset.input.json](context/tg6.aws.lifecycle.reset.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_reset_guarded_without_cloud | **PASS** |
| aws_reset_did_not_receive_environment_outputs | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `awsGuardReason`: `aws-lifecycle-not-selected`

**Node-process stdout**: [node-logs/tg6.aws.lifecycle.reset.stdout.log](node-logs/tg6.aws.lifecycle.reset.stdout.log)

---

## `tg6.environment.repository.scaffold.aws` — **PASS**

executor start: `2026-06-28T19:33:35.939808Z`  
executor end: `2026-06-28T19:33:36.202179Z`  
spawn exit code: 0

**Input context**: [context/tg6.environment.repository.scaffold.aws.input.json](context/tg6.environment.repository.scaffold.aws.input.json)

### Assertions

| Name | Status |
|---|---|
| aws_repository_has_git_dir | **PASS** |
| aws_repository_has_initial_commit | **PASS** |
| aws_repository_is_clean | **PASS** |
| shared_local_template_still_exists | **PASS** |
| aws_template_exists | **PASS** |
| required_outputs_exist | **PASS** |
| metadata_targets_aws_preview | **PASS** |
| metadata_backend_is_aws | **PASS** |
| aws_scaffold_does_not_provision_in_normal_graph | **PASS** |

### Metrics

- `durationMs`: 207

### Artifacts

- `scaffolded-aws-environment-repository` — [`/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-aws-preview`](/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-aws-preview)

### Published context

- `environmentRepositoryPath`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-aws-preview`
- `environmentRepositoryCommit`: `fda5e966215c54f320dd979e7c16f02e7381addb`

**Node-process stdout**: [node-logs/tg6.environment.repository.scaffold.aws.stdout.log](node-logs/tg6.environment.repository.scaffold.aws.stdout.log)

---

