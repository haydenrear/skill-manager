# Validation report — 20260712-180828

**Overall**: PASSED  
**Nodes**: 12 (passed=12, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 585ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 622ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `gateway.up` | **PASS** | 3142ms | [context/gateway.up.input.json](context/gateway.up.input.json) | [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log) |
| `harness.child.home.materialized` | **PASS** | 2682ms | [context/harness.child.home.materialized.input.json](context/harness.child.home.materialized.input.json) | [node-logs/harness.child.home.materialized.stdout.log](node-logs/harness.child.home.materialized.stdout.log) |
| `harness.child.home.removed` | **PASS** | 2646ms | [context/harness.child.home.removed.input.json](context/harness.child.home.removed.input.json) | [node-logs/harness.child.home.removed.stdout.log](node-logs/harness.child.home.removed.stdout.log) |
| `harness.command.coverage` | **PASS** | 6569ms | [context/harness.command.coverage.input.json](context/harness.command.coverage.input.json) | [node-logs/harness.command.coverage.stdout.log](node-logs/harness.command.coverage.stdout.log) |
| `harness.instance.materialized` | **PASS** | 1621ms | [context/harness.instance.materialized.input.json](context/harness.instance.materialized.input.json) | [node-logs/harness.instance.materialized.stdout.log](node-logs/harness.instance.materialized.stdout.log) |
| `harness.instance.removed` | **PASS** | 1655ms | [context/harness.instance.removed.input.json](context/harness.instance.removed.input.json) | [node-logs/harness.instance.removed.stdout.log](node-logs/harness.instance.removed.stdout.log) |
| `harness.template.uninstalled` | **PASS** | 2128ms | [context/harness.template.uninstalled.input.json](context/harness.template.uninstalled.input.json) | [node-logs/harness.template.uninstalled.stdout.log](node-logs/harness.template.uninstalled.stdout.log) |
| `harness.transitive.installed` | **PASS** | 8926ms | [context/harness.transitive.installed.input.json](context/harness.transitive.installed.input.json) | [node-logs/harness.transitive.installed.stdout.log](node-logs/harness.transitive.installed.stdout.log) |
| `resolver.cycles.verified` | **PASS** | 9441ms | [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json) | [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log) |
| `servers.down` | **PASS** | 1912ms | [context/servers.down.input.json](context/servers.down.input.json) | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:08:28.143216Z`  
executor end: `2026-07-12T18:08:28.728628Z`  
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

- `registryPort`: 65216
- `gatewayPort`: 65217
- `durationMs`: 28

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/agent-home/.gemini`
- `registryPort`: `65216`
- `gatewayPort`: `65217`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:08:38.171280Z`  
executor end: `2026-07-12T18:08:38.793008Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 35

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 29ms | 86986 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `gateway.up` — **PASS**

executor start: `2026-07-12T18:08:38.793897Z`  
executor end: `2026-07-12T18:08:41.935049Z`  
spawn exit code: 0

**Input context**: [context/gateway.up.input.json](context/gateway.up.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_healthy | **PASS** |

### Metrics

- `port`: 65217
- `durationMs`: 2543

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-up | 0 | 2191ms | 86993 | [`node-logs/gateway.up.gateway-up.log`](node-logs/gateway.up.gateway-up.log) |  |

### Published context

- `baseUrl`: `http://127.0.0.1:65217`

**Node-process stdout**: [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log)

---

## `harness.child.home.materialized` — **PASS**

executor start: `2026-07-12T18:08:52.484275Z`  
executor end: `2026-07-12T18:08:55.166242Z`  
spawn exit code: 0

**Input context**: [context/harness.child.home.materialized.input.json](context/harness.child.home.materialized.input.json)

### Assertions

| Name | Status |
|---|---|
| instantiate_ok | **PASS** |
| child_store_initialized | **PASS** |
| child_units_projected_from_parent | **PASS** |
| child_agent_homes_created | **PASS** |
| agent_projections_created | **PASS** |
| agent_projections_point_at_child_store | **PASS** |
| docs_bound_into_child_project_root | **PASS** |
| cli_shim_mirrored_into_child_home | **PASS** |
| harness_instance_lock_present | **PASS** |
| harness_instance_lock_uses_child_paths | **PASS** |
| parent_child_home_registry_present | **PASS** |
| child_home_registry_claims_skill_and_harness | **PASS** |
| parent_ledger_tracks_child_projection | **PASS** |
| plain_remove_rejects_child_home_claimed_skill | **PASS** |

### Metrics

- `exitCode`: 0
- `removeExitCode`: 1
- `durationMs`: 2097

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| instantiate-child-home | 0 | 1087ms | 87172 | [`node-logs/harness.child.home.materialized.instantiate-child-home.log`](node-logs/harness.child.home.materialized.instantiate-child-home.log) |  |
| remove-child-claimed-skill | 1 | 997ms | 87181 | [`node-logs/harness.child.home.materialized.remove-child-claimed-skill.log`](node-logs/harness.child.home.materialized.remove-child-claimed-skill.log) |  |

### Published context

- `childHomeDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/child-harness-project`
- `childSkillManagerHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/child-harness-project/.skill-manager`

**Node-process stdout**: [node-logs/harness.child.home.materialized.stdout.log](node-logs/harness.child.home.materialized.stdout.log)

---

## `harness.child.home.removed` — **PASS**

executor start: `2026-07-12T18:09:03.392875Z`  
executor end: `2026-07-12T18:09:06.038615Z`  
spawn exit code: 0

**Input context**: [context/harness.child.home.removed.input.json](context/harness.child.home.removed.input.json)

### Assertions

| Name | Status |
|---|---|
| harness_rm_ok | **PASS** |
| sandbox_removed | **PASS** |
| child_home_registry_removed | **PASS** |
| child_agent_projections_removed | **PASS** |
| child_store_survives_teardown | **PASS** |
| parent_ledgers_released_child_home_bindings | **PASS** |
| plain_remove_no_longer_sees_child_home_claim | **PASS** |

### Metrics

- `rmExitCode`: 0
- `dryRunRemoveExitCode`: 0
- `durationMs`: 2074

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| rm-child-home-harness | 0 | 1036ms | 87274 | [`node-logs/harness.child.home.removed.rm-child-home-harness.log`](node-logs/harness.child.home.removed.rm-child-home-harness.log) |  |
| remove-after-child-home-rm | 0 | 1022ms | 87283 | [`node-logs/harness.child.home.removed.remove-after-child-home-rm.log`](node-logs/harness.child.home.removed.remove-after-child-home-rm.log) |  |

**Node-process stdout**: [node-logs/harness.child.home.removed.stdout.log](node-logs/harness.child.home.removed.stdout.log)

---

## `harness.command.coverage` — **PASS**

executor start: `2026-07-12T18:08:55.166999Z`  
executor end: `2026-07-12T18:09:01.735582Z`  
spawn exit code: 0

**Input context**: [context/harness.command.coverage.input.json](context/harness.command.coverage.input.json)

### Assertions

| Name | Status |
|---|---|
| list_shows_harness_and_transitive_unit_kinds | **PASS** |
| show_displays_harness | **PASS** |
| deps_displays_harness_refs | **PASS** |
| lock_status_accounts_for_harness | **PASS** |
| upgrade_rejects_harness_with_sync_hint | **PASS** |

### Metrics

- `listExitCode`: 0
- `showExitCode`: 0
- `depsExitCode`: 0
- `refreshExitCode`: 0
- `lockExitCode`: 0
- `upgradeExitCode`: 5
- `durationMs`: 5970

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| list | 0 | 998ms | 87196 | [`node-logs/harness.command.coverage.list.log`](node-logs/harness.command.coverage.list.log) |  |
| show | 0 | 979ms | 87205 | [`node-logs/harness.command.coverage.show.log`](node-logs/harness.command.coverage.show.log) |  |
| deps | 0 | 997ms | 87217 | [`node-logs/harness.command.coverage.deps.log`](node-logs/harness.command.coverage.deps.log) |  |
| sync-refresh | 0 | 1006ms | 87226 | [`node-logs/harness.command.coverage.sync-refresh.log`](node-logs/harness.command.coverage.sync-refresh.log) |  |
| lock-status | 0 | 994ms | 87235 | [`node-logs/harness.command.coverage.lock-status.log`](node-logs/harness.command.coverage.lock-status.log) |  |
| upgrade | 5 | 980ms | 87244 | [`node-logs/harness.command.coverage.upgrade.log`](node-logs/harness.command.coverage.upgrade.log) |  |

**Node-process stdout**: [node-logs/harness.command.coverage.stdout.log](node-logs/harness.command.coverage.stdout.log)

---

## `harness.instance.materialized` — **PASS**

executor start: `2026-07-12T18:08:50.862977Z`  
executor end: `2026-07-12T18:08:52.483445Z`  
spawn exit code: 0

**Input context**: [context/harness.instance.materialized.input.json](context/harness.instance.materialized.input.json)

### Assertions

| Name | Status |
|---|---|
| instantiate_ok | **PASS** |
| claude_skill_at_claude_config_dir | **PASS** |
| codex_skill_at_codex_home | **PASS** |
| gemini_skill_at_gemini_home | **PASS** |
| claude_plugin_at_claude_config_dir | **PASS** |
| plugin_not_in_codex | **PASS** |
| plugin_not_in_gemini | **PASS** |
| docs_in_project_dir | **PASS** |
| claude_md_imports_both_docs | **PASS** |
| agents_md_imports_codex_doc | **PASS** |
| no_dead_letter_sandbox_skills | **PASS** |
| no_dead_letter_sandbox_plugins | **PASS** |
| lock_file_persisted | **PASS** |
| lock_carries_resolved_paths | **PASS** |
| ledger_binding_ids_are_harness_scoped | **PASS** |
| ledger_source_is_HARNESS | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1052

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| instantiate | 0 | 1039ms | 87152 | [`node-logs/harness.instance.materialized.instantiate.log`](node-logs/harness.instance.materialized.instantiate.log) |  |

### Published context

- `instanceId`: `smoke-instance`
- `claudeConfigDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/agent-harness-claude`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/agent-harness-codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/agent-harness-gemini`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/agent-harness-project`

**Node-process stdout**: [node-logs/harness.instance.materialized.stdout.log](node-logs/harness.instance.materialized.stdout.log)

---

## `harness.instance.removed` — **PASS**

executor start: `2026-07-12T18:09:01.736363Z`  
executor end: `2026-07-12T18:09:03.391684Z`  
spawn exit code: 0

**Input context**: [context/harness.instance.removed.input.json](context/harness.instance.removed.input.json)

### Assertions

| Name | Status |
|---|---|
| rm_ok | **PASS** |
| sandbox_dir_removed | **PASS** |
| harness_ids_purged_from_ledger | **PASS** |
| claude_skill_unmaterialized | **PASS** |
| codex_skill_unmaterialized | **PASS** |
| gemini_skill_unmaterialized | **PASS** |
| claude_plugin_unmaterialized | **PASS** |
| project_dir_doc_imports_cleared | **PASS** |
| template_survives_rm | **PASS** |
| transitive_units_survive_rm | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1074

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| harness-rm | 0 | 1057ms | 87259 | [`node-logs/harness.instance.removed.harness-rm.log`](node-logs/harness.instance.removed.harness-rm.log) |  |

**Node-process stdout**: [node-logs/harness.instance.removed.stdout.log](node-logs/harness.instance.removed.stdout.log)

---

## `harness.template.uninstalled` — **PASS**

executor start: `2026-07-12T18:09:06.039565Z`  
executor end: `2026-07-12T18:09:08.167553Z`  
spawn exit code: 0

**Input context**: [context/harness.template.uninstalled.input.json](context/harness.template.uninstalled.input.json)

### Assertions

| Name | Status |
|---|---|
| uninstall_ok | **PASS** |
| template_dir_removed | **PASS** |
| installed_record_removed | **PASS** |
| ledger_absent | **PASS** |
| transitive_units_survive_uninstall | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1534

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uninstall | 0 | 1526ms | 87306 | [`node-logs/harness.template.uninstalled.uninstall.log`](node-logs/harness.template.uninstalled.uninstall.log) |  |

**Node-process stdout**: [node-logs/harness.template.uninstalled.stdout.log](node-logs/harness.template.uninstalled.stdout.log)

---

## `harness.transitive.installed` — **PASS**

executor start: `2026-07-12T18:08:41.936020Z`  
executor end: `2026-07-12T18:08:50.862244Z`  
spawn exit code: 0

**Input context**: [context/harness.transitive.installed.input.json](context/harness.transitive.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_ok | **PASS** |
| harness_template_in_store | **PASS** |
| transitive_skill_installed | **PASS** |
| transitive_plugin_installed | **PASS** |
| transitive_doc_installed | **PASS** |
| transitive_cli_bundled | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 8336

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 8325ms | 87025 | [`node-logs/harness.transitive.installed.install.log`](node-logs/harness.transitive.installed.install.log) |  |

### Published context

- `harnessName`: `smoke-harness`
- `templateDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/harness-tpl`

**Node-process stdout**: [node-logs/harness.transitive.installed.stdout.log](node-logs/harness.transitive.installed.stdout.log)

---

## `resolver.cycles.verified` — **PASS**

executor start: `2026-07-12T18:08:28.729454Z`  
executor end: `2026-07-12T18:08:38.170276Z`  
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
- `durationMs`: 8865

### Inline logs

```
self-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=gateway pid=86915 log=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/self-cycle/home/gateway.log | ! reference cycle: semantic-self-unit -> semantic-self-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/self-cycle/repos/coord-self-repository | INSTALLED: semantic-self-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/self-cycle/home/skills/semantic-self-unit | ✓ units.lock.toml: wrote 1 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/self-cycle/home/units.lock.toml
two-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-alpha-unit -> semantic-beta-unit -> semantic-alpha-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/two-skill-cycle/repos/coord-alpha-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/two-skill-cycle/repos/coord-beta-repository | INSTALLED: semantic-alpha-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-alpha-unit | INSTALLED: semantic-beta-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-beta-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/two-skill-cycle/home/units.lock.toml
three-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-first-unit -> semantic-second-unit -> semantic-third-unit -> semantic-first-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/three-skill-cycle/repos/coord-first-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/three-skill-cycle/repos/coord-second-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/three-skill-cycle/repos/coord-third-repository | INSTALLED: semantic-first-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-first-unit | INSTALLED: semantic-second-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-second-unit | INSTALLED: semantic-third-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-third-unit | ✓ units.lock.toml: wrote 3 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/three-skill-cycle/home/units.lock.toml
skill-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-skill-unit -> semantic-plugin-unit -> semantic-skill-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-skill-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-plugin-repository | INSTALLED: semantic-skill-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/skill-plugin-cycle/home/skills/semantic-skill-unit | INSTALLED: semantic-plugin-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/skill-plugin-cycle/home/plugins/semantic-plugin-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/skill-plugin-cycle/home/units.lock.toml
plugin-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-left-plugin -> semantic-right-plugin -> semantic-left-plugin |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-left-plugin-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-right-plugin-repository | INSTALLED: semantic-left-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-left-plugin | INSTALLED: semantic-right-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-right-plugin | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-15319530240006582231/resolver-cycle-matrix/plugin-plugin-cycle/home/units.lock.toml
```

**Node-process stdout**: [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-07-12T18:09:08.168808Z`  
executor end: `2026-07-12T18:09:10.080272Z`  
spawn exit code: 0

**Input context**: [context/servers.down.input.json](context/servers.down.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 1332

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 1325ms | 87324 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

