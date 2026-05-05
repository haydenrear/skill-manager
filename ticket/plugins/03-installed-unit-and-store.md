# 03 — `InstalledUnit` + `UnitStore` rename + migration

**Phase**: A — Foundations
**Depends on**: 01
**Spec**: § "Storage layout", "`InstalledUnit` (replaces `SkillSource`)".

## Goal

Rename `SkillSource` → `InstalledUnit`, `SkillSourceStore` → `UnitStore`.
Add `pluginsDir()`, `unitDir(name, kind)`. Move per-unit metadata from
`$SKILL_MANAGER_HOME/sources/` to `installed/` with an automatic
migration on first run.

## What to do

### Rename + extend

```
src/main/java/dev/skillmanager/source/
├── InstalledUnit.java      // renamed from SkillSource, adds `kind` field
├── UnitStore.java          // renamed from SkillSourceStore
└── (remove)                // SkillSource.java, SkillSourceStore.java
```

`InstalledUnit` adds `UnitKind kind` (defaults to `SKILL` when reading
legacy records). Keep all existing fields (`installSource`, `origin`,
`gitHash`, `gitRef`, `installedAt`, `errors`) and the `SkillError` /
`ErrorKind` shape — same enum values, just renamed to `UnitError`.

### `SkillStore` extensions

Add to `dev.skillmanager.store.SkillStore`:
- `Path pluginsDir()` → `<root>/plugins`
- `Path unitDir(String name, UnitKind kind)` → routes to `pluginsDir()`
  or `skillsDir()`
- `Path installedDir()` → `<root>/installed` (new — replaces
  `sourcesDir()`)
- Keep `sourcesDir()` for one release as a deprecated accessor that
  returns `installedDir()`.
- `init()` ensures `pluginsDir` and `installedDir` exist.

Don't rename `SkillStore` itself yet — too much surface area. Defer
that to a follow-up; the per-unit accessors are what callers use.

### Migration

In `SkillReconciler` (the lifecycle reconciler), on first run after
upgrade:

1. List `<root>/sources/*.json`.
2. For each: read, set `kind = SKILL`, write to
   `<root>/installed/<name>.json`, delete the old file.
3. Idempotent — if `installed/<name>.json` already exists, skip.

Log one line per migrated file at INFO so users see what happened.

## Tests

```
src/test/java/dev/skillmanager/store/
├── UnitStoreDirChoiceTest.java          // unitDir(name, PLUGIN) → plugins/<name>; SKILL → skills/<name>
├── InstalledUnitRoundTripTest.java      // JSON read/write, kind field defaults to SKILL on legacy reads
└── MigrationFromSkillSourceTest.java    // sources/<name>.json → installed/<name>.json with kind=SKILL, idempotent
```

`MigrationFromSkillSourceTest` runs against `InMemoryFs` — fast,
deterministic, no real disk.

## Out of scope

- The lock file (ticket 10).
- Rewiring effect handlers to use `unitDir(kind)` — defer to ticket 08
  (orchestrator widening). Until then, plugin installs route via
  `unitDir(name, PLUGIN)` directly from the few places that already
  know they're handling a plugin.

## Acceptance

- All existing call sites of `SkillSource` / `SkillSourceStore` updated
  to `InstalledUnit` / `UnitStore`.
- Reconciler migrates legacy `sources/*.json` once and is idempotent
  on subsequent runs.
- Test_graph nodes that read `sources/` (e.g. `OwnershipRecorded`)
  updated to read `installed/`. No behavioral regressions.
