# Validation report — 20260712-180542

**Overall**: PASSED  
**Nodes**: 10 (passed=10, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 590ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 614ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `gateway.up` | **PASS** | 2828ms | [context/gateway.up.input.json](context/gateway.up.input.json) | [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log) |
| `gls.conflict` | **PASS** | 3313ms | [context/gls.conflict.input.json](context/gls.conflict.input.json) | [node-logs/gls.conflict.stdout.log](node-logs/gls.conflict.stdout.log) |
| `gls.fast_forwards` | **PASS** | 3214ms | [context/gls.fast_forwards.input.json](context/gls.fast_forwards.input.json) | [node-logs/gls.fast_forwards.stdout.log](node-logs/gls.fast_forwards.stdout.log) |
| `gls.fixture.bootstrapped` | **PASS** | 654ms | [context/gls.fixture.bootstrapped.input.json](context/gls.fixture.bootstrapped.input.json) | [node-logs/gls.fixture.bootstrapped.stdout.log](node-logs/gls.fixture.bootstrapped.stdout.log) |
| `gls.fixture.installed` | **PASS** | 3697ms | [context/gls.fixture.installed.input.json](context/gls.fixture.installed.input.json) | [node-logs/gls.fixture.installed.stdout.log](node-logs/gls.fixture.installed.stdout.log) |
| `gls.merges_after_local_commit` | **PASS** | 3302ms | [context/gls.merges_after_local_commit.input.json](context/gls.merges_after_local_commit.input.json) | [node-logs/gls.merges_after_local_commit.stdout.log](node-logs/gls.merges_after_local_commit.stdout.log) |
| `gls.refuses_on_local_commit` | **PASS** | 3220ms | [context/gls.refuses_on_local_commit.input.json](context/gls.refuses_on_local_commit.input.json) | [node-logs/gls.refuses_on_local_commit.stdout.log](node-logs/gls.refuses_on_local_commit.stdout.log) |
| `servers.down` | **PASS** | 1892ms | [context/servers.down.input.json](context/servers.down.input.json) | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:05:42.490853Z`  
executor end: `2026-07-12T18:05:43.080753Z`  
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

- `registryPort`: 64790
- `gatewayPort`: 64791
- `durationMs`: 31

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4287391369915476779`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4287391369915476779/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4287391369915476779/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4287391369915476779/agent-home/.gemini`
- `registryPort`: `64790`
- `gatewayPort`: `64791`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:05:43.081557Z`  
executor end: `2026-07-12T18:05:43.695525Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 38

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 30ms | 85188 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `gateway.up` — **PASS**

executor start: `2026-07-12T18:05:43.696239Z`  
executor end: `2026-07-12T18:05:46.524961Z`  
spawn exit code: 0

**Input context**: [context/gateway.up.input.json](context/gateway.up.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_healthy | **PASS** |

### Metrics

- `port`: 64791
- `durationMs`: 2245

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-up | 0 | 2204ms | 85195 | [`node-logs/gateway.up.gateway-up.log`](node-logs/gateway.up.gateway-up.log) |  |

### Published context

- `baseUrl`: `http://127.0.0.1:64791`

**Node-process stdout**: [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log)

---

## `gls.conflict` — **PASS**

executor start: `2026-07-12T18:06:00.617220Z`  
executor end: `2026-07-12T18:06:03.930694Z`  
spawn exit code: 0

**Input context**: [context/gls.conflict.input.json](context/gls.conflict.input.json)

### Assertions

| Name | Status |
|---|---|
| exited_with_rc_8 | **PASS** |
| conflict_logged_with_filename | **PASS** |
| conflict_markers_in_skill_md | **PASS** |
| working_tree_shows_unmerged | **PASS** |

### Metrics

- `durationMs`: 2740

**Node-process stdout**: [node-logs/gls.conflict.stdout.log](node-logs/gls.conflict.stdout.log)

---

## `gls.fast_forwards` — **PASS**

executor start: `2026-07-12T18:05:50.878539Z`  
executor end: `2026-07-12T18:05:54.092977Z`  
spawn exit code: 0

**Input context**: [context/gls.fast_forwards.input.json](context/gls.fast_forwards.input.json)

### Assertions

| Name | Status |
|---|---|
| sync_exit_zero | **PASS** |
| upstream_file_landed_in_store | **PASS** |
| install_head_at_upstream | **PASS** |
| source_record_hash_refreshed | **PASS** |

### Metrics

- `durationMs`: 2728

**Node-process stdout**: [node-logs/gls.fast_forwards.stdout.log](node-logs/gls.fast_forwards.stdout.log)

---

## `gls.fixture.bootstrapped` — **PASS**

executor start: `2026-07-12T18:05:46.525712Z`  
executor end: `2026-07-12T18:05:47.179427Z`  
spawn exit code: 0

**Input context**: [context/gls.fixture.bootstrapped.input.json](context/gls.fixture.bootstrapped.input.json)

### Assertions

| Name | Status |
|---|---|
| git_init_ok | **PASS** |
| commit_ok | **PASS** |
| head_readable | **PASS** |

### Metrics

- `durationMs`: 89

### Published context

- `skillName`: `git-latest-fixture`
- `skillDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4287391369915476779/gls-fixture`
- `initialHash`: `6e5ac7d057c61229216b03445daa455799128da6`

**Node-process stdout**: [node-logs/gls.fixture.bootstrapped.stdout.log](node-logs/gls.fixture.bootstrapped.stdout.log)

---

## `gls.fixture.installed` — **PASS**

executor start: `2026-07-12T18:05:47.180196Z`  
executor end: `2026-07-12T18:05:50.877790Z`  
spawn exit code: 0

**Input context**: [context/gls.fixture.installed.input.json](context/gls.fixture.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_exit_zero | **PASS** |
| store_has_git_dir | **PASS** |
| source_json_written | **PASS** |
| source_kind_is_git | **PASS** |
| source_hash_matches_fixture_head | **PASS** |
| source_origin_pins_fixture_path | **PASS** |
| source_git_ref_is_main | **PASS** |

### Metrics

- `durationMs`: 3213

### Published context

- `storeDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-4287391369915476779/skills/git-latest-fixture`

**Node-process stdout**: [node-logs/gls.fixture.installed.stdout.log](node-logs/gls.fixture.installed.stdout.log)

---

## `gls.merges_after_local_commit` — **PASS**

executor start: `2026-07-12T18:05:57.314161Z`  
executor end: `2026-07-12T18:06:00.616426Z`  
spawn exit code: 0

**Input context**: [context/gls.merges_after_local_commit.input.json](context/gls.merges_after_local_commit.input.json)

### Assertions

| Name | Status |
|---|---|
| merge_exit_zero | **PASS** |
| local_commit_survived_merge | **PASS** |
| upstream_commit_applied | **PASS** |
| working_tree_clean_after_merge | **PASS** |
| install_head_advanced | **PASS** |
| source_record_hash_refreshed | **PASS** |

### Metrics

- `durationMs`: 2809

**Node-process stdout**: [node-logs/gls.merges_after_local_commit.stdout.log](node-logs/gls.merges_after_local_commit.stdout.log)

---

## `gls.refuses_on_local_commit` — **PASS**

executor start: `2026-07-12T18:05:54.093721Z`  
executor end: `2026-07-12T18:05:57.313448Z`  
spawn exit code: 0

**Input context**: [context/gls.refuses_on_local_commit.input.json](context/gls.refuses_on_local_commit.input.json)

### Assertions

| Name | Status |
|---|---|
| exited_with_rc_7 | **PASS** |
| banner_mentions_extra_local_changes | **PASS** |
| upstream_commit_not_contained_before_sync | **PASS** |
| recipe_preserves_git_latest_flag | **PASS** |
| local_commit_preserved | **PASS** |

### Metrics

- `durationMs`: 2649

**Node-process stdout**: [node-logs/gls.refuses_on_local_commit.stdout.log](node-logs/gls.refuses_on_local_commit.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-07-12T18:06:03.931393Z`  
executor end: `2026-07-12T18:06:05.823332Z`  
spawn exit code: 0

**Input context**: [context/servers.down.input.json](context/servers.down.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 1304

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 1295ms | 85516 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

