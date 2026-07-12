# Validation report — 20260712-180421

**Overall**: PASSED  
**Nodes**: 15 (passed=15, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `campaigns.created` | **PASS** | 4705ms | [context/campaigns.created.input.json](context/campaigns.created.input.json) | [node-logs/campaigns.created.stdout.log](node-logs/campaigns.created.stdout.log) |
| `ci.logged.in` | **PASS** | 2072ms | [context/ci.logged.in.input.json](context/ci.logged.in.input.json) | [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log) |
| `env.prepared` | **PASS** | 595ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `formatter.published` | **PASS** | 2021ms | [context/formatter.published.input.json](context/formatter.published.input.json) | [node-logs/formatter.published.stdout.log](node-logs/formatter.published.stdout.log) |
| `gateway.python.venv.ready` | **PASS** | 592ms | [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json) | [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log) |
| `jwt.valid` | **PASS** | 1136ms | [context/jwt.valid.input.json](context/jwt.valid.input.json) | [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log) |
| `postgres.down` | **PASS** | 821ms | [context/postgres.down.input.json](context/postgres.down.input.json) | [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log) |
| `postgres.up` | **PASS** | 1074ms | [context/postgres.up.input.json](context/postgres.up.input.json) | [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log) |
| `registry.up` | **PASS** | 4599ms | [context/registry.up.input.json](context/registry.up.input.json) | [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log) |
| `reviewer.published` | **PASS** | 2048ms | [context/reviewer.published.input.json](context/reviewer.published.input.json) | [node-logs/reviewer.published.stdout.log](node-logs/reviewer.published.stdout.log) |
| `sponsored.higher.bid.wins` | **PASS** | 1118ms | [context/sponsored.higher.bid.wins.input.json](context/sponsored.higher.bid.wins.input.json) | [node-logs/sponsored.higher.bid.wins.stdout.log](node-logs/sponsored.higher.bid.wins.stdout.log) |
| `sponsored.no.ads.suppresses` | **PASS** | 1117ms | [context/sponsored.no.ads.suppresses.input.json](context/sponsored.no.ads.suppresses.input.json) | [node-logs/sponsored.no.ads.suppresses.stdout.log](node-logs/sponsored.no.ads.suppresses.stdout.log) |
| `sponsored.organic.unchanged` | **PASS** | 1129ms | [context/sponsored.organic.unchanged.input.json](context/sponsored.organic.unchanged.input.json) | [node-logs/sponsored.organic.unchanged.stdout.log](node-logs/sponsored.organic.unchanged.stdout.log) |
| `sponsored.search.matches.keyword` | **PASS** | 1154ms | [context/sponsored.search.matches.keyword.input.json](context/sponsored.search.matches.keyword.input.json) | [node-logs/sponsored.search.matches.keyword.stdout.log](node-logs/sponsored.search.matches.keyword.stdout.log) |
| `sponsored.teardown` | **PASS** | 2394ms | [context/sponsored.teardown.input.json](context/sponsored.teardown.input.json) | [node-logs/sponsored.teardown.stdout.log](node-logs/sponsored.teardown.stdout.log) |

## `campaigns.created` — **PASS**

executor start: `2026-07-12T18:04:36.100138Z`  
executor end: `2026-07-12T18:04:40.805190Z`  
spawn exit code: 0

**Input context**: [context/campaigns.created.input.json](context/campaigns.created.input.json)

### Assertions

| Name | Status |
|---|---|
| reviewer_campaign_created | **PASS** |
| formatter_campaign_created | **PASS** |
| high_bid_campaign_created | **PASS** |

### Metrics

- `durationMs`: 4114

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| ads-reviewer | 0 | 1402ms | 84498 | [`node-logs/campaigns.created.ads-reviewer.log`](node-logs/campaigns.created.ads-reviewer.log) |  |
| ads-formatter | 0 | 1356ms | 84510 | [`node-logs/campaigns.created.ads-formatter.log`](node-logs/campaigns.created.ads-formatter.log) |  |
| ads-highbid | 0 | 1349ms | 84521 | [`node-logs/campaigns.created.ads-highbid.log`](node-logs/campaigns.created.ads-highbid.log) |  |

**Node-process stdout**: [node-logs/campaigns.created.stdout.log](node-logs/campaigns.created.stdout.log)

---

## `ci.logged.in` — **PASS**

executor start: `2026-07-12T18:04:28.819608Z`  
executor end: `2026-07-12T18:04:30.891687Z`  
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
| login | 0 | 1478ms | 84447 | [`node-logs/ci.logged.in.login.log`](node-logs/ci.logged.in.login.log) |  |

### Published context

- `clientId`: `skill-manager-ci`

**Node-process stdout**: [node-logs/ci.logged.in.stdout.log](node-logs/ci.logged.in.stdout.log)

---

## `env.prepared` — **PASS**

executor start: `2026-07-12T18:04:22.548479Z`  
executor end: `2026-07-12T18:04:23.143357Z`  
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

- `registryPort`: 64653
- `gatewayPort`: 64654
- `durationMs`: 29

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-17435396376216599686`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-17435396376216599686/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-17435396376216599686/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-17435396376216599686/agent-home/.gemini`
- `registryPort`: `64653`
- `gatewayPort`: `64654`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `formatter.published` — **PASS**

executor start: `2026-07-12T18:04:34.078401Z`  
executor end: `2026-07-12T18:04:36.099384Z`  
spawn exit code: 0

**Input context**: [context/formatter.published.input.json](context/formatter.published.input.json)

### Assertions

| Name | Status |
|---|---|
| published_ok | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1452

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| publish | 0 | 1445ms | 84483 | [`node-logs/formatter.published.publish.log`](node-logs/formatter.published.publish.log) |  |

**Node-process stdout**: [node-logs/formatter.published.stdout.log](node-logs/formatter.published.stdout.log)

---

## `gateway.python.venv.ready` — **PASS**

executor start: `2026-07-12T18:04:21.955488Z`  
executor end: `2026-07-12T18:04:22.547754Z`  
spawn exit code: 0

**Input context**: [context/gateway.python.venv.ready.input.json](context/gateway.python.venv.ready.input.json)

### Assertions

| Name | Status |
|---|---|
| uv_sync_ok | **PASS** |
| venv_python_present | **PASS** |

### Metrics

- `durationMs`: 34

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| uv-sync | 0 | 27ms | 84407 | [`node-logs/gateway.python.venv.ready.uv-sync.log`](node-logs/gateway.python.venv.ready.uv-sync.log) |  |

### Published context

- `venvPython`: `/Users/hayde/IdeaProjects/wt-109-resolver-cycle/virtual-mcp-gateway/.venv/bin/python`

**Node-process stdout**: [node-logs/gateway.python.venv.ready.stdout.log](node-logs/gateway.python.venv.ready.stdout.log)

---

## `jwt.valid` — **PASS**

executor start: `2026-07-12T18:04:30.892617Z`  
executor end: `2026-07-12T18:04:32.028406Z`  
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
- `durationMs`: 193

**Node-process stdout**: [node-logs/jwt.valid.stdout.log](node-logs/jwt.valid.stdout.log)

---

## `postgres.down` — **PASS**

executor start: `2026-07-12T18:04:47.723566Z`  
executor end: `2026-07-12T18:04:48.544722Z`  
spawn exit code: 0

**Input context**: [context/postgres.down.input.json](context/postgres.down.input.json)

### Assertions

| Name | Status |
|---|---|
| stopped | **PASS** |

### Metrics

- `durationMs`: 231

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-stop | 0 | 224ms | 84572 | [`node-logs/postgres.down.compose-stop.log`](node-logs/postgres.down.compose-stop.log) |  |

**Node-process stdout**: [node-logs/postgres.down.stdout.log](node-logs/postgres.down.stdout.log)

---

## `postgres.up` — **PASS**

executor start: `2026-07-12T18:04:23.144157Z`  
executor end: `2026-07-12T18:04:24.218248Z`  
spawn exit code: 0

**Input context**: [context/postgres.up.input.json](context/postgres.up.input.json)

### Assertions

| Name | Status |
|---|---|
| reachable | **PASS** |

### Metrics

- `durationMs`: 511

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| compose-up | 0 | 214ms | 84423 | [`node-logs/postgres.up.compose-up.log`](node-logs/postgres.up.compose-up.log) |  |

### Published context

- `dbUrl`: `jdbc:postgresql://localhost:5432/skill_registry_test`
- `dbUser`: `postgres`
- `dbPassword`: `postgres`

**Node-process stdout**: [node-logs/postgres.up.stdout.log](node-logs/postgres.up.stdout.log)

---

## `registry.up` — **PASS**

executor start: `2026-07-12T18:04:24.219015Z`  
executor end: `2026-07-12T18:04:28.818376Z`  
spawn exit code: 0

**Input context**: [context/registry.up.input.json](context/registry.up.input.json)

### Assertions

| Name | Status |
|---|---|
| health_ok | **PASS** |

### Metrics

- `pid`: 84432
- `port`: 64653
- `durationMs`: 3638

### Published context

- `baseUrl`: `http://127.0.0.1:64653`

**Node-process stdout**: [node-logs/registry.up.stdout.log](node-logs/registry.up.stdout.log)

---

## `reviewer.published` — **PASS**

executor start: `2026-07-12T18:04:32.029290Z`  
executor end: `2026-07-12T18:04:34.077672Z`  
spawn exit code: 0

**Input context**: [context/reviewer.published.input.json](context/reviewer.published.input.json)

### Assertions

| Name | Status |
|---|---|
| published_ok | **PASS** |

### Metrics

- `exitCode`: 0
- `durationMs`: 1480

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| publish | 0 | 1474ms | 84468 | [`node-logs/reviewer.published.publish.log`](node-logs/reviewer.published.publish.log) |  |

**Node-process stdout**: [node-logs/reviewer.published.stdout.log](node-logs/reviewer.published.stdout.log)

---

## `sponsored.higher.bid.wins` — **PASS**

executor start: `2026-07-12T18:04:44.209188Z`  
executor end: `2026-07-12T18:04:45.327484Z`  
spawn exit code: 0

**Input context**: [context/sponsored.higher.bid.wins.input.json](context/sponsored.higher.bid.wins.input.json)

### Assertions

| Name | Status |
|---|---|
| slots_ordered_by_bid_desc | **PASS** |
| highest_bidder_in_first_slot | **PASS** |

### Metrics

- `first_bid_cents`: 1000
- `second_bid_cents`: 500
- `durationMs`: 265

**Node-process stdout**: [node-logs/sponsored.higher.bid.wins.stdout.log](node-logs/sponsored.higher.bid.wins.stdout.log)

---

## `sponsored.no.ads.suppresses` — **PASS**

executor start: `2026-07-12T18:04:41.961257Z`  
executor end: `2026-07-12T18:04:43.078743Z`  
spawn exit code: 0

**Input context**: [context/sponsored.no.ads.suppresses.input.json](context/sponsored.no.ads.suppresses.input.json)

### Assertions

| Name | Status |
|---|---|
| default_has_sponsored | **PASS** |
| no_ads_flag_empties_sponsored | **PASS** |

### Metrics

- `durationMs`: 269

**Node-process stdout**: [node-logs/sponsored.no.ads.suppresses.stdout.log](node-logs/sponsored.no.ads.suppresses.stdout.log)

---

## `sponsored.organic.unchanged` — **PASS**

executor start: `2026-07-12T18:04:43.079537Z`  
executor end: `2026-07-12T18:04:44.208366Z`  
spawn exit code: 0

**Input context**: [context/sponsored.organic.unchanged.input.json](context/sponsored.organic.unchanged.input.json)

### Assertions

| Name | Status |
|---|---|
| organic_count_identical | **PASS** |
| organic_order_identical | **PASS** |

### Metrics

- `organic_items`: 1
- `durationMs`: 265

**Node-process stdout**: [node-logs/sponsored.organic.unchanged.stdout.log](node-logs/sponsored.organic.unchanged.stdout.log)

---

## `sponsored.search.matches.keyword` — **PASS**

executor start: `2026-07-12T18:04:40.806105Z`  
executor end: `2026-07-12T18:04:41.960385Z`  
spawn exit code: 0

**Input context**: [context/sponsored.search.matches.keyword.input.json](context/sponsored.search.matches.keyword.input.json)

### Assertions

| Name | Status |
|---|---|
| review_keyword_surfaces_reviewer | **PASS** |
| format_keyword_surfaces_formatter | **PASS** |
| format_does_not_leak_reviewer_campaign | **PASS** |
| unrelated_query_has_no_ads | **PASS** |

### Metrics

- `durationMs`: 306

**Node-process stdout**: [node-logs/sponsored.search.matches.keyword.stdout.log](node-logs/sponsored.search.matches.keyword.stdout.log)

---

## `sponsored.teardown` — **PASS**

executor start: `2026-07-12T18:04:45.328677Z`  
executor end: `2026-07-12T18:04:47.722743Z`  
spawn exit code: 0

**Input context**: [context/sponsored.teardown.input.json](context/sponsored.teardown.input.json)

### Assertions

| Name | Status |
|---|---|
| registry_stopped | **PASS** |

### Metrics

- `durationMs`: 1822

**Node-process stdout**: [node-logs/sponsored.teardown.stdout.log](node-logs/sponsored.teardown.stdout.log)

---

