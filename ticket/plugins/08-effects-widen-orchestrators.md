# 08 — Widen orchestrator + remaining string-keyed effects

**Phase**: C — Effects
**Depends on**: 07
**Spec**: § "Effect graph: plugin/skill substitutability →
Required widening order".

## Goal

Widen the remaining string-keyed effects whose handlers must dispatch
on kind to pick a store path or unlink the right symlink. Wire
`UnitStore.unitDir(name, kind)` into the handlers that need it.

## What to do

### Effects to widen

| Effect | Change |
| --- | --- |
| `OnboardSource(skill)` | `OnboardUnit(unit)` |
| `RemoveSkillFromStore(skillName)` | `RemoveUnitFromStore(unitName, kind)` — handler calls `store.unitDir(unitName, kind)` |
| `UnlinkAgentSkill(agentId, skillName)` | `UnlinkAgentUnit(agentId, unitName, kind)` — handler picks `pluginsDir` vs `skillsDir` per agent |
| `SyncGit(skillName, ...)` | `SyncGit(unitName, kind, ...)` — handler clones into `unitDir(name, kind)` |
| `CommitSkillsToStore(graph)` | `CommitUnitsToStore(graph)` — graph already carries AgentUnit; handler routes per `unit.kind()` |
| `BuildInstallPlan(graph)` | unchanged (graph type widened in ticket 05) |
| `RunInstallPlan(gateway)` | unchanged |

### `Agent` interface extension

```
src/main/java/dev/skillmanager/agent/Agent.java
```

Add `Path pluginsDir()` to the interface. `ClaudeAgent.pluginsDir()`
returns `<claudeHome>/.claude/plugins`. `CodexAgent.pluginsDir()`
returns `<codexHome>/.codex/plugins` (for symmetry; Codex projector
won't use it in v1 — ticket 11 explains).

### Plugin install side effect (provisional)

Until ticket 11 (Projector) lands, kind-aware symlinking lives in the
widened `SyncAgents` / `UnlinkAgentUnit` handlers as a direct
`Files.createSymbolicLink` call. Ticket 11 extracts this into
`Projector.apply` / `Projector.remove`.

## Tests

```
src/test/java/dev/skillmanager/effects/
└── KindAwareDispatchTest.java     // pin the four divergence points: store dir, projector (provisional), scaffold (deferred), uninstall re-walk (deferred)
```

Sweep: `(unit kind × pre-state)` for each kind-aware handler. Assert:
`PLUGIN` units route to `plugins/<name>`, `SKILL` units to
`skills/<name>`; agent unlink picks the correct dir.

## Out of scope

- Compensations (ticket 09).
- Projector extraction (ticket 11) — the symlink call here is
  provisional and replaced by the projector.
- `ScaffoldPlugin` effect (ticket 13).
- Uninstall re-walk (ticket 09 — comes with the journal/compensation
  work).

## Acceptance

- All effects widened. `Skill skill` / `String skillName` no longer
  appears as effect field types except in legacy `Skill`-typed
  records that exist for adapter purposes.
- `KindAwareDispatchTest` pins both routing branches.
- Plugin install end-to-end (with no compensations yet) succeeds in a
  unit-test scenario: a plugin can be installed, files land in
  `plugins/<name>`, agent symlink lands in `~/.claude/plugins/<name>`.
- No test_graph regressions on existing skill scenarios.
