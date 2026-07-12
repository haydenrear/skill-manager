# Validation report — 20260712-181143

**Overall**: PASSED  
**Nodes**: 2 (passed=2, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 605ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `project.libs.resolved` | **PASS** | 3680ms | [context/project.libs.resolved.input.json](context/project.libs.resolved.input.json) | [node-logs/project.libs.resolved.stdout.log](node-logs/project.libs.resolved.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:11:43.947634Z`  
executor end: `2026-07-12T18:11:44.552281Z`  
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

- `registryPort`: 65420
- `gatewayPort`: 65421
- `durationMs`: 30

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-2693819824008767440`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-2693819824008767440/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-2693819824008767440/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-2693819824008767440/agent-home/.gemini`
- `registryPort`: `65420`
- `gatewayPort`: `65421`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `project.libs.resolved` — **PASS**

executor start: `2026-07-12T18:11:44.554025Z`  
executor end: `2026-07-12T18:11:48.234147Z`  
spawn exit code: 0

**Input context**: [context/project.libs.resolved.input.json](context/project.libs.resolved.input.json)

### Assertions

| Name | Status |
|---|---|
| resolve_libs_command_ok | **PASS** |
| lib_checkout_materialized_under_project_libs | **PASS** |
| libs_directory_gitignored | **PASS** |
| project_lock_records_lib_provenance | **PASS** |
| project_show_reports_lib_lock_count | **PASS** |
| existing_checkout_without_origin_rejected | **PASS** |

### Metrics

- `resolveExitCode`: 0
- `showExitCode`: 0
- `noOriginResolveExitCode`: 1
- `durationMs`: 3101

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| resolve-libs | 0 | 998ms | 88973 | [`node-logs/project.libs.resolved.resolve-libs.log`](node-logs/project.libs.resolved.resolve-libs.log) |  |
| show | 0 | 915ms | 88989 | [`node-logs/project.libs.resolved.show.log`](node-logs/project.libs.resolved.show.log) |  |
| resolve-libs-no-origin | 1 | 965ms | 89001 | [`node-logs/project.libs.resolved.resolve-libs-no-origin.log`](node-logs/project.libs.resolved.resolve-libs-no-origin.log) |  |

### Published context

- `projectName`: `tg-libs-project`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-libs-7576553022536579029`
- `libSha`: `c37281069040489408684735e1297e144a87fb60`

**Node-process stdout**: [node-logs/project.libs.resolved.stdout.log](node-logs/project.libs.resolved.stdout.log)

---

