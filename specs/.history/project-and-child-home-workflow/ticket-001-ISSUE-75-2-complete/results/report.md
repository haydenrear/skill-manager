# Validation report — 20260603-204615

**Overall**: PASSED  
**Nodes**: 2 (passed=2, failed=0, errored=0)

| Node | Status | Duration | Captured stdout |
|---|---|---|---|
| `env.prepared` | **PASS** | 874ms | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `project.dependencies.resolved` | **PASS** | 8079ms | [node-logs/project.dependencies.resolved.stdout.log](node-logs/project.dependencies.resolved.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-06-03T20:46:15.268296Z`  
executor end: `2026-06-03T20:46:16.142774Z`  
spawn exit code: 0

### Assertions

| Name | Status |
|---|---|
| home_created | **PASS** |
| agent_home_created | **PASS** |
| codex_home_created | **PASS** |
| gemini_home_created | **PASS** |
| ports_allocated | **PASS** |

### Metrics

- `registryPort`: 58211
- `gatewayPort`: 58212
- `durationMs`: 35

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14682771475060883662`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14682771475060883662/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14682771475060883662/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14682771475060883662/agent-home/.gemini`
- `registryPort`: `58211`
- `gatewayPort`: `58212`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `project.dependencies.resolved` — **PASS**

executor start: `2026-06-03T20:46:16.143396Z`  
executor end: `2026-06-03T20:46:24.222765Z`  
spawn exit code: 0

### Assertions

| Name | Status |
|---|---|
| resolve_command_ok | **PASS** |
| show_reports_lock_counts | **PASS** |
| project_lock_written | **PASS** |
| lock_records_direct_and_transitive_units | **PASS** |
| units_installed_in_home | **PASS** |
| doc_binding_materialized | **PASS** |
| harness_binding_materialized | **PASS** |
| plain_remove_blocked_by_project_lock | **PASS** |

### Metrics

- `resolveExitCode`: 0
- `showExitCode`: 0
- `removeExitCode`: 1
- `durationMs`: 7148

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| resolve | 0 | 4709ms | 45174 | [`node-logs/project.dependencies.resolved.resolve.log`](node-logs/project.dependencies.resolved.resolve.log) |  |
| show | 0 | 1295ms | 45194 | [`node-logs/project.dependencies.resolved.show.log`](node-logs/project.dependencies.resolved.show.log) |  |
| remove-claimed | 1 | 1099ms | 45207 | [`node-logs/project.dependencies.resolved.remove-claimed.log`](node-logs/project.dependencies.resolved.remove-claimed.log) |  |

### Published context

- `projectName`: `tg-resolved-project`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-resolve-3680229971077569298`
- `lockFile`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14682771475060883662/projects/tg-resolved-project/project-lock.toml`

**Node-process stdout**: [node-logs/project.dependencies.resolved.stdout.log](node-logs/project.dependencies.resolved.stdout.log)

---

