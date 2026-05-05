# 07 — Widen list-typed effects

**Phase**: C — Effects
**Depends on**: 06
**Spec**: § "Effect graph: plugin/skill substitutability".

## Goal

Widen the effects that take `List<Skill>` to `List<AgentUnit>`. These
fan out over a batch — typically one per command — and feed handler
loops that already do per-element work. Per-element processing
becomes kind-agnostic (the union'd dep set is the same shape on a
`PluginUnit` as on a `SkillUnit`).

## What to do

### Effects to widen

| Effect | Change |
| --- | --- |
| `ResolveTransitives(skills)` | `List<AgentUnit> units` |
| `InstallTools(skills)` | `List<AgentUnit> units` |
| `InstallCli(skills)` | `List<AgentUnit> units` |
| `RegisterMcp(skills, gateway)` | `List<AgentUnit> units` |
| `SyncAgents(skills, gateway)` | `List<AgentUnit> units` |
| `RecordSourceProvenance(graph)` | unchanged (graph already carries `AgentUnit` from ticket 05) |

### Handlers

Each handler iterates over `units` and reads `cliDependencies()`,
`mcpDependencies()`, `references()` on the `AgentUnit`. Plugin's
already-unioned dep set means no special-case branching: the handler
emits one register/install per dep regardless of which kind owns it.

`SyncAgents` will eventually need kind-aware dispatch for the
projector — but that's ticket 11 (Projector). For this ticket,
`SyncAgents` keeps its current per-skill symlink behavior, just
typed against `AgentUnit` (which still has only skill carriers in
practice until ticket 11 routes plugins separately).

## Tests

```
src/test/java/dev/skillmanager/effects/
└── ListTypedHandlerSubstitutabilityTest.java  // sweep batches of equivalent units across UnitKind
```

Sweep: `(unit kind × batch size 1..3 × dep mix)` per list-typed
effect. Assert: receipts contain the same per-unit outcomes regardless
of which kind the units are.

## Out of scope

- Orchestrators (ticket 08).
- Projector dispatch (ticket 11). `SyncAgents` is widened in type
  here; the kind branch lands in ticket 11.

## Acceptance

- All list-typed effect signatures updated.
- Existing skill-only test_graph scenarios pass byte-identically.
- `ListTypedHandlerSubstitutabilityTest` passes for all sweeps.
