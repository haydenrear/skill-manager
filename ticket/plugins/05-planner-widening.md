# 05 — Planner widening + heterogeneous DAG + cycle detection

**Phase**: B — Resolution
**Depends on**: 04
**Spec**: § "Effect graph: plugin/skill substitutability",
"Plan/effects model".

## Goal

Widen `PlanBuilder` to accept `AgentUnit`. Walk plugin contents (already
unioned at parse time), build the heterogeneous reference DAG, detect
cycles, emit the policy categorization in plan-print (gates wired up
in ticket 12).

## What to do

### Plan-action types

```
src/main/java/dev/skillmanager/plan/PlanAction.java
```

- Rename `PlanAction.FetchSkill` → `FetchUnit`, carrying a
  `ResolvedGraph.Resolved` that now wraps `UnitDescriptor`.
- Rename `PlanAction.InstallSkillIntoStore` → `InstallUnitIntoStore`,
  add `UnitKind kind` field so the executor picks the right store dir
  in ticket 08.
- `RunCliInstall` and `RegisterMcpServer` keep their shape — rename
  field `skillName` → `unitName`.

### `ResolvedGraph` widening

```
src/main/java/dev/skillmanager/resolve/ResolvedGraph.java
```

`ResolvedGraph.Resolved` carries `AgentUnit unit` (instead of `Skill`).
Keep a transitional `skill()` accessor that down-casts and emits a
deprecation warning at call sites — stays until ticket 08 widens those
sites.

### `PlanBuilder` extensions

- Walk references heterogeneously. A `UnitReference` with
  `kindFilter=PLUGIN_ONLY` must resolve to a plugin; with
  `SKILL_ONLY` must resolve to a skill; with `ANY` either.
- Cycle detection across plugin + skill nodes. Surface the offending
  chain in the error message.
- Plugin-effective dep set is already computed at parse time (ticket 01) —
  the planner just emits one `RunCliInstall` / `RegisterMcpServer` per
  entry.
- Plan-print: emit the `! HOOKS` / `! MCP` / `! CLI` /
  `+ COMMANDS` / `+ AGENTS` / `+ SKILLS` categorization. `!` lines
  are display-only this ticket; the gating logic comes in ticket 12.

## Tests

```
src/test/java/dev/skillmanager/plan/
├── PlanShapeInvariantTest.java         // contract #1: equivalent DepSpec → identical plan shape across UnitKind
├── CycleDetectionTest.java             // contract #7: heterogeneous cycles caught at plan time
├── MixedKindTopoOrderTest.java         // mixed install set in one Program, topologically ordered
└── PlanPolicyCategorizationTest.java   // plan-print emits the expected lines (no gating yet)
```

`PlanShapeInvariantTest` is the spine of substitutability — sweep
`(kind × DepSpec)` and assert per-section action counts and dep-name
mappings match across kinds.

## Out of scope

- Effect-layer widening (tickets 06–08). The planner emits the new
  `PlanAction` shapes; downstream effects still take `Skill`. The
  bridge between is the `ResolvedGraph.Resolved.skill()` shim — the
  planner uses `unit()` going forward, the effects keep using
  `skill()` until widened.
- Policy gating (ticket 12).

## Acceptance

- `PlanBuilder.build(AgentUnit)` works for both plugin and skill
  inputs.
- `PlanShapeInvariantTest` passes for the contract slice.
- Cycle detection covers all four chain types: skill→skill,
  skill→plugin, plugin→skill, plugin→plugin (and mixed chains).
- Plan-print categorization renders correctly; no gating side effects.
