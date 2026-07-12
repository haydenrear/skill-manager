# Validation report — 20260712-181057

**Overall**: PASSED  
**Nodes**: 2 (passed=2, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 601ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `project.dependencies.resolved` | **PASS** | 7698ms | [context/project.dependencies.resolved.input.json](context/project.dependencies.resolved.input.json) | [node-logs/project.dependencies.resolved.stdout.log](node-logs/project.dependencies.resolved.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:10:57.072154Z`  
executor end: `2026-07-12T18:10:57.673643Z`  
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

- `registryPort`: 65397
- `gatewayPort`: 65398
- `durationMs`: 31

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9602157786913985994`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9602157786913985994/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9602157786913985994/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9602157786913985994/agent-home/.gemini`
- `registryPort`: `65397`
- `gatewayPort`: `65398`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `project.dependencies.resolved` — **PASS**

executor start: `2026-07-12T18:10:57.674447Z`  
executor end: `2026-07-12T18:11:05.372904Z`  
spawn exit code: 0

**Input context**: [context/project.dependencies.resolved.input.json](context/project.dependencies.resolved.input.json)

### Assertions

| Name | Status |
|---|---|
| resolve_command_ok | **PASS** |
| resolve_existing_project_is_idempotent | **PASS** |
| project_sync_placeholder_ok | **PASS** |
| show_reports_lock_counts | **PASS** |
| project_lock_written | **PASS** |
| lock_records_direct_and_transitive_units | **PASS** |
| units_installed_in_home | **PASS** |
| doc_binding_materialized | **PASS** |
| project_agent_skill_bindings_materialized | **PASS** |
| project_agent_plugin_binding_materialized | **PASS** |
| project_child_home_scaffolded | **PASS** |
| project_child_home_units_projected | **PASS** |
| parent_child_home_registry_claims_project_units | **PASS** |
| project_agent_projections_point_at_child_store | **PASS** |
| plain_remove_blocked_by_project_lock | **PASS** |
| project_remove_command_ok | **PASS** |
| project_remove_clears_registration | **PASS** |
| project_remove_clears_child_home_registry | **PASS** |
| project_remove_clears_child_home_generated_units | **PASS** |
| project_remove_clears_project_bindings | **PASS** |
| project_remove_keeps_parent_home_units | **PASS** |

### Metrics

- `resolveExitCode`: 0
- `resolveAgainExitCode`: 0
- `syncExitCode`: 0
- `showExitCode`: 0
- `removeExitCode`: 1
- `projectRemoveExitCode`: 0
- `durationMs`: 7092

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| resolve | 0 | 1844ms | 88456 | [`node-logs/project.dependencies.resolved.resolve.log`](node-logs/project.dependencies.resolved.resolve.log) |  |
| resolve-again | 0 | 1089ms | 88479 | [`node-logs/project.dependencies.resolved.resolve-again.log`](node-logs/project.dependencies.resolved.resolve-again.log) |  |
| sync | 0 | 1112ms | 88488 | [`node-logs/project.dependencies.resolved.sync.log`](node-logs/project.dependencies.resolved.sync.log) |  |
| show | 0 | 1002ms | 88497 | [`node-logs/project.dependencies.resolved.show.log`](node-logs/project.dependencies.resolved.show.log) |  |
| remove-claimed | 1 | 975ms | 88511 | [`node-logs/project.dependencies.resolved.remove-claimed.log`](node-logs/project.dependencies.resolved.remove-claimed.log) |  |
| project-remove | 0 | 1031ms | 88520 | [`node-logs/project.dependencies.resolved.project-remove.log`](node-logs/project.dependencies.resolved.project-remove.log) |  |

### Published context

- `projectName`: `tg-resolved-project`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-resolve-5097023173654128665`
- `lockFile`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-9602157786913985994/projects/tg-resolved-project/project-lock.toml`

**Node-process stdout**: [node-logs/project.dependencies.resolved.stdout.log](node-logs/project.dependencies.resolved.stdout.log)

---

