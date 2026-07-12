# Validation report — 20260712-181108

**Overall**: PASSED  
**Nodes**: 5 (passed=5, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 607ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `project.dependencies.resolved` | **PASS** | 7714ms | [context/project.dependencies.resolved.input.json](context/project.dependencies.resolved.input.json) | [node-logs/project.dependencies.resolved.stdout.log](node-logs/project.dependencies.resolved.stdout.log) |
| `project.global.sync.cli.refresh` | **PASS** | 4971ms | [context/project.global.sync.cli.refresh.input.json](context/project.global.sync.cli.refresh.input.json) | [node-logs/project.global.sync.cli.refresh.stdout.log](node-logs/project.global.sync.cli.refresh.stdout.log) |
| `project.local.sync.cli.refresh` | **PASS** | 4165ms | [context/project.local.sync.cli.refresh.input.json](context/project.local.sync.cli.refresh.input.json) | [node-logs/project.local.sync.cli.refresh.stdout.log](node-logs/project.local.sync.cli.refresh.stdout.log) |
| `resolver.cycles.verified` | **PASS** | 8619ms | [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json) | [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:11:08.590085Z`  
executor end: `2026-07-12T18:11:09.197775Z`  
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

- `registryPort`: 65411
- `gatewayPort`: 65412
- `durationMs`: 29

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/agent-home/.gemini`
- `registryPort`: `65411`
- `gatewayPort`: `65412`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `project.dependencies.resolved` — **PASS**

executor start: `2026-07-12T18:11:17.818059Z`  
executor end: `2026-07-12T18:11:25.532231Z`  
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
- `durationMs`: 7120

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| resolve | 0 | 1846ms | 88658 | [`node-logs/project.dependencies.resolved.resolve.log`](node-logs/project.dependencies.resolved.resolve.log) |  |
| resolve-again | 0 | 1072ms | 88683 | [`node-logs/project.dependencies.resolved.resolve-again.log`](node-logs/project.dependencies.resolved.resolve-again.log) |  |
| sync | 0 | 1127ms | 88692 | [`node-logs/project.dependencies.resolved.sync.log`](node-logs/project.dependencies.resolved.sync.log) |  |
| show | 0 | 1003ms | 88701 | [`node-logs/project.dependencies.resolved.show.log`](node-logs/project.dependencies.resolved.show.log) |  |
| remove-claimed | 1 | 990ms | 88710 | [`node-logs/project.dependencies.resolved.remove-claimed.log`](node-logs/project.dependencies.resolved.remove-claimed.log) |  |
| project-remove | 0 | 1040ms | 88721 | [`node-logs/project.dependencies.resolved.project-remove.log`](node-logs/project.dependencies.resolved.project-remove.log) |  |

### Published context

- `projectName`: `tg-resolved-project`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-project-resolve-262605024587699061`
- `lockFile`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/projects/tg-resolved-project/project-lock.toml`

**Node-process stdout**: [node-logs/project.dependencies.resolved.stdout.log](node-logs/project.dependencies.resolved.stdout.log)

---

## `project.global.sync.cli.refresh` — **PASS**

executor start: `2026-07-12T18:11:25.533288Z`  
executor end: `2026-07-12T18:11:30.504624Z`  
spawn exit code: 0

**Input context**: [context/project.global.sync.cli.refresh.input.json](context/project.global.sync.cli.refresh.input.json)

### Assertions

| Name | Status |
|---|---|
| project_resolve_ok | **PASS** |
| upstream_cli_commit_ok | **PASS** |
| global_sync_ok | **PASS** |
| parent_cli_absent_before_sync | **PASS** |
| project_cli_absent_before_sync | **PASS** |
| parent_cli_installed_after_global_sync | **PASS** |
| project_child_cli_installed_after_global_sync | **PASS** |

### Metrics

- `durationMs`: 4391

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| resolve | 0 | 1820ms | 88743 | [`node-logs/project.global.sync.cli.refresh.resolve.log`](node-logs/project.global.sync.cli.refresh.resolve.log) |  |
| commit-cli | 0 | 32ms | 88774 | [`node-logs/project.global.sync.cli.refresh.commit-cli.log`](node-logs/project.global.sync.cli.refresh.commit-cli.log) |  |
| global-sync | 0 | 2413ms | 88777 | [`node-logs/project.global.sync.cli.refresh.global-sync.log`](node-logs/project.global.sync.cli.refresh.global-sync.log) |  |

**Node-process stdout**: [node-logs/project.global.sync.cli.refresh.stdout.log](node-logs/project.global.sync.cli.refresh.stdout.log)

---

## `project.local.sync.cli.refresh` — **PASS**

executor start: `2026-07-12T18:11:30.505401Z`  
executor end: `2026-07-12T18:11:34.670732Z`  
spawn exit code: 0

**Input context**: [context/project.local.sync.cli.refresh.input.json](context/project.local.sync.cli.refresh.input.json)

### Assertions

| Name | Status |
|---|---|
| project_resolve_ok | **PASS** |
| local_sync_ok | **PASS** |
| parent_cli_absent_before_sync | **PASS** |
| project_cli_absent_before_sync | **PASS** |
| parent_cli_installed_after_local_sync | **PASS** |
| project_child_cli_installed_after_local_sync | **PASS** |

### Metrics

- `durationMs`: 3583

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| resolve | 0 | 1573ms | 88814 | [`node-logs/project.local.sync.cli.refresh.resolve.log`](node-logs/project.local.sync.cli.refresh.resolve.log) |  |
| local-sync | 0 | 1974ms | 88825 | [`node-logs/project.local.sync.cli.refresh.local-sync.log`](node-logs/project.local.sync.cli.refresh.local-sync.log) |  |

**Node-process stdout**: [node-logs/project.local.sync.cli.refresh.stdout.log](node-logs/project.local.sync.cli.refresh.stdout.log)

---

## `resolver.cycles.verified` — **PASS**

executor start: `2026-07-12T18:11:09.198507Z`  
executor end: `2026-07-12T18:11:17.817028Z`  
spawn exit code: 0

**Input context**: [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json)

### Assertions

| Name | Status |
|---|---|
| self-cycle.terminated | **PASS** |
| self-cycle.install_exit_zero | **PASS** |
| self-cycle.cycle_path_reported | **PASS** |
| self-cycle.each_unit_installed_once | **PASS** |
| self-cycle.no_stage_dirs_leaked | **PASS** |
| two-skill-cycle.terminated | **PASS** |
| two-skill-cycle.install_exit_zero | **PASS** |
| two-skill-cycle.cycle_path_reported | **PASS** |
| two-skill-cycle.each_unit_installed_once | **PASS** |
| two-skill-cycle.no_stage_dirs_leaked | **PASS** |
| three-skill-cycle.terminated | **PASS** |
| three-skill-cycle.install_exit_zero | **PASS** |
| three-skill-cycle.cycle_path_reported | **PASS** |
| three-skill-cycle.each_unit_installed_once | **PASS** |
| three-skill-cycle.no_stage_dirs_leaked | **PASS** |
| skill-plugin-cycle.terminated | **PASS** |
| skill-plugin-cycle.install_exit_zero | **PASS** |
| skill-plugin-cycle.cycle_path_reported | **PASS** |
| skill-plugin-cycle.each_unit_installed_once | **PASS** |
| skill-plugin-cycle.no_stage_dirs_leaked | **PASS** |
| plugin-plugin-cycle.terminated | **PASS** |
| plugin-plugin-cycle.install_exit_zero | **PASS** |
| plugin-plugin-cycle.cycle_path_reported | **PASS** |
| plugin-plugin-cycle.each_unit_installed_once | **PASS** |
| plugin-plugin-cycle.no_stage_dirs_leaked | **PASS** |

### Metrics

- `cases`: 5
- `durationMs`: 8057

### Inline logs

```
self-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-self-unit -> semantic-self-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/self-cycle/repos/coord-self-repository | INSTALLED: semantic-self-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/self-cycle/home/skills/semantic-self-unit | ✓ units.lock.toml: wrote 1 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/self-cycle/home/units.lock.toml
two-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-alpha-unit -> semantic-beta-unit -> semantic-alpha-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/two-skill-cycle/repos/coord-alpha-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/two-skill-cycle/repos/coord-beta-repository | INSTALLED: semantic-alpha-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-alpha-unit | INSTALLED: semantic-beta-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-beta-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/two-skill-cycle/home/units.lock.toml
three-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-first-unit -> semantic-second-unit -> semantic-third-unit -> semantic-first-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/three-skill-cycle/repos/coord-first-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/three-skill-cycle/repos/coord-second-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/three-skill-cycle/repos/coord-third-repository | INSTALLED: semantic-first-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-first-unit | INSTALLED: semantic-second-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-second-unit | INSTALLED: semantic-third-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-third-unit | ✓ units.lock.toml: wrote 3 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/three-skill-cycle/home/units.lock.toml
skill-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-skill-unit -> semantic-plugin-unit -> semantic-skill-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-skill-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-plugin-repository | INSTALLED: semantic-skill-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/skill-plugin-cycle/home/skills/semantic-skill-unit | INSTALLED: semantic-plugin-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/skill-plugin-cycle/home/plugins/semantic-plugin-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/skill-plugin-cycle/home/units.lock.toml
plugin-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-left-plugin -> semantic-right-plugin -> semantic-left-plugin |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-left-plugin-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-right-plugin-repository | INSTALLED: semantic-left-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-left-plugin | INSTALLED: semantic-right-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-right-plugin | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5557616306404722598/resolver-cycle-matrix/plugin-plugin-cycle/home/units.lock.toml
```

**Node-process stdout**: [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log)

---

