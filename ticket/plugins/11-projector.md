# 11 — `Projector` interface + `ClaudeProjector` / `CodexProjector`

**Phase**: D — Orthogonal features
**Depends on**: 08
**Spec**: § "Projector".

## Goal

Extract the per-agent symlink work into a `Projector` interface.
`ClaudeProjector` handles plugin + skill. `CodexProjector` handles
skills only in v1 (pending Codex's plugin story).

## What to do

### Interface

```
src/main/java/dev/skillmanager/project/
├── Projector.java          // interface
├── Projection.java         // record(agentId, sourcePath, targetPath, kind)
├── ClaudeProjector.java    // PLUGIN → ~/.claude/plugins/<name>; SKILL → ~/.claude/skills/<name>
├── CodexProjector.java     // SKILL → ~/.codex/skills/<name>; PLUGIN → no projection (yet)
└── ProjectorRegistry.java  // List<Projector> wired at startup
```

```java
public interface Projector {
    String agentId();
    Path pluginsDir();
    Path skillsDir();
    List<Projection> planProjection(InstalledUnit unit);
    void apply(Projection projection) throws IOException;
    void remove(Projection projection) throws IOException;
}
```

### Replace direct symlinks

The provisional symlink calls added in ticket 08 (in `SyncAgents` and
`UnlinkAgentUnit` handlers) are replaced with calls to the
`ProjectorRegistry`. The handlers iterate over registered projectors
and call `apply` / `remove` per unit.

### Compensation update

`Project(unit, agent)` compensation `UnprojectIfOrphan(unit, agent)`
flows through `Projector.remove`. Already wired in ticket 09's
compensation table — this ticket just makes the implementation real.

## Tests

```
src/test/java/dev/skillmanager/project/
├── ClaudeProjectorTest.java        // sweep UnitKind: PLUGIN → plugins/<name> symlink; SKILL → skills/<name>
├── CodexProjectorTest.java         // SKILL → ~/.codex/skills/<name>; PLUGIN → no-op (returns empty Projection list)
└── ProjectorRegistryTest.java      // applies all registered projectors per install/uninstall
```

Sweep: `(unit kind × agent × pre-state with/without conflicting symlink)`.

## Out of scope

- Per-agent layout *transformation* for plugins (e.g. extracting
  contained skills into Codex's `skills/` tree). Defer until Codex's
  plugin story stabilizes.
- A registry of more than two agents (Cursor, Zed, etc.) — the
  interface accepts new impls without further refactoring.

## Acceptance

- All agent symlink writes route through `Projector.apply`.
- `ClaudeProjector` and `CodexProjector` produce the documented
  projections.
- Plugin installs land in `~/.claude/plugins/<name>` (symlink to store);
  Codex receives no plugin symlink.
- Skill installs unchanged byte-for-byte from current behavior.
