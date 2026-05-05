# 02 — `UnitReference` + coordinate grammar

**Phase**: A — Foundations
**Depends on**: 01
**Spec**: § "`UnitReference` (heterogeneous deps)", "Coordinate grammar".

## Goal

Replace `SkillReference` with a heterogeneous `UnitReference` and a
formal coord parser that handles `skill:`, `plugin:`, `github:`,
`git+`, `file://`, and bare names with kind inferred at resolution.

## What to do

### New types

```
src/main/java/dev/skillmanager/model/
├── UnitReference.java      // record(coord, kindFilter, name, version, path)
├── UnitKindFilter.java     // enum ANY, SKILL_ONLY, PLUGIN_ONLY
└── Coord.java              // parser: parse(String) → ParsedCoord (sealed sum type)
```

`UnitReference` replaces `SkillReference`. Both `PluginUnit` and
`SkillUnit` carry `List<UnitReference>`. The plugin's effective list
already unions plugin-level + contained — no change to that logic.

### `Coord.parse(String)` produces

| Input | `ParsedCoord` variant |
| --- | --- |
| `name` / `name@v` | `Bare(name, version)` |
| `skill:name` / `skill:name@v` | `Kinded(SKILL, name, version)` |
| `plugin:name` / `plugin:name@v` | `Kinded(PLUGIN, name, version)` |
| `github:owner/repo` / `#ref` | `DirectGit(githubUrl, ref)` |
| `git+https://...#ref` | `DirectGit(url, ref)` |
| `file:///abs` / `./rel` / `/abs` | `Local(path)` |

Keep `SkillParser.parseCoord` as a thin shim that calls `Coord.parse`
and wraps the result in a `UnitReference` so existing call sites
continue to compile.

### Skill manifest extension

`skill-manager.toml`'s `skill_references` array can now contain coords
that resolve to plugins (`"plugin:..."`). The parser yields
`UnitReference`s with the appropriate `kindFilter`. No syntax change
required for purely-skill references.

## Tests

```
src/test/java/dev/skillmanager/model/
├── CoordParserTest.java                  // every grammar form, edge cases
├── CoordRoundTripTest.java               // parse(toString(coord)) == coord
└── UnitReferenceFromTomlTest.java        // skill_references with mixed kinds
```

Each test sweeps all coord forms via `@ParameterizedTest`. Use
`ScenarioMatrix` slicing if it makes sense (ticket 02 introduces a
small slice; the full matrix grows in later tickets).

## Out of scope

- The actual resolver (ticket 04). This ticket only delivers coord
  *parsing* and the in-memory `UnitReference`. No registry/network code.

## Acceptance

- `Coord.parse` handles every grammar form in the spec table.
- Ambiguity (e.g. `skill:` and `plugin:` of same bare name) is *not*
  resolved here — the resolver does that in ticket 04. This ticket
  emits the parsed forms; ambiguity surfaces when the resolver tries
  to bind them.
- Existing `SkillReference` call sites compile against
  `UnitReference` via the shim, with no test_graph regressions.
