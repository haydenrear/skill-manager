# 06 — Widen leaf effects (name-keyed)

**Phase**: C — Effects
**Depends on**: 05
**Spec**: § "Effect graph: plugin/skill substitutability →
Required widening order".

## Goal

Widen the leaf effects that take `(String skillName, X dep)` to
`(String unitName, X dep)`. These are the smallest blast-radius
changes — they hold one row of work, no `List<Skill>`, no
orchestration.

## What to do

### Effects to widen

In `dev.skillmanager.effects.SkillEffect`:

| Effect | Change |
| --- | --- |
| `RunCliInstall(skillName, dep)` | rename field `skillName → unitName` |
| `RegisterMcpServer(skillName, dep, gateway)` | rename field |
| `UnregisterMcpOrphan(serverId, gateway)` | unchanged (no skill dep already) |
| `AddSkillError(skillName, kind, message)` | rename to `AddUnitError`, field `unitName` |
| `ClearSkillError(skillName, kind)` | rename to `ClearUnitError` |
| `ValidateAndClearError(skillName, kind)` | rename field `unitName` |
| `RejectIfAlreadyInstalled(skillName)` | rename field `unitName` |

### Handler updates

Each handler in `effects/*Handler.java` updates to read `unitName` and
look up the unit via `EffectContext.source(unitName)` (which now
returns `Optional<InstalledUnit>` carrying the kind from ticket 03).

The handlers don't dispatch on kind for these effects — the work is
the same regardless. This is the substitutability claim made concrete
at the leaf level.

### `EffectContext` adjustment

`EffectContext.source(name)` returns `Optional<InstalledUnit>` (already
done in ticket 03). Existing accessor `EffectContext.sources()`
returns `Map<String, InstalledUnit>` — unchanged in shape, just typed.

## Tests

```
src/test/java/dev/skillmanager/effects/
└── HandlerSubstitutabilityTest.java     // @ParameterizedTest(UnitKind) — leaf handlers behave identically
```

Sweep: `(unit kind × dep variant)` for each leaf effect. ~30 cells per
test method; <100ms each.

Use `TestHarness` (introduced here):

```
src/test/java/dev/skillmanager/_lib/harness/
└── TestHarness.java     // wires fakes into LiveInterpreter, returns observable receipts/state
```

`TestHarness` is the substrate for tickets 06–10. It uses the real
`LiveInterpreter` against `InMemoryFs` + `FakeGateway` + `FakeRegistry`
+ `FakeGit` + `FakeProcessRunner`, so handlers run for real and
state transitions are observable.

## Out of scope

- List-typed effects (ticket 07).
- Orchestrators / string-keyed effects with kind-dependent dispatch
  (ticket 08).
- Compensations (ticket 09).

## Acceptance

- All leaf effect renames complete; existing call sites updated.
- `HandlerSubstitutabilityTest` passes for every leaf effect across
  both `UnitKind` values.
- No test_graph regressions.
- `TestHarness` substrate landed and minimally exercised.
