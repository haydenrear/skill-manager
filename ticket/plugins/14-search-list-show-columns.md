# 14 — `kind` + `sha` columns; plugin contained-skills in `show`

**Phase**: E — Surface
**Depends on**: 03, 11
**Spec**: § "Search and list".

## Goal

Surface `kind` and `sha` columns in `list` and `search` output. `show`
for a plugin lists its contained skills (just names — they aren't
addressable) plus its unioned effective deps.

## What to do

### `list`

Add columns:

```
NAME                  KIND    VERSION    SHA       SOURCE
repo-intelligence     plugin  0.4.2      abc123    registry
hello-skill           skill   1.0.0      def456    registry
```

`SHA` is the resolved sha truncated to 7 chars. `KIND` reads from
`InstalledUnit.kind`.

### `search`

Same column additions. Search behavior is unchanged (single-registry,
no marketplace composer — that's deferred).

### `show <name>`

For a plugin:

```
PLUGIN  repo-intelligence@0.4.2  (sha abc123, source registry)
description: ...
contained skills (3):
  - summarize-repo
  - diff-narrative
  - internal-helpers
effective dependencies (unioned):
  CLI:  cowsay==6.0  (declared at: plugin level)
  MCP:  shared-mcp   (declared at: plugin level)
        repo-mcp     (declared at: skills/summarize-repo)
references:
  plugin:shared-tools
  skill:hello-skill@1.0.0
```

For a skill, the existing `show` output is preserved.

## Tests

```
src/test/java/dev/skillmanager/command/
├── ListShowsKindAndShaTest.java
├── SearchShowsKindTest.java
└── ShowPluginListsContainedSkillsTest.java
```

## Out of scope

- Source-id column (deferred until multi-source).
- JSON output mode (defer; keep parity with current text output for
  now).

## Acceptance

- `list` and `search` render the new columns; existing scripts
  parsing the output continue to work (column-based, with stable
  ordering).
- `show <plugin>` lists contained skills and their dep contributions.
- `show <skill>` is byte-identical to current behavior.
