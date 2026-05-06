# 17 — Full test_graph `*Plugin*` parallel sweep

**Phase**: F — Verification + ship
**Depends on**: 15 (minimal subset already landed), 18 (server-side `unit_kind`)
**Spec**: § "Testing strategy → Layer 1" (parallel-nodes table) — same as ticket 15.

## Goal

Land the rest of the parallel `*Plugin*` nodes ticket 15 sketched out
but didn't fully wire. Promote the `plugin-smoke` graph onto the
default smoke run.

## What to do

### Fixture mirrors (7)

Under `test_graph/fixtures/`:

| Fixture | Mirrors |
| --- | --- |
| `echo-plugin-template/` | `echo-skill-template/` |
| `echo-plugin-stdio-template/` | `echo-skill-stdio-template/` |
| `mcp-tool-loads-plugin/` | `mcp-tool-loads-skill/` |
| `umbrella-plugin/` | `umbrella-skill/` |
| `formatter-plugin/` | `formatter-skill/` |
| `mixed-plugin/` | (new — plugin with heterogeneous transitive refs) |
| `cross-ref-skill/` | (new — bare skill referencing a plugin) |
| `private-collision-plugin/` | (new — name collision with bare skill) |

Each one is a runnable plugin/skill dir with the minimum content needed
to exercise its scenario. Keep them small — these are fixtures, not
documentation.

### Parallel `*Plugin*` nodes (~27 remaining)

The minimal subset (`HelloPluginPublished`, `HelloPluginInstalled`,
`PluginContainedSkillNotAddressable`) shipped in ticket 15. Add the
rest:

| Existing skill node | Plugin parallel |
| --- | --- |
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

### Plugin-specific nodes (8 remaining; 3 done)

| Node | Verifies | Done? |
| --- | --- | --- |
| `PluginContainedSkillNotAddressable` | install of contained name fails | ✅ ticket 15 |
| `PluginUninstallReWalkPreventsOrphan` | contained MCP unregistered on uninstall | |
| `SkillReferencesPlugin` | `skill_references = ["plugin:..."]` triggers transitive plugin install | |
| `PluginReferencesSkill` | `references = ["skill:..."]` triggers transitive skill install | |
| `PluginCycleDetected` | cycle reported at plan time | |
| `PluginPluginJsonDriftWarns` | toml-vs-`plugin.json` drift warns, doesn't error | |
| `PluginMcpDoubleDeclarationWarns` | declaring same MCP in `.mcp.json` and toml warns | |
| `PluginEmptyTomlInstalls` | plugin without `skill-manager-plugin.toml` installs cleanly | |
| `LockReproducesInstallSet` | mixed-kind install + delete + `sync --lock` recreates byte-identically | |
| `LockUnchangedOnPartialUpgradeFailure` | partial upgrade failure → lock unchanged | |
| `MixedKindInstallSetTopologicalOrder` | mixed install set installs in topo order | |

### Smoke graph wiring

Once ticket 18 lands server-side `unit_kind` so plugin publish/install
goes through cleanly against the registry, fold `plugin-smoke` into the
default `smoke` graph and tag plugin-specific nodes with
`@Tag("plugin")` so they can be filtered.

## Out of scope

- New plugin behavior — every node here exercises behavior already
  delivered by tickets 01–14. If a node fails, the bug is in those
  tickets, not this one.
- Search-related nodes — search behavior is unchanged.

## Acceptance

- All parallel `*Plugin*` nodes pass.
- All plugin-specific nodes pass.
- `plugin-smoke` folded into default smoke graph; runtime increase
  <30% over the current smoke.
