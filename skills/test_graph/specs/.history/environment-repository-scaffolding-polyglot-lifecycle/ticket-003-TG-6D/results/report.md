# Validation report — 20260628-170851

**Overall**: PASSED
**Nodes**: 4 (passed=4, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `tg6.environment.context.env-all.jbang` | **PASS** | 614ms | [context/tg6.environment.context.env-all.jbang.input.json](context/tg6.environment.context.env-all.jbang.input.json) | [node-logs/tg6.environment.context.env-all.jbang.stdout.log](node-logs/tg6.environment.context.env-all.jbang.stdout.log) |
| `tg6.environment.context.env-key.jbang` | **PASS** | 644ms | [context/tg6.environment.context.env-key.jbang.input.json](context/tg6.environment.context.env-key.jbang.input.json) | [node-logs/tg6.environment.context.env-key.jbang.stdout.log](node-logs/tg6.environment.context.env-key.jbang.stdout.log) |
| `tg6.environment.repository.provision.jbang` | **PASS** | 611ms | [context/tg6.environment.repository.provision.jbang.input.json](context/tg6.environment.repository.provision.jbang.input.json) | [node-logs/tg6.environment.repository.provision.jbang.stdout.log](node-logs/tg6.environment.repository.provision.jbang.stdout.log) |
| `tg6.environment.repository.scaffold.local` | **PASS** | 359ms | [context/tg6.environment.repository.scaffold.local.input.json](context/tg6.environment.repository.scaffold.local.input.json) | [node-logs/tg6.environment.repository.scaffold.local.stdout.log](node-logs/tg6.environment.repository.scaffold.local.stdout.log) |

## `tg6.environment.context.env-all.jbang` — **PASS**

executor start: `2026-06-28T17:08:53.166657Z`
executor end: `2026-06-28T17:08:53.780079Z`
spawn exit code: 0

**Input context**: [context/tg6.environment.context.env-all.jbang.input.json](context/tg6.environment.context.env-all.jbang.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_projected | **PASS** |
| kubeconfig_projected | **PASS** |
| kubecontext_projected | **PASS** |
| environment_reused_projected_by_all | **PASS** |

### Metrics

- `durationMs`: 1

**Node-process stdout**: [node-logs/tg6.environment.context.env-all.jbang.stdout.log](node-logs/tg6.environment.context.env-all.jbang.stdout.log)

---

## `tg6.environment.context.env-key.jbang` — **PASS**

executor start: `2026-06-28T17:08:52.521304Z`
executor end: `2026-06-28T17:08:53.165593Z`
spawn exit code: 0

**Input context**: [context/tg6.environment.context.env-key.jbang.input.json](context/tg6.environment.context.env-key.jbang.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_projected | **PASS** |
| kubeconfig_projected | **PASS** |
| kubecontext_projected | **PASS** |
| unrequested_key_not_projected | **PASS** |

### Metrics

- `durationMs`: 1

**Node-process stdout**: [node-logs/tg6.environment.context.env-key.jbang.stdout.log](node-logs/tg6.environment.context.env-key.jbang.stdout.log)

---

## `tg6.environment.repository.provision.jbang` — **PASS**

executor start: `2026-06-28T17:08:51.907190Z`
executor end: `2026-06-28T17:08:52.518849Z`
spawn exit code: 0

**Input context**: [context/tg6.environment.repository.provision.jbang.input.json](context/tg6.environment.repository.provision.jbang.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_id_is_available | **PASS** |
| kubeconfig_env_is_available | **PASS** |
| kubecontext_uses_local_preview_target | **PASS** |
| target_env_is_local_preview | **PASS** |
| backend_env_is_local | **PASS** |
| environment_was_not_reused_first_time | **PASS** |
| repository_dir_env_is_available | **PASS** |
| template_dir_env_is_available | **PASS** |

### Metrics

- `durationMs`: 2

### Published context

- `EnvironmentId`: `environmentRepositoryContractJbang__local__local-preview__local`
- `KUBECONFIG`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/testgraph-environment-repositories/environmentRepositoryContractJbang__local__local-preview__local/repo/templates/branch-preview/generated/local-preview/kubeconfig`
- `KUBECONTEXT`: `test-graph-local-preview-local`
- `EnvironmentRepositoryReused`: `false`

### Provisioning state

- `environmentId`: `environmentRepositoryContractJbang__local__local-preview__local`
- `branch`: `local`
- `target`: `local-preview`
- `backend`: `local`
- `actions`: `provision`
- `provisionedMarker`: [`../../testgraph-provisioning-state/provisioned/environmentRepositoryContractJbang__local__local-preview__local.json`](../../testgraph-provisioning-state/provisioned/environmentRepositoryContractJbang__local__local-preview__local.json)

**Node-process stdout**: [node-logs/tg6.environment.repository.provision.jbang.stdout.log](node-logs/tg6.environment.repository.provision.jbang.stdout.log)

---

## `tg6.environment.repository.scaffold.local` — **PASS**

executor start: `2026-06-28T17:08:51.302412Z`
executor end: `2026-06-28T17:08:51.661856Z`
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

- `durationMs`: 300

### Artifacts

- `scaffolded-local-environment-repository` — [`/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`](/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview)

### Published context

- `environmentRepositoryPath`: `/Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`
- `environmentRepositoryFileUrl`: `file:///Users/hayde/IdeaProjects/test_graph/test_graph/build/tg6-environment-repository-local-preview`
- `environmentRepositoryCommit`: `b60a17b2ff2d8a7dcd634eef1ddf4a851c1024ea`
- `environmentRepositoryTemplate`: `templates/branch-preview`

**Node-process stdout**: [node-logs/tg6.environment.repository.scaffold.local.stdout.log](node-logs/tg6.environment.repository.scaffold.local.stdout.log)

---
