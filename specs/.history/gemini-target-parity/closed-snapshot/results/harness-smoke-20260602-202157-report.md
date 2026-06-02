# Validation report — 20260602-202157

**Overall**: PASSED  
**Nodes**: 9 (passed=9, failed=0, errored=0)

| Node | Status | Duration | Captured stdout |
|---|---|---|---|
| `env.prepared` | **PASS** | 2274ms | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 4402ms | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `gateway.up` | **PASS** | 10623ms | [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log) |
| `harness.command.coverage` | **PASS** | 23025ms | [node-logs/harness.command.coverage.stdout.log](node-logs/harness.command.coverage.stdout.log) |
| `harness.instance.materialized` | **PASS** | 5568ms | [node-logs/harness.instance.materialized.stdout.log](node-logs/harness.instance.materialized.stdout.log) |
| `harness.instance.removed` | **PASS** | 5067ms | [node-logs/harness.instance.removed.stdout.log](node-logs/harness.instance.removed.stdout.log) |
| `harness.template.uninstalled` | **PASS** | 6296ms | [node-logs/harness.template.uninstalled.stdout.log](node-logs/harness.template.uninstalled.stdout.log) |
| `harness.transitive.installed` | **PASS** | 18350ms | [node-logs/harness.transitive.installed.stdout.log](node-logs/harness.transitive.installed.stdout.log) |
| `servers.down` | **PASS** | 5789ms | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-06-02T20:21:57.873316Z`  
executor end: `2026-06-02T20:22:00.147731Z`  
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

- `registryPort`: 64270
- `gatewayPort`: 64271
- `durationMs`: 73

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/agent-home/.gemini`
- `registryPort`: `64270`
- `gatewayPort`: `64271`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-06-02T20:22:00.156319Z`  
executor end: `2026-06-02T20:22:04.558828Z`  
spawn exit code: 0

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 2332

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 2247ms | 79262 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/skill-manager/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `gateway.up` — **PASS**

executor start: `2026-06-02T20:22:04.560820Z`  
executor end: `2026-06-02T20:22:15.183735Z`  
spawn exit code: 0

### Assertions

| Name | Status |
|---|---|
| gateway_healthy | **PASS** |

### Metrics

- `port`: 64271
- `durationMs`: 8557

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-up | 0 | 8148ms | 79285 | [`node-logs/gateway.up.gateway-up.log`](node-logs/gateway.up.gateway-up.log) |  |

### Published context

- `baseUrl`: `http://127.0.0.1:64271`

**Node-process stdout**: [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log)

---

## `harness.command.coverage` — **PASS**

executor start: `2026-06-02T20:22:39.118755Z`  
executor end: `2026-06-02T20:23:02.143202Z`  
spawn exit code: 0

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
- `durationMs`: 21451

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| list | 0 | 3025ms | 79555 | [`node-logs/harness.command.coverage.list.log`](node-logs/harness.command.coverage.list.log) |  |
| show | 0 | 3518ms | 79590 | [`node-logs/harness.command.coverage.show.log`](node-logs/harness.command.coverage.show.log) |  |
| deps | 0 | 3524ms | 79612 | [`node-logs/harness.command.coverage.deps.log`](node-logs/harness.command.coverage.deps.log) |  |
| sync-refresh | 0 | 4373ms | 79647 | [`node-logs/harness.command.coverage.sync-refresh.log`](node-logs/harness.command.coverage.sync-refresh.log) |  |
| lock-status | 0 | 3718ms | 79677 | [`node-logs/harness.command.coverage.lock-status.log`](node-logs/harness.command.coverage.lock-status.log) |  |
| upgrade | 5 | 3256ms | 79704 | [`node-logs/harness.command.coverage.upgrade.log`](node-logs/harness.command.coverage.upgrade.log) |  |

**Node-process stdout**: [node-logs/harness.command.coverage.stdout.log](node-logs/harness.command.coverage.stdout.log)

---

## `harness.instance.materialized` — **PASS**

executor start: `2026-06-02T20:22:33.545501Z`  
executor end: `2026-06-02T20:22:39.113602Z`  
spawn exit code: 0

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
- `durationMs`: 3576

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| instantiate | 0 | 3539ms | 79504 | [`node-logs/harness.instance.materialized.instantiate.log`](node-logs/harness.instance.materialized.instantiate.log) |  |

### Published context

- `instanceId`: `smoke-instance`
- `claudeConfigDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/agent-harness-claude`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/agent-harness-codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/agent-harness-gemini`
- `projectDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/agent-harness-project`

**Node-process stdout**: [node-logs/harness.instance.materialized.stdout.log](node-logs/harness.instance.materialized.stdout.log)

---

## `harness.instance.removed` — **PASS**

executor start: `2026-06-02T20:23:02.180376Z`  
executor end: `2026-06-02T20:23:07.247275Z`  
spawn exit code: 0

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
- `durationMs`: 2848

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| harness-rm | 0 | 2812ms | 79746 | [`node-logs/harness.instance.removed.harness-rm.log`](node-logs/harness.instance.removed.harness-rm.log) |  |

**Node-process stdout**: [node-logs/harness.instance.removed.stdout.log](node-logs/harness.instance.removed.stdout.log)

---

## `harness.template.uninstalled` — **PASS**

executor start: `2026-06-02T20:23:07.249267Z`  
executor end: `2026-06-02T20:23:13.545025Z`  
spawn exit code: 0

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
- `durationMs`: 4241

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uninstall | 0 | 4222ms | 79791 | [`node-logs/harness.template.uninstalled.uninstall.log`](node-logs/harness.template.uninstalled.uninstall.log) |  |

**Node-process stdout**: [node-logs/harness.template.uninstalled.stdout.log](node-logs/harness.template.uninstalled.stdout.log)

---

## `harness.transitive.installed` — **PASS**

executor start: `2026-06-02T20:22:15.189374Z`  
executor end: `2026-06-02T20:22:33.539465Z`  
spawn exit code: 0

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
- `durationMs`: 16484

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 16420ms | 79345 | [`node-logs/harness.transitive.installed.install.log`](node-logs/harness.transitive.installed.install.log) |  |

### Published context

- `harnessName`: `smoke-harness`
- `templateDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5914013655479224237/harness-tpl`

**Node-process stdout**: [node-logs/harness.transitive.installed.stdout.log](node-logs/harness.transitive.installed.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-06-02T20:23:13.559039Z`  
executor end: `2026-06-02T20:23:19.348183Z`  
spawn exit code: 0

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 3895

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 3876ms | 79817 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

