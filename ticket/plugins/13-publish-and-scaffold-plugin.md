# 13 — Publish + `ScaffoldPlugin` for plugins

**Phase**: E — Surface
**Depends on**: 11
**Spec**: § "Publish flow", "Manifest model" (plugin layout).

## Goal

`skill-manager publish [<dir>]` detects plugin vs bare skill and bundles
appropriately. `skill-manager create plugin <name>` scaffolds a new
plugin directory with the minimum file set.

## What to do

### Publish detection

```
src/main/java/dev/skillmanager/registry/SkillPackager.java
```

At publish time, inspect `<dir>`:
- `.claude-plugin/plugin.json` exists → plugin. Bundle `skills/`,
  `commands/`, `agents/`, `hooks/`, `.mcp.json`,
  `skill-manager-plugin.toml`, `plugin.json`.
- `SKILL.md` at root → bare skill (existing behavior).

The bundle format (tar.gz today) stays the same; only the file
inclusion list differs.

### `ScaffoldPlugin` effect

```
src/main/java/dev/skillmanager/effects/SkillEffect.java
```

Add `ScaffoldPlugin(Path dir, String pluginName, Map<String, String> files)`
parallel to existing `ScaffoldSkill`. The file map for a plugin scaffold:

- `.claude-plugin/plugin.json` — minimal valid plugin manifest
- `skill-manager-plugin.toml` — empty `[plugin]` section + name
- `skills/.gitkeep` — directory placeholder

`CreateCommand` learns a `--kind plugin` flag (default `skill`) and
routes to the right effect.

### Registry server side

The registry server (`server-java/`) accepts plugin bundles. The
server's database needs a `unit_kind` column on the published units
table; existing rows default to `skill`. Migration:

```
server-java/src/main/resources/db/migration/V<N>__add_unit_kind.sql
```

Search/list endpoints surface `kind` in their response payloads.

## Tests

```
src/test/java/dev/skillmanager/registry/
├── PublishDetectsPluginTest.java         // .claude-plugin/plugin.json present → plugin bundle includes plugin contents
└── PublishDetectsSkillTest.java          // SKILL.md at root → skill bundle (regression)
```

```
src/test/java/dev/skillmanager/effects/
└── ScaffoldPluginTest.java               // file map writes the expected files
```

```
src/test/java/dev/skillmanager/command/
└── CreatePluginScenarioTest.java         // create plugin + publish round-trip via fakes
```

Server-side: integration tests for the migration and the new API
fields under `server-java/src/test/`.

## Out of scope

- Pushing to marketplaces (deferred entirely).
- Full schema validation of `plugin.json` against Claude's spec
  (defer; warn-on-malformed is enough for now).

## Acceptance

- `skill-manager publish <plugin-dir>` produces a bundle that, when
  installed, recreates the plugin byte-for-byte.
- `skill-manager create plugin <name>` scaffolds a runnable empty
  plugin.
- Existing skill publish continues to work unchanged.
- Server migration applies cleanly; existing rows have `unit_kind=skill`.
