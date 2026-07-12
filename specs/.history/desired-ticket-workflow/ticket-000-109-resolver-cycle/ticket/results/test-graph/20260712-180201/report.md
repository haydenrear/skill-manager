# Validation report — 20260712-180201

**Overall**: PASSED  
**Nodes**: 24 (passed=24, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `ci.logged.in` | **PASS** | 2176ms | [context/ci.logged.in.input.json](context/ci.logged.in.input.json) | [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log) |
| `env.hyper.prepared` | **PASS** | 598ms | [context/env.hyper.prepared.input.json](context/env.hyper.prepared.input.json) | [node-logs/env.hyper.prepared.stdout.log](node-logs/env.hyper.prepared.stdout.log) |
| `env.prepared` | **PASS** | 609ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 633ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `gateway.up` | **PASS** | 3454ms | [context/gateway.up.input.json](context/gateway.up.input.json) | [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log) |
| `hyper.checkout` | **PASS** | 2607ms | [context/hyper.checkout.input.json](context/hyper.checkout.input.json) | [node-logs/hyper.checkout.stdout.log](node-logs/hyper.checkout.stdout.log) |
| `hyper.cli.tbquery` | **PASS** | 2354ms | [context/hyper.cli.tbquery.input.json](context/hyper.cli.tbquery.input.json) | [node-logs/hyper.cli.tbquery.stdout.log](node-logs/hyper.cli.tbquery.stdout.log) |
| `hyper.installed` | **PASS** | 26178ms | [context/hyper.installed.input.json](context/hyper.installed.input.json) | [node-logs/hyper.installed.stdout.log](node-logs/hyper.installed.stdout.log) |
| `hyper.published` | **PASS** | 8070ms | [context/hyper.published.input.json](context/hyper.published.input.json) | [node-logs/hyper.published.stdout.log](node-logs/hyper.published.stdout.log) |
| `hyper.registry.no.tarball` | **PASS** | 768ms | [context/hyper.registry.no.tarball.input.json](context/hyper.registry.no.tarball.input.json) | [node-logs/hyper.registry.no.tarball.stdout.log](node-logs/hyper.registry.no.tarball.stdout.log) |
| `hyper.runpod.deployed` | **PASS** | 1404ms | [context/hyper.runpod.deployed.input.json](context/hyper.runpod.deployed.input.json) | [node-logs/hyper.runpod.deployed.stdout.log](node-logs/hyper.runpod.deployed.stdout.log) |
| `hyper.runpod.registered` | **PASS** | 1414ms | [context/hyper.runpod.registered.input.json](context/hyper.runpod.registered.input.json) | [node-logs/hyper.runpod.registered.stdout.log](node-logs/hyper.runpod.registered.stdout.log) |
| `hyper.runpod.tool.invoked` | **PASS** | 2641ms | [context/hyper.runpod.tool.invoked.input.json](context/hyper.runpod.tool.invoked.input.json) | [node-logs/hyper.runpod.tool.invoked.stdout.log](node-logs/hyper.runpod.tool.invoked.stdout.log) |
| `hyper.runpod.tools` | **PASS** | 1404ms | [context/hyper.runpod.tools.input.json](context/hyper.runpod.tools.input.json) | [node-logs/hyper.runpod.tools.stdout.log](node-logs/hyper.runpod.tools.stdout.log) |
| `hyper.server.hash.matches.install` | **PASS** | 764ms | [context/hyper.server.hash.matches.install.input.json](context/hyper.server.hash.matches.install.input.json) | [node-logs/hyper.server.hash.matches.install.stdout.log](node-logs/hyper.server.hash.matches.install.stdout.log) |
| `hyper.source.recorded` | **PASS** | 603ms | [context/hyper.source.recorded.input.json](context/hyper.source.recorded.input.json) | [node-logs/hyper.source.recorded.stdout.log](node-logs/hyper.source.recorded.stdout.log) |
| `hyper.sync.clean.noop` | **PASS** | 5059ms | [context/hyper.sync.clean.noop.input.json](context/hyper.sync.clean.noop.input.json) | [node-logs/hyper.sync.clean.noop.stdout.log](node-logs/hyper.sync.clean.noop.stdout.log) |
| `hyper.sync.merges.after.commit` | **PASS** | 4235ms | [context/hyper.sync.merges.after.commit.input.json](context/hyper.sync.merges.after.commit.input.json) | [node-logs/hyper.sync.merges.after.commit.stdout.log](node-logs/hyper.sync.merges.after.commit.stdout.log) |
| `hyper.sync.refuses.on.local.commit` | **PASS** | 3881ms | [context/hyper.sync.refuses.on.local.commit.input.json](context/hyper.sync.refuses.on.local.commit.input.json) | [node-logs/hyper.sync.refuses.on.local.commit.stdout.log](node-logs/hyper.sync.refuses.on.local.commit.stdout.log) |
| `jwt.valid` | **PASS** | 1157ms | [context/jwt.valid.input.json](context/jwt.valid.input.json) | [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log) |
| `postgres.down` | **PASS** | 806ms | [context/postgres.down.input.json](context/postgres.down.input.json) | [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log) |
| `postgres.up` | **PASS** | 1106ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `registry.up` | **PASS** | 6752ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `servers.down` | **PASS** | 4180ms | [context/servers.down.input.json](context/servers.down.input.json) | [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log) |

## `ci.logged.in` — **PASS**

executor start: `2026-07-12T18:02:10.478363Z`  
executor end: `2026-07-12T18:02:12.654277Z`  
spawn exit code: 0

**Input context**: [context/ci.logged.in.input.json](context/ci.logged.in.input.json)

### Assertions

| Name | Status |
|---|---|
| token_cached | **PASS** |

### Metrics

- `durationMs`: 1555

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| login | 0 | 1546ms | 83223 | [`node-logs/ci.logged.in.login.log`](node-logs/ci.logged.in.login.log) |  |

### Published context

- `clientId`: `skill-manager-ci`

**Node-process stdout**: [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log)

---

## `env.hyper.prepared` — **PASS**

executor start: `2026-07-12T18:02:03.126794Z`  
executor end: `2026-07-12T18:02:03.724322Z`  
spawn exit code: 0

**Input context**: [context/env.hyper.prepared.input.json](context/env.hyper.prepared.input.json)

### Metrics

- `durationMs`: 0

### Published context

- `allowFileUpload`: `false`

**Node-process stdout**: [node-logs/env.hyper.prepared.stdout.log](node-logs/env.hyper.prepared.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:02:01.410085Z`  
executor end: `2026-07-12T18:02:02.019803Z`  
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

- `registryPort`: 64407
- `gatewayPort`: 64408
- `durationMs`: 30

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-173799915506936706`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-173799915506936706/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-173799915506936706/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-173799915506936706/agent-home/.gemini`
- `registryPort`: `64407`
- `gatewayPort`: `64408`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:02:13.813107Z`  
executor end: `2026-07-12T18:02:14.446282Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 41

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 34ms | 83244 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `gateway.up` — **PASS**

executor start: `2026-07-12T18:02:14.447105Z`  
executor end: `2026-07-12T18:02:17.901118Z`  
spawn exit code: 0

**Input context**: [context/gateway.up.input.json](context/gateway.up.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_healthy | **PASS** |

### Metrics

- `port`: 64408
- `durationMs`: 2810

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-up | 0 | 2457ms | 83251 | [`node-logs/gateway.up.gateway-up.log`](node-logs/gateway.up.gateway-up.log) |  |

### Published context

- `baseUrl`: `http://127.0.0.1:64408`

**Node-process stdout**: [node-logs/gateway.up.stdout.log](node-logs/gateway.up.stdout.log)

---

## `hyper.checkout` — **PASS**

executor start: `2026-07-12T18:02:17.902056Z`  
executor end: `2026-07-12T18:02:20.509534Z`  
spawn exit code: 0

**Input context**: [context/hyper.checkout.input.json](context/hyper.checkout.input.json)

### Assertions

| Name | Status |
|---|---|
| clone_ok | **PASS** |
| skill_md_present | **PASS** |
| manifest_present | **PASS** |

### Metrics

- `ref`: 0
- `durationMs`: 2009

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| git-clone | 0 | 2000ms | 83270 | [`node-logs/hyper.checkout.git-clone.log`](node-logs/hyper.checkout.git-clone.log) |  |

### Published context

- `skillDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-173799915506936706/hyper-checkout`
- `source`: `github:https://github.com/haydenrear/hyper-experiments-skill.git@main`

**Node-process stdout**: [node-logs/hyper.checkout.stdout.log](node-logs/hyper.checkout.stdout.log)

---

## `hyper.cli.tbquery` — **PASS**

executor start: `2026-07-12T18:02:55.534416Z`  
executor end: `2026-07-12T18:02:57.888469Z`  
spawn exit code: 0

**Input context**: [context/hyper.cli.tbquery.input.json](context/hyper.cli.tbquery.input.json)

### Assertions

| Name | Status |
|---|---|
| tb_query_bundled_under_home | **PASS** |
| tb_query_runs_help | **PASS** |

### Metrics

- `helpExitCode`: 0
- `durationMs`: 1776

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| tb-query-help | 0 | 1769ms | 83426 | [`node-logs/hyper.cli.tbquery.tb-query-help.log`](node-logs/hyper.cli.tbquery.tb-query-help.log) |  |

**Node-process stdout**: [node-logs/hyper.cli.tbquery.stdout.log](node-logs/hyper.cli.tbquery.stdout.log)

---

## `hyper.installed` — **PASS**

executor start: `2026-07-12T18:02:29.355170Z`  
executor end: `2026-07-12T18:02:55.533682Z`  
spawn exit code: 0

**Input context**: [context/hyper.installed.input.json](context/hyper.installed.input.json)

### Assertions

| Name | Status |
|---|---|
| install_ok | **PASS** |
| skill_md_present | **PASS** |
| skill_manager_toml_present | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 25581

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install | 0 | 25569ms | 83323 | [`node-logs/hyper.installed.install.log`](node-logs/hyper.installed.install.log) |  |

### Published context

- `skillDir`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-173799915506936706/skills/hyper-experiments`

**Node-process stdout**: [node-logs/hyper.installed.stdout.log](node-logs/hyper.installed.stdout.log)

---

## `hyper.published` — **PASS**

executor start: `2026-07-12T18:02:20.510485Z`  
executor end: `2026-07-12T18:02:28.580018Z`  
spawn exit code: 0

**Input context**: [context/hyper.published.input.json](context/hyper.published.input.json)

### Assertions

| Name | Status |
|---|---|
| upload_tarball_rejected | **PASS** |
| github_register_ok | **PASS** |

### Metrics

- `rejectExitCode`: 1
- `registerExitCode`: 0
- `durationMs`: 7470

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| publish-tarball-rejected | 1 | 1575ms | 83281 | [`node-logs/hyper.published.publish-tarball-rejected.log`](node-logs/hyper.published.publish-tarball-rejected.log) |  |
| publish | 0 | 5886ms | 83291 | [`node-logs/hyper.published.publish.log`](node-logs/hyper.published.publish.log) |  |

### Published context

- `skillName`: `hyper-experiments`

**Node-process stdout**: [node-logs/hyper.published.stdout.log](node-logs/hyper.published.stdout.log)

---

## `hyper.registry.no.tarball` — **PASS**

executor start: `2026-07-12T18:02:28.582029Z`  
executor end: `2026-07-12T18:02:29.350873Z`  
spawn exit code: 0

**Input context**: [context/hyper.registry.no.tarball.input.json](context/hyper.registry.no.tarball.input.json)

### Assertions

| Name | Status |
|---|---|
| no_tarball_on_disk | **PASS** |
| all_rows_github_pointer | **PASS** |

### Metrics

- `tarballsOnDisk`: 0
- `hyperRows`: 1
- `nullSha`: 1
- `nullSize`: 1
- `withGithubUrl`: 1
- `withGitSha`: 1
- `durationMs`: 209

**Node-process stdout**: [node-logs/hyper.registry.no.tarball.stdout.log](node-logs/hyper.registry.no.tarball.stdout.log)

---

## `hyper.runpod.deployed` — **PASS**

executor start: `2026-07-12T18:02:59.304801Z`  
executor end: `2026-07-12T18:03:00.708688Z`  
spawn exit code: 0

**Input context**: [context/hyper.runpod.deployed.input.json](context/hyper.runpod.deployed.input.json)

### Assertions

| Name | Status |
|---|---|
| runpod_deployed_via_env_init | **PASS** |
| scope_global_sticky | **PASS** |

### Metrics

- `durationMs`: 547

**Node-process stdout**: [node-logs/hyper.runpod.deployed.stdout.log](node-logs/hyper.runpod.deployed.stdout.log)

---

## `hyper.runpod.registered` — **PASS**

executor start: `2026-07-12T18:02:57.889211Z`  
executor end: `2026-07-12T18:02:59.303977Z`  
spawn exit code: 0

**Input context**: [context/hyper.runpod.registered.input.json](context/hyper.runpod.registered.input.json)

### Assertions

| Name | Status |
|---|---|
| runpod_listed_via_mcp | **PASS** |
| default_scope_global_sticky | **PASS** |

### Metrics

- `durationMs`: 558

**Node-process stdout**: [node-logs/hyper.runpod.registered.stdout.log](node-logs/hyper.runpod.registered.stdout.log)

---

## `hyper.runpod.tool.invoked` — **PASS**

executor start: `2026-07-12T18:03:02.114557Z`  
executor end: `2026-07-12T18:03:04.755685Z`  
spawn exit code: 0

**Input context**: [context/hyper.runpod.tool.invoked.input.json](context/hyper.runpod.tool.invoked.input.json)

### Assertions

| Name | Status |
|---|---|
| describe_returned_list_endpoints | **PASS** |
| invoke_no_error | **PASS** |
| invoke_has_content | **PASS** |

### Metrics

- `contentItems`: 1
- `durationMs`: 1791

**Node-process stdout**: [node-logs/hyper.runpod.tool.invoked.stdout.log](node-logs/hyper.runpod.tool.invoked.stdout.log)

---

## `hyper.runpod.tools` — **PASS**

executor start: `2026-07-12T18:03:00.709604Z`  
executor end: `2026-07-12T18:03:02.113720Z`  
spawn exit code: 0

**Input context**: [context/hyper.runpod.tools.input.json](context/hyper.runpod.tools.input.json)

### Assertions

| Name | Status |
|---|---|
| runpod_returned_tools | **PASS** |
| canary_tool_present | **PASS** |

### Metrics

- `toolCount`: 50
- `durationMs`: 554

### Published context

- `toolPath`: `runpod/list-endpoints`
- `toolCount`: `50`

**Node-process stdout**: [node-logs/hyper.runpod.tools.stdout.log](node-logs/hyper.runpod.tools.stdout.log)

---

## `hyper.server.hash.matches.install` — **PASS**

executor start: `2026-07-12T18:03:05.360639Z`  
executor end: `2026-07-12T18:03:06.124184Z`  
spawn exit code: 0

**Input context**: [context/hyper.server.hash.matches.install.input.json](context/hyper.server.hash.matches.install.input.json)

### Assertions

| Name | Status |
|---|---|
| source_record_hash_matches_install_head | **PASS** |
| postgres_row_for_published_version_exists | **PASS** |
| postgres_git_sha_matches_source_record | **PASS** |

### Metrics

- `durationMs`: 277

**Node-process stdout**: [node-logs/hyper.server.hash.matches.install.stdout.log](node-logs/hyper.server.hash.matches.install.stdout.log)

---

## `hyper.source.recorded` — **PASS**

executor start: `2026-07-12T18:03:04.756455Z`  
executor end: `2026-07-12T18:03:05.359878Z`  
spawn exit code: 0

**Input context**: [context/hyper.source.recorded.input.json](context/hyper.source.recorded.input.json)

### Assertions

| Name | Status |
|---|---|
| install_has_git_dir | **PASS** |
| source_json_written | **PASS** |
| source_kind_is_git | **PASS** |
| source_hash_matches_head | **PASS** |
| source_origin_is_github_hyper | **PASS** |

### Metrics

- `durationMs`: 118

### Published context

- `installedHash`: `5d5d509f3f74711ed72f9f7b65298e7c8c9cb677`

**Node-process stdout**: [node-logs/hyper.source.recorded.stdout.log](node-logs/hyper.source.recorded.stdout.log)

---

## `hyper.sync.clean.noop` — **PASS**

executor start: `2026-07-12T18:03:06.125006Z`  
executor end: `2026-07-12T18:03:11.184763Z`  
spawn exit code: 0

**Input context**: [context/hyper.sync.clean.noop.input.json](context/hyper.sync.clean.noop.input.json)

### Assertions

| Name | Status |
|---|---|
| sync_exit_zero | **PASS** |
| working_tree_clean_after_sync | **PASS** |

### Metrics

- `durationMs`: 4486

**Node-process stdout**: [node-logs/hyper.sync.clean.noop.stdout.log](node-logs/hyper.sync.clean.noop.stdout.log)

---

## `hyper.sync.merges.after.commit` — **PASS**

executor start: `2026-07-12T18:03:15.066974Z`  
executor end: `2026-07-12T18:03:19.301473Z`  
spawn exit code: 0

**Input context**: [context/hyper.sync.merges.after.commit.input.json](context/hyper.sync.merges.after.commit.input.json)

### Assertions

| Name | Status |
|---|---|
| merge_exit_zero | **PASS** |
| working_tree_clean_after_merge | **PASS** |
| local_file_survived_merge | **PASS** |
| source_record_hash_refreshed | **PASS** |

### Metrics

- `durationMs`: 3756

**Node-process stdout**: [node-logs/hyper.sync.merges.after.commit.stdout.log](node-logs/hyper.sync.merges.after.commit.stdout.log)

---

## `hyper.sync.refuses.on.local.commit` — **PASS**

executor start: `2026-07-12T18:03:11.185495Z`  
executor end: `2026-07-12T18:03:15.066140Z`  
spawn exit code: 0

**Input context**: [context/hyper.sync.refuses.on.local.commit.input.json](context/hyper.sync.refuses.on.local.commit.input.json)

### Assertions

| Name | Status |
|---|---|
| exited_with_rc_7 | **PASS** |
| banner_mentions_extra_local_changes | **PASS** |
| banner_includes_merge_flag | **PASS** |
| banner_names_github_upstream | **PASS** |
| local_commit_preserved | **PASS** |

### Metrics

- `durationMs`: 3321

**Node-process stdout**: [node-logs/hyper.sync.refuses.on.local.commit.stdout.log](node-logs/hyper.sync.refuses.on.local.commit.stdout.log)

---

## `jwt.valid` — **PASS**

executor start: `2026-07-12T18:02:12.655083Z`  
executor end: `2026-07-12T18:02:13.812136Z`  
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
- `durationMs`: 202

**Node-process stdout**: [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log)

---

## `postgres.down` — **PASS**

executor start: `2026-07-12T18:03:23.483674Z`  
executor end: `2026-07-12T18:03:24.289607Z`  
spawn exit code: 0

**Input context**: [context/postgres.down.input.json](context/postgres.down.input.json)

### Assertions

| Name | Status |
|---|---|
| stopped | **PASS** |

### Metrics

- `durationMs`: 208

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-stop | 0 | 201ms | 83793 | [`node-logs/postgres.down.compose-stop.log`](node-logs/postgres.down.compose-stop.log) |  |

**Node-process stdout**: [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T18:02:02.020824Z`  
executor end: `2026-07-12T18:02:03.126009Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 528

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-up | 0 | 232ms | 83193 | [`node-logs/postgres.up.compose-up.log`](node-logs/postgres.up.compose-up.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T18:02:03.725118Z`  
executor end: `2026-07-12T18:02:10.477489Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 83208
- `port`: 64407
- `durationMs`: 5773

### Published context

- `baseUrl`: `http://127.0.0.1:64407`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `servers.down` — **PASS**

executor start: `2026-07-12T18:03:19.302362Z`  
executor end: `2026-07-12T18:03:23.482824Z`  
spawn exit code: 0

**Input context**: [context/servers.down.input.json](context/servers.down.input.json)

### Assertions

| Name | Status |
|---|---|
| gateway_down | **PASS** |
| registry_down | **PASS** |
| echo_fixture_down | **PASS** |

### Metrics

- `durationMs`: 3596

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| gateway-down | 0 | 1317ms | 83762 | [`node-logs/servers.down.gateway-down.log`](node-logs/servers.down.gateway-down.log) |  |

**Node-process stdout**: [node-logs/servers.down.stdout.log](node-logs/servers.down.stdout.log)

---

