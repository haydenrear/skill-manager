# 19 — `sync --lock` reverse-coord engineering for added rows

**Phase**: F — Verification + ship (post-ship refinement)
**Depends on**: 10 (sync --lock landed; this ticket fills its
"added" branch).

## Goal

Today, `skill-manager sync --lock <path>` converges the "bumped"
case (sha drift on existing units) but only logs a warning for
"added" rows (in lock, not on disk). Make it actually install the
missing unit by reverse-engineering an install coord from the
{@code LockedUnit}'s {@code installSource} / {@code origin} /
{@code ref} fields.

## What to do

### Coord recovery mapping

```
src/main/java/dev/skillmanager/lock/CoordFromLock.java
```

```java
public static Resolver.Coord toCoord(LockedUnit u) {
    return switch (u.installSource()) {
        case REGISTRY    -> new Coord(u.name(), u.version());
        case GIT         -> gitCoord(u);   // origin@ref
        case LOCAL_FILE  -> null;          // can't reproduce from lock alone
        case UNKNOWN     -> null;          // no provenance, no recovery
    };
}
```

`gitCoord` translates an `origin` URL + `ref` into the form `Resolver`
expects. Three input shapes the `origin` field can carry today:

- `https://github.com/user/repo[.git]` → `github:user/repo` (with
  `--ref <ref>` or `@<version>` if `version` set).
- `git@github.com:user/repo.git` → SSH form, treat as
  `git+ssh://...`.
- Anything else (custom git host, absolute git URL) → `git+<origin>`.

Local-file rows surface a clear error ("can't reproduce from lock
alone — original install path not recorded") so the user can finish
the reconciliation manually.

### Wire into sync --lock

```
src/main/java/dev/skillmanager/commands/SyncCommand.java
```

The current "added" branch (around line ~190) logs:

```java
Log.warn("not installed: %s — run `skill-manager install %s%s` to converge", ...);
```

Replace with a real install path: build a `Resolver.Coord` per
recoverable row, hand to `Resolver.resolveAll`, run an
`InstallUseCase` program against the union of recovered units. Keep
the warn for `LOCAL_FILE` / `UNKNOWN` — those are genuinely
unrecoverable.

### Tests

```
src/test/java/dev/skillmanager/lock/CoordFromLockTest.java
  - REGISTRY row → Coord(name, version)
  - GIT row + github origin → Coord("github:user/repo", ref)
  - GIT row + ssh origin → Coord("git+ssh://...", ref)
  - LOCAL_FILE / UNKNOWN → null (caller skips with warning)

src/test/java/dev/skillmanager/command/
  SyncFromLockAddedScenarioTest.java
    - lock has alpha@1.0.0 (registry); disk doesn't → sync --lock
      installs alpha
    - lock has beta@2.0.0 (github); disk doesn't → sync --lock fetches
      from github
    - lock has gamma (local-file); disk doesn't → sync --lock warns
      and exits with a non-zero "manual reconciliation needed" code
```

## Out of scope

- Recovering local-file rows from anything outside the lock (the
  install path was never recorded; would require a separate
  `installPath` column).
- "Recover but don't install" plan-only mode (a {@code --dry-run}
  variant; defer until the install path itself is solid).

## Acceptance

- `sync --lock <path>` after a clean uninstall reproduces the
  install set byte-for-byte for registry + git rows.
- Local-file rows get a clear warning + non-zero exit.
- Idempotent — running twice yields the same disk state.
