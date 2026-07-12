# Validation report — 20260712-181149

**Overall**: PASSED  
**Nodes**: 2 (passed=2, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 599ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `project.profiles.resolved` | **PASS** | 4694ms | [context/project.profiles.resolved.input.json](context/project.profiles.resolved.input.json) | [node-logs/project.profiles.resolved.stdout.log](node-logs/project.profiles.resolved.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:11:49.691460Z`  
executor end: `2026-07-12T18:11:50.290815Z`  
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

- `registryPort`: 65422
- `gatewayPort`: 65423
- `durationMs`: 29

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4009002439593784890`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4009002439593784890/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4009002439593784890/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4009002439593784890/agent-home/.gemini`
- `registryPort`: `65422`
- `gatewayPort`: `65423`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `project.profiles.resolved` — **PASS**

executor start: `2026-07-12T18:11:50.291577Z`  
executor end: `2026-07-12T18:11:54.985619Z`  
spawn exit code: 0

**Input context**: [context/project.profiles.resolved.input.json](context/project.profiles.resolved.input.json)

### Assertions

| Name | Status |
|---|---|
| profiles_list_reports_declared_profiles | **PASS** |
| dev_profile_resolve_ok | **PASS** |
| review_profile_resolve_ok | **PASS** |
| profile_locks_are_distinct_and_selected | **PASS** |
| dev_profile_child_home_is_isolated | **PASS** |
| review_profile_child_home_is_isolated | **PASS** |
| parent_registry_records_profile_child_homes | **PASS** |

### Metrics

- `durationMs`: 4118

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| profiles-list | 0 | 881ms | 89044 | [`node-logs/project.profiles.resolved.profiles-list.log`](node-logs/project.profiles.resolved.profiles-list.log) |  |
| resolve-dev | 0 | 1617ms | 89053 | [`node-logs/project.profiles.resolved.resolve-dev.log`](node-logs/project.profiles.resolved.resolve-dev.log) |  |
| resolve-review | 0 | 1583ms | 89066 | [`node-logs/project.profiles.resolved.resolve-review.log`](node-logs/project.profiles.resolved.resolve-review.log) |  |

### Published context

- `projectName`: `tg-profiled-project`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-profiles-17674982112786364690`
- `devChildHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-profiles-17674982112786364690/.skill-manager/profiles/dev`
- `reviewChildHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-profiles-17674982112786364690/.skill-manager/profiles/review`

**Node-process stdout**: [node-logs/project.profiles.resolved.stdout.log](node-logs/project.profiles.resolved.stdout.log)

---

