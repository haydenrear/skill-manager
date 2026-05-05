# 10 — `units.lock.toml` + atomic flip + `sync --lock`

**Phase**: D — Orthogonal features
**Depends on**: 09
**Spec**: § "`units.lock.toml` (shipping in v1)".

## Goal

Ship the lockfile from day one. Track every installed unit's resolved
sha. Flip the lock atomically only on successful commit. Add
`sync --lock <path>` to reproduce a vendored lock byte-for-byte.

## What to do

### Lock model

```
src/main/java/dev/skillmanager/lock/
├── UnitsLock.java          // record(schemaVersion, List<LockedUnit>)
├── LockedUnit.java         // record(name, kind, version, installSource, origin, ref, resolvedSha)
├── UnitsLockReader.java    // toml parse
├── UnitsLockWriter.java    // toml emit (deterministic ordering)
└── LockDiff.java           // diff(current, target) → set of operations
```

Lock lives at `$SKILL_MANAGER_HOME/units.lock.toml` by default;
`--lock <path>` overrides for project-vendored use.

### New effects

```
SkillEffect.UpdateUnitsLock(LockDiff diff)
SkillEffect.RestoreUnitsLock(UnitsLock prev)         // compensation
```

Lock is read at command start, mutated in-memory, and written *only at
commit*. Compensation walks restore the in-memory copy and never
write a half-applied lock.

### CLI extensions

| Command | Behavior |
| --- | --- |
| `skill-manager lock status` | Show diff between lock and disk reality. |
| `skill-manager sync --lock <path>` | Reconcile disk toward the given lock; idempotent. |
| `skill-manager sync --refresh` | Re-resolve everything, advance lock shas, write. |

`install` / `upgrade` / `uninstall` (existing commands) all call
`UpdateUnitsLock` as their last effect before commit.

## Tests

```
src/test/java/dev/skillmanager/lock/
├── LockReadWriteTest.java          // round-trip across kinds; deterministic ordering
├── LockDiffTest.java               // detects added / removed / sha-bumped rows
├── LockAtomicityTest.java          // contract #3: lock unchanged on partial-failure rollback
└── LockSchemaVersionTest.java      // future-proof: unknown schema_version errors loudly
```

```
src/test/java/dev/skillmanager/command/
├── SyncFromLockScenarioTest.java   // contract #8: idempotence — running sync --lock twice = same disk state
└── (extends) UpgradeScenarioTest.java  // partial-upgrade-failure leaves lock byte-identical to pre-state
```

Sweep `SyncFromLockScenarioTest` over
`(unit kinds in lock × pre-state × dep mix)`.

## Out of scope

- Multi-source / source-id in the lock (deferred — when sources land,
  add `source_id` column).
- Lock signature/verification (deferred).

## Acceptance

- Every install/upgrade/uninstall flips the lock atomically at
  commit; partial-failure paths leave the lock byte-identical.
- `lock status` accurately reports drift.
- `sync --lock <path>` reproduces a known-good install set; running
  it twice yields the same disk state.
