# 115-direct-git-references — validation summary

Ticket: Traverse direct-git skill references in resolver graphs (GitHub issue #115).

## Divergence closed

`Resolver.resolveAll` dispatched child references on the legacy
`isLocal()` / `isRegistry()` projections. A `Coord.DirectGit` reference
(`github:owner/repo`, `git+<url>`) is neither: it carries no registry name and
no path. Every direct-git reference edge was therefore warned about and
dropped, so transitive git dependencies never resolved and a cycle whose edges
use the recommended coordinate form could never be observed.

The accepted model had the matching gap: `InstallUnit` / `InstallUnitForceScripts` /
`SyncUnit` / `SyncUnitForceScripts` required the whole dependency closure to be a
subset of `server_registry_units`, so a unit reachable only by clone coordinate
could not be modeled at all.

## Model change (whole-program)

- `DirectGitReferenceEdges` / `RegistryReferenceEdges` partition `ReferenceEdges`
  by coordinate kind; the model's cycle now closes over the direct-git edge.
- `GitResolvableUnits` — units nameable by a clone coordinate, resolvable without
  registry publication.
- `ResolvableUnits == server_registry_units \cup GitResolvableUnits`; the four
  resolution guards now admit it.
- Closure (`RefsFor` / `DependencyClosure`) is taken over `ReferenceEdges` whole,
  never over a coordinate-kind subset.

New invariants: `ReferenceClosureIgnoresCoordinateKind`,
`DirectGitCyclesAreRecoverable`,
`GitReferencedUnitsInstallWithoutRegistryPublication`.

## Production change

`CoordSource.of(Coord, baseRoot)` is the single exhaustive projection from a
parsed coord to the `(source, version)` pair `Fetcher` accepts — a direct-git
coord becomes `git+<url>` with the git ref as the version, which is what
`Fetcher` clones and what `Resolver.coordKey` canonicalizes. A `github:` coord
reached as a top-level source and as a reference edge therefore collapses onto
one graph node and one revision-aware cycle key. The resolver, the sync
unmet-reference scan, and project dependency resolution all route through it, so
a new coord shape is a compile error rather than a silently dropped edge.

Project closure walks additionally match a direct-git child back to its installed
unit by recorded git origin, so a git-referenced transitive unit reaches the
project lock and the project child home.

## Evidence

| Check | Result |
|---|---|
| `bash run_tlc.sh SkillManager.tla MC.cfg` (ticket desired) | No error found |
| `tla-spec-dev run spec-unit-tests --ticket 115-direct-git-references` | passed, 2 targets (8 + 7 tests) |
| `jbang RunTests.java` (full unit suite) | ALL PASSED; `ResolverCycleTest` 6/6 |
| `python skills/test_graph/scripts/run.py project-resolve` | BUILD SUCCESSFUL |
| `python skills/test_graph/scripts/run.py project-smoke` | BUILD SUCCESSFUL; `resolver.cycles.verified` 6/6 cases |
| `python skills/test_graph/scripts/run.py --all` | BUILD SUCCESSFUL |

New regression coverage:

- `ResolverCycleTest` — a direct-git child reference is traversed into a
  transitive install; a mutual direct-git cycle resolves each unit once and
  reports its path; a git reference edge keys onto its top-level coord and stays
  revision-aware.
- `ResolverCyclesVerified` (`git-coord-cycle`) — two units naming each other by
  unpinned direct-git coordinate: terminates, exits 0, reports the cycle path,
  installs each unit once, leaks no staging dirs.
- `ProjectDependenciesResolved` — a git-coordinate transitive dependency reaches
  the lock, installs in the home, and is projected into the project child home.

### Clean-home reproduction (live repos)

Resolving the real `github:haydenrear/deploy-cdc` (whose `deploy-helm` manifest
declares `skill_references = ["github:haydenrear/tracing_skill"]`) into a clean
store, driven through `Resolver.resolveAll`:

Before — the reported failure:

```
→ cloning https://github.com/haydenrear/deploy-cdc.git
! skipping reference with no name or path in deploy-helm
--- resolved units (1) ---
  deploy-helm  <- github:haydenrear/deploy-cdc  (top-level)
```

After:

```
→ cloning https://github.com/haydenrear/deploy-cdc.git
→ cloning https://github.com/haydenrear/tracing_skill
--- resolved units (2) ---
  deploy-helm            <- github:haydenrear/deploy-cdc  (top-level)
  tracing-observability  <- git+https://github.com/haydenrear/tracing_skill  requestedBy=[deploy-helm]
--- failures (0) ---
```

The mutual `tracing_skill ↔ deploy-cdc` back-edge does not exist upstream yet —
that is what `haydenrear/tracing_skill#1` is blocked on — so the mutual-cycle
half of the acceptance is covered hermetically instead, by the
`ResolverCycleTest` mutual-git-cycle case and the `git-coord-cycle` graph case,
both of which exercise two units naming each other by direct-git coordinate and
assert the cycle is recoverable.

## Notes

- `MC_program_promotion.cfg` is a long-running whole-program state space: on the
  **unmodified accepted baseline** it generates 6.8M+ states in 5 minutes without
  converging, so it is not a per-ticket gate. The ticket gate is `MC.cfg`, which
  passes. The three new invariants are registered in both configs.
- The misleading `exit=0, errors=1` diagnostic in
  `ProjectDependencyResolver.discoverTopLevelName` is untouched: with the
  reference edge resolving, that failure path is no longer reached for this
  ticket. It remains worth a follow-up.
