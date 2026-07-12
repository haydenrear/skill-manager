# Validation report — 20260712-180502

**Overall**: PASSED  
**Nodes**: 12 (passed=12, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 590ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 610ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `gateway.up` | **PASS** | 2841ms | [context/gateway.up.input.json](context/gateway.up.input.json) | [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log) |
| `servers.down` | **PASS** | 1854ms | [context/servers.down.input.json](context/servers.down.input.json) | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |
| `source.fixture.installed` | **PASS** | 3730ms | [context/source.fixture.installed.input.json](context/source.fixture.installed.input.json) | [node-logs/source.fixture.installed.stdout.log](node-logs/source.fixture.installed.stdout.log) |
| `source.fixture.published` | **PASS** | 653ms | [context/source.fixture.published.input.json](context/source.fixture.published.input.json) | [node-logs/source.fixture.published.stdout.log](node-logs/source.fixture.published.stdout.log) |
| `source.sync.all_aggregates` | **PASS** | 3242ms | [context/source.sync.all_aggregates.input.json](context/source.sync.all_aggregates.input.json) | [node-logs/source.sync.all_aggregates.stdout.log](node-logs/source.sync.all_aggregates.stdout.log) |
| `source.sync.merges_clean` | **PASS** | 3266ms | [context/source.sync.merges_clean.input.json](context/source.sync.merges_clean.input.json) | [node-logs/source.sync.merges_clean.stdout.log](node-logs/source.sync.merges_clean.stdout.log) |
| `source.sync.no_merge_when_already_merged` | **PASS** | 3345ms | [context/source.sync.no_merge_when_already_merged.input.json](context/source.sync.no_merge_when_already_merged.input.json) | [node-logs/source.sync.no_merge_when_already_merged.stdout.log](node-logs/source.sync.no_merge_when_already_merged.stdout.log) |
| `source.sync.produces_conflict` | **PASS** | 3235ms | [context/source.sync.produces_conflict.input.json](context/source.sync.produces_conflict.input.json) | [node-logs/source.sync.produces_conflict.stdout.log](node-logs/source.sync.produces_conflict.stdout.log) |
| `source.sync.refuses_on_dirty` | **PASS** | 2980ms | [context/source.sync.refuses_on_dirty.input.json](context/source.sync.refuses_on_dirty.input.json) | [node-logs/source.sync.refuses_on_dirty.stdout.log](node-logs/source.sync.refuses_on_dirty.stdout.log) |
| `source.sync.refuses_without_from` | **PASS** | 2963ms | [context/source.sync.refuses_without_from.input.json](context/source.sync.refuses_without_from.input.json) | [node-logs/source.sync.refuses_without_from.stdout.log](node-logs/source.sync.refuses_without_from.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:05:02.190157Z`  
executor end: `2026-07-12T18:05:02.780912Z`  
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

- `registryPort`: 64711
- `gatewayPort`: 64712
- `durationMs`: 29

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829/agent-home/.gemini`
- `registryPort`: `64711`
- `gatewayPort`: `64712`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:05:02.781737Z`  
executor end: `2026-07-12T18:05:03.391666Z`  
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
| uv-sync | 0 | 31ms | 84711 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `gateway.up` — **PASS**

executor start: `2026-07-12T18:05:03.392382Z`  
executor end: `2026-07-12T18:05:06.233795Z`  
spawn exit code: 0

**Input context**: [context/gateway.up.input.json](context/gateway.up.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_healthy | **PASS** |

### Metrics

- `port`: 64712
- `durationMs`: 2246

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-up | 0 | 2203ms | 84724 | [`node-logs/gateway.up.gateway-up.log`](node-logs/gateway.up.gateway-up.log) |  |

### Published context

- `baseUrl`: `http://127.0.0.1:64712`

**Node-process stdout**: [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-07-12T18:05:29.653791Z`  
executor end: `2026-07-12T18:05:31.507507Z`  
spawn exit code: 0

**Input context**: [context/servers.down.input.json](context/servers.down.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 1282

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 1274ms | 85078 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

## `source.fixture.installed` — **PASS**

executor start: `2026-07-12T18:05:06.888238Z`  
executor end: `2026-07-12T18:05:10.618005Z`  
spawn exit code: 0

**Input context**: [context/source.fixture.installed.input.json](context/source.fixture.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_exit_zero | **PASS** |
| store_has_git_dir | **PASS** |
| source_json_written | **PASS** |
| source_kind_is_git | **PASS** |
| source_hash_matches_fixture_head | **PASS** |
| source_origin_present | **PASS** |
| source_git_ref_is_main | **PASS** |

### Metrics

- `durationMs`: 3223

### Published context

- `storeDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829/skills/source-tracking-fixture`
- `sourceJson`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829/installed/source-tracking-fixture.json`

**Node-process stdout**: [node-logs/source.fixture.installed.stdout.log](node-logs/source.fixture.installed.stdout.log)

---

## `source.fixture.published` — **PASS**

executor start: `2026-07-12T18:05:06.234550Z`  
executor end: `2026-07-12T18:05:06.887534Z`  
spawn exit code: 0

**Input context**: [context/source.fixture.published.input.json](context/source.fixture.published.input.json)

### Assertions

| Name | Status |
|---|---|
| git_init_ok | **PASS** |
| commit_ok | **PASS** |
| head_readable | **PASS** |

### Metrics

- `durationMs`: 86

### Published context

- `skillName`: `source-tracking-fixture`
- `skillDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829/source-fixture`
- `initialHash`: `d3ff307da0da1e05cebcf32358fd8682ecc24d9b`

**Node-process stdout**: [node-logs/source.fixture.published.stdout.log](node-logs/source.fixture.published.stdout.log)

---

## `source.sync.all_aggregates` — **PASS**

executor start: `2026-07-12T18:05:26.410070Z`  
executor end: `2026-07-12T18:05:29.652874Z`  
spawn exit code: 0

**Input context**: [context/source.sync.all_aggregates.input.json](context/source.sync.all_aggregates.input.json)

### Assertions

| Name | Status |
|---|---|
| exited_with_rc_7 | **PASS** |
| aggregate_summary_header_present | **PASS** |
| fixture_listed_with_merge_recipe | **PASS** |

### Metrics

- `durationMs`: 2669

**Node-process stdout**: [node-logs/source.sync.all_aggregates.stdout.log](node-logs/source.sync.all_aggregates.stdout.log)

---

## `source.sync.merges_clean` — **PASS**

executor start: `2026-07-12T18:05:16.562949Z`  
executor end: `2026-07-12T18:05:19.828526Z`  
spawn exit code: 0

**Input context**: [context/source.sync.merges_clean.input.json](context/source.sync.merges_clean.input.json)

### Assertions

| Name | Status |
|---|---|
| merge_exit_zero | **PASS** |
| local_edit_preserved | **PASS** |
| upstream_applied | **PASS** |
| source_hash_advanced | **PASS** |

### Metrics

- `durationMs`: 2784

**Node-process stdout**: [node-logs/source.sync.merges_clean.stdout.log](node-logs/source.sync.merges_clean.stdout.log)

---

## `source.sync.no_merge_when_already_merged` — **PASS**

executor start: `2026-07-12T18:05:19.829269Z`  
executor end: `2026-07-12T18:05:23.174023Z`  
spawn exit code: 0

**Input context**: [context/source.sync.no_merge_when_already_merged.input.json](context/source.sync.no_merge_when_already_merged.input.json)

### Assertions

| Name | Status |
|---|---|
| sync_exit_zero | **PASS** |
| output_has_no_merge_recipe | **PASS** |
| upstream_commit_already_contained | **PASS** |
| worktree_clean_before_sync | **PASS** |
| source_record_refreshed_to_current_head | **PASS** |

### Metrics

- `durationMs`: 2859

**Node-process stdout**: [node-logs/source.sync.no_merge_when_already_merged.stdout.log](node-logs/source.sync.no_merge_when_already_merged.stdout.log)

---

## `source.sync.produces_conflict` — **PASS**

executor start: `2026-07-12T18:05:23.174714Z`  
executor end: `2026-07-12T18:05:26.409351Z`  
spawn exit code: 0

**Input context**: [context/source.sync.produces_conflict.input.json](context/source.sync.produces_conflict.input.json)

### Assertions

| Name | Status |
|---|---|
| exited_with_rc_8 | **PASS** |
| conflict_logged_with_filename | **PASS** |
| conflict_markers_in_skill_md | **PASS** |
| working_tree_shows_unmerged | **PASS** |

### Metrics

- `durationMs`: 2668

**Node-process stdout**: [node-logs/source.sync.produces_conflict.stdout.log](node-logs/source.sync.produces_conflict.stdout.log)

---

## `source.sync.refuses_on_dirty` — **PASS**

executor start: `2026-07-12T18:05:10.618764Z`  
executor end: `2026-07-12T18:05:13.598706Z`  
spawn exit code: 0

**Input context**: [context/source.sync.refuses_on_dirty.input.json](context/source.sync.refuses_on_dirty.input.json)

### Assertions

| Name | Status |
|---|---|
| exited_with_rc_7 | **PASS** |
| banner_mentions_local_changes | **PASS** |
| banner_includes_git_fetch_recipe | **PASS** |
| banner_includes_merge_flag_recipe | **PASS** |
| local_edit_preserved | **PASS** |

### Metrics

- `durationMs`: 2429

### Published context

- `dirtyStoreDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-5350649601121143829/skills/source-tracking-fixture`

**Node-process stdout**: [node-logs/source.sync.refuses_on_dirty.stdout.log](node-logs/source.sync.refuses_on_dirty.stdout.log)

---

## `source.sync.refuses_without_from` — **PASS**

executor start: `2026-07-12T18:05:13.599836Z`  
executor end: `2026-07-12T18:05:16.562219Z`  
spawn exit code: 0

**Input context**: [context/source.sync.refuses_without_from.input.json](context/source.sync.refuses_without_from.input.json)

### Assertions

| Name | Status |
|---|---|
| exited_with_rc_7 | **PASS** |
| banner_mentions_extra_local_changes | **PASS** |
| banner_includes_merge_flag | **PASS** |
| banner_names_implicit_origin | **PASS** |

### Metrics

- `durationMs`: 2401

**Node-process stdout**: [node-logs/source.sync.refuses_without_from.stdout.log](node-logs/source.sync.refuses_without_from.stdout.log)

---

