# Validation report — 20260914-000125

**Graph**: `home-verdicts`

**Trace ID**: `3ea8913c21d525688c095e64f56b4dac`

**Overall**: PASSED

**Execution scope**: full graph

**Plan evidence**: 9/9 expected node envelopes observed

**Input-context evidence**: 9/9 expected node snapshots observed

**Nodes**: 9 (passed=9, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 794ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `home.fixpoint.law` | **PASS** | 6863ms | [context/home.fixpoint.law.input.json](context/home.fixpoint.law.input.json) | [node-logs/home.fixpoint.law.stdout.log](node-logs/home.fixpoint.law.stdout.log) |
| `home.membership.law` | **PASS** | 6886ms | [context/home.membership.law.input.json](context/home.membership.law.input.json) | [node-logs/home.membership.law.stdout.log](node-logs/home.membership.law.stdout.log) |
| `home.verdicts.clean.home` | **PASS** | 2969ms | [context/home.verdicts.clean.home.input.json](context/home.verdicts.clean.home.input.json) | [node-logs/home.verdicts.clean.home.stdout.log](node-logs/home.verdicts.clean.home.stdout.log) |
| `home.verdicts.fixture` | **PASS** | 784ms | [context/home.verdicts.fixture.input.json](context/home.verdicts.fixture.input.json) | [node-logs/home.verdicts.fixture.stdout.log](node-logs/home.verdicts.fixture.stdout.log) |
| `home.verdicts.foreign.path.in.shim` | **PASS** | 8706ms | [context/home.verdicts.foreign.path.in.shim.input.json](context/home.verdicts.foreign.path.in.shim.input.json) | [node-logs/home.verdicts.foreign.path.in.shim.stdout.log](node-logs/home.verdicts.foreign.path.in.shim.stdout.log) |
| `home.verdicts.frozen.shim` | **PASS** | 8663ms | [context/home.verdicts.frozen.shim.input.json](context/home.verdicts.frozen.shim.input.json) | [node-logs/home.verdicts.frozen.shim.stdout.log](node-logs/home.verdicts.frozen.shim.stdout.log) |
| `home.verdicts.misanchored.agent.link` | **PASS** | 8891ms | [context/home.verdicts.misanchored.agent.link.input.json](context/home.verdicts.misanchored.agent.link.input.json) | [node-logs/home.verdicts.misanchored.agent.link.stdout.log](node-logs/home.verdicts.misanchored.agent.link.stdout.log) |
| `home.verdicts.unstamped.pm.tree` | **PASS** | 8651ms | [context/home.verdicts.unstamped.pm.tree.input.json](context/home.verdicts.unstamped.pm.tree.input.json) | [node-logs/home.verdicts.unstamped.pm.tree.stdout.log](node-logs/home.verdicts.unstamped.pm.tree.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-09-14T00:01:25.140960Z`

executor end: `2026-09-14T00:01:25.934199Z`

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

- `registryPort`: 59479
- `gatewayPort`: 59480
- `durationMs`: 24

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/agent-home/.gemini`
- `registryPort`: `59479`
- `gatewayPort`: `59480`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `home.fixpoint.law` — **PASS**

executor start: `2026-09-14T00:02:04.744348Z`

executor end: `2026-09-14T00:02:11.607367Z`

spawn exit code: 0

**Input context**: [context/home.fixpoint.law.input.json](context/home.fixpoint.law.input.json)

### Assertions

| Name | Status |
|---|---|
| every_home_verifies_or_its_own_remedy_repairs_it | **PASS** |

### Metrics

- `homesChecked`: 1
- `homesRepaired`: 0
- `homesOutsideSandbox`: 0
- `durationMs`: 6152

### Published context

- `homesChecked`: `/private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/clean/.skill-manager`
- `homesRepaired`: ``

### Inline logs

```
PASS  /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/clean/.skill-manager
```

**Node-process stdout**: [node-logs/home.fixpoint.law.stdout.log](node-logs/home.fixpoint.law.stdout.log)

---

## `home.membership.law` — **PASS**

executor start: `2026-09-14T00:02:11.621239Z`

executor end: `2026-09-14T00:02:18.507939Z`

spawn exit code: 0

**Input context**: [context/home.membership.law.input.json](context/home.membership.law.input.json)

### Assertions

| Name | Status |
|---|---|
| every_home_holds_exactly_what_was_installed_into_it | **PASS** |
| the_detector_flags_a_planted_gain_and_a_planted_loss | **PASS** |
| an_unmarked_intruder_beside_a_marked_staged_unit_is_still_flagged | **PASS** |
| at_least_one_home_was_actually_checked | **PASS** |

### Metrics

- `homesChecked`: 1
- `unitsObserved`: 0
- `homesOutsideSandbox`: 0
- `descriptorDrift`: 0
- `homesWithMembership`: 0
- `stagedUnitsExcused`: 0
- `durationMs`: 6164

### Published context

- `homesWithMembership`: `0`
- `homesChecked`: `/private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/clean/.skill-manager`
- `unitsObserved`: `0`

### Inline logs

```
SELF-TEST (runs before any real home):
  consistent home -> 0 violation(s)
  gained a unit   -> 1 violation(s)
  lost  a unit    -> 2 violation(s)
  staged + intruder -> 1 violation(s), staged=[hand-planted], violations=[/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/membership-selftest-9591384646912697226/staged-and-intruder GAINED [intruder] — present in the home, and no installed/ record names them. A unit nobody installed. (1 other unit(s) here carry .test-graph-staged and were excused.)]
PASS  /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/clean/.skill-manager
    disk    []
    records []
    lock    []
    staged  []  (.test-graph-staged)
```

**Node-process stdout**: [node-logs/home.membership.law.stdout.log](node-logs/home.membership.law.stdout.log)

---

## `home.verdicts.clean.home` — **PASS**

executor start: `2026-09-14T00:01:26.751739Z`

executor end: `2026-09-14T00:01:29.720424Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.clean.home.input.json](context/home.verdicts.clean.home.input.json)

### Assertions

| Name | Status |
|---|---|
| production_accepts_the_layout_as_a_home | **PASS** |
| home_repair_json_exits_0_and_says_clean | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_verify_exits_0 | **PASS** |
| the_finding_parser_matches_by_field_not_by_key_order_or_whitespace | **PASS** |

### Metrics

- `repair.exit`: 0
- `verify.exit`: 0
- `durationMs`: 2294

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| repair | 0 | 1147ms | 93656 | [`node-logs/home.verdicts.clean.home.repair.stdout.log`](node-logs/home.verdicts.clean.home.repair.stdout.log) |  |
| verify | 0 | 1081ms | 93670 | [`node-logs/home.verdicts.clean.home.verify.stdout.log`](node-logs/home.verdicts.clean.home.verify.stdout.log) |  |

### Inline logs

```
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/clean/.skill-manager","examined":0,"clean":true,"findings":[]}
```

**Node-process stdout**: [node-logs/home.verdicts.clean.home.stdout.log](node-logs/home.verdicts.clean.home.stdout.log)

---

## `home.verdicts.fixture` — **PASS**

executor start: `2026-09-14T00:01:25.955342Z`

executor end: `2026-09-14T00:01:26.739076Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.fixture.input.json](context/home.verdicts.fixture.input.json)

### Assertions

| Name | Status |
|---|---|
| the_home_did_not_exist_before_this_node | **PASS** |
| a_clean_home_was_laid_out_from_nothing | **PASS** |
| the_published_home_holds_no_unit_so_membership_is_empty | **PASS** |

### Metrics

- `durationMs`: 4

### Published context

- `cleanHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/clean/.skill-manager`
- `scratchRoot`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch`

**Node-process stdout**: [node-logs/home.verdicts.fixture.stdout.log](node-logs/home.verdicts.fixture.stdout.log)

---

## `home.verdicts.foreign.path.in.shim` — **PASS**

executor start: `2026-09-14T00:01:38.419187Z`

executor end: `2026-09-14T00:01:47.125958Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.foreign.path.in.shim.input.json](context/home.verdicts.foreign.path.in.shim.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_FOREIGN_PATH_IN_SHIM_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 8034

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1114ms | 93796 | [`node-logs/home.verdicts.foreign.path.in.shim.control.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.control.stdout.log) |  |
| detect | 1 | 1105ms | 93806 | [`node-logs/home.verdicts.foreign.path.in.shim.detect.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.detect.stdout.log) |  |
| detect-again | 1 | 1098ms | 93815 | [`node-logs/home.verdicts.foreign.path.in.shim.detect-again.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.detect-again.stdout.log) |  |
| verify | 1 | 1142ms | 93824 | [`node-logs/home.verdicts.foreign.path.in.shim.verify.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.verify.stdout.log) |  |
| fix | 0 | 1182ms | 93836 | [`node-logs/home.verdicts.foreign.path.in.shim.fix.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.fix.stdout.log) |  |
| detect-after-fix | 0 | 1154ms | 93849 | [`node-logs/home.verdicts.foreign.path.in.shim.detect-after-fix.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1149ms | 93858 | [`node-logs/home.verdicts.foreign.path.in.shim.verify-after-fix.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=FOREIGN_PATH_IN_SHIM subject=bin/cli/wrapper | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/foreign-path-in-shim/subject/.skill-manager","examined":3,"clean":false,"findings":[{"kind":"FOREIGN_PATH_IN_SHIM","subject":"bin/cli/wrapper","detail":"runs /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/foreign-path-in-shim/neighbour/.skill-manager/venvs/v/bin/wrapper, which is inside the home at /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/foreign-path-in-shim/neighbour/.skill-manager — the shim's path resolves to /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/foreign-path-in-shim/neighbour/.skill-manager/venvs/v/bin/wrapper","repair":"rewrite that path to /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/foreign-path-in-shim/subject/.skill-manager/venvs/v/bin/wrapper","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.foreign.path.in.shim.stdout.log](node-logs/home.verdicts.foreign.path.in.shim.stdout.log)

---

## `home.verdicts.frozen.shim` — **PASS**

executor start: `2026-09-14T00:01:29.736616Z`

executor end: `2026-09-14T00:01:38.399302Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.frozen.shim.input.json](context/home.verdicts.frozen.shim.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_FROZEN_HOME_PATH_IN_SHIM_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_0 | **PASS** |
| TODAY_home_verify_does_not_name_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 0
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 7972

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1121ms | 93687 | [`node-logs/home.verdicts.frozen.shim.control.stdout.log`](node-logs/home.verdicts.frozen.shim.control.stdout.log) |  |
| detect | 1 | 1104ms | 93716 | [`node-logs/home.verdicts.frozen.shim.detect.stdout.log`](node-logs/home.verdicts.frozen.shim.detect.stdout.log) |  |
| detect-again | 1 | 1101ms | 93733 | [`node-logs/home.verdicts.frozen.shim.detect-again.stdout.log`](node-logs/home.verdicts.frozen.shim.detect-again.stdout.log) |  |
| verify | 0 | 1085ms | 93745 | [`node-logs/home.verdicts.frozen.shim.verify.stdout.log`](node-logs/home.verdicts.frozen.shim.verify.stdout.log) |  |
| fix | 0 | 1214ms | 93754 | [`node-logs/home.verdicts.frozen.shim.fix.stdout.log`](node-logs/home.verdicts.frozen.shim.fix.stdout.log) |  |
| detect-after-fix | 0 | 1133ms | 93766 | [`node-logs/home.verdicts.frozen.shim.detect-after-fix.stdout.log`](node-logs/home.verdicts.frozen.shim.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1122ms | 93777 | [`node-logs/home.verdicts.frozen.shim.verify-after-fix.stdout.log`](node-logs/home.verdicts.frozen.shim.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=FROZEN_HOME_PATH_IN_SHIM subject=bin/cli/frozen-tool | repair=1 verify=0 (names it: false) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/frozen-shim/subject/.skill-manager","examined":3,"clean":false,"findings":[{"kind":"FROZEN_HOME_PATH_IN_SHIM","subject":"bin/cli/frozen-tool","detail":"names this home by absolute path, so it runs the home it was WRITTEN in rather than the one it is standing in — correct here, wrong the moment this home is copied","repair":"skill-manager home repair --fix (or `sync <unit> --force-scripts`, which rewrites it on the way past)","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.frozen.shim.stdout.log](node-logs/home.verdicts.frozen.shim.stdout.log)

---

## `home.verdicts.misanchored.agent.link` — **PASS**

executor start: `2026-09-14T00:01:47.149467Z`

executor end: `2026-09-14T00:01:56.040778Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.misanchored.agent.link.input.json](context/home.verdicts.misanchored.agent.link.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_MISANCHORED_AGENT_LINK_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_0 | **PASS** |
| TODAY_home_verify_does_not_name_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 0
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 8208

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1110ms | 93906 | [`node-logs/home.verdicts.misanchored.agent.link.control.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.control.stdout.log) |  |
| detect | 1 | 1093ms | 93943 | [`node-logs/home.verdicts.misanchored.agent.link.detect.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.detect.stdout.log) |  |
| detect-again | 1 | 1110ms | 93954 | [`node-logs/home.verdicts.misanchored.agent.link.detect-again.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.detect-again.stdout.log) |  |
| verify | 0 | 1243ms | 93980 | [`node-logs/home.verdicts.misanchored.agent.link.verify.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.verify.stdout.log) |  |
| fix | 0 | 1209ms | 94016 | [`node-logs/home.verdicts.misanchored.agent.link.fix.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.fix.stdout.log) |  |
| detect-after-fix | 0 | 1181ms | 94040 | [`node-logs/home.verdicts.misanchored.agent.link.detect-after-fix.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1173ms | 94076 | [`node-logs/home.verdicts.misanchored.agent.link.verify-after-fix.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=MISANCHORED_AGENT_LINK subject=.claude/skills/hv-unit | repair=1 verify=0 (names it: false) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/misanchored-agent-link/subject/.skill-manager","examined":1,"clean":false,"findings":[{"kind":"MISANCHORED_AGENT_LINK","subject":".claude/skills/hv-unit","detail":"resolves into the store at /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/misanchored-agent-link/neighbour/.skill-manager","repair":"re-point it at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/misanchored-agent-link/subject/.skill-manager/skills/hv-unit","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.misanchored.agent.link.stdout.log](node-logs/home.verdicts.misanchored.agent.link.stdout.log)

---

## `home.verdicts.unstamped.pm.tree` — **PASS**

executor start: `2026-09-14T00:01:56.073289Z`

executor end: `2026-09-14T00:02:04.724214Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.unstamped.pm.tree.input.json](context/home.verdicts.unstamped.pm.tree.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_UNSTAMPED_PM_TREE_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_0 | **PASS** |
| TODAY_home_verify_does_not_name_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 0
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 7987

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1087ms | 94100 | [`node-logs/home.verdicts.unstamped.pm.tree.control.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.control.stdout.log) |  |
| detect | 1 | 1093ms | 94109 | [`node-logs/home.verdicts.unstamped.pm.tree.detect.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.detect.stdout.log) |  |
| detect-again | 1 | 1102ms | 94120 | [`node-logs/home.verdicts.unstamped.pm.tree.detect-again.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.detect-again.stdout.log) |  |
| verify | 0 | 1127ms | 94129 | [`node-logs/home.verdicts.unstamped.pm.tree.verify.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.verify.stdout.log) |  |
| fix | 0 | 1179ms | 94138 | [`node-logs/home.verdicts.unstamped.pm.tree.fix.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.fix.stdout.log) |  |
| detect-after-fix | 0 | 1162ms | 94148 | [`node-logs/home.verdicts.unstamped.pm.tree.detect-after-fix.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1146ms | 94160 | [`node-logs/home.verdicts.unstamped.pm.tree.verify-after-fix.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=UNSTAMPED_PM_TREE subject=pm/node/22.9.0 | repair=1 verify=0 (names it: false) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-14488351458413571420/home-verdicts/scratch/unstamped-pm-tree/subject/.skill-manager","examined":2,"clean":false,"findings":[{"kind":"UNSTAMPED_PM_TREE","subject":"pm/node/22.9.0","detail":"carries no platform stamp, so a copy of this home on another platform cannot tell these bytes are foreign — it reports the tool installed and fails with a format error later, far from the copy that caused it","repair":"skill-manager home repair --fix stamps it for THIS platform, which is only true while the home is still on the machine that provisioned it","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.unstamped.pm.tree.stdout.log](node-logs/home.verdicts.unstamped.pm.tree.stdout.log)

---
