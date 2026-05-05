# 15 — Test_graph parallel `*Plugin*` nodes + new fixtures

**Phase**: F — Verification + ship
**Depends on**: 11
**Spec**: § "Testing strategy → Layer 1".

## Goal

Replicate every existing skill test_graph node for plugins, plus add
plugin-specific nodes that have no skill equivalent. Land all new
fixtures.

## What to do

### New fixtures

Under `test_graph/fixtures/`:

| Fixture | Mirrors |
| --- | --- |
| `echo-plugin-template/` | `echo-skill-template/` |
| `echo-plugin-stdio-template/` | `echo-skill-stdio-template/` |
| `mcp-tool-loads-plugin/` | `mcp-tool-loads-skill/` |
| `umbrella-plugin/` | `umbrella-skill/` |
| `formatter-plugin/` | `formatter-skill/` |
| `mixed-plugin/` | (new — heterogeneous transitive refs) |
| `cross-ref-skill/` | (new — bare skill referencing a plugin) |
| `private-collision-plugin/` | (new — name collision with bare skill) |

Each fixture is the minimal directory that exercises the corresponding
scenario. Use the shared `_lib/` fixture builders (introduced in
ticket 01) where possible.

### Parallel `*Plugin*` nodes

For every node in the spec's parallel-nodes table, add a corresponding
JBang `Node` under `test_graph/sources/smoke/` (or
`source-tracking/`):

| Existing | New |
| --- | --- |
| `HelloPublished` / `HelloInstalled` | `HelloPluginPublished` / `HelloPluginInstalled` |
| `EchoStdioSkillInstalled` | `EchoStdioPluginInstalled` |
| `EchoHttpSkillInstalled` / `EchoHttpDeployed` | `EchoHttpPluginInstalled` / `EchoHttpPluginDeployed` |
| `EchoSessionSkillInstalled` / `EchoGlobalSkillInstalled` | `EchoSessionPluginInstalled` / `EchoGlobalPluginInstalled` |
| `UmbrellaInstalled` | `UmbrellaPluginInstalled` |
| `TransitiveClisPresent` | `TransitivePluginClisPresent` |
| `AgentSkillSymlinks` | `AgentPluginSymlinks` |
| `AgentConfigsCorrect` | `AgentConfigsCorrectPlugin` |
| `OwnershipRecorded` | `OwnershipRecordedPlugin` |
| `SkillSynced` | `PluginSynced` |
| `SkillUninstalled` | `PluginUninstalled` |
| `McpToolInvoked` | `McpToolInvokedFromPlugin` |
| `McpToolLoadsBundled` / `McpToolLoadsInstalled` | `McpToolLoadsFromPlugin` |
| `SemverEnforced` / `ImmutabilityEnforced` | `SemverEnforcedPlugin` / `ImmutabilityEnforcedPlugin` |
| `SourceFixturePublished` / `SourceFixtureInstalled` | `SourceFixturePluginPublished` / `SourceFixturePluginInstalled` |
| `SourceSyncMergesClean` / `...ProducesConflict` / `...RefusesOnDirty` / `...RefusesWithoutFrom` | corresponding `...Plugin*` |
| `SourceSyncAllAggregates` | `SourceSyncAllAggregatesPlugin` |

### Plugin-specific nodes (no skill parallel)

| Node | Verifies |
| --- | --- |
| `PluginContainedSkillNotAddressable` | `skill-manager install <contained-skill-name>` fails after parent plugin is installed |
| `PluginUninstallReWalkPreventsOrphan` | uninstall removes contained-skill MCP server (re-walk works in production) |
| `SkillReferencesPlugin` | `skill_references = ["plugin:..."]` triggers transitive plugin install |
| `PluginReferencesSkill` | `references = ["skill:..."]` triggers transitive skill install |
| `PluginCycleDetected` | cycle reported at plan time |
| `PluginPluginJsonDriftWarns` | toml-vs-`plugin.json` drift warns, doesn't error |
| `PluginMcpDoubleDeclarationWarns` | declaring same MCP in `.mcp.json` and toml warns |
| `PluginEmptyTomlInstalls` | plugin without `skill-manager-plugin.toml` installs cleanly |
| `LockReproducesInstallSet` | mixed-kind install + delete + `sync --lock` recreates byte-identically |
| `LockUnchangedOnPartialUpgradeFailure` | partial upgrade failure → lock unchanged |
| `MixedKindInstallSetTopologicalOrder` | mixed install set installs in topo order; no premature visibility |

### Smoke graph wiring

Update `test_graph/build.gradle.kts` to include the new nodes in the
default smoke graph. Tag plugin-specific nodes with
`@Tag("plugin")` so they can be filtered.

## Out of scope

- New behavior — every node here exercises behavior already
  delivered by tickets 01–14. If a node fails, the bug is in those
  tickets, not this one.
- Search-related nodes — search behavior is unchanged this round.

## Acceptance

- All parallel `*Plugin*` nodes pass.
- All plugin-specific nodes pass.
- Smoke graph runs cleanly end-to-end.
- Total runtime increase from new nodes is <30% over the
  current smoke graph runtime (parallel execution where possible).
