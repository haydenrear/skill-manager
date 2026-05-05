# 04 — `Resolver` (pure coord → descriptor)

**Phase**: B — Resolution
**Depends on**: 02, 03
**Spec**: § "Architecture", "Coordinate grammar" (resolution table).

## Goal

Introduce a pure `Resolver` that takes a `ParsedCoord` and produces a
`UnitDescriptor`. No side effects, no IO except registry/git lookups
through ports that can be faked. Detects unit kind by inspecting the
on-disk shape of the resolved unit.

## What to do

### New types

```
src/main/java/dev/skillmanager/resolve/
├── UnitDescriptor.java     // immutable: name, kind, version, source, origin, sha, deps, refs
├── Resolver.java           // resolve(ParsedCoord) → Optional<UnitDescriptor>
└── ResolutionError.java    // ambiguity, not-found, multi-kind-collision
```

`UnitDescriptor` contains everything needed by the planner: the name,
kind, version, install source (`REGISTRY` / `GIT` / `LOCAL_FILE`),
origin URL, resolved sha, and parsed deps + references (already
unioned for plugins).

### Resolution rules

- Bare name → registry lookup; first match wins. If both a skill and a
  plugin share the name, return `ResolutionError.MultiKindCollision`
  with the candidate list.
- `skill:name` / `plugin:name` → kind-filtered registry lookup.
- `github:owner/repo` / `git+url` → clone-or-cache the repo (via the
  `Git` port), inspect on-disk shape: `.claude-plugin/plugin.json`
  → plugin, `SKILL.md` at root → skill, neither → error.
- `file:///abs` / `./rel` → same on-disk-shape detection, no clone.

### `Git` port (faked in tests)

```
src/main/java/dev/skillmanager/source/Git.java        // interface
└── GitOps.java                                       // existing impl, now implements the interface
```

Move existing `GitOps` behind a `Git` interface so `FakeGit` (in
`_lib/fakes/`) can stand in. Same for `Registry` if not already
behind one (existing `RegistryClient` likely needs an interface
extraction).

### Test fakes

```
src/test/java/dev/skillmanager/_lib/fakes/
├── FakeGit.java          // clone/fetch/checkout against in-memory repos
└── FakeRegistry.java     // canned descriptors per (name, version)
```

## Tests

```
src/test/java/dev/skillmanager/resolve/
├── ResolverKindFilterTest.java                  // skill:/plugin:/bare; ambiguity errors
├── ResolverHeterogeneousRefsTest.java           // skill→plugin, plugin→skill resolution
├── ResolverContainedSkillNotMatchedTest.java    // contained skills are never resolution targets
├── ResolverDirectGitDetectsKindTest.java        // clone shape → kind
└── ResolverDeterminismTest.java                 // same coord + same registry state → same descriptor
```

Use `ScenarioMatrix` (initial slice) — sweep
`(kind filter × registry state × coord form)` ≈ 30 cells; budget <500ms.

## Out of scope

- Planner / executor — descriptors are produced, nothing consumes them
  yet beyond test assertions.
- Multi-source / marketplace composition (deferred entirely).

## Acceptance

- `Resolver.resolve` is pure: same inputs → same `UnitDescriptor`.
- Multi-kind collisions error with both candidates in the message.
- Direct-git resolution detects kind from on-disk shape.
- Existing install code still uses the old fetch path — this ticket
  only adds resolution; ticket 05 wires the planner against it.
