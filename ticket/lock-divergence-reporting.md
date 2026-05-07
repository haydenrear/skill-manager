# Lock divergence: report "added" rows as errors instead of auto-installing

## TL;DR

`skill-manager sync --lock <path>` today converges the **bumped** case
(sha drift on a unit that exists on disk) but only logs a warning for
**added** rows (in lock, not on disk). Plugin-marketplace ticket #19
proposed reverse-engineering an install coord from the lock row's
`installSource` / `origin` / `ref` and running a fresh install.

That's the wrong shape for how `units.lock.toml` is actually used
today. The lock lives in `$SKILL_MANAGER_HOME` and records what
skill-manager itself installed on this machine — an "added" row in
that context means something *went sideways* (a store dir got deleted
out from under us, an install half-failed, the lock was hand-edited).
Auto-installing papers over a real bug.

This ticket replaces #19's auto-install path with **error reporting**:
attach a `LockedUnitError` to the divergent lock row, surface it
alongside per-unit errors in `list` / `status`, and tell the user how
to remediate.

The reverse-coord auto-install can come back later, gated behind a
`--bootstrap` flag, when project-vendored locks become a real workflow.

## Why this shape, not auto-install

Two distinct lock-usage patterns, only one of which is real today:

| Pattern | "Added" row means | Right response |
| --- | --- | --- |
| **Single-machine, today.** Lock at `$SKILL_MANAGER_HOME/units.lock.toml`. Records what skill-manager installed on this machine. | Drift / corruption — a unit that should be on disk isn't. | Surface as an error. Don't silently re-install; the user wants to know their machine is out of sync. |
| **Project-vendored lock, future.** `units.lock.toml` checked into a project repo, teammate clones, runs `sync --lock`. Every row is "added" on day one. | Normal first-time bootstrap. | Auto-install (#19's behavior). Gate behind `--bootstrap` so single-machine drift isn't masked. |

We don't ship project-vendored locks yet. Doing the simple thing now
keeps `sync --lock` honest about real divergence; the auto-install
path can be revived later as a follow-up without re-architecting.

## What to do

### Add `LockedUnitError`

```
src/main/java/dev/skillmanager/lock/LockedUnitError.java
```

```java
public record LockedUnitError(
    Kind kind,
    String detail,
    String remediationHint
) {
    public enum Kind {
        MISSING_FROM_DISK,            // lock row, no store dir
        STORE_PRESENT_NOT_IN_LOCK,    // store dir, no lock row (inverse divergence)
        SHA_MISMATCH_UNRECOVERABLE    // store dir at a sha not reachable from origin/ref
    }
}
```

Errors live on the **lock row** itself (or, for the inverse case, on
the unit's `InstalledUnit` record). Phantom-unit errors don't fit on
`InstalledUnit` because there is no installed unit — the lock row is
the only data we have.

### Extend `LockedUnit`

```
src/main/java/dev/skillmanager/lock/LockedUnit.java
```

Add `List<LockedUnitError> errors` (default empty). Errors are
populated by the sync reconciler, never persisted to
`units.lock.toml` — they are *runtime* divergence findings, not lock
content. Keeping them off-disk also avoids stale-error churn.

### Sync reconciler reports, doesn't install

```
src/main/java/dev/skillmanager/commands/SyncCommand.java
```

The current "added" branch:

```java
Log.warn("not installed: %s — run `skill-manager install %s%s` to converge", ...);
```

Replace with: build a `LockedUnitError(MISSING_FROM_DISK, …, hint)`
where `hint` is a *suggested* install command derived from the lock
row's provenance (the same coord-shape mapping #19 sketched, just
emitted as text in the hint instead of executed). Attach to the lock
row in the in-memory sync report. Sync exits non-zero if any row has
errors.

```
divergence: 2 lock rows do not match disk
  ! repo-intelligence@0.4.2  MISSING_FROM_DISK
    hint: skill-manager install github:me/repo-intelligence@0.4.2
  ! hello-skill@1.0.0        MISSING_FROM_DISK
    hint: skill-manager install hello-skill@1.0.0
```

### Surface in `list` / `status`

`skill-manager list` already shows per-unit errors. Add a divergence
section at the bottom for lock rows that have no corresponding
`InstalledUnit`:

```
NAME              KIND    VERSION    STATE       ERRORS
hello-skill       skill   1.0.0      installed   —
repo-intelligence plugin  0.4.2      installed   —

DIVERGENT (in lock, not on disk):
old-helper        skill   0.3.0      MISSING_FROM_DISK
  hint: skill-manager install old-helper@0.3.0
```

`skill-manager status` (or its equivalent) returns non-zero when
divergence rows are present.

### Inverse divergence

While we're here: a unit on disk but not in the lock is also
divergence. Today this is silent. Surface it as
`STORE_PRESENT_NOT_IN_LOCK` on the `InstalledUnit`'s error list.
Remediation hint: `skill-manager lock add <name>` (or "remove the
unit if you didn't intend to install it").

`lock add` is a one-line helper that re-resolves an installed unit
and writes its row back into the lock. Saves the user from
hand-editing.

## Tests

```
src/test/java/dev/skillmanager/lock/
  LockDivergenceReconcilerTest.java
    - lock has alpha@1.0.0 (registry); disk doesn't
        → reconciler attaches MISSING_FROM_DISK with hint "install alpha@1.0.0"
    - lock has beta@2.0.0 (github origin); disk doesn't
        → hint "install github:user/beta@v2.0.0"
    - lock has gamma (local-file); disk doesn't
        → MISSING_FROM_DISK, hint says "originally local-file install — re-add manually"
    - disk has delta; lock doesn't
        → InstalledUnit.errors contains STORE_PRESENT_NOT_IN_LOCK
        → hint "skill-manager lock add delta"

src/test/java/dev/skillmanager/commands/
  SyncReportsDivergenceScenarioTest.java
    - sync --lock with one MISSING_FROM_DISK row
        → exit code non-zero
        → stdout contains the divergence section + hint
        → no install actions executed (FakeRegistry / FakeGit see zero calls)
    - sync --lock with one bumped row (existing behavior)
        → still converges (re-checkout)
        → exits zero if no other divergence
```

## Out of scope

- **Reverse-coord auto-install** (#19's original behavior). Comes back
  later behind `--bootstrap` if we ship project-vendored locks.
- **Tracking original install path for `LOCAL_FILE` rows.** Would
  enable hint to say "re-install from `<path>`" for that case. Easy
  follow-up; not blocking.
- **Auto-removing store dirs not in the lock.** The
  `STORE_PRESENT_NOT_IN_LOCK` case reports only — never deletes.

## Acceptance

- `sync --lock` converges bumped rows (unchanged) and reports
  added/inverse rows as errors with non-zero exit.
- `list` shows a `DIVERGENT` section when divergence is present.
- `LockedUnitError` and `InstalledUnit.errors` are the single source
  of truth for divergence reporting; no other code path warns about
  the same condition.
- No silent auto-install. Reverse-coord engineering happens *only*
  inside the hint-string builder.

## Relationship to other tickets

- **Replaces** plugin-marketplace #19's "fills the added branch"
  behavior. The coord-recovery mapping #19 introduced lives on as
  hint-string generation only.
- **Independent** of bindings (#49). Lock divergence is store-side;
  bindings are projection-side. They share no code.
- **Compatible** with future project-vendored locks. The
  `--bootstrap` flag re-introducing auto-install would consume the
  same `MISSING_FROM_DISK` rows the reconciler already emits — it
  would loop over them and run install per row instead of just
  printing hints.
