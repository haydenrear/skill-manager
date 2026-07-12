# Validation report — 20260712-181136

**Overall**: PASSED  
**Nodes**: 2 (passed=2, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 586ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `project.env.materialized` | **PASS** | 4107ms | [context/project.env.materialized.input.json](context/project.env.materialized.input.json) | [node-logs/project.env.materialized.stdout.log](node-logs/project.env.materialized.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:11:36.941275Z`  
executor end: `2026-07-12T18:11:37.527025Z`  
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

- `registryPort`: 65418
- `gatewayPort`: 65419
- `durationMs`: 31

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13507527503226642334`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13507527503226642334/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13507527503226642334/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-13507527503226642334/agent-home/.gemini`
- `registryPort`: `65418`
- `gatewayPort`: `65419`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `project.env.materialized` — **PASS**

executor start: `2026-07-12T18:11:37.527755Z`  
executor end: `2026-07-12T18:11:41.634024Z`  
spawn exit code: 0

**Input context**: [context/project.env.materialized.input.json](context/project.env.materialized.input.json)

### Assertions

| Name | Status |
|---|---|
| resolve_command_ok | **PASS** |
| env_sync_command_ok | **PASS** |
| pyproject_uses_project_relative_vendor_path | **PASS** |
| skill_unit_vendored_project_locally | **PASS** |
| tool_shim_executable | **PASS** |
| env_docs_rendered_as_documentation | **PASS** |
| project_lock_records_env_realization | **PASS** |
| project_show_reports_env_lock_count | **PASS** |

### Metrics

- `resolveExitCode`: 0
- `syncExitCode`: 0
- `showExitCode`: 0
- `durationMs`: 3524

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| resolve | 0 | 1563ms | 88879 | [`node-logs/project.env.materialized.resolve.log`](node-logs/project.env.materialized.resolve.log) |  |
| env-sync | 0 | 968ms | 88890 | [`node-logs/project.env.materialized.env-sync.log`](node-logs/project.env.materialized.env-sync.log) |  |
| show | 0 | 957ms | 88901 | [`node-logs/project.env.materialized.show.log`](node-logs/project.env.materialized.show.log) |  |

### Published context

- `projectName`: `tg-env-project`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-env-13093221144074978045`
- `envRoot`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-env-13093221144074978045/.skill-manager/envs/dev`

**Node-process stdout**: [node-logs/project.env.materialized.stdout.log](node-logs/project.env.materialized.stdout.log)

---

