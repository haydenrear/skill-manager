# Validation report — 20260712-181048

**Overall**: PASSED  
**Nodes**: 2 (passed=2, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 596ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `project.manifest.registered` | **PASS** | 6034ms | [context/project.manifest.registered.input.json](context/project.manifest.registered.input.json) | [node-logs/project.manifest.registered.stdout.log](node-logs/project.manifest.registered.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:10:48.991271Z`  
executor end: `2026-07-12T18:10:49.587403Z`  
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

- `registryPort`: 65390
- `gatewayPort`: 65391
- `durationMs`: 29

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10482522127525919074`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10482522127525919074/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10482522127525919074/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10482522127525919074/agent-home/.gemini`
- `registryPort`: `65390`
- `gatewayPort`: `65391`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `project.manifest.registered` — **PASS**

executor start: `2026-07-12T18:10:49.588089Z`  
executor end: `2026-07-12T18:10:55.622264Z`  
spawn exit code: 0

**Input context**: [context/project.manifest.registered.input.json](context/project.manifest.registered.input.json)

### Assertions

| Name | Status |
|---|---|
| register_command_ok | **PASS** |
| custom_manifest_register_command_ok | **PASS** |
| reserved_manifest_register_rejected | **PASS** |
| registration_metadata_written | **PASS** |
| manifest_snapshot_written | **PASS** |
| custom_manifest_snapshot_written | **PASS** |
| show_summarizes_portable_intent | **PASS** |
| custom_show_summarizes_portable_intent | **PASS** |
| list_includes_registered_project | **PASS** |
| skills_not_installed | **PASS** |
| custom_skill_not_installed | **PASS** |
| plugins_not_installed | **PASS** |
| harnesses_not_installed | **PASS** |
| docs_not_installed | **PASS** |

### Metrics

- `registerExitCode`: 0
- `registerCustomExitCode`: 0
- `registerReservedExitCode`: 1
- `showExitCode`: 0
- `showCustomExitCode`: 0
- `listExitCode`: 0
- `durationMs`: 5462

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| register | 0 | 900ms | 88337 | [`node-logs/project.manifest.registered.register.log`](node-logs/project.manifest.registered.register.log) |  |
| register-custom | 0 | 923ms | 88346 | [`node-logs/project.manifest.registered.register-custom.log`](node-logs/project.manifest.registered.register-custom.log) |  |
| register-reserved | 1 | 913ms | 88363 | [`node-logs/project.manifest.registered.register-reserved.log`](node-logs/project.manifest.registered.register-reserved.log) |  |
| show | 0 | 920ms | 88374 | [`node-logs/project.manifest.registered.show.log`](node-logs/project.manifest.registered.show.log) |  |
| show-custom | 0 | 887ms | 88385 | [`node-logs/project.manifest.registered.show-custom.log`](node-logs/project.manifest.registered.show-custom.log) |  |
| list | 0 | 883ms | 88413 | [`node-logs/project.manifest.registered.list.log`](node-logs/project.manifest.registered.list.log) |  |

### Published context

- `projectName`: `tg-project`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-manifest-17323010490689173685`
- `registrationDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-10482522127525919074/projects/tg-project`

**Node-process stdout**: [node-logs/project.manifest.registered.stdout.log](node-logs/project.manifest.registered.stdout.log)

---

