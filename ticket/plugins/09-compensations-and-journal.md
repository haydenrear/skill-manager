# 09 — Compensations + rollback journal + plugin uninstall re-walk

**Phase**: C — Effects
**Depends on**: 08
**Spec**: § "Plan/effects model (compensations)", "Uninstall flow".

## Goal

Make every install/upgrade/uninstall a single transaction with declared
compensations. On failure, the journal walks back fully — no
half-applied state on store, gateway, or agent dirs. Plugin uninstall
re-walks contained skills to recover the effective dep set so
`*_IfOrphan` compensations don't leak.

## What to do

### Journal

```
src/main/java/dev/skillmanager/effects/
├── RollbackJournal.java        // append-only journal of executed effects
├── Compensation.java           // sealed sum: DeleteClone, RestorePreviousCheckout, ...
└── Executor.java               // runs Program with journal + compensation walk
```

The journal lives at `$SKILL_MANAGER_HOME/journals/<install-id>/` until
the install commits, then archives/prunes.

### Compensation table (from spec)

| Effect | Compensation |
| --- | --- |
| `CloneRepo(url, dest)` | `DeleteClone(dest)` |
| `CheckoutRef(repo, ref)` | `RestorePreviousCheckout(repo, prev_ref)` |
| `RunCliInstall(spec)` | `UninstallCliDependencyIfOrphan(spec)` |
| `RegisterMcpServer(spec)` | `UnregisterMcpServerIfOrphan(spec)` |
| `WriteInstalledUnit(record)` | `RestoreInstalledUnit(prev_or_none)` |
| `Project(unit, agent)` | `UnprojectIfOrphan(unit, agent)` |
| `UpdateUnitsLock(diff)` | (deferred to ticket 10) |

`*_IfOrphan` handlers consult `EffectContext.sources()` — if the dep
is still claimed by another installed unit, the compensation is a
no-op.

### Re-route commands

`InstallCommand`, `UpgradeCommand`, `UninstallCommand` build a
`Program` and run it through `Executor` (which wraps `LiveInterpreter`
with journal + compensation logic). Existing rollback code that lives
in the commands themselves goes away.

### Plugin uninstall re-walk

`UninstallCommand` for a plugin must re-parse `<plugin>/skills/*/skill-manager.toml`
to recover the effective dep set before emitting `*_IfOrphan` effects.
Without this, an MCP server declared by a contained skill leaks on
uninstall.

Add to `dev.skillmanager.cli.installer` (or wherever uninstall plan
build lives):

```java
EffectiveDepSet recover(PluginUnit p) {
    // re-walk plugin's skills/ dir on disk, union with plugin-level toml
}
```

## Tests

```
src/test/java/dev/skillmanager/effects/
├── CompensationPairingTest.java        // every effect emitted on success has a registered compensation
├── CompensationOrphanTest.java         // *_IfOrphan respects surviving owners across UnitKind
├── ProgramComposabilityTest.java       // Program.then composes plugin + skill installs without kind glue
└── FailureInjectionSweepTest.java      // contract: force fail at every step idx, assert journal walks back
```

`FailureInjectionSweepTest` is the high-value sweep: for each command,
for each step index, force the corresponding fake to fail and assert
no leaked state. ~hundreds of cells; budget <5s for the whole sweep.

```
src/test/java/dev/skillmanager/command/
└── UninstallScenarioTest.java          // contract #4: no orphan registrations
```

Sweep: `(unit kind × pre-state with deps held by other units × dep mix)`.

## Out of scope

- Lock atomicity (ticket 10) — `UpdateUnitsLock` and its compensation
  arrive there.
- `ScaffoldPlugin` (ticket 13).

## Acceptance

- Every install/upgrade/uninstall runs through `Executor`.
- `CompensationPairingTest` enumerates the effect surface and asserts
  every effect has a paired compensation.
- `FailureInjectionSweepTest` passes — no leaked state under any
  failure injection.
- Plugin uninstall re-walks contained skills; `UninstallScenarioTest`
  with a plugin pre-state confirms no orphan MCP/CLI rows after
  uninstall.
