# Validation report — 20260914-153435

**Graph**: `home-verdicts`

**Trace ID**: `37f2acc056faf6d3f62d0e1d7babf13c`

**Overall**: PASSED

**Execution scope**: full graph

**Plan evidence**: 18/18 expected node envelopes observed

**Input-context evidence**: 18/18 expected node snapshots observed

**Nodes**: 18 (passed=18, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `env.prepared` | **PASS** | 887ms | [context/env.prepared.input.json](context/env.prepared.input.json) | [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log) |
| `home.fixpoint.law` | **PASS** | 9237ms | [context/home.fixpoint.law.input.json](context/home.fixpoint.law.input.json) | [node-logs/home.fixpoint.law.stdout.log](node-logs/home.fixpoint.law.stdout.log) |
| `home.membership.law` | **PASS** | 9912ms | [context/home.membership.law.input.json](context/home.membership.law.input.json) | [node-logs/home.membership.law.stdout.log](node-logs/home.membership.law.stdout.log) |
| `home.verdicts.child.install.writes.only.itself` | **PASS** | 3107ms | [context/home.verdicts.child.install.writes.only.itself.input.json](context/home.verdicts.child.install.writes.only.itself.input.json) | [node-logs/home.verdicts.child.install.writes.only.itself.stdout.log](node-logs/home.verdicts.child.install.writes.only.itself.stdout.log) |
| `home.verdicts.clean.home` | **PASS** | 3381ms | [context/home.verdicts.clean.home.input.json](context/home.verdicts.clean.home.input.json) | [node-logs/home.verdicts.clean.home.stdout.log](node-logs/home.verdicts.clean.home.stdout.log) |
| `home.verdicts.copied.marketplace.identity` | **PASS** | 10460ms | [context/home.verdicts.copied.marketplace.identity.input.json](context/home.verdicts.copied.marketplace.identity.input.json) | [node-logs/home.verdicts.copied.marketplace.identity.stdout.log](node-logs/home.verdicts.copied.marketplace.identity.stdout.log) |
| `home.verdicts.dangling.agent.link` | **PASS** | 9414ms | [context/home.verdicts.dangling.agent.link.input.json](context/home.verdicts.dangling.agent.link.input.json) | [node-logs/home.verdicts.dangling.agent.link.stdout.log](node-logs/home.verdicts.dangling.agent.link.stdout.log) |
| `home.verdicts.fixture` | **PASS** | 915ms | [context/home.verdicts.fixture.input.json](context/home.verdicts.fixture.input.json) | [node-logs/home.verdicts.fixture.stdout.log](node-logs/home.verdicts.fixture.stdout.log) |
| `home.verdicts.foreign.marketplace.registration` | **PASS** | 9256ms | [context/home.verdicts.foreign.marketplace.registration.input.json](context/home.verdicts.foreign.marketplace.registration.input.json) | [node-logs/home.verdicts.foreign.marketplace.registration.stdout.log](node-logs/home.verdicts.foreign.marketplace.registration.stdout.log) |
| `home.verdicts.foreign.path.in.shim` | **PASS** | 9352ms | [context/home.verdicts.foreign.path.in.shim.input.json](context/home.verdicts.foreign.path.in.shim.input.json) | [node-logs/home.verdicts.foreign.path.in.shim.stdout.log](node-logs/home.verdicts.foreign.path.in.shim.stdout.log) |
| `home.verdicts.frozen.shim` | **PASS** | 9579ms | [context/home.verdicts.frozen.shim.input.json](context/home.verdicts.frozen.shim.input.json) | [node-logs/home.verdicts.frozen.shim.stdout.log](node-logs/home.verdicts.frozen.shim.stdout.log) |
| `home.verdicts.half.rewritten.shim` | **PASS** | 12687ms | [context/home.verdicts.half.rewritten.shim.input.json](context/home.verdicts.half.rewritten.shim.input.json) | [node-logs/home.verdicts.half.rewritten.shim.stdout.log](node-logs/home.verdicts.half.rewritten.shim.stdout.log) |
| `home.verdicts.marketplace.identity.unregistered` | **PASS** | 9177ms | [context/home.verdicts.marketplace.identity.unregistered.input.json](context/home.verdicts.marketplace.identity.unregistered.input.json) | [node-logs/home.verdicts.marketplace.identity.unregistered.stdout.log](node-logs/home.verdicts.marketplace.identity.unregistered.stdout.log) |
| `home.verdicts.marketplace.under.another.name` | **PASS** | 11092ms | [context/home.verdicts.marketplace.under.another.name.input.json](context/home.verdicts.marketplace.under.another.name.input.json) | [node-logs/home.verdicts.marketplace.under.another.name.stdout.log](node-logs/home.verdicts.marketplace.under.another.name.stdout.log) |
| `home.verdicts.misanchored.agent.link` | **PASS** | 9327ms | [context/home.verdicts.misanchored.agent.link.input.json](context/home.verdicts.misanchored.agent.link.input.json) | [node-logs/home.verdicts.misanchored.agent.link.stdout.log](node-logs/home.verdicts.misanchored.agent.link.stdout.log) |
| `home.verdicts.orphaned.projection.record` | **PASS** | 9497ms | [context/home.verdicts.orphaned.projection.record.input.json](context/home.verdicts.orphaned.projection.record.input.json) | [node-logs/home.verdicts.orphaned.projection.record.stdout.log](node-logs/home.verdicts.orphaned.projection.record.stdout.log) |
| `home.verdicts.unstamped.pm.tree` | **PASS** | 11562ms | [context/home.verdicts.unstamped.pm.tree.input.json](context/home.verdicts.unstamped.pm.tree.input.json) | [node-logs/home.verdicts.unstamped.pm.tree.stdout.log](node-logs/home.verdicts.unstamped.pm.tree.stdout.log) |
| `home.verdicts.verify.names.every.repair.finding` | **PASS** | 8386ms | [context/home.verdicts.verify.names.every.repair.finding.input.json](context/home.verdicts.verify.names.every.repair.finding.input.json) | [node-logs/home.verdicts.verify.names.every.repair.finding.stdout.log](node-logs/home.verdicts.verify.names.every.repair.finding.stdout.log) |

## `env.prepared` — **PASS**

executor start: `2026-09-14T15:34:36.041481Z`

executor end: `2026-09-14T15:34:36.928221Z`

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

- `registryPort`: 64873
- `gatewayPort`: 64874
- `durationMs`: 24

### Published context

- `home`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424`
- `claudeHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/agent-home`
- `codexHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/agent-home/.codex`
- `geminiHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/agent-home/.gemini`
- `registryPort`: `64873`
- `gatewayPort`: `64874`

**Node-process stdout**: [node-logs/env.prepared.stdout.log](node-logs/env.prepared.stdout.log)

---

## `home.fixpoint.law` — **PASS**

executor start: `2026-09-14T15:36:44.519688Z`

executor end: `2026-09-14T15:36:53.756601Z`

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
- `homesDamagedOnPurpose`: 0
- `durationMs`: 8480

### Published context

- `homesChecked`: `/private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/clean/.skill-manager`
- `homesRepaired`: ``

### Inline logs

```
PASS  /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/clean/.skill-manager
```

**Node-process stdout**: [node-logs/home.fixpoint.law.stdout.log](node-logs/home.fixpoint.law.stdout.log)

---

## `home.membership.law` — **PASS**

executor start: `2026-09-14T15:36:53.775539Z`

executor end: `2026-09-14T15:37:03.687443Z`

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
- `durationMs`: 9119

### Published context

- `homesWithMembership`: `0`
- `homesChecked`: `/private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/clean/.skill-manager`
- `unitsObserved`: `0`

### Inline logs

```
SELF-TEST (runs before any real home):
  consistent home -> 0 violation(s)
  gained a unit   -> 1 violation(s)
  lost  a unit    -> 2 violation(s)
  staged + intruder -> 1 violation(s), staged=[hand-planted], violations=[/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/membership-selftest-13290865550320688362/staged-and-intruder GAINED [intruder] — present in the home, and no installed/ record names them. A unit nobody installed. (1 other unit(s) here carry .test-graph-staged and were excused.)]
PASS  /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/clean/.skill-manager
    disk    []
    records []
    lock    []
    staged  []  (.test-graph-staged)
```

**Node-process stdout**: [node-logs/home.membership.law.stdout.log](node-logs/home.membership.law.stdout.log)

---

## `home.verdicts.child.install.writes.only.itself` — **PASS**

executor start: `2026-09-14T15:36:41.399306Z`

executor end: `2026-09-14T15:36:44.506292Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.child.install.writes.only.itself.input.json](context/home.verdicts.child.install.writes.only.itself.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_child_link_resolves_to_the_parents_shim | **PASS** |
| install_into_the_child_exits_0 | **PASS** |
| the_parents_shim_is_byte_identical | **PASS** |
| every_parent_file_is_byte_identical_after_the_child_install | **PASS** |
| the_child_holds_its_own_real_shim | **PASS** |
| the_child_shim_is_token_form | **PASS** |
| the_child_shim_names_no_form_of_the_parent | **PASS** |
| the_child_shim_runs_the_childs_tool | **PASS** |
| the_scratch_homes_are_deleted | **PASS** |

### Metrics

- `parent.files.changed`: 0
- `install.exit`: 0
- `durationMs`: 2397

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| install-into-child | 0 | 2107ms | 96679 | [`node-logs/home.verdicts.child.install.writes.only.itself.install-into-child.stdout.log`](node-logs/home.verdicts.child.install.writes.only.itself.install-into-child.stdout.log) |  |

### Inline logs

```
child shim after install:
#!/usr/bin/env bash
# Rewritten by skill-manager: resolve the home this shim is standing in
# rather than the one it was written into, so a copy of the home works.
SKILL_MANAGER_SHIM_HOME="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"
exec "${SKILL_MANAGER_SHIM_HOME}/cache/skill-script-wt-unit-wt-tool/venv/bin/wt-tool" "$@"

```

**Node-process stdout**: [node-logs/home.verdicts.child.install.writes.only.itself.stdout.log](node-logs/home.verdicts.child.install.writes.only.itself.stdout.log)

---

## `home.verdicts.clean.home` — **PASS**

executor start: `2026-09-14T15:34:37.892402Z`

executor end: `2026-09-14T15:34:41.273933Z`

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
- `durationMs`: 2638

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| repair | 0 | 1250ms | 93142 | [`node-logs/home.verdicts.clean.home.repair.stdout.log`](node-logs/home.verdicts.clean.home.repair.stdout.log) |  |
| verify | 0 | 1320ms | 93186 | [`node-logs/home.verdicts.clean.home.verify.stdout.log`](node-logs/home.verdicts.clean.home.verify.stdout.log) |  |

### Inline logs

```
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/clean/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":0,"clean":true,"findings":[]}
```

**Node-process stdout**: [node-logs/home.verdicts.clean.home.stdout.log](node-logs/home.verdicts.clean.home.stdout.log)

---

## `home.verdicts.copied.marketplace.identity` — **PASS**

executor start: `2026-09-14T15:36:21.639053Z`

executor end: `2026-09-14T15:36:32.099545Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.copied.marketplace.identity.input.json](context/home.verdicts.copied.marketplace.identity.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_MARKETPLACE_IDENTITY_COPIED_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_manifest_names_this_home_s_derived_identity | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 9789

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1141ms | 96182 | [`node-logs/home.verdicts.copied.marketplace.identity.control.stdout.log`](node-logs/home.verdicts.copied.marketplace.identity.control.stdout.log) |  |
| detect | 1 | 1296ms | 96225 | [`node-logs/home.verdicts.copied.marketplace.identity.detect.stdout.log`](node-logs/home.verdicts.copied.marketplace.identity.detect.stdout.log) |  |
| detect-again | 1 | 1466ms | 96266 | [`node-logs/home.verdicts.copied.marketplace.identity.detect-again.stdout.log`](node-logs/home.verdicts.copied.marketplace.identity.detect-again.stdout.log) |  |
| verify | 1 | 1639ms | 96299 | [`node-logs/home.verdicts.copied.marketplace.identity.verify.stdout.log`](node-logs/home.verdicts.copied.marketplace.identity.verify.stdout.log) |  |
| fix | 0 | 1528ms | 96343 | [`node-logs/home.verdicts.copied.marketplace.identity.fix.stdout.log`](node-logs/home.verdicts.copied.marketplace.identity.fix.stdout.log) |  |
| detect-after-fix | 0 | 1334ms | 96377 | [`node-logs/home.verdicts.copied.marketplace.identity.detect-after-fix.stdout.log`](node-logs/home.verdicts.copied.marketplace.identity.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1287ms | 96423 | [`node-logs/home.verdicts.copied.marketplace.identity.verify-after-fix.stdout.log`](node-logs/home.verdicts.copied.marketplace.identity.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=MARKETPLACE_IDENTITY_COPIED subject=plugin-marketplace/.claude-plugin/marketplace.json | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/copied-marketplace-identity/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":3,"clean":false,"findings":[{"kind":"MARKETPLACE_IDENTITY_COPIED","subject":"plugin-marketplace/.claude-plugin/marketplace.json","detail":"names the marketplace skill-manager-ea11086d, but this home's identity is skill-manager-6a9e1cc7 (derived from its store path /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/copied-marketplace-identity/subject/.skill-manager) — expected skill-manager-6a9e1cc7, found skill-manager-ea11086d: a copied manifest carries its source's identity, and every agent registers this home under it","repair":"skill-manager home repair --fix regenerates the marketplace, which writes skill-manager-6a9e1cc7 (so does the next plugin `sync`)","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.copied.marketplace.identity.stdout.log](node-logs/home.verdicts.copied.marketplace.identity.stdout.log)

---

## `home.verdicts.dangling.agent.link` — **PASS**

executor start: `2026-09-14T15:35:29.657657Z`

executor end: `2026-09-14T15:35:39.071621Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.dangling.agent.link.input.json](context/home.verdicts.dangling.agent.link.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_DANGLING_AGENT_LINK_on_the_planted_subject | **PASS** |
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
- `durationMs`: 8707

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1143ms | 94832 | [`node-logs/home.verdicts.dangling.agent.link.control.stdout.log`](node-logs/home.verdicts.dangling.agent.link.control.stdout.log) |  |
| detect | 1 | 1125ms | 94858 | [`node-logs/home.verdicts.dangling.agent.link.detect.stdout.log`](node-logs/home.verdicts.dangling.agent.link.detect.stdout.log) |  |
| detect-again | 1 | 1224ms | 94881 | [`node-logs/home.verdicts.dangling.agent.link.detect-again.stdout.log`](node-logs/home.verdicts.dangling.agent.link.detect-again.stdout.log) |  |
| verify | 1 | 1235ms | 94956 | [`node-logs/home.verdicts.dangling.agent.link.verify.stdout.log`](node-logs/home.verdicts.dangling.agent.link.verify.stdout.log) |  |
| fix | 0 | 1381ms | 94987 | [`node-logs/home.verdicts.dangling.agent.link.fix.stdout.log`](node-logs/home.verdicts.dangling.agent.link.fix.stdout.log) |  |
| detect-after-fix | 0 | 1310ms | 95066 | [`node-logs/home.verdicts.dangling.agent.link.detect-after-fix.stdout.log`](node-logs/home.verdicts.dangling.agent.link.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1201ms | 95104 | [`node-logs/home.verdicts.dangling.agent.link.verify-after-fix.stdout.log`](node-logs/home.verdicts.dangling.agent.link.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=DANGLING_AGENT_LINK subject=.codex/skills/hv-gone | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/dangling-agent-link/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":4,"clean":false,"findings":[{"kind":"DANGLING_AGENT_LINK","subject":".codex/skills/hv-gone","detail":"points at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/dangling-agent-link/subject/.skill-manager/skills/hv-gone, inside this home, and nothing is there — this home no longer holds the unit it projected","repair":"skill-manager home repair --fix removes the link — it resolves to nothing, so removing it loses nothing","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.dangling.agent.link.stdout.log](node-logs/home.verdicts.dangling.agent.link.stdout.log)

---

## `home.verdicts.fixture` — **PASS**

executor start: `2026-09-14T15:34:36.954787Z`

executor end: `2026-09-14T15:34:37.869604Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.fixture.input.json](context/home.verdicts.fixture.input.json)

### Assertions

| Name | Status |
|---|---|
| the_home_did_not_exist_before_this_node | **PASS** |
| a_clean_home_was_laid_out_from_nothing | **PASS** |
| the_published_home_holds_no_unit_so_membership_is_empty | **PASS** |

### Metrics

- `durationMs`: 6

### Published context

- `cleanHome`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/clean/.skill-manager`
- `scratchRoot`: `/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch`

**Node-process stdout**: [node-logs/home.verdicts.fixture.stdout.log](node-logs/home.verdicts.fixture.stdout.log)

---

## `home.verdicts.foreign.marketplace.registration` — **PASS**

executor start: `2026-09-14T15:36:32.125438Z`

executor end: `2026-09-14T15:36:41.381918Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.foreign.marketplace.registration.input.json](context/home.verdicts.foreign.marketplace.registration.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_FOREIGN_MARKETPLACE_REGISTRATION_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_foreign_codex_marketplace_and_its_plugin_are_gone | **PASS** |
| the_home_s_own_and_unrelated_codex_entries_survive | **PASS** |
| the_claude_enablement_of_another_home_is_gone | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 4
- `durationMs`: 8531

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1244ms | 96504 | [`node-logs/home.verdicts.foreign.marketplace.registration.control.stdout.log`](node-logs/home.verdicts.foreign.marketplace.registration.control.stdout.log) |  |
| detect | 1 | 1158ms | 96536 | [`node-logs/home.verdicts.foreign.marketplace.registration.detect.stdout.log`](node-logs/home.verdicts.foreign.marketplace.registration.detect.stdout.log) |  |
| detect-again | 1 | 1134ms | 96548 | [`node-logs/home.verdicts.foreign.marketplace.registration.detect-again.stdout.log`](node-logs/home.verdicts.foreign.marketplace.registration.detect-again.stdout.log) |  |
| verify | 1 | 1177ms | 96558 | [`node-logs/home.verdicts.foreign.marketplace.registration.verify.stdout.log`](node-logs/home.verdicts.foreign.marketplace.registration.verify.stdout.log) |  |
| fix | 0 | 1246ms | 96571 | [`node-logs/home.verdicts.foreign.marketplace.registration.fix.stdout.log`](node-logs/home.verdicts.foreign.marketplace.registration.fix.stdout.log) |  |
| detect-after-fix | 0 | 1214ms | 96582 | [`node-logs/home.verdicts.foreign.marketplace.registration.detect-after-fix.stdout.log`](node-logs/home.verdicts.foreign.marketplace.registration.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1261ms | 96630 | [`node-logs/home.verdicts.foreign.marketplace.registration.verify-after-fix.stdout.log`](node-logs/home.verdicts.foreign.marketplace.registration.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=FOREIGN_MARKETPLACE_REGISTRATION subject=.codex/config.toml:marketplaces.skill-manager | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":9,"clean":false,"findings":[{"kind":"FOREIGN_MARKETPLACE_REGISTRATION","subject":".claude/plugins/known_marketplaces.json:known_marketplaces.skill-manager-012bbe95","detail":"registers skill-manager-012bbe95 at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/neighbour/.skill-manager/plugin-marketplace, another home's marketplace — this home's is skill-manager-42b381ee at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/subject/.skill-manager/plugin-marketplace, and a plugin enabled from both loads twice","repair":"skill-manager home repair --fix removes this entry and nothing else","repairable":true},{"kind":"FOREIGN_MARKETPLACE_REGISTRATION","subject":".claude/settings.json:enabledPlugins.hv-plugin@skill-manager-012bbe95","detail":"enables hv-plugin@skill-manager-012bbe95 from skill-manager-012bbe95, registered at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/neighbour/.skill-manager/plugin-marketplace — expected only skill-manager-42b381ee at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/subject/.skill-manager/plugin-marketplace in this home","repair":"skill-manager home repair --fix removes this entry and nothing else","repairable":true},{"kind":"FOREIGN_MARKETPLACE_REGISTRATION","subject":".codex/config.toml:marketplaces.skill-manager","detail":"registers skill-manager at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/neighbour/.skill-manager/plugin-marketplace, another home's marketplace — this home's is skill-manager-42b381ee at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/subject/.skill-manager/plugin-marketplace, and a plugin enabled from both loads twice","repair":"skill-manager home repair --fix removes this entry and nothing else","repairable":true},{"kind":"FOREIGN_MARKETPLACE_REGISTRATION","subject":".codex/config.toml:plugins.hv-plugin@skill-manager","detail":"enables hv-plugin@skill-manager from skill-manager, registered at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/neighbour/.skill-manager/plugin-marketplace — expected only skill-manager-42b381ee at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-marketplace-registration/subject/.skill-manager/plugin-marketplace in this home","repair":"skill-manager home repair --fix removes this entry and nothing else","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.foreign.marketplace.registration.stdout.log](node-logs/home.verdicts.foreign.marketplace.registration.stdout.log)

---

## `home.verdicts.foreign.path.in.shim` — **PASS**

executor start: `2026-09-14T15:34:50.912917Z`

executor end: `2026-09-14T15:35:00.264957Z`

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
- `durationMs`: 8657

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1197ms | 93659 | [`node-logs/home.verdicts.foreign.path.in.shim.control.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.control.stdout.log) |  |
| detect | 1 | 1159ms | 93708 | [`node-logs/home.verdicts.foreign.path.in.shim.detect.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.detect.stdout.log) |  |
| detect-again | 1 | 1164ms | 93768 | [`node-logs/home.verdicts.foreign.path.in.shim.detect-again.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.detect-again.stdout.log) |  |
| verify | 1 | 1178ms | 93805 | [`node-logs/home.verdicts.foreign.path.in.shim.verify.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.verify.stdout.log) |  |
| fix | 0 | 1334ms | 93844 | [`node-logs/home.verdicts.foreign.path.in.shim.fix.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.fix.stdout.log) |  |
| detect-after-fix | 0 | 1270ms | 93919 | [`node-logs/home.verdicts.foreign.path.in.shim.detect-after-fix.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1264ms | 93961 | [`node-logs/home.verdicts.foreign.path.in.shim.verify-after-fix.stdout.log`](node-logs/home.verdicts.foreign.path.in.shim.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=FOREIGN_PATH_IN_SHIM subject=bin/cli/wrapper | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-path-in-shim/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":4,"clean":false,"findings":[{"kind":"FOREIGN_PATH_IN_SHIM","subject":"bin/cli/wrapper","detail":"runs /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-path-in-shim/neighbour/.skill-manager/venvs/v/bin/wrapper, which is inside the home at /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-path-in-shim/neighbour/.skill-manager — the shim's path resolves to /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-path-in-shim/neighbour/.skill-manager/venvs/v/bin/wrapper","repair":"rewrite that path to /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/foreign-path-in-shim/subject/.skill-manager/venvs/v/bin/wrapper","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.foreign.path.in.shim.stdout.log](node-logs/home.verdicts.foreign.path.in.shim.stdout.log)

---

## `home.verdicts.frozen.shim` — **PASS**

executor start: `2026-09-14T15:34:41.301990Z`

executor end: `2026-09-14T15:34:50.880655Z`

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
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 8839

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1241ms | 93266 | [`node-logs/home.verdicts.frozen.shim.control.stdout.log`](node-logs/home.verdicts.frozen.shim.control.stdout.log) |  |
| detect | 1 | 1274ms | 93335 | [`node-logs/home.verdicts.frozen.shim.detect.stdout.log`](node-logs/home.verdicts.frozen.shim.detect.stdout.log) |  |
| detect-again | 1 | 1167ms | 93379 | [`node-logs/home.verdicts.frozen.shim.detect-again.stdout.log`](node-logs/home.verdicts.frozen.shim.detect-again.stdout.log) |  |
| verify | 1 | 1274ms | 93413 | [`node-logs/home.verdicts.frozen.shim.verify.stdout.log`](node-logs/home.verdicts.frozen.shim.verify.stdout.log) |  |
| fix | 0 | 1299ms | 93457 | [`node-logs/home.verdicts.frozen.shim.fix.stdout.log`](node-logs/home.verdicts.frozen.shim.fix.stdout.log) |  |
| detect-after-fix | 0 | 1245ms | 93523 | [`node-logs/home.verdicts.frozen.shim.detect-after-fix.stdout.log`](node-logs/home.verdicts.frozen.shim.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1247ms | 93569 | [`node-logs/home.verdicts.frozen.shim.verify-after-fix.stdout.log`](node-logs/home.verdicts.frozen.shim.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=FROZEN_HOME_PATH_IN_SHIM subject=bin/cli/frozen-tool | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/frozen-shim/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":4,"clean":false,"findings":[{"kind":"FROZEN_HOME_PATH_IN_SHIM","subject":"bin/cli/frozen-tool","detail":"names this home by absolute path, so it runs the home it was WRITTEN in rather than the one it is standing in — correct here, wrong the moment this home is copied","repair":"skill-manager home repair --fix (or `sync <unit> --force-scripts`, which rewrites it on the way past)","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.frozen.shim.stdout.log](node-logs/home.verdicts.frozen.shim.stdout.log)

---

## `home.verdicts.half.rewritten.shim` — **PASS**

executor start: `2026-09-14T15:35:48.623239Z`

executor end: `2026-09-14T15:36:01.310686Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.half.rewritten.shim.input.json](context/home.verdicts.half.rewritten.shim.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_FROZEN_HOME_PATH_IN_SHIM_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_fixed_shim_spells_no_form_of_its_own_home | **PASS** |
| the_exec_line_derives_the_home_from_the_token | **PASS** |
| exactly_one_assignment_of_the_token_no_second_preamble | **PASS** |
| the_fixed_shim_still_runs_its_tool | **PASS** |
| a_second_fix_is_a_no_op | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 11984

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1170ms | 95367 | [`node-logs/home.verdicts.half.rewritten.shim.control.stdout.log`](node-logs/home.verdicts.half.rewritten.shim.control.stdout.log) |  |
| detect | 1 | 1206ms | 95381 | [`node-logs/home.verdicts.half.rewritten.shim.detect.stdout.log`](node-logs/home.verdicts.half.rewritten.shim.detect.stdout.log) |  |
| detect-again | 1 | 1135ms | 95394 | [`node-logs/home.verdicts.half.rewritten.shim.detect-again.stdout.log`](node-logs/home.verdicts.half.rewritten.shim.detect-again.stdout.log) |  |
| verify | 1 | 3168ms | 95430 | [`node-logs/home.verdicts.half.rewritten.shim.verify.stdout.log`](node-logs/home.verdicts.half.rewritten.shim.verify.stdout.log) |  |
| fix | 0 | 1296ms | 95487 | [`node-logs/home.verdicts.half.rewritten.shim.fix.stdout.log`](node-logs/home.verdicts.half.rewritten.shim.fix.stdout.log) |  |
| detect-after-fix | 0 | 1259ms | 95533 | [`node-logs/home.verdicts.half.rewritten.shim.detect-after-fix.stdout.log`](node-logs/home.verdicts.half.rewritten.shim.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1251ms | 95552 | [`node-logs/home.verdicts.half.rewritten.shim.verify-after-fix.stdout.log`](node-logs/home.verdicts.half.rewritten.shim.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=FROZEN_HOME_PATH_IN_SHIM subject=bin/cli/half-tool | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/half-rewritten-shim/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":4,"clean":false,"findings":[{"kind":"FROZEN_HOME_PATH_IN_SHIM","subject":"bin/cli/half-tool","detail":"names this home by absolute path, so it runs the home it was WRITTEN in rather than the one it is standing in — correct here, wrong the moment this home is copied","repair":"skill-manager home repair --fix (or `sync <unit> --force-scripts`, which rewrites it on the way past)","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.half.rewritten.shim.stdout.log](node-logs/home.verdicts.half.rewritten.shim.stdout.log)

---

## `home.verdicts.marketplace.identity.unregistered` — **PASS**

executor start: `2026-09-14T15:36:12.432329Z`

executor end: `2026-09-14T15:36:21.609172Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.marketplace.identity.unregistered.input.json](context/home.verdicts.marketplace.identity.unregistered.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_MARKETPLACE_IDENTITY_UNREGISTERED_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_identity_is_registered_at_this_home | **PASS** |
| the_enabled_plugin_is_kept | **PASS** |
| the_name_that_merely_contains_it_is_untouched | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 8456

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1175ms | 95756 | [`node-logs/home.verdicts.marketplace.identity.unregistered.control.stdout.log`](node-logs/home.verdicts.marketplace.identity.unregistered.control.stdout.log) |  |
| detect | 1 | 1186ms | 95796 | [`node-logs/home.verdicts.marketplace.identity.unregistered.detect.stdout.log`](node-logs/home.verdicts.marketplace.identity.unregistered.detect.stdout.log) |  |
| detect-again | 1 | 1182ms | 95939 | [`node-logs/home.verdicts.marketplace.identity.unregistered.detect-again.stdout.log`](node-logs/home.verdicts.marketplace.identity.unregistered.detect-again.stdout.log) |  |
| verify | 1 | 1207ms | 96006 | [`node-logs/home.verdicts.marketplace.identity.unregistered.verify.stdout.log`](node-logs/home.verdicts.marketplace.identity.unregistered.verify.stdout.log) |  |
| fix | 0 | 1272ms | 96073 | [`node-logs/home.verdicts.marketplace.identity.unregistered.fix.stdout.log`](node-logs/home.verdicts.marketplace.identity.unregistered.fix.stdout.log) |  |
| detect-after-fix | 0 | 1202ms | 96150 | [`node-logs/home.verdicts.marketplace.identity.unregistered.detect-after-fix.stdout.log`](node-logs/home.verdicts.marketplace.identity.unregistered.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1137ms | 96162 | [`node-logs/home.verdicts.marketplace.identity.unregistered.verify-after-fix.stdout.log`](node-logs/home.verdicts.marketplace.identity.unregistered.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=MARKETPLACE_IDENTITY_UNREGISTERED subject=.claude/settings.json:enabledPlugins.hv-plugin@skill-manager-b294dbea | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-identity-unregistered/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":5,"clean":false,"findings":[{"kind":"MARKETPLACE_IDENTITY_UNREGISTERED","subject":".claude/settings.json:enabledPlugins.hv-plugin@skill-manager-b294dbea","detail":"enables hv-plugin@skill-manager-b294dbea from this home's own marketplace, but this config registers no skill-manager-b294dbea at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-identity-unregistered/subject/.skill-manager/plugin-marketplace — found skill-manager-b294dbea-old at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-identity-unregistered/neighbour/.skill-manager/plugin-marketplace; a registered name that merely contains skill-manager-b294dbea is not skill-manager-b294dbea","repair":"skill-manager home repair --fix registers skill-manager-b294dbea at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-identity-unregistered/subject/.skill-manager/plugin-marketplace in this agent's config, as its own `marketplace add` would","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.marketplace.identity.unregistered.stdout.log](node-logs/home.verdicts.marketplace.identity.unregistered.stdout.log)

---

## `home.verdicts.marketplace.under.another.name` — **PASS**

executor start: `2026-09-14T15:36:01.325946Z`

executor end: `2026-09-14T15:36:12.417320Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.marketplace.under.another.name.input.json](context/home.verdicts.marketplace.under.another.name.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME_on_the_planted_subject | **PASS** |
| detection_alone_changes_nothing | **PASS** |
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_registration_is_re_pointed_at_the_identity | **PASS** |
| the_enabled_plugin_migrates_with_it | **PASS** |
| an_unrelated_marketplace_and_plugin_are_untouched | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 3
- `durationMs`: 10402

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1172ms | 95592 | [`node-logs/home.verdicts.marketplace.under.another.name.control.stdout.log`](node-logs/home.verdicts.marketplace.under.another.name.control.stdout.log) |  |
| detect | 1 | 1155ms | 95604 | [`node-logs/home.verdicts.marketplace.under.another.name.detect.stdout.log`](node-logs/home.verdicts.marketplace.under.another.name.detect.stdout.log) |  |
| detect-again | 1 | 1143ms | 95618 | [`node-logs/home.verdicts.marketplace.under.another.name.detect-again.stdout.log`](node-logs/home.verdicts.marketplace.under.another.name.detect-again.stdout.log) |  |
| verify | 1 | 1164ms | 95628 | [`node-logs/home.verdicts.marketplace.under.another.name.verify.stdout.log`](node-logs/home.verdicts.marketplace.under.another.name.verify.stdout.log) |  |
| fix | 0 | 3292ms | 95643 | [`node-logs/home.verdicts.marketplace.under.another.name.fix.stdout.log`](node-logs/home.verdicts.marketplace.under.another.name.fix.stdout.log) |  |
| detect-after-fix | 0 | 1193ms | 95697 | [`node-logs/home.verdicts.marketplace.under.another.name.detect-after-fix.stdout.log`](node-logs/home.verdicts.marketplace.under.another.name.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1190ms | 95710 | [`node-logs/home.verdicts.marketplace.under.another.name.verify-after-fix.stdout.log`](node-logs/home.verdicts.marketplace.under.another.name.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME subject=.claude/plugins/known_marketplaces.json:known_marketplaces.skill-manager | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-under-another-name/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":7,"clean":false,"findings":[{"kind":"MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME","subject":".claude/plugins/known_marketplaces.json:known_marketplaces.skill-manager","detail":"registers this home's marketplace /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-under-another-name/subject/.skill-manager/plugin-marketplace under skill-manager, but its identity is skill-manager-d9d20d3b — expected skill-manager-d9d20d3b at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-under-another-name/subject/.skill-manager/plugin-marketplace, found skill-manager there, so every sync updates and installs from a name this agent does not know","repair":"skill-manager home repair --fix re-points it at skill-manager-d9d20d3b, with every skill-manager registration of this directory and every plugin enabled from it in the same agent's config; the next `sync` then updates and installs from skill-manager-d9d20d3b","repairable":true},{"kind":"MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME","subject":".claude/settings.json:extraKnownMarketplaces.skill-manager","detail":"registers this home's marketplace /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-under-another-name/subject/.skill-manager/plugin-marketplace under skill-manager, but its identity is skill-manager-d9d20d3b — expected skill-manager-d9d20d3b at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-under-another-name/subject/.skill-manager/plugin-marketplace, found skill-manager there, so every sync updates and installs from a name this agent does not know","repair":"skill-manager home repair --fix re-points it at skill-manager-d9d20d3b, with every skill-manager registration of this directory and every plugin enabled from it in the same agent's config; the next `sync` then updates and installs from skill-manager-d9d20d3b","repairable":true},{"kind":"MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME","subject":".claude/settings.json:enabledPlugins.hv-plugin@skill-manager","detail":"enables hv-plugin@skill-manager from skill-manager, which this config registers at this home's marketplace /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/marketplace-under-another-name/subject/.skill-manager/plugin-marketplace — expected hv-plugin@skill-manager-d9d20d3b","repair":"skill-manager home repair --fix re-points it at skill-manager-d9d20d3b, with every skill-manager registration of this directory and every plugin enabled from it in the same agent's config; the next `sync` then updates and installs from skill-manager-d9d20d3b","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.marketplace.under.another.name.stdout.log](node-logs/home.verdicts.marketplace.under.another.name.stdout.log)

---

## `home.verdicts.misanchored.agent.link` — **PASS**

executor start: `2026-09-14T15:35:00.286631Z`

executor end: `2026-09-14T15:35:09.613220Z`

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
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 8587

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1197ms | 94036 | [`node-logs/home.verdicts.misanchored.agent.link.control.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.control.stdout.log) |  |
| detect | 1 | 1210ms | 94079 | [`node-logs/home.verdicts.misanchored.agent.link.detect.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.detect.stdout.log) |  |
| detect-again | 1 | 1182ms | 94116 | [`node-logs/home.verdicts.misanchored.agent.link.detect-again.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.detect-again.stdout.log) |  |
| verify | 1 | 1199ms | 94158 | [`node-logs/home.verdicts.misanchored.agent.link.verify.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.verify.stdout.log) |  |
| fix | 0 | 1285ms | 94200 | [`node-logs/home.verdicts.misanchored.agent.link.fix.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.fix.stdout.log) |  |
| detect-after-fix | 0 | 1243ms | 94247 | [`node-logs/home.verdicts.misanchored.agent.link.detect-after-fix.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1183ms | 94281 | [`node-logs/home.verdicts.misanchored.agent.link.verify-after-fix.stdout.log`](node-logs/home.verdicts.misanchored.agent.link.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=MISANCHORED_AGENT_LINK subject=.claude/skills/hv-unit | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/misanchored-agent-link/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":2,"clean":false,"findings":[{"kind":"MISANCHORED_AGENT_LINK","subject":".claude/skills/hv-unit","detail":"resolves into the store at /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/misanchored-agent-link/neighbour/.skill-manager","repair":"re-point it at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/misanchored-agent-link/subject/.skill-manager/skills/hv-unit","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.misanchored.agent.link.stdout.log](node-logs/home.verdicts.misanchored.agent.link.stdout.log)

---

## `home.verdicts.orphaned.projection.record` — **PASS**

executor start: `2026-09-14T15:35:39.111630Z`

executor end: `2026-09-14T15:35:48.608790Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.orphaned.projection.record.input.json](context/home.verdicts.orphaned.projection.record.input.json)

### Assertions

| Name | Status |
|---|---|
| control_the_unplanted_home_is_clean | **PASS** |
| home_repair_json_exits_1_on_the_planted_shape | **PASS** |
| home_repair_json_stdout_alone_is_one_json_object | **PASS** |
| home_repair_names_ORPHANED_PROJECTION_RECORD_on_the_planted_subject | **PASS** |
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
- `durationMs`: 8751

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1173ms | 95148 | [`node-logs/home.verdicts.orphaned.projection.record.control.stdout.log`](node-logs/home.verdicts.orphaned.projection.record.control.stdout.log) |  |
| detect | 1 | 1200ms | 95190 | [`node-logs/home.verdicts.orphaned.projection.record.detect.stdout.log`](node-logs/home.verdicts.orphaned.projection.record.detect.stdout.log) |  |
| detect-again | 1 | 1179ms | 95230 | [`node-logs/home.verdicts.orphaned.projection.record.detect-again.stdout.log`](node-logs/home.verdicts.orphaned.projection.record.detect-again.stdout.log) |  |
| verify | 1 | 1336ms | 95249 | [`node-logs/home.verdicts.orphaned.projection.record.verify.stdout.log`](node-logs/home.verdicts.orphaned.projection.record.verify.stdout.log) |  |
| fix | 0 | 1287ms | 95302 | [`node-logs/home.verdicts.orphaned.projection.record.fix.stdout.log`](node-logs/home.verdicts.orphaned.projection.record.fix.stdout.log) |  |
| detect-after-fix | 0 | 1191ms | 95330 | [`node-logs/home.verdicts.orphaned.projection.record.detect-after-fix.stdout.log`](node-logs/home.verdicts.orphaned.projection.record.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1301ms | 95344 | [`node-logs/home.verdicts.orphaned.projection.record.verify-after-fix.stdout.log`](node-logs/home.verdicts.orphaned.projection.record.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=ORPHANED_PROJECTION_RECORD subject=installed/hv-gone.projections.json | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/orphaned-projection-record/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":3,"clean":false,"findings":[{"kind":"ORPHANED_PROJECTION_RECORD","subject":"installed/hv-gone.projections.json","detail":"records 1 projection(s) of hv-gone, and this home holds no installed/hv-gone.json and no hv-gone unit directory — the unit is gone and its record outlived it","repair":"skill-manager home repair --fix deletes this record and nothing else; a link it names that is left dangling is reported separately as DANGLING_AGENT_LINK","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.orphaned.projection.record.stdout.log](node-logs/home.verdicts.orphaned.projection.record.stdout.log)

---

## `home.verdicts.unstamped.pm.tree` — **PASS**

executor start: `2026-09-14T15:35:09.634281Z`

executor end: `2026-09-14T15:35:21.196937Z`

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
| TODAY_home_verify_exits_1 | **PASS** |
| TODAY_home_verify_names_the_shape | **PASS** |
| home_repair_fix_then_a_separate_detection_is_clean | **PASS** |
| home_verify_is_clean_after_the_fix | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `verify.exit`: 1
- `repair.exit`: 1
- `repair.findings`: 1
- `durationMs`: 10856

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control | 0 | 1174ms | 94314 | [`node-logs/home.verdicts.unstamped.pm.tree.control.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.control.stdout.log) |  |
| detect | 1 | 1113ms | 94327 | [`node-logs/home.verdicts.unstamped.pm.tree.detect.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.detect.stdout.log) |  |
| detect-again | 1 | 1191ms | 94337 | [`node-logs/home.verdicts.unstamped.pm.tree.detect-again.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.detect-again.stdout.log) |  |
| verify | 1 | 3196ms | 94371 | [`node-logs/home.verdicts.unstamped.pm.tree.verify.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.verify.stdout.log) |  |
| fix | 0 | 1433ms | 94461 | [`node-logs/home.verdicts.unstamped.pm.tree.fix.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.fix.stdout.log) |  |
| detect-after-fix | 0 | 1323ms | 94493 | [`node-logs/home.verdicts.unstamped.pm.tree.detect-after-fix.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.detect-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1336ms | 94523 | [`node-logs/home.verdicts.unstamped.pm.tree.verify-after-fix.stdout.log`](node-logs/home.verdicts.unstamped.pm.tree.verify-after-fix.stdout.log) |  |

### Inline logs

```
shape=UNSTAMPED_PM_TREE subject=pm/node/22.9.0 | repair=1 verify=1 (names it: true) fix=0 after=0 verify-after=0
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/unstamped-pm-tree/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":3,"clean":false,"findings":[{"kind":"UNSTAMPED_PM_TREE","subject":"pm/node/22.9.0","detail":"carries no platform stamp, so a copy of this home on another platform cannot tell these bytes are foreign — it reports the tool installed and fails with a format error later, far from the copy that caused it","repair":"skill-manager home repair --fix stamps it for THIS platform, which is only true while the home is still on the machine that provisioned it","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.unstamped.pm.tree.stdout.log](node-logs/home.verdicts.unstamped.pm.tree.stdout.log)

---

## `home.verdicts.verify.names.every.repair.finding` — **PASS**

executor start: `2026-09-14T15:35:21.241796Z`

executor end: `2026-09-14T15:35:29.627959Z`

spawn exit code: 0

**Input context**: [context/home.verdicts.verify.names.every.repair.finding.input.json](context/home.verdicts.verify.names.every.repair.finding.input.json)

### Assertions

| Name | Status |
|---|---|
| control_home_verify_exits_0_on_the_unplanted_home | **PASS** |
| home_repair_json_exits_1 | **PASS** |
| home_repair_reports_every_planted_kind | **PASS** |
| home_verify_exits_1_when_home_repair_reports | **PASS** |
| home_verify_names_every_repair_finding_by_kind_and_subject | **PASS** |
| home_verify_prints_home_repair_fix_as_its_remedy | **PASS** |
| after_the_fix_both_readers_are_clean | **PASS** |
| the_damaged_homes_are_deleted | **PASS** |

### Metrics

- `repair.findings`: 5
- `verify.unnamed`: 0
- `durationMs`: 7648

### Subprocesses

| Label | Exit | Duration | PID | Log | Error |
|---|---|---|---|---|---|
| control-verify | 0 | 1223ms | 94581 | [`node-logs/home.verdicts.verify.names.every.repair.finding.control-verify.stdout.log`](node-logs/home.verdicts.verify.names.every.repair.finding.control-verify.stdout.log) |  |
| repair | 1 | 1241ms | 94617 | [`node-logs/home.verdicts.verify.names.every.repair.finding.repair.stdout.log`](node-logs/home.verdicts.verify.names.every.repair.finding.repair.stdout.log) |  |
| verify | 1 | 1241ms | 94652 | [`node-logs/home.verdicts.verify.names.every.repair.finding.verify.stdout.log`](node-logs/home.verdicts.verify.names.every.repair.finding.verify.stdout.log) |  |
| fix | 0 | 1371ms | 94685 | [`node-logs/home.verdicts.verify.names.every.repair.finding.fix.stdout.log`](node-logs/home.verdicts.verify.names.every.repair.finding.fix.stdout.log) |  |
| repair-after-fix | 0 | 1298ms | 94721 | [`node-logs/home.verdicts.verify.names.every.repair.finding.repair-after-fix.stdout.log`](node-logs/home.verdicts.verify.names.every.repair.finding.repair-after-fix.stdout.log) |  |
| verify-after-fix | 0 | 1186ms | 94765 | [`node-logs/home.verdicts.verify.names.every.repair.finding.verify-after-fix.stdout.log`](node-logs/home.verdicts.verify.names.every.repair.finding.verify-after-fix.stdout.log) |  |

### Inline logs

```
remedy as printed: env SKILL_MANAGER_HOME=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.skill-manager CLAUDE_CONFIG_DIR=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.claude CODEX_HOME=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.codex GEMINI_HOME=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.gemini SKILL_MANAGER_CONFINE_ROOT=/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject /Users/hayde/IdeaProjects/wt-ohv-8/skill-manager home repair --home /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.skill-manager --fix
repair --json stdout: {"home":"/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.skill-manager","build":"skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)","examined":8,"clean":false,"findings":[{"kind":"MISANCHORED_AGENT_LINK","subject":".claude/skills/hv-unit","detail":"resolves into the store at /private/var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/neighbour/.skill-manager","repair":"re-point it at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.skill-manager/skills/hv-unit","repairable":true},{"kind":"FROZEN_HOME_PATH_IN_SHIM","subject":"bin/cli/frozen-tool","detail":"names this home by absolute path, so it runs the home it was WRITTEN in rather than the one it is standing in — correct here, wrong the moment this home is copied","repair":"skill-manager home repair --fix (or `sync <unit> --force-scripts`, which rewrites it on the way past)","repairable":true},{"kind":"UNSTAMPED_PM_TREE","subject":"pm/node/22.9.0","detail":"carries no platform stamp, so a copy of this home on another platform cannot tell these bytes are foreign — it reports the tool installed and fails with a format error later, far from the copy that caused it","repair":"skill-manager home repair --fix stamps it for THIS platform, which is only true while the home is still on the machine that provisioned it","repairable":true},{"kind":"DANGLING_AGENT_LINK","subject":".gemini/skills/hv-gone","detail":"points at /var/folders/b7/rsz3g6wn4hg8zl2bwmdx61q00000gn/T/sm-testgraph-6486854002877709424/home-verdicts/scratch/verify-names-every-finding/subject/.skill-manager/skills/hv-gone, inside this home, and nothing is there — this home no longer holds the unit it projected","repair":"skill-manager home repair --fix removes the link — it resolves to nothing, so removing it loses nothing","repairable":true},{"kind":"ORPHANED_PROJECTION_RECORD","subject":"installed/hv-orphan.projections.json","detail":"records 1 projection(s) of hv-orphan, and this home holds no installed/hv-orphan.json and no hv-orphan unit directory — the unit is gone and its record outlived it","repair":"skill-manager home repair --fix deletes this record and nothing else; a link it names that is left dangling is reported separately as DANGLING_AGENT_LINK","repairable":true}]}
```

**Node-process stdout**: [node-logs/home.verdicts.verify.names.every.repair.finding.stdout.log](node-logs/home.verdicts.verify.names.every.repair.finding.stdout.log)

---
