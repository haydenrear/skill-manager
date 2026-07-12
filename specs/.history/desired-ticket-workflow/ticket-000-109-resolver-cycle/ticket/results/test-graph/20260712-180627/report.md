# Validation report — 20260712-180627

**Overall**: PASSED  
**Nodes**: 21 (passed=21, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `ci.logged.in` | **PASS** | 2065ms | [context/ci.logged.in.input.json](context/ci.logged.in.input.json) | [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log) |
| `echo.http.up` | **PASS** | 1436ms | [context/echo.http.up.input.json](context/echo.http.up.input.json) | [node-logs/echo.http.up.stdout.log](node-logs/echo.http.up.stdout.log) |
| `env.prepared` | **PASS** | 597ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 625ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `gateway.up` | **PASS** | 3155ms | [context/gateway.up.input.json](context/gateway.up.input.json) | [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log) |
| `hello.plugin.installed` | **PASS** | 9641ms | [context/hello.plugin.installed.input.json](context/hello.plugin.installed.input.json) | [node-logs/hello.plugin.installed.stdout.log](node-logs/hello.plugin.installed.stdout.log) |
| `hello.plugin.published` | **PASS** | 2071ms | [context/hello.plugin.published.input.json](context/hello.plugin.published.input.json) | [node-logs/hello.plugin.published.stdout.log](node-logs/hello.plugin.published.stdout.log) |
| `hello.plugin.registered.with.harness` | **PASS** | 880ms | [context/hello.plugin.registered.with.harness.input.json](context/hello.plugin.registered.with.harness.input.json) | [node-logs/hello.plugin.registered.with.harness.stdout.log](node-logs/hello.plugin.registered.with.harness.stdout.log) |
| `jwt.valid` | **PASS** | 1135ms | [context/jwt.valid.input.json](context/jwt.valid.input.json) | [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log) |
| `partner.skill.installed` | **PASS** | 3999ms | [context/partner.skill.installed.input.json](context/partner.skill.installed.input.json) | [node-logs/partner.skill.installed.stdout.log](node-logs/partner.skill.installed.stdout.log) |
| `plugin.command.coverage` | **PASS** | 7152ms | [context/plugin.command.coverage.input.json](context/plugin.command.coverage.input.json) | [node-logs/plugin.command.coverage.stdout.log](node-logs/plugin.command.coverage.stdout.log) |
| `plugin.contained.skill.not.addressable` | **PASS** | 2149ms | [context/plugin.contained.skill.not.addressable.input.json](context/plugin.contained.skill.not.addressable.input.json) | [node-logs/plugin.contained.skill.not.addressable.stdout.log](node-logs/plugin.contained.skill.not.addressable.stdout.log) |
| `plugin.markdown.import.targets` | **PASS** | 13569ms | [context/plugin.markdown.import.targets.input.json](context/plugin.markdown.import.targets.input.json) | [node-logs/plugin.markdown.import.targets.stdout.log](node-logs/plugin.markdown.import.targets.stdout.log) |
| `plugin.skill_script.force.sync` | **PASS** | 22084ms | [context/plugin.skill_script.force.sync.input.json](context/plugin.skill_script.force.sync.input.json) | [node-logs/plugin.skill_script.force.sync.stdout.log](node-logs/plugin.skill_script.force.sync.stdout.log) |
| `plugin.synced` | **PASS** | 6198ms | [context/plugin.synced.input.json](context/plugin.synced.input.json) | [node-logs/plugin.synced.stdout.log](node-logs/plugin.synced.stdout.log) |
| `plugin.uninstalled.mixed.orphans` | **PASS** | 4237ms | [context/plugin.uninstalled.mixed.orphans.input.json](context/plugin.uninstalled.mixed.orphans.input.json) | [node-logs/plugin.uninstalled.mixed.orphans.stdout.log](node-logs/plugin.uninstalled.mixed.orphans.stdout.log) |
| `postgres.up` | **PASS** | 1615ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `registry.up` | **PASS** | 4622ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `resolver.cycles.verified` | **PASS** | 9476ms | [context/resolver.cycles.verified.input.json](context/resolver.cycles.verified.input.json) | [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log) |
| `servers.down` | **PASS** | 4045ms | [context/servers.down.input.json](context/servers.down.input.json) | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |
| `umbrella.plugin.installed` | **PASS** | 7031ms | [context/umbrella.plugin.installed.input.json](context/umbrella.plugin.installed.input.json) | [node-logs/umbrella.plugin.installed.stdout.log](node-logs/umbrella.plugin.installed.stdout.log) |

## `ci.logged.in` — **PASS**

executor start: `2026-07-12T18:06:43.858572Z`  
executor end: `2026-07-12T18:06:45.923939Z`  
spawn exit code: 0

**Input context**: [context/ci.logged.in.input.json](context/ci.logged.in.input.json)

### Assertions

| Name | Status |
|---|---|
| token_cached | **PASS** |

### Metrics

- `durationMs`: 1486

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| login | 0 | 1478ms | 85892 | [`node-logs/ci.logged.in.login.log`](node-logs/ci.logged.in.login.log) |  |

### Published context

- `clientId`: `skill-manager-ci`

**Node-process stdout**: [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log)

---

## `echo.http.up` — **PASS**

executor start: `2026-07-12T18:06:50.840909Z`  
executor end: `2026-07-12T18:06:52.276945Z`  
spawn exit code: 0

**Input context**: [context/echo.http.up.input.json](context/echo.http.up.input.json)

### Assertions

| Name | Status |
|---|---|
| fixture_healthy | **PASS** |

### Metrics

- `port`: 64921
- `pid`: 85955
- `durationMs`: 490

### Published context

- `mcpUrl`: `http://127.0.0.1:64921/mcp`

**Node-process stdout**: [node-logs/echo.http.up.stdout.log](node-logs/echo.http.up.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:06:27.543645Z`  
executor end: `2026-07-12T18:06:28.140826Z`  
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

- `registryPort`: 64850
- `gatewayPort`: 64851
- `durationMs`: 30

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/agent-home/.gemini`
- `registryPort`: `64850`
- `gatewayPort`: `64851`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:06:47.059915Z`  
executor end: `2026-07-12T18:06:47.684905Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 37

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 30ms | 85932 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `gateway.up` — **PASS**

executor start: `2026-07-12T18:06:47.685685Z`  
executor end: `2026-07-12T18:06:50.840123Z`  
spawn exit code: 0

**Input context**: [context/gateway.up.input.json](context/gateway.up.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_healthy | **PASS** |

### Metrics

- `port`: 64851
- `durationMs`: 2559

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-up | 0 | 2209ms | 85939 | [`node-logs/gateway.up.gateway-up.log`](node-logs/gateway.up.gateway-up.log) |  |

### Published context

- `baseUrl`: `http://127.0.0.1:64851`

**Node-process stdout**: [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log)

---

## `hello.plugin.installed` — **PASS**

executor start: `2026-07-12T18:06:54.350138Z`  
executor end: `2026-07-12T18:07:03.991281Z`  
spawn exit code: 0

**Input context**: [context/hello.plugin.installed.input.json](context/hello.plugin.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_ok | **PASS** |
| plugin_manifest_present | **PASS** |
| plugin_toml_present | **PASS** |
| contained_skill_present | **PASS** |
| lock_advanced | **PASS** |
| plugin_marketplace_manifest_present | **PASS** |
| marketplace_lists_hello_plugin | **PASS** |
| marketplace_plugin_symlink_present | **PASS** |
| legacy_per_plugin_namespace_unused | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 9067

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 9056ms | 85977 | [`node-logs/hello.plugin.installed.install.log`](node-logs/hello.plugin.installed.install.log) |  |

### Published context

- `pluginDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/plugins/hello-plugin`
- `marketplaceDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/plugin-marketplace`

**Node-process stdout**: [node-logs/hello.plugin.installed.stdout.log](node-logs/hello.plugin.installed.stdout.log)

---

## `hello.plugin.published` — **PASS**

executor start: `2026-07-12T18:06:52.278171Z`  
executor end: `2026-07-12T18:06:54.349240Z`  
spawn exit code: 0

**Input context**: [context/hello.plugin.published.input.json](context/hello.plugin.published.input.json)

### Assertions

| Name | Status |
|---|---|
| published_ok | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1483

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| publish | 0 | 1474ms | 85962 | [`node-logs/hello.plugin.published.publish.log`](node-logs/hello.plugin.published.publish.log) |  |

### Published context

- `pluginName`: `hello-plugin`

**Node-process stdout**: [node-logs/hello.plugin.published.stdout.log](node-logs/hello.plugin.published.stdout.log)

---

## `hello.plugin.registered.with.harness` — **PASS**

executor start: `2026-07-12T18:07:03.992028Z`  
executor end: `2026-07-12T18:07:04.872929Z`  
spawn exit code: 0

**Input context**: [context/hello.plugin.registered.with.harness.input.json](context/hello.plugin.registered.with.harness.input.json)

### Assertions

| Name | Status |
|---|---|
| claude_plugin_list_ok | **PASS** |
| claude_lists_hello_plugin_from_skill_manager_marketplace | **PASS** |
| codex_config_lists_skill_manager_marketplace_local | **PASS** |

### Metrics

- `durationMs`: 292

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| claude-plugin-list | 0 | 280ms | 86081 | [`node-logs/hello.plugin.registered.with.harness.claude-plugin-list.log`](node-logs/hello.plugin.registered.with.harness.claude-plugin-list.log) |  |

**Node-process stdout**: [node-logs/hello.plugin.registered.with.harness.stdout.log](node-logs/hello.plugin.registered.with.harness.stdout.log)

---

## `jwt.valid` — **PASS**

executor start: `2026-07-12T18:06:45.924618Z`  
executor end: `2026-07-12T18:06:47.059104Z`  
spawn exit code: 0

**Input context**: [context/jwt.valid.input.json](context/jwt.valid.input.json)

### Assertions

| Name | Status |
|---|---|
| authed_me_is_200 | **PASS** |
| identity_matches_expected_user | **PASS** |
| unauthed_publish_is_401 | **PASS** |

### Metrics

- `me_status`: 200
- `unauth_publish_status`: 401
- `durationMs`: 195

**Node-process stdout**: [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log)

---

## `partner.skill.installed` — **PASS**

executor start: `2026-07-12T18:07:20.255275Z`  
executor end: `2026-07-12T18:07:24.254659Z`  
spawn exit code: 0

**Input context**: [context/partner.skill.installed.input.json](context/partner.skill.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_exit_zero | **PASS** |
| partner_skill_in_store | **PASS** |
| shared_mcp_server_still_registered_after_partner_install | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 3110

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 2574ms | 86266 | [`node-logs/partner.skill.installed.install.log`](node-logs/partner.skill.installed.install.log) |  |

### Published context

- `partnerSkillName`: `partner-skill`
- `sharedServerId`: `umbrella-plugin-server`

**Node-process stdout**: [node-logs/partner.skill.installed.stdout.log](node-logs/partner.skill.installed.stdout.log)

---

## `plugin.command.coverage` — **PASS**

executor start: `2026-07-12T18:07:24.256043Z`  
executor end: `2026-07-12T18:07:31.408526Z`  
spawn exit code: 0

**Input context**: [context/plugin.command.coverage.input.json](context/plugin.command.coverage.input.json)

### Assertions

| Name | Status |
|---|---|
| list_shows_plugin_kind_and_bindings_column | **PASS** |
| show_displays_plugin | **PASS** |
| deps_displays_plugin | **PASS** |
| lock_status_accounts_for_plugin | **PASS** |
| upgrade_resolves_plugin_target | **PASS** |

### Metrics

- `listExitCode`: 0
- `showExitCode`: 0
- `depsExitCode`: 0
- `refreshExitCode`: 0
- `lockExitCode`: 0
- `upgradeExitCode`: 5
- `durationMs`: 6543

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| list | 0 | 1075ms | 86297 | [`node-logs/plugin.command.coverage.list.log`](node-logs/plugin.command.coverage.list.log) |  |
| show | 0 | 1099ms | 86308 | [`node-logs/plugin.command.coverage.show.log`](node-logs/plugin.command.coverage.show.log) |  |
| deps | 0 | 1124ms | 86340 | [`node-logs/plugin.command.coverage.deps.log`](node-logs/plugin.command.coverage.deps.log) |  |
| sync-refresh | 0 | 1063ms | 86359 | [`node-logs/plugin.command.coverage.sync-refresh.log`](node-logs/plugin.command.coverage.sync-refresh.log) |  |
| lock-status | 0 | 1088ms | 86370 | [`node-logs/plugin.command.coverage.lock-status.log`](node-logs/plugin.command.coverage.lock-status.log) |  |
| upgrade | 5 | 1078ms | 86381 | [`node-logs/plugin.command.coverage.upgrade.log`](node-logs/plugin.command.coverage.upgrade.log) |  |

**Node-process stdout**: [node-logs/plugin.command.coverage.stdout.log](node-logs/plugin.command.coverage.stdout.log)

---

## `plugin.contained.skill.not.addressable` — **PASS**

executor start: `2026-07-12T18:07:04.874278Z`  
executor end: `2026-07-12T18:07:07.023930Z`  
spawn exit code: 0

**Input context**: [context/plugin.contained.skill.not.addressable.input.json](context/plugin.contained.skill.not.addressable.input.json)

### Assertions

| Name | Status |
|---|---|
| install_rejected | **PASS** |

### Metrics

- `exitCode`: 1
- `durationMs`: 1546

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install-contained | 1 | 1537ms | 86095 | [`node-logs/plugin.contained.skill.not.addressable.install-contained.log`](node-logs/plugin.contained.skill.not.addressable.install-contained.log) |  |

**Node-process stdout**: [node-logs/plugin.contained.skill.not.addressable.stdout.log](node-logs/plugin.contained.skill.not.addressable.stdout.log)

---

## `plugin.markdown.import.targets` — **PASS**

executor start: `2026-07-12T18:07:31.409411Z`  
executor end: `2026-07-12T18:07:44.978850Z`  
spawn exit code: 0

**Input context**: [context/plugin.markdown.import.targets.input.json](context/plugin.markdown.import.targets.input.json)

### Assertions

| Name | Status |
|---|---|
| all_installs_exit_zero | **PASS** |
| missing_plugin_violation_rendered | **PASS** |
| installed_doc_harness_plugin_imports_resolved | **PASS** |

### Metrics

- `durationMs`: 12978

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install-doc | 0 | 2493ms | 86399 | [`node-logs/plugin.markdown.import.targets.install-doc.log`](node-logs/plugin.markdown.import.targets.install-doc.log) |  |
| install-harness | 0 | 2518ms | 86422 | [`node-logs/plugin.markdown.import.targets.install-harness.log`](node-logs/plugin.markdown.import.targets.install-harness.log) |  |
| install-target-plugin | 0 | 3983ms | 86448 | [`node-logs/plugin.markdown.import.targets.install-target-plugin.log`](node-logs/plugin.markdown.import.targets.install-target-plugin.log) |  |
| install-source-plugin | 0 | 3961ms | 86482 | [`node-logs/plugin.markdown.import.targets.install-source-plugin.log`](node-logs/plugin.markdown.import.targets.install-source-plugin.log) |  |

**Node-process stdout**: [node-logs/plugin.markdown.import.targets.stdout.log](node-logs/plugin.markdown.import.targets.stdout.log)

---

## `plugin.skill_script.force.sync` — **PASS**

executor start: `2026-07-12T18:07:49.217669Z`  
executor end: `2026-07-12T18:08:11.301772Z`  
spawn exit code: 0

**Input context**: [context/plugin.skill_script.force.sync.input.json](context/plugin.skill_script.force.sync.input.json)

### Assertions

| Name | Status |
|---|---|
| install_exit_zero | **PASS** |
| sync_noop_exit_zero | **PASS** |
| sync_force_exit_zero | **PASS** |
| initial_plugin_script_run_count_one | **PASS** |
| plugin_skill_script_binary_present | **PASS** |
| plugin_pip_cli_binaries_present | **PASS** |
| plugin_skill_script_cli_lock_present | **PASS** |
| noop_plugin_sync_skipped_script | **PASS** |
| noop_plugin_sync_did_not_increment_counter | **PASS** |
| force_plugin_sync_incremented_counter | **PASS** |
| noop_plugin_sync_emitted_mcp_results | **PASS** |
| force_plugin_sync_emitted_mcp_results | **PASS** |
| plugin_level_mcp_registered | **PASS** |
| contained_skill_mcp_registered | **PASS** |

### Metrics

- `scriptRunCountAfterInstall`: 1
- `scriptRunCountAfterNoopSync`: 1
- `scriptRunCountAfterForceSync`: 2
- `durationMs`: 21214

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 4225ms | 86563 | [`node-logs/plugin.skill_script.force.sync.install.log`](node-logs/plugin.skill_script.force.sync.install.log) |  |
| sync_noop | 0 | 8118ms | 86602 | [`node-logs/plugin.skill_script.force.sync.sync_noop.log`](node-logs/plugin.skill_script.force.sync.sync_noop.log) |  |
| sync_force | 0 | 8329ms | 86679 | [`node-logs/plugin.skill_script.force.sync.sync_force.log`](node-logs/plugin.skill_script.force.sync.sync_force.log) |  |

**Node-process stdout**: [node-logs/plugin.skill_script.force.sync.stdout.log](node-logs/plugin.skill_script.force.sync.stdout.log)

---

## `plugin.synced` — **PASS**

executor start: `2026-07-12T18:07:14.056230Z`  
executor end: `2026-07-12T18:07:20.254061Z`  
spawn exit code: 0

**Input context**: [context/plugin.synced.input.json](context/plugin.synced.input.json)

### Assertions

| Name | Status |
|---|---|
| marketplace_symlink_was_drifted | **PASS** |
| marketplace_manifest_was_drifted | **PASS** |
| marketplace_symlink_restored | **PASS** |
| marketplace_manifest_restored | **PASS** |
| mcp_register_results_emitted | **PASS** |

### Metrics

- `exitCode`: 1
- `durationMs`: 5616

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| sync | 1 | 5604ms | 86172 | [`node-logs/plugin.synced.sync.log`](node-logs/plugin.synced.sync.log) |  |

**Node-process stdout**: [node-logs/plugin.synced.stdout.log](node-logs/plugin.synced.stdout.log)

---

## `plugin.uninstalled.mixed.orphans` — **PASS**

executor start: `2026-07-12T18:07:44.979647Z`  
executor end: `2026-07-12T18:07:49.216922Z`  
spawn exit code: 0

**Input context**: [context/plugin.uninstalled.mixed.orphans.input.json](context/plugin.uninstalled.mixed.orphans.input.json)

### Assertions

| Name | Status |
|---|---|
| uninstall_exit_zero | **PASS** |
| both_mcp_registered_pre_uninstall | **PASS** |
| plugin_store_dir_removed | **PASS** |
| plugin_installed_record_removed | **PASS** |
| plugin_marketplace_symlink_removed | **PASS** |
| plugin_marketplace_manifest_entry_removed | **PASS** |
| shared_mcp_server_kept_partner_still_claims_it | **PASS** |
| orphan_contained_mcp_server_unregistered | **PASS** |

### Metrics

- `plugin_level_cli_still_on_disk`: 0
- `contained_skill_cli_still_on_disk`: 0
- `exitCode`: 0
- `durationMs`: 3364

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uninstall | 0 | 2784ms | 86522 | [`node-logs/plugin.uninstalled.mixed.orphans.uninstall.log`](node-logs/plugin.uninstalled.mixed.orphans.uninstall.log) |  |

**Node-process stdout**: [node-logs/plugin.uninstalled.mixed.orphans.stdout.log](node-logs/plugin.uninstalled.mixed.orphans.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T18:06:37.618752Z`  
executor end: `2026-07-12T18:06:39.233656Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 1058

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-up | 0 | 239ms | 85867 | [`node-logs/postgres.up.compose-up.log`](node-logs/postgres.up.compose-up.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T18:06:39.235063Z`  
executor end: `2026-07-12T18:06:43.857760Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 85876
- `port`: 64850
- `durationMs`: 3644

### Published context

- `baseUrl`: `http://127.0.0.1:64850`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `resolver.cycles.verified` — **PASS**

executor start: `2026-07-12T18:06:28.141762Z`  
executor end: `2026-07-12T18:06:37.617689Z`  
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
- `durationMs`: 8913

### Inline logs

```
self-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=gateway pid=85759 log=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/self-cycle/home/gateway.log | ! reference cycle: semantic-self-unit -> semantic-self-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/self-cycle/repos/coord-self-repository | INSTALLED: semantic-self-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/self-cycle/home/skills/semantic-self-unit | ✓ units.lock.toml: wrote 1 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/self-cycle/home/units.lock.toml
two-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-alpha-unit -> semantic-beta-unit -> semantic-alpha-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/two-skill-cycle/repos/coord-alpha-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/two-skill-cycle/repos/coord-beta-repository | INSTALLED: semantic-alpha-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-alpha-unit | INSTALLED: semantic-beta-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/two-skill-cycle/home/skills/semantic-beta-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/two-skill-cycle/home/units.lock.toml
three-skill-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-first-unit -> semantic-second-unit -> semantic-third-unit -> semantic-first-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/three-skill-cycle/repos/coord-first-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/three-skill-cycle/repos/coord-second-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/three-skill-cycle/repos/coord-third-repository | INSTALLED: semantic-first-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-first-unit | INSTALLED: semantic-second-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-second-unit | INSTALLED: semantic-third-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/three-skill-cycle/home/skills/semantic-third-unit | ✓ units.lock.toml: wrote 3 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/three-skill-cycle/home/units.lock.toml
skill-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-skill-unit -> semantic-plugin-unit -> semantic-skill-unit |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-skill-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/skill-plugin-cycle/repos/coord-plugin-repository | INSTALLED: semantic-skill-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/skill-plugin-cycle/home/skills/semantic-skill-unit | INSTALLED: semantic-plugin-unit@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/skill-plugin-cycle/home/plugins/semantic-plugin-unit | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/skill-plugin-cycle/home/units.lock.toml
plugin-plugin-cycle terminated=true exit=0 cycle=true installedOnce=true noStages=true output=! reference cycle: semantic-left-plugin -> semantic-right-plugin -> semantic-left-plugin |        · source: file:/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-left-plugin-repository |        · source: /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/plugin-plugin-cycle/repos/coord-right-plugin-repository | INSTALLED: semantic-left-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-left-plugin | INSTALLED: semantic-right-plugin@0.1.0 -> /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/plugin-plugin-cycle/home/plugins/semantic-right-plugin | ✓ units.lock.toml: wrote 2 unit(s) → /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-7549455378202886437/resolver-cycle-matrix/plugin-plugin-cycle/home/units.lock.toml
```

**Node-process stdout**: [node-logs/resolver.cycles.verified.stdout.log](node-logs/resolver.cycles.verified.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-07-12T18:08:11.302513Z`  
executor end: `2026-07-12T18:08:15.347021Z`  
spawn exit code: 0

**Input context**: [context/servers.down.input.json](context/servers.down.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 3478

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 1339ms | 86768 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

## `umbrella.plugin.installed` — **PASS**

executor start: `2026-07-12T18:07:07.024808Z`  
executor end: `2026-07-12T18:07:14.055313Z`  
spawn exit code: 0

**Input context**: [context/umbrella.plugin.installed.input.json](context/umbrella.plugin.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_exit_zero | **PASS** |
| plugin_in_store | **PASS** |
| plugin_level_cli_binary_installed | **PASS** |
| contained_skill_cli_binary_installed | **PASS** |
| plugin_level_cli_in_lock | **PASS** |
| contained_skill_cli_in_lock | **PASS** |
| plugin_level_mcp_registered_with_gateway | **PASS** |
| contained_skill_mcp_registered_with_gateway | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 6108

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 5556ms | 86132 | [`node-logs/umbrella.plugin.installed.install.log`](node-logs/umbrella.plugin.installed.install.log) |  |

### Published context

- `pluginName`: `umbrella-plugin`
- `pluginServerId`: `umbrella-plugin-server`
- `skillServerId`: `umbrella-plugin-inner-server`
- `pluginCliBinary`: `pycowsay`
- `skillCliBinary`: `cowsay`

**Node-process stdout**: [node-logs/umbrella.plugin.installed.stdout.log](node-logs/umbrella.plugin.installed.stdout.log)

---

