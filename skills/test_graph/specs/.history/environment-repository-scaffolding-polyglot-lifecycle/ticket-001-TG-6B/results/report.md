# Validation report — 20260628-160658

**Overall**: PASSED  
**Nodes**: 5 (passed=5, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `tg6.environment.context.env-all` | **PASS** | 49ms | [context/tg6.environment.context.env-all.input.json](context/tg6.environment.context.env-all.input.json) | [node-logs/tg6.environment.context.env-all.stdout.log](node-logs/tg6.environment.context.env-all.stdout.log) |
| `tg6.environment.context.env-key` | **PASS** | 50ms | [context/tg6.environment.context.env-key.input.json](context/tg6.environment.context.env-key.input.json) | [node-logs/tg6.environment.context.env-key.stdout.log](node-logs/tg6.environment.context.env-key.stdout.log) |
| `tg6.environment.repository.provision` | **PASS** | 48ms | [context/tg6.environment.repository.provision.input.json](context/tg6.environment.repository.provision.input.json) | [node-logs/tg6.environment.repository.provision.stdout.log](node-logs/tg6.environment.repository.provision.stdout.log) |
| `tg6.environment.repository.reuse` | **PASS** | 50ms | [context/tg6.environment.repository.reuse.input.json](context/tg6.environment.repository.reuse.input.json) | [node-logs/tg6.environment.repository.reuse.stdout.log](node-logs/tg6.environment.repository.reuse.stdout.log) |
| `tg6.environment.repository.scaffold.local` | **PASS** | 529ms | [context/tg6.environment.repository.scaffold.local.input.json](context/tg6.environment.repository.scaffold.local.input.json) | [node-logs/tg6.environment.repository.scaffold.local.stdout.log](node-logs/tg6.environment.repository.scaffold.local.stdout.log) |

## `tg6.environment.context.env-all` — **PASS**

executor start: `2026-06-28T16:06:59.215698Z`  
executor end: `2026-06-28T16:06:59.264289Z`  
spawn exit code: 0

**Input context**: [context/tg6.environment.context.env-all.input.json](context/tg6.environment.context.env-all.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_projected | **PASS** |
| kubeconfig_projected | **PASS** |
| kubecontext_projected | **PASS** |
| all_mode_projects_extra_eligible_key | **PASS** |

### Metrics

- `durationMs`: 0

**Node-process stdout**: [node-logs/tg6.environment.context.env-all.stdout.log](node-logs/tg6.environment.context.env-all.stdout.log)

---

## `tg6.environment.context.env-key` — **PASS**

executor start: `2026-06-28T16:06:59.164140Z`  
executor end: `2026-06-28T16:06:59.214650Z`  
spawn exit code: 0

**Input context**: [context/tg6.environment.context.env-key.input.json](context/tg6.environment.context.env-key.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_projected | **PASS** |
| kubeconfig_projected | **PASS** |
| kubecontext_projected | **PASS** |
| unrequested_key_not_projected | **PASS** |

### Metrics

- `durationMs`: 0

**Node-process stdout**: [node-logs/tg6.environment.context.env-key.stdout.log](node-logs/tg6.environment.context.env-key.stdout.log)

---

## `tg6.environment.repository.provision` — **PASS**

executor start: `2026-06-28T16:06:59.113150Z`  
executor end: `2026-06-28T16:06:59.161846Z`  
spawn exit code: 0

**Input context**: [context/tg6.environment.repository.provision.input.json](context/tg6.environment.repository.provision.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_is_available | **PASS** |
| kubeconfig_env_is_available | **PASS** |
| kubecontext_uses_local_preview_target | **PASS** |
| target_env_is_local_preview | **PASS** |
| backend_env_is_local | **PASS** |
| environment_was_not_reused_first_time | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `EnvironmentId`: `environmentRepositoryScaffoldLocal__local__local-preview__local`
- `KUBECONFIG`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-environment-repositories/environmentRepositoryScaffoldLocal__local__local-preview__local/repo/templates/branch-preview/generated/local-preview/kubeconfig`
- `KUBECONTEXT`: `test-graph-local-preview-local`
- `EnvironmentRepositoryReused`: `false`

### Provisioning state

- `environmentId`: `environmentRepositoryScaffoldLocal__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `provision`
- `provisionedMarker`: [`../../testgraph-provisioning-state/provisioned/environmentRepositoryScaffoldLocal__local__local-preview__local.json`](../../testgraph-provisioning-state/provisioned/environmentRepositoryScaffoldLocal__local__local-preview__local.json)

**Node-process stdout**: [node-logs/tg6.environment.repository.provision.stdout.log](node-logs/tg6.environment.repository.provision.stdout.log)

---

## `tg6.environment.repository.reuse` — **PASS**

executor start: `2026-06-28T16:06:59.289715Z`  
executor end: `2026-06-28T16:06:59.339614Z`  
spawn exit code: 0

**Input context**: [context/tg6.environment.repository.reuse.input.json](context/tg6.environment.repository.reuse.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_is_available | **PASS** |
| kubeconfig_env_is_available | **PASS** |
| environment_was_reused | **PASS** |
| same_environment_as_first_provision | **PASS** |

### Metrics

- `durationMs`: 0

### Published context

- `EnvironmentId`: `environmentRepositoryScaffoldLocal__local__local-preview__local`
- `KUBECONFIG`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-environment-repositories/environmentRepositoryScaffoldLocal__local__local-preview__local/repo/templates/branch-preview/generated/local-preview/kubeconfig`
- `KUBECONTEXT`: `test-graph-local-preview-local`
- `EnvironmentRepositoryReused`: `true`

### Provisioning state

- `environmentId`: `environmentRepositoryScaffoldLocal__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `provision`
- `provisionedMarker`: [`../../testgraph-provisioning-state/provisioned/environmentRepositoryScaffoldLocal__local__local-preview__local.json`](../../testgraph-provisioning-state/provisioned/environmentRepositoryScaffoldLocal__local__local-preview__local.json)

**Node-process stdout**: [node-logs/tg6.environment.repository.reuse.stdout.log](node-logs/tg6.environment.repository.reuse.stdout.log)

---

## `tg6.environment.repository.scaffold.local` — **PASS**

executor start: `2026-06-28T16:06:58.317517Z`  
executor end: `2026-06-28T16:06:58.846866Z`  
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

- `durationMs`: 477

### Artifacts

- `scaffolded-local-environment-repository` — [`/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`](/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview)

### Published context

- `environmentRepositoryPath`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`
- `environmentRepositoryFileUrl`: `file:///Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`
- `environmentRepositoryCommit`: `21bce6b53b34b9925c3eb86e8e7301722bfe9ede`
- `environmentRepositoryTemplate`: `templates/branch-preview`

**Node-process stdout**: [node-logs/tg6.environment.repository.scaffold.local.stdout.log](node-logs/tg6.environment.repository.scaffold.local.stdout.log)

---

