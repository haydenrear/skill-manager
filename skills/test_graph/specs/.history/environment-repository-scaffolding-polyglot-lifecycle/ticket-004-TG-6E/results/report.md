# Validation report — 20260628-172952

**Overall**: PASSED
**Nodes**: 7 (passed=7, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `tg6.environment.repository.scaffold.local` | **PASS** | 245ms | [context/tg6.environment.repository.scaffold.local.input.json](context/tg6.environment.repository.scaffold.local.input.json) | [node-logs/tg6.environment.repository.scaffold.local.stdout.log](node-logs/tg6.environment.repository.scaffold.local.stdout.log) |
| `tg6.local.lifecycle.deploy-existing` | **PASS** | 57ms | [context/tg6.local.lifecycle.deploy-existing.input.json](context/tg6.local.lifecycle.deploy-existing.input.json) | [node-logs/tg6.local.lifecycle.deploy-existing.stdout.log](node-logs/tg6.local.lifecycle.deploy-existing.stdout.log) |
| `tg6.local.lifecycle.deploy-markers` | **PASS** | 68ms | [context/tg6.local.lifecycle.deploy-markers.input.json](context/tg6.local.lifecycle.deploy-markers.input.json) | [node-logs/tg6.local.lifecycle.deploy-markers.stdout.log](node-logs/tg6.local.lifecycle.deploy-markers.stdout.log) |
| `tg6.local.lifecycle.provision-missing` | **PASS** | 57ms | [context/tg6.local.lifecycle.provision-missing.input.json](context/tg6.local.lifecycle.provision-missing.input.json) | [node-logs/tg6.local.lifecycle.provision-missing.stdout.log](node-logs/tg6.local.lifecycle.provision-missing.stdout.log) |
| `tg6.local.lifecycle.reset-markers` | **PASS** | 58ms | [context/tg6.local.lifecycle.reset-markers.input.json](context/tg6.local.lifecycle.reset-markers.input.json) | [node-logs/tg6.local.lifecycle.reset-markers.stdout.log](node-logs/tg6.local.lifecycle.reset-markers.stdout.log) |
| `tg6.local.lifecycle.reset` | **PASS** | 53ms | [context/tg6.local.lifecycle.reset.input.json](context/tg6.local.lifecycle.reset.input.json) | [node-logs/tg6.local.lifecycle.reset.stdout.log](node-logs/tg6.local.lifecycle.reset.stdout.log) |
| `tg6.local.lifecycle.skip-destroy` | **PASS** | 56ms | [context/tg6.local.lifecycle.skip-destroy.input.json](context/tg6.local.lifecycle.skip-destroy.input.json) | [node-logs/tg6.local.lifecycle.skip-destroy.stdout.log](node-logs/tg6.local.lifecycle.skip-destroy.stdout.log) |

## `tg6.environment.repository.scaffold.local` — **PASS**

executor start: `2026-06-28T17:29:52.251711Z`
executor end: `2026-06-28T17:29:52.496515Z`
spawn exit code: 0

**Input context**: [context/tg6.environment.repository.scaffold.local.input.json](context/tg6.environment.repository.scaffold.local.input.json)

### Assertions

| Name | Status |
|---|---|
| scaffolded_repository_has_git_dir | **PASS** |
| scaffolded_repository_has_initial_commit | **PASS** |
| scaffolded_repository_is_clean | **PASS** |
| template_directory_exists | **PASS** |
| local_template_exists | **PASS** |
| local_tofu_shim_is_executable | **PASS** |
| required_outputs_exist | **PASS** |

### Metrics

- `durationMs`: 189

### Artifacts

- `scaffolded-local-environment-repository` — [`/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`](/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview)

### Published context

- `environmentRepositoryPath`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`
- `environmentRepositoryFileUrl`: `file:///Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`
- `environmentRepositoryCommit`: `5dcfcfd997ab3a8141b55dc0e3d82ae151d3018c`
- `environmentRepositoryTemplate`: `templates/branch-preview`

**Node-process stdout**: [node-logs/tg6.environment.repository.scaffold.local.stdout.log](node-logs/tg6.environment.repository.scaffold.local.stdout.log)

---

## `tg6.local.lifecycle.deploy-existing` — **PASS**

executor start: `2026-06-28T17:29:53.014030Z`
executor end: `2026-06-28T17:29:53.071893Z`
spawn exit code: 0

**Input context**: [context/tg6.local.lifecycle.deploy-existing.input.json](context/tg6.local.lifecycle.deploy-existing.input.json)

### Assertions

| Name | Status |
|---|---|
| deploy_targets_provisioned_environment | **PASS** |
| deploy_reuses_existing_environment | **PASS** |
| deploy_receives_kubeconfig | **PASS** |
| application_ready_marker_created | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `EnvironmentId`: `environmentRepositoryLocalLifecycle__local__local-preview__local`
- `ApplicationReadyPath`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-environment-repositories/environmentRepositoryLocalLifecycle__local__local-preview__local/repo/templates/branch-preview/generated/local-preview/tg6-local-lifecycle-application.ready`
- `KUBECONFIG`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-environment-repositories/environmentRepositoryLocalLifecycle__local__local-preview__local/repo/templates/branch-preview/generated/local-preview/kubeconfig`
- `KUBECONTEXT`: `test-graph-local-preview-local`
- `EnvironmentRepositoryReused`: `true`

### Provisioning state

- `environmentId`: `environmentRepositoryLocalLifecycle__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `deploy`
- `deployedMarker`: [`../../testgraph-provisioning-state/deployed/environmentRepositoryLocalLifecycle__local__local-preview__local.json`](../../testgraph-provisioning-state/deployed/environmentRepositoryLocalLifecycle__local__local-preview__local.json)

**Node-process stdout**: [node-logs/tg6.local.lifecycle.deploy-existing.stdout.log](node-logs/tg6.local.lifecycle.deploy-existing.stdout.log)

---

## `tg6.local.lifecycle.deploy-markers` — **PASS**

executor start: `2026-06-28T17:29:53.074849Z`
executor end: `2026-06-28T17:29:53.142603Z`
spawn exit code: 0

**Input context**: [context/tg6.local.lifecycle.deploy-markers.input.json](context/tg6.local.lifecycle.deploy-markers.input.json)

### Assertions

| Name | Status |
|---|---|
| provisioned_marker_exists | **PASS** |
| deployed_marker_exists | **PASS** |
| first_provision_applied_missing_cluster | **PASS** |
| deploy_existing_skipped_recreate_apply | **PASS** |
| deploy_execution_reported_reuse | **PASS** |

### Metrics

- `durationMs`: 0

**Node-process stdout**: [node-logs/tg6.local.lifecycle.deploy-markers.stdout.log](node-logs/tg6.local.lifecycle.deploy-markers.stdout.log)

---

## `tg6.local.lifecycle.provision-missing` — **PASS**

executor start: `2026-06-28T17:29:52.822944Z`
executor end: `2026-06-28T17:29:52.879182Z`
spawn exit code: 0

**Input context**: [context/tg6.local.lifecycle.provision-missing.input.json](context/tg6.local.lifecycle.provision-missing.input.json)

### Assertions

| Name | Status |
|---|---|
| missing_environment_was_provisioned | **PASS** |
| environment_id_available | **PASS** |
| kubeconfig_created | **PASS** |
| target_is_local_preview | **PASS** |
| backend_is_local | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `EnvironmentId`: `environmentRepositoryLocalLifecycle__local__local-preview__local`
- `KUBECONFIG`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-environment-repositories/environmentRepositoryLocalLifecycle__local__local-preview__local/repo/templates/branch-preview/generated/local-preview/kubeconfig`
- `KUBECONTEXT`: `test-graph-local-preview-local`
- `EnvironmentRepositoryReused`: `false`

### Provisioning state

- `environmentId`: `environmentRepositoryLocalLifecycle__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `provision`
- `provisionedMarker`: [`../../testgraph-provisioning-state/provisioned/environmentRepositoryLocalLifecycle__local__local-preview__local.json`](../../testgraph-provisioning-state/provisioned/environmentRepositoryLocalLifecycle__local__local-preview__local.json)

**Node-process stdout**: [node-logs/tg6.local.lifecycle.provision-missing.stdout.log](node-logs/tg6.local.lifecycle.provision-missing.stdout.log)

---

## `tg6.local.lifecycle.reset-markers` — **PASS**

executor start: `2026-06-28T17:29:53.385719Z`
executor end: `2026-06-28T17:29:53.443958Z`
spawn exit code: 0

**Input context**: [context/tg6.local.lifecycle.reset-markers.input.json](context/tg6.local.lifecycle.reset-markers.input.json)

### Assertions

| Name | Status |
|---|---|
| reset_kept_provisioned_marker | **PASS** |
| reset_cleared_deployed_marker | **PASS** |
| reset_marker_exists | **PASS** |
| reset_reapplied_environment | **PASS** |

### Metrics

- `durationMs`: 0

### Artifacts

- `reset-marker` — [`/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-provisioning-state/reset/environmentRepositoryLocalLifecycle__local__local-preview__local__~32303236303632382d3137323935325f5f7467362e6c6f63616c2e6c6966656379636c652e7265736574.json`](/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-provisioning-state/reset/environmentRepositoryLocalLifecycle__local__local-preview__local__~32303236303632382d3137323935325f5f7467362e6c6f63616c2e6c6966656379636c652e7265736574.json)

**Node-process stdout**: [node-logs/tg6.local.lifecycle.reset-markers.stdout.log](node-logs/tg6.local.lifecycle.reset-markers.stdout.log)

---

## `tg6.local.lifecycle.reset` — **PASS**

executor start: `2026-06-28T17:29:53.330128Z`
executor end: `2026-06-28T17:29:53.383266Z`
spawn exit code: 0

**Input context**: [context/tg6.local.lifecycle.reset.input.json](context/tg6.local.lifecycle.reset.input.json)

### Assertions

| Name | Status |
|---|---|
| reset_targets_provisioned_environment | **PASS** |
| reset_is_not_reuse | **PASS** |
| reset_clears_application_state | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `EnvironmentId`: `environmentRepositoryLocalLifecycle__local__local-preview__local`
- `KUBECONFIG`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-environment-repositories/environmentRepositoryLocalLifecycle__local__local-preview__local/repo/templates/branch-preview/generated/local-preview/kubeconfig`
- `KUBECONTEXT`: `test-graph-local-preview-local`
- `EnvironmentRepositoryReused`: `false`

### Provisioning state

- `environmentId`: `environmentRepositoryLocalLifecycle__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `reset`
- `resetMarker`: [`../../testgraph-provisioning-state/reset/environmentRepositoryLocalLifecycle__local__local-preview__local__~32303236303632382d3137323935325f5f7467362e6c6f63616c2e6c6966656379636c652e7265736574.json`](../../testgraph-provisioning-state/reset/environmentRepositoryLocalLifecycle__local__local-preview__local__~32303236303632382d3137323935325f5f7467362e6c6f63616c2e6c6966656379636c652e7265736574.json)

**Node-process stdout**: [node-logs/tg6.local.lifecycle.reset.stdout.log](node-logs/tg6.local.lifecycle.reset.stdout.log)

---

## `tg6.local.lifecycle.skip-destroy` — **PASS**

executor start: `2026-06-28T17:29:53.444950Z`
executor end: `2026-06-28T17:29:53.500708Z`
spawn exit code: 0

**Input context**: [context/tg6.local.lifecycle.skip-destroy.input.json](context/tg6.local.lifecycle.skip-destroy.input.json)

### Assertions

| Name | Status |
|---|---|
| skip_destroy_keeps_environment_active | **PASS** |
| skip_destroy_does_not_write_destroyed_marker | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `destroyRequested`: `false`

**Node-process stdout**: [node-logs/tg6.local.lifecycle.skip-destroy.stdout.log](node-logs/tg6.local.lifecycle.skip-destroy.stdout.log)

---
