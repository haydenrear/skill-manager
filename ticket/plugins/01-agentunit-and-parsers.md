# 01 — `AgentUnit` foundation + `PluginParser`

**Phase**: A — Foundations
**Depends on**: —
**Spec**: [`../plugin-marketplace.md`](../plugin-marketplace.md) §
"`AgentUnit` (shared abstraction)", "Manifest model".

## Goal

Introduce the shared `AgentUnit` abstraction and a parser that produces
either a plugin or a skill from a directory. Existing skill flow stays
green.

## What to do

### New types

```
src/main/java/dev/skillmanager/model/
├── AgentUnit.java          // sealed permits PluginUnit, SkillUnit
├── PluginUnit.java         // plugin layout
├── SkillUnit.java          // wraps existing Skill record
├── ContainedSkill.java     // internal record on PluginUnit; not an AgentUnit
├── UnitKind.java           // enum PLUGIN, SKILL
└── PluginParser.java       // .claude-plugin/plugin.json + skill-manager-plugin.toml + walk skills/
```

`SkillUnit` wraps the existing `Skill` record so all current skill call
sites keep working. Add a `Skill.asUnit()` accessor that returns
`SkillUnit`. Don't widen any other call sites yet — that's tickets 06–08.

### `PluginParser` reads

- `<root>/.claude-plugin/plugin.json` — `name`, `version`, `description`,
  `mcpServers` (kept for ticket 09's double-registration warning).
- `<root>/skill-manager-plugin.toml` — `[plugin]`, `references`,
  `[[cli_dependencies]]`, `[[mcp_dependencies]]`. Identity precedence:
  toml > `plugin.json` > directory name. Warn (don't error) on mismatch.
- `<root>/skills/*/` — each subdir with `SKILL.md` is a `ContainedSkill`.
  Parse its `skill-manager.toml` if present.

### Effective dep set

`PluginUnit.cliDependencies()`, `mcpDependencies()`, `references()`
return the **union** of plugin-level + every contained skill's. Compute
once at parse time; conflicts (same name, different version) flow
through the existing `cli-lock.toml` machinery in later tickets.

### Test substrate (bootstrap of `_lib/`)

```
src/test/java/dev/skillmanager/_lib/
├── fixtures/
│   ├── DepSpec.java         // CLI deps, MCP deps, references — kind-agnostic
│   ├── ContainedSkillSpec.java
│   ├── UnitFixtures.java    // buildEquivalent(kind, DepSpec) → AgentUnit
│   └── PreStates.java       // canned UnitStore states (will grow per ticket)
└── fakes/
    └── InMemoryFs.java      // initial version: read/write, symlinks. Will grow.
```

`DepSpec` must be reusable from JBang (test_graph) — keep it dependency-free.

## Tests

```
src/test/java/dev/skillmanager/model/
├── PluginParserTest.java          // parses minimal plugin, with and without toml
├── PluginParserDriftWarnsTest.java
├── EffectiveDepUnionTest.java     // union of plugin-level + contained
└── SkillUnitWrapsSkillTest.java   // existing skill behavior unchanged
```

## Out of scope

- Resolver / planner / executor wiring (tickets 04–08).
- `UnitReference` (ticket 02).
- Renaming `SkillSource` (ticket 03).
- Test_graph fixtures (ticket 15).

## Acceptance

- `AgentUnit` sealed interface with two permits.
- `PluginParser` produces a `PluginUnit` from a sample plugin directory
  with one contained skill, and the unioned deps match.
- Existing skill flow is byte-identical (no test_graph regressions).
- Layer-2 tests above are green.
