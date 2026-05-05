# Plugins as installable units

## TL;DR

Skill-manager learns to install **plugins** alongside the bare **skills** it
already manages. A plugin uses Claude Code's existing layout
(`<root>/.claude-plugin/plugin.json`) plus a new `skill-manager-plugin.toml`
sidecar at the plugin root for skill-manager-specific metadata (CLI deps,
MCP deps, references). Skills inside a plugin keep their own
`skill-manager.toml`. Plugin-level deps and contained-skill deps are unioned
at install time.

The shared abstraction is **`AgentUnit`** — a sealed interface that both
plugins and bare skills implement. Most existing CLI commands operate on
`AgentUnit` and don't branch on the subtype; only the small minority of
code that must walk plugin contents or read `plugin.json` cares about the
distinction.

References are now **bi-directional**: a plugin can reference plugins and
skills, and a skill can reference plugins and skills. The reference graph
is one DAG over heterogeneous nodes.

The architectural backbone is **Resolver → Planner → Executor → Projector**.
Resolver pins a coord to an immutable descriptor. Planner expands the
descriptor into a DAG of effects. Executor applies effects with declared
compensations and a rollback journal. Projector materializes installs into
each agent's directory layout.

We ship `units.lock.toml` from day one for reproducible install graphs.
Contained skills inside a plugin are **bundle-internal**: skill-manager
parses each contained skill's `skill-manager.toml` to harvest its CLI
deps, MCP deps, and references (all unioned to the plugin level), but
contained skills are never separately installed, never addressable as
`skill:<name>` coords, and never depend-on-able. If you want a skill to
be transitively reusable, ship it as a bare skill — a plugin owns its
contents.

**Explicitly out of scope this round:**
- Multi-source / "apt sources" / marketplace composition. The single
  registry server stays as-is. `search` keeps its current behavior. We
  may add multiple skill-manager registries later — designed-around, not
  built-in.
- Policy gating around marketplace trust. (`policy.install` still ships,
  see Policy section.)
- Per-agent layout *transformation*. The Claude projector ships;
  others are added when needed.
- Pushing plugins to marketplaces.
- Forcing existing bare-skill publishers to migrate.

Bare-skill installs continue to work unchanged.

## Goals

1. Skill-manager treats plugins and bare skills as `AgentUnit`s. List,
   install, upgrade, uninstall, sync, deps, show — all uniform.
2. A plugin's `skill-manager-plugin.toml` declares plugin-level CLI deps,
   MCP deps, and references. Each contained skill at
   `<plugin>/skills/<x>/skill-manager.toml` declares its own deps and
   references too. At parse time, all of these are unioned into the
   plugin's effective dep set. Contained skills are not separately
   installable.
3. References are heterogeneous: a plugin can reference plugins or
   skills, and a bare skill's `skill_references` can resolve to plugins
   or skills. The resolver only ever lands on a *bare skill* or a
   *plugin* — never on a contained skill, since contained skills aren't
   addressable.
4. Install/upgrade is uniformly transactional: every effect has a
   compensation. The executor commits or rolls back as a whole.
5. Reproducibility from day one: `units.lock.toml` records the resolved
   sha for every installed unit. `skill-manager sync --lock <path>`
   reproduces the install set.
6. Agent visibility is delivered by a `Projector` interface — Claude is
   the first impl; others added without touching planner/executor.

## Non-goals (this ticket)

- Multi-source resolution / `sources.toml` / marketplace composition.
- Trust policy around marketplaces.
- Per-agent layout transformation beyond Claude.
- Publishing plugins to a marketplace via skill-manager.
- Migrating existing bare skills to plugins.

## Architecture

```
                  ┌──────────────┐
   coord ──▶───▶  │   Resolver   │ ──▶  UnitDescriptor (immutable, pinned sha)
                  └──────────────┘
                         │
                         ▼
                  ┌──────────────┐
                  │   Planner    │ ──▶  InstallPlan = DAG of PlanAction
                  └──────────────┘
                         │
                         ▼
                  ┌──────────────┐
                  │   Executor   │ ──▶  Effects + Compensations (journal)
                  └──────────────┘
                         │
                         ▼
                  ┌──────────────┐
                  │  Projector   │ ──▶  ~/.claude/plugins/<x>, ~/.claude/skills/<x>
                  └──────────────┘
```

Boundary rules:

- **Resolver** is pure: coord in, descriptor out. The current
  registry/git/file install paths feed it; resolver doesn't add new
  sources.
- **Planner** is pure: descriptor + current store state in,
  `InstallPlan` out. Walks heterogeneous references.
- **Executor** is the only thing that mutates state. Every effect emits
  a compensation; on failure, compensations run in reverse.
- **Projector** is per-agent. Knows nothing about install transports —
  only about installed units and the agent it serves.

## Concepts and naming

| Term | What it is |
| --- | --- |
| **AgentUnit** | The shared abstraction over plugin and bare skill. Has a name, version, kind, and the deps & references all units carry. |
| **Plugin** | An `AgentUnit` whose root has `.claude-plugin/plugin.json`, optionally `skills/`, `commands/`, `agents/`, `hooks/`, `.mcp.json`, and `skill-manager-plugin.toml`. |
| **Bare skill** | An `AgentUnit` whose root has `SKILL.md` (+ optional `skill-manager.toml`) and is not nested in a plugin. |
| **Contained skill** | A skill at `<plugin>/skills/<name>/`. Bundle-internal: parsed for deps, never separately installed, never addressable as a coord. |
| **UnitDescriptor** | Immutable resolution result. |
| **InstallPlan** | DAG of effects with declared compensations. |
| **Projector** | Per-agent component that materializes installed units into the agent's directory layout. |

## `AgentUnit` (shared abstraction)

```java
public sealed interface AgentUnit permits PluginUnit, SkillUnit {
    String name();
    String version();
    String description();
    UnitKind kind();                              // PLUGIN | SKILL

    List<CliDependency> cliDependencies();        // unioned for plugins
    List<McpDependency> mcpDependencies();        // unioned for plugins
    List<UnitReference> references();             // unioned for plugins

    Path sourcePath();                            // where the unit lives on disk

    enum UnitKind { PLUGIN, SKILL }
}
```

`PluginUnit` carries: the parsed `plugin.json`, the plugin-level toml,
and a `List<ContainedSkill>` (parsed from `<plugin>/skills/*/`). A
`ContainedSkill` is *not* an `AgentUnit` — it's an internal record
holding the contained skill's frontmatter, body, and toml so the
plugin's effective dep set can be computed and removal/upgrade can
walk it. It is never returned by the resolver and never installed
separately.

`SkillUnit` carries: the `SKILL.md` body, frontmatter, and optional
skill-level toml.

The existing `Skill` record becomes the carrier behind `SkillUnit`. Most
call sites that today take `Skill` are widened to `AgentUnit`; they only
branch when they need plugin-specific behavior (walking contained skills,
reading `plugin.json`).

This is the answer to "most commands stay basically the same": `install`,
`list`, `upgrade`, `uninstall`, `remove`, `sync`, `deps`, `show` operate
on `AgentUnit`. The branch — *is this a plugin or a skill?* — happens at
the parser, not the command.

## `UnitReference` (heterogeneous deps)

```java
public record UnitReference(
    String coord,                  // raw coord string, kept for provenance
    UnitKindFilter kindFilter,     // ANY | SKILL_ONLY | PLUGIN_ONLY
    String name,                   // null for direct-git / local
    String version,                // null = unpinned
    String path                    // non-null for local refs
) {}
```

Both `PluginUnit` and `SkillUnit` carry `List<UnitReference>`. For a
plugin, the list is the union of references declared at the plugin
level (`skill-manager-plugin.toml`'s `references`) plus every contained
skill's `skill_references`. The planner walks one DAG over plugins and
bare skills: a plugin referencing a skill, a skill referencing a
plugin, plugin → plugin → skill chains — all handled by the same walk.
Cycles are detected at plan time and error out with the offending
chain.

## Manifest model

### `skill-manager-plugin.toml` at the plugin root

```toml
[plugin]
name = "repo-intelligence"
version = "0.4.2"
description = "Tools for understanding a repo end-to-end."

# References. Heterogeneous: plugins or skills, in either direction.
# These are unioned with every contained skill's `skill_references` to
# form the plugin's effective reference set.
references = [
  "plugin:shared-tools",
  "skill:hello-skill@1.0.0",
  "github:user/some-other-plugin",
  "./relative/path/to/local-skill",
]

# Plugin-level CLI deps. Unioned with each contained skill's cli_dependencies.
[[cli_dependencies]]
spec = "pip:cowsay==6.0"

# Plugin-level MCP deps. Unioned with each contained skill's mcp_dependencies.
[[mcp_dependencies]]
name = "shared-mcp"
[mcp_dependencies.load]
type = "docker"
image = "ghcr.io/me/shared-mcp:1.2.0"
```

There is no `[[exports.skills]]` table. Every directory under
`<plugin>/skills/<x>/` that has a `SKILL.md` is a contained skill; it
contributes its toml's deps and references to the plugin and is
visible to the harness via `~/.claude/plugins/<plugin>/skills/<x>/`,
but it is not separately installable, addressable, or depend-on-able.

If `plugin.json` already provides `name`/`version`/`description`, the
`[plugin]` section may be omitted. When both files have the field,
`skill-manager-plugin.toml` wins and we emit a warning (not an error)
on mismatch.

A plugin with no `skill-manager-plugin.toml` is still installable — its
only side effect is the projector linking it into `~/.claude/plugins/`.
Useful for plugins that are pure agents/hooks/commands.

### `skill-manager.toml` (skill-level, unchanged)

The existing skill manifest format stays exactly as it is today, with
one extension: `skill_references` can now resolve to plugins as well as
skills.

```toml
skill_references = [
  "skill:other-skill",
  "plugin:repo-intelligence",   # NEW: skills can reference plugins
]

[skill]
name = "my-skill"
version = "1.0.0"
```

### Composition rules (plugin → contained skills)

At parse time, the plugin parser walks `<plugin>/skills/*/` and reads
each contained skill's `SKILL.md` + optional `skill-manager.toml`.
From those tomls plus the plugin's own toml, it computes the plugin's
effective dep set:

- `cli_dependencies` = plugin-level ∪ every contained skill's.
  Conflicts (same name, different version) flow through the existing
  `cli-lock.toml` / `CONFLICT` machinery.
- `mcp_dependencies` = plugin-level ∪ every contained skill's. Same
  conflict resolution.
- `references` = plugin-level `references` ∪ every contained skill's
  `skill_references`. Cycles detected at plan time.
- Identity precedence: `skill-manager-plugin.toml` > `plugin.json` >
  inferred from directory name.

Contained skills are kept around as `ContainedSkill` records on the
`PluginUnit` so removal/upgrade can re-walk them (e.g. to know which
MCP servers the plugin currently has registered, even if the
plugin-level toml didn't declare them directly). They are never
exposed as `AgentUnit`s.

### Contained skill addressability

Contained skills are **not** addressable as coords. There is no
`skill:<contained-skill-name>` form, no `<plugin>/<skill>` form,
no separate install. The plugin is the unit of distribution; the
contained skills are its implementation.

The agent harness still sees contained skills via the plugin's
projected directory (`~/.claude/plugins/<plugin>/skills/<x>/`),
because Claude Code reads plugins recursively. Skill-manager does
nothing special to expose or hide them — they're just files inside
the plugin clone.

The trade-off: you lose the ability to depend on a single skill from
a plugin without pulling in the whole plugin. If you want that,
publish the skill as a bare skill, not as a contained skill. This is
the simplification we explicitly chose: it eliminates the
double-management problem (a contained skill referenced both directly
and transitively via its parent plugin) and keeps the resolver's
output set narrow (plugins + bare skills only).

### `plugin.json` — what we read and don't read

Read: `name`, `version`, `description`, `mcpServers` (only for the
double-registration warning).

Don't read / don't write: anything else. Specifically, `plugin.json`'s
`mcpServers` are addressed to the harness, not the gateway. Skill-manager
never registers them with the gateway, and never edits `plugin.json`.

If the same MCP server name appears in both `plugin.json` (or `.mcp.json`)
and `skill-manager-plugin.toml`, we warn at plan time:

```
WARN: MCP server "shared-mcp" is declared in both `.mcp.json` (harness-facing)
      and `skill-manager-plugin.toml` (gateway-facing). skill-manager will
      register the toml entry with the gateway. The harness will separately
      spawn its own .mcp.json copy. If that's not intentional, remove one.
```

We do not auto-dedupe. The runtimes are different.

### Plugin layout (concrete)

```
my-plugin/
├── .claude-plugin/
│   └── plugin.json                  # Claude-facing manifest
├── skill-manager-plugin.toml        # NEW: skill-manager metadata
├── .mcp.json                        # optional, Claude-facing
├── skills/
│   ├── summarize-repo/              # contained (deps unioned to plugin level)
│   │   ├── SKILL.md
│   │   └── skill-manager.toml
│   ├── diff-narrative/
│   │   └── SKILL.md
│   └── internal-helpers/
│       └── SKILL.md
├── commands/
├── agents/
└── hooks/
```

## Coordinate grammar

Coordinates are how users name a unit. Grammar:

```
coord       := kinded | bare | direct | local
kinded      := ("skill:" | "plugin:") name [ "@" version ]
bare        := name [ "@" version ]              # kind inferred at resolve time
direct      := "github:" owner "/" repo [ "#" ref ] | "git+" url [ "#" ref ]
local       := "file://" abs-path | "./" rel | "../" rel | "/" abs

name        := [a-z0-9][a-z0-9_-]*
version     := semver
```

Resolution rules (deterministic):

| Coord | Resolution |
| --- | --- |
| `hello-skill` | Registry lookup; first match wins. If both a skill and a plugin share the name → error, ask user to disambiguate with `skill:` or `plugin:`. |
| `skill:hello-skill` | Registry lookup; only consider bare skills. (Contained skills are never matched.) |
| `plugin:repo-intelligence` | Registry lookup; only consider plugins. |
| `github:me/x` | Direct git, default branch. The clone is inspected on disk for `.claude-plugin/plugin.json` (→ plugin) or `SKILL.md` at root (→ skill) to determine kind. |
| `git+https://github.com/me/x#v1.2.0` | Direct git, pinned ref. Same kind detection. |
| `file:///abs/path` / `./rel` | Local path. Same kind detection. |

Ambiguity (multi-kind same name in the registry) errors with the
candidate list and the exact pinned coords to use. No silent
wrong-pick path. Contained skills never participate in resolution, so
there is no contained-skill ambiguity to resolve.

## Effect graph: plugin/skill substitutability

The codebase just refactored to an effect-program model
(`effects/Program.java`, `effects/SkillEffect.java`,
`effects/LiveInterpreter.java`). The new abstraction must preserve and
extend that model: a plugin and a bare skill should produce
**structurally identical effect graphs**, with `unit.kind()` as data
carried on the descriptor — not as a type that branches the graph
shape. The only place the graph shape can diverge is at the parser; from
the resolved graph onward, every effect should be kind-agnostic.

This is the "swap a plugin for a skill" property: if `repo-intelligence`
resolves to a skill in one configuration and a plugin in another, the
emitted `Program` differs only in the descriptor values — same effect
sequence, same compensations, same `Program.then` composition.

### What's typed on `Skill` today (and what needs to widen)

Survey of the existing effect surface:

| Site | Current type | After |
| --- | --- | --- |
| `SkillEffect.ResolveTransitives` | `List<Skill> skills` | `List<AgentUnit> units` |
| `SkillEffect.InstallTools` | `List<Skill> skills` | `List<AgentUnit> units` |
| `SkillEffect.InstallCli` | `List<Skill> skills` | `List<AgentUnit> units` |
| `SkillEffect.RegisterMcp` | `List<Skill> skills` | `List<AgentUnit> units` |
| `SkillEffect.SyncAgents` | `List<Skill> skills` | `List<AgentUnit> units` |
| `SkillEffect.OnboardSource` | `Skill skill` | `AgentUnit unit` |
| `SkillEffect.RunCliInstall` | `String skillName, CliDependency dep` | `String unitName, CliDependency dep` (no shape change) |
| `SkillEffect.RegisterMcpServer` | `String skillName, McpDependency dep, ...` | `String unitName, McpDependency dep, ...` |
| `SkillEffect.RemoveSkillFromStore` | `String skillName` | `String unitName` |
| `SkillEffect.UnlinkAgentSkill` | `String agentId, String skillName` | `String agentId, String unitName, UnitKind kind` (handler picks `pluginsDir` / `skillsDir`) |
| `SkillEffect.SyncGit` | `String skillName, ...` | `String unitName, ...` |
| `SkillEffect.RejectIfAlreadyInstalled` | `String skillName` | `String unitName` |
| `SkillEffect.AddSkillError` / `ClearSkillError` / `ValidateAndClearError` | `String skillName, ...` | `String unitName, ...` |
| `SkillEffect.ScaffoldSkill` | `Path dir, String skillName, Map<...>` | unchanged for `scaffold skill`; new `ScaffoldPlugin` parallel effect (different file map) |
| `PlanAction.FetchSkill` | `ResolvedGraph.Resolved` (carries `Skill`) | `FetchUnit` carrying a `Resolved` whose `unit` is `AgentUnit` |
| `PlanAction.InstallSkillIntoStore` | `String name, String version` | `InstallUnitIntoStore(String name, String version, UnitKind kind)` |
| `PlanAction.RunCliInstall` / `RegisterMcpServer` | already keyed by `skillName` + dep — rename field only |
| `ResolvedGraph.Resolved` | `Skill skill` | `AgentUnit unit` (kind on the unit) |
| `EffectContext.source(name)` | `Optional<SkillSource>` | `Optional<InstalledUnit>` (carries kind) |
| `SkillStore.skillDir(name)` | one tree | `UnitStore.unitDir(name, kind)` — picks `plugins/` or `skills/` |
| `Agent.skillsDir()` | one path | adds `pluginsDir()`; sync handler uses the unit's kind to choose |

The renames are mechanical. The key observation: **most effects already
take a name + a dep record + a gateway/store handle**. Those don't
change shape — they only need `unitName` to be looked up against
`InstalledUnit` (which carries the kind) instead of `SkillSource` (which
implies skill).

### Where kind actually matters (and is contained)

Five places, all isolated:

1. **Parsing** — `SkillParser` vs. `PluginParser`. The parser is the
   factory that produces `AgentUnit`s. Plugin parser additionally walks
   `<plugin>/skills/*/` and unions deps + references into the
   `PluginUnit`'s effective dep set.
2. **Store directory choice** — `UnitStore.unitDir(name, kind)` picks
   `plugins/` or `skills/`. One method, one switch.
3. **Projector** — Claude projector links `~/.claude/plugins/<x>` for
   plugins, `~/.claude/skills/<x>` for skills. The handler reads
   `unit.kind()` and dispatches.
4. **Scaffold** — `ScaffoldPlugin` is a separate effect from `ScaffoldSkill`
   because the file map differs (`.claude-plugin/plugin.json` +
   `skill-manager-plugin.toml` vs. `SKILL.md` + `skill-manager.toml`).
5. **Removal re-walk** — `UninstallUnit` for a plugin re-parses
   `<plugin>/skills/*/skill-manager.toml` to recover the effective dep set
   so `*_IfOrphan` compensations don't leak. For a skill, no re-walk
   needed. The handler dispatches on `unit.kind()` once.

Everywhere else (CLI install, MCP register, agent sync routing, error
recording, audit log, source-provenance writes) is already keyed on
`(name, dep)` pairs and is structurally kind-agnostic.

### Effect graph composition (the swap)

The planner emits the same backbone for both kinds:

```
[FetchUnit]
[InstallUnitIntoStore]
[EnsureTool ×N]              # union of plugin-level + contained-skill tool needs
[RunCliInstall ×N]           # one per CLI dep in the unit's effective set
[RegisterMcpServer ×N]       # one per MCP dep in the unit's effective set
[OnboardSource]              # writes installed/<name>.json with kind=plugin|skill
[Project ×agents]            # projector picks the right agent dir
```

For a plugin, `effective set = plugin-level toml ∪ each contained
skill's toml`. For a skill, `effective set = skill's toml`. The planner
computes the union from the `AgentUnit` (the parser already did the
work) and emits the same effect shape regardless.

`Program.then` composability falls out for free: an
`install repo-intelligence (plugin)` program can be concatenated with an
`install hello-skill (skill)` program because the effect sequences are
the same shape, the decoders both produce `InstallReport`, and the
`then`-combiner just merges. No kind-aware glue.

Compensations have the same property. The `*_IfOrphan` handlers consult
`EffectContext.sources()` (the unit map keyed by name + kind) and ask
"is this dep still claimed by any surviving unit?" — they don't care
whether the surviving owner is a plugin or a skill.

### Substitution scenarios

The "swap when we see it" property covers three concrete cases:

1. **Coord-kind reinterpretation.** A coord `repo-intelligence` resolves
   to a skill today, plugin tomorrow (registry republished). `upgrade`
   produces the same effect graph backbone; the diff is `kind` on the
   descriptor. The store dir + projector dispatch handle the actual
   plugin vs skill move at execution time.
2. **Heterogeneous transitive deps.** A skill references
   `plugin:foo`, a plugin references `skill:bar`. The planner walks the
   reference DAG without branching: each node yields the same
   `[FetchUnit, InstallUnitIntoStore, ...]` sub-program, concatenated
   via `Program.then`.
3. **Mixed install set.** `install plugin:p1 skill:s1 plugin:p2` builds
   one `Program` whose effects are interleaved by topological order
   over the union of dep graphs. No special handling for the mixed
   case.

### Required widening order (incremental)

To preserve build-greenness during the refactor:

1. Introduce `AgentUnit` sealed interface; make existing `Skill` carry
   `SkillUnit` (delegate to the existing record). Keep `Skill` callsites
   working by exposing `Skill` as a static factory that returns
   `SkillUnit`.
2. Widen `ResolvedGraph.Resolved` to carry `AgentUnit unit`. Keep a
   transitional `skill()` accessor that down-casts (warns at boundary).
3. Widen each `SkillEffect` variant in dependency order: leaves first
   (`RegisterMcpServer`, `RunCliInstall` — name-keyed, smallest blast
   radius), then list-typed (`RegisterMcp`, `InstallCli`, etc.), then
   the orchestrating ones (`ResolveTransitives`, `SyncAgents`).
4. Rename `SkillStore` → `UnitStore`, add `pluginsDir()` and
   `unitDir(name, kind)`. The skills-only path stays the default until
   the first plugin install lands.
5. Add `PluginParser`. From this point on, the resolver can return a
   plugin descriptor and the rest of the graph already speaks `AgentUnit`.
6. Add `Projector` interface. Replace direct symlink calls in
   `SyncAgents` handler with `projector.apply(...)`.

Each step keeps tests green; no flag day.

### What this gives us

- One effect graph shape for both kinds; one `Program` composition story.
- New unit kinds in the future (e.g. "agent" as a top-level unit, if
  Claude grows that) plug in by adding a new `AgentUnit` permit, a
  parser, a store dir, and a projector branch — no effect-layer
  changes.
- Compensations are uniform: `Uninstall*IfOrphan` queries the unit
  map, doesn't care about kind.
- `Program.then` continues to be the only composition primitive.
  Mixed-kind install sets don't need a new operator.

## Storage layout

```
$SKILL_MANAGER_HOME/
├── plugins/                              # NEW
│   └── <plugin-name>/
│       ├── .git/
│       ├── .claude-plugin/plugin.json
│       ├── skill-manager-plugin.toml
│       └── skills/...
├── skills/                               # bare skills only
│   └── <skill-name>/
│       ├── .git/
│       └── SKILL.md
├── installed/                            # per-unit metadata (renamed from sources/)
│   └── <unit-name>.json                  # InstalledUnit records
├── units.lock.toml                       # NEW: reproducible install graph
├── cli-lock.toml
├── policy.toml
└── bin/cli/...
```

Directory rename: today's `$SKILL_MANAGER_HOME/sources/` is per-unit
metadata. We rename it to `installed/` to make space conceptually for
non-registry installs and to free up the noun "source." The reconciler
handles the migration on next run (read old, write new, delete old).

## `units.lock.toml` (shipping in v1)

```toml
# Auto-generated by skill-manager. Do not edit by hand.
schema_version = 1

[[units]]
name             = "repo-intelligence"
kind             = "plugin"
version          = "0.4.2"
install_source   = "registry"        # registry | git | local_file
origin           = "https://github.com/me/repo-intelligence"
ref              = "v0.4.2"           # what we tracked at install time
resolved_sha     = "abc123..."

[[units]]
name             = "hello-skill"
kind             = "skill"
version          = "1.0.0"
install_source   = "registry"
origin           = "https://github.com/skill-manager/hello-skill"
ref              = "v1.0.0"
resolved_sha     = "def456..."
```

Modes:

- `skill-manager install <coord>` — resolves fresh, writes the lock.
- `skill-manager upgrade <name>` — re-resolves, updates the row, writes
  the lock.
- `skill-manager sync` — if the lock differs from disk reality, reconciles
  *toward* the lock (re-clone at the pinned sha, re-run install side
  effects). This is the "reproduce my install set" verb.
- `skill-manager sync --refresh` — re-resolves everything, potentially
  advancing pinned shas, then writes the new lock. (This is what
  `upgrade --all` does today, just spelled in lock terms.)

The lock lives at `$SKILL_MANAGER_HOME/units.lock.toml` by default and
is also vendor-able: a project can check in a `units.lock.toml` and
agents can `skill-manager sync --lock ./units.lock.toml`.

## `InstalledUnit` (replaces `SkillSource`)

```java
public record InstalledUnit(
    String name,
    UnitKind kind,                                // PLUGIN | SKILL
    String version,
    InstallSource installSource,                  // REGISTRY | GIT | LOCAL_FILE
    String origin,
    String gitRef,
    String resolvedSha,
    String installedAt,
    List<UnitError> errors
) {
    public enum InstallSource { REGISTRY, GIT, LOCAL_FILE, UNKNOWN }
}
```

We rename `SkillSource` → `InstalledUnit`, `SkillSourceStore` →
`UnitStore`. Migration: on first run after upgrade, the reconciler reads
each old `sources/<name>.json`, fills `kind=skill`,
`install_source=<existing value>`, and writes to `installed/<name>.json`,
then deletes the old file.

`UnitError` keeps the same shape and enum values as today's
`SkillSource.SkillError`.

## Plan/effects model (compensations)

Every install action is an effect with a compensation:

| Effect | Compensation |
| --- | --- |
| `CloneRepo(url, dest)` | `DeleteClone(dest)` |
| `CheckoutRef(repo, ref)` | `RestorePreviousCheckout(repo, prev_ref)` |
| `InstallCliDependency(spec)` | `UninstallCliDependencyIfOrphan(spec)` |
| `RegisterMcpServer(spec)` | `UnregisterMcpServerIfOrphan(spec)` |
| `WriteInstalledUnit(record)` | `RestoreInstalledUnit(prev_or_none)` |
| `UpdateUnitsLock(diff)` | `RestoreUnitsLock(prev)` |
| `Project(unit, agent)` | `UnprojectIfOrphan(unit, agent)` |

The Executor runs effects in declared order and writes a journal. On
failure or `--abort`, it walks the journal in reverse and runs each
compensation. The journal lives at
`$SKILL_MANAGER_HOME/journals/<install-id>/` until the install commits,
then is archived/pruned.

This consolidates rollback logic that today is sprinkled across
`UpgradeCommand`, `InstallCommand`. Most existing handlers in `effects/`
already speak this shape — `SyncGitHandler`, `SyncFromLocalDirHandler`
slot in cleanly.

## Projector

```java
public interface Projector {
    String agentId();                             // "claude" | "codex" | ...
    Path pluginsDir();                            // ~/.claude/plugins
    Path skillsDir();                             // ~/.claude/skills

    List<Projection> planProjection(InstalledUnit unit);
    void apply(Projection projection) throws IOException;
    void remove(Projection projection) throws IOException;
}
```

Claude projector ships first:

- `PLUGIN` unit → symlink `~/.claude/plugins/<name>` →
  `$SKILL_MANAGER_HOME/plugins/<name>`.
- `SKILL` unit → symlink `~/.claude/skills/<name>` →
  `$SKILL_MANAGER_HOME/skills/<name>` (unchanged from today).

Codex projector ships at the same time but only handles bare skills
(unchanged from today). Codex plugin support is a follow-up — when added,
its projector either symlinks into `~/.codex/plugins/` (if Codex grows a
plugin convention) or transforms the plugin into Codex's expected layout.
The point of the abstraction: that addition doesn't touch
planner/executor.

We do not project plugin contents per-agent in v1. The bet: agents that
learn about plugins follow Claude's directory shape closely enough that
one symlink per plugin is sufficient.

## Policy

`policy.install` ships:

```toml
# ~/.skill-manager/policy.toml
[policy.install]
require_confirmation_for_hooks               = true
require_confirmation_for_mcp                 = true
require_confirmation_for_cli_deps            = true
require_confirmation_for_executable_commands = true
```

Plugins are higher-risk than bare skills (they can carry hooks, agents,
executable commands, MCP servers). The plan-print categorizes the
install:

```
PLAN: install repo-intelligence@0.4.2 (plugin)
  ! HOOKS         : 2 hook scripts (pre-tool, post-tool) — review skills/_helpers/hooks
  ! MCP           : 1 server (shared-mcp) — gateway-registered
  ! CLI           : 1 dependency (cowsay==6.0)
  + COMMANDS      : 4 slash commands
  + AGENTS        : 1 agent
  + SKILLS        : 3 contained (summarize-repo, diff-narrative, internal-helpers)
```

`!` lines require explicit confirmation if their policy flag is true.
`--yes` does not bypass policy; the user must edit `policy.toml`.

Multi-source / marketplace-trust policies are deferred until the source
composer lands.

## Install flow

`skill-manager install <coord>`:

1. **Resolve.** Walk the existing install paths (registry → git URL →
   github → local). Output: `UnitDescriptor`, including `unit_kind`
   detected from disk shape (`.claude-plugin/plugin.json` → plugin,
   `SKILL.md` at root → skill).
2. **Plan.** Walk references (transitive, heterogeneous) to build a DAG.
   Plan-print includes the policy categorization.
3. **Confirm.** Interactive unless `--yes`. Policy `!` lines block
   `--yes`.
4. **Execute.** For each unit in topological order:
   - `CloneRepo` into `$SKILL_MANAGER_HOME/plugins/<name>` or
     `skills/<name>` based on kind.
   - `CheckoutRef <resolved_sha>`.
   - For each unit (plugin: union of plugin-level + contained-skill
     deps; skill: its own): install CLI deps, register MCP deps with the
     gateway.
   - `WriteInstalledUnit`.
   - `Project` per active agent.
   - `UpdateUnitsLock`.
5. **Commit.** Journal pruned, plan complete.

Failure → executor walks the journal in reverse, runs compensations. The
lock is unchanged (it only flips at commit).

Output:

```
INSTALLED: repo-intelligence@0.4.2 kind=plugin sha=abc123 -> /Users/you/.skill-manager/plugins/repo-intelligence
INSTALLED: hello-skill@1.0.0       kind=skill  sha=def456 -> /Users/you/.skill-manager/skills/hello-skill
```

## Upgrade flow

`skill-manager upgrade <name>` / `--all`:

1. Re-resolve.
2. New `UnitDescriptor` → diff against the lock.
3. Plan: `CheckoutRef <new_sha>` + re-register deps + `UpdateUnitsLock`.
4. Execute with compensations. Old sha is the rollback target.

`upgrade --all` is a single transaction — partial failures roll the lock
back to its pre-upgrade state.

## Uninstall flow

`skill-manager uninstall <name>`:

1. Re-parse the unit on disk to recover its full effective dep set.
   For a plugin, this means re-walking `<plugin>/skills/*/skill-manager.toml`
   to know every MCP server and CLI tool the plugin currently has
   registered (since the plugin's effective deps are the union of
   plugin-level + every contained skill's). Without this re-walk we'd
   leak orphan registrations from contained skills.
2. Plan: for each (CLI dep, MCP dep) in the union,
   `UninstallCliDependencyIfOrphan`, `UnregisterMcpServerIfOrphan`.
   Then `UnprojectIfOrphan` per agent, `DeleteClone`,
   `RestoreInstalledUnit(none)`, `UpdateUnitsLock`.
3. Execute. (`if orphan` handlers consult the rest of the lock to decide
   whether the dep is still required by another installed unit.)

## Publish flow

`skill-manager publish [<dir>]` infers the unit kind:

- `<dir>/.claude-plugin/plugin.json` exists → publish as plugin. Bundle
  `skills/`, `commands/`, `agents/`, `hooks/`, `.mcp.json`,
  `skill-manager-plugin.toml`, `plugin.json`.
- `<dir>/SKILL.md` exists at root → publish as bare skill (current).

Plugin publish writes to whichever target the existing `registry` config
points at. Same single registry as today.

## Search and list

`search` is unchanged this round (single-registry, current behavior).

`skill-manager list` gains a `KIND` and `SHA` column:

```
NAME                  KIND    VERSION    SHA       SOURCE
repo-intelligence     plugin  0.4.2      abc123    registry
hello-skill           skill   1.0.0      def456    registry
```

`skill-manager show <name>`:

- Plugin → list of contained skills (just names, since none are
  addressable), unioned effective deps, source/ref/sha.
- Skill → unchanged.

## CLI verb summary (delta vs. today)

New:

- `lock status` — show lock vs. disk diff
- `sync --lock <path>` — reproduce from a vendored lock
- `plugin:<name>` and `skill:<name>` coord forms (kind-pinned lookup)

Unchanged in user-facing shape (extended internally for plugins):

- `install`, `list`, `upgrade`, `uninstall`, `remove`, `sync`, `deps`,
  `search`, `show`, `gateway`, `login`, `policy`, `registry`, `publish`.

## Implementation order

1. **`AgentUnit` + manifest model.** Add `AgentUnit` sealed interface,
   `PluginUnit`, `SkillUnit`. Make existing `Skill` carry `SkillUnit`.
   Add `PluginParser` (reads `plugin.json` + `skill-manager-plugin.toml`).
   Compose plugin deps from contained skills + plugin-level toml.
2. **Heterogeneous references.** `UnitReference` replaces
   `SkillReference`. `kindFilter` lets a coord narrow to skill/plugin.
   Skill manifest's `skill_references` continues to parse but yields
   `UnitReference`s.
3. **Coordinate grammar.** New `Coord` parser; replaces ad-hoc string
   handling in `SkillParser.parseCoord`.
4. **`InstalledUnit` + `UnitStore`.** Rename. Migration in reconciler.
   Move per-unit metadata from `sources/` to `installed/`.
5. **Resolver.** Pure: coord in, descriptor out. Detects unit kind from
   on-disk shape. Errors on multi-kind same-name ambiguity.
6. **Planner.** Widens to `AgentUnit`. Walks plugin contents, unions
   deps, detects ref cycles, emits the policy categorization.
7. **Effect-layer widening.** Widen `SkillEffect` and `PlanAction`
   field types from `Skill` to `AgentUnit` and from `skillName` to
   `unitName`, in the order specified in **Effect graph:
   plugin/skill substitutability → Required widening order**. Each
   step keeps tests green. End state: every effect except
   `ScaffoldSkill` / `ScaffoldPlugin` and the projector dispatch is
   structurally kind-agnostic.
8. **Effects + journal (compensations).** Shape every effect with a
   compensation. Add `RollbackJournal`. Re-route `InstallCommand`,
   `UpgradeCommand`, `UninstallCommand` through the executor. The
   `Uninstall` plan-builder for plugins re-walks
   `<plugin>/skills/*/skill-manager.toml` to recover the effective
   dep set before emitting `*_IfOrphan` effects.
8. **`units.lock.toml`.** Read/write. `lock status`. `sync --lock`.
   Update `install`/`upgrade`/`uninstall` to flip the lock at commit.
9. **Projector.** `Projector` interface. `ClaudeProjector`,
   `CodexProjector` (skills only for now). Replace direct symlink calls.
10. **Policy.** `policy.install` flags into plan-print.
11. **Publish.** Detect plugin vs bare skill at publish dir. Bundle
    plugin contents.
12. **Search/list/show.** Surface `kind` + `sha` columns. Show contained
    skills (just names) for plugins.
13. **Tests.** See the dedicated **Testing strategy** section. Two
    layers, added incrementally — each implementation step above owns
    its tests:
    - **Layer 1 (test graph)**: parallel `*Plugin*` nodes for every
      existing skill scenario in `smoke/` and `source-tracking/`, plus
      plugin-specific nodes for contained-skill non-addressability,
      uninstall re-walk, heterogeneous refs, lock-driven reproduce.
    - **Layer 2 (unit)**: fast combinatorial coverage of command
      contracts with IO mocked at the edge. Sweeps command × unit
      kind × install source × pre-state × dep mix × failure injection
      × policy. Substitutability is one axis. Whole suite under 30s.
14. **Docs.** Update `skill-manager-skill/SKILL.md` and `README.md`.

## Testing strategy

> Layer 2's purpose is **fast combinatorial coverage of command
> contracts with IO mocked at the edge**. The test_graph stays the
> production-fidelity layer — real binaries, real gateway, real git.
> Unit tests get permutation throughput; the test_graph gets fidelity.
> Substitutability across `UnitKind` is one axis of permutation, not
> the only one.

Two layers, addressing different failure modes:

| Layer | What it proves | Where it lives |
| --- | --- | --- |
| **Test graph (system)** | The CLI end-to-end behaves correctly with real binaries, real gateway, real git. Replicates the existing skill matrix (install, sync, upgrade, uninstall, MCP register, agent symlink, ownership, transitive deps) for plugins. | `test_graph/sources/<package>/` (JBang action nodes), with new fixtures under `test_graph/fixtures/`. |
| **Unit (combinatorial contracts)** | Command flows respect their contracts under every reasonable permutation of inputs and failure injections. IO mocked at the edges; thousands of permutations run in seconds. Substitutability across `UnitKind` is one axis among many. | `src/test/java/dev/skillmanager/...` (currently empty — this introduces the directory). |

### Layer 1 — Test graph (replicate existing skill scenarios for plugins)

The existing `test_graph/sources/smoke/` and `source-tracking/` packages
are the spine. Every node that takes a skill action gets a parallel
node that takes the equivalent plugin action. We don't replace; we
parallel. The shared infrastructure nodes (gateway up, env prepared,
agent configs written) stay singular.

#### New fixtures (under `test_graph/fixtures/`)

| Fixture | Purpose | Equivalent skill fixture |
| --- | --- | --- |
| `echo-plugin-template/` | Minimal plugin: `.claude-plugin/plugin.json` + `skill-manager-plugin.toml` + `skills/echo/SKILL.md`. Used by every install/uninstall happy-path test. | `echo-skill-template/` |
| `echo-plugin-stdio-template/` | Plugin variant carrying an MCP stdio dep. | `echo-skill-stdio-template/` |
| `mcp-tool-loads-plugin/` | Plugin whose contained skill declares an MCP dep that requires bundling (`uv` / `npx`). Verifies effective-dep-set rollup. | `mcp-tool-loads-skill/` |
| `umbrella-plugin/` | Plugin with two contained skills, each declaring distinct CLI deps (one pip, one npm). Verifies dep union and conflict surface. | `umbrella-skill/` |
| `formatter-plugin/` | Plugin referencing another plugin (`references = ["plugin:foo"]`). Verifies plugin→plugin transitive resolution. | `formatter-skill/` |
| `mixed-plugin/` | Plugin whose contained skills' `skill_references` point to one bare skill and one plugin. Verifies heterogeneous transitive deps under a plugin install. | (new — no skill equivalent) |
| `cross-ref-skill/` | Bare skill whose `skill_references` includes `plugin:echo-plugin-template`. Verifies skill→plugin transitive resolution. | (new — no skill equivalent) |
| `private-collision-plugin/` | Plugin with a contained skill whose name collides with an installed bare skill. Verifies the contained skill is *not* separately installable and doesn't conflict with the bare skill in the resolver. | (new) |

#### New / parallel test_graph nodes

For every existing smoke node listed below, a `*Plugin*` parallel is
added (same shape, plugin fixture, asserts the plugin-specific paths
under `~/.skill-manager/plugins/` and `~/.claude/plugins/`):

| Existing skill node | Parallel plugin node | What it proves |
| --- | --- | --- |
| `HelloPublished` / `HelloInstalled` | `HelloPluginPublished` / `HelloPluginInstalled` | Registry → client → store commit for plugins. |
| `EchoStdioSkillInstalled` | `EchoStdioPluginInstalled` | MCP stdio dep declared at the plugin level registers with the gateway. |
| `EchoHttpSkillInstalled` / `EchoHttpDeployed` | `EchoHttpPluginInstalled` / `EchoHttpPluginDeployed` | HTTP MCP server flow from a plugin. |
| `EchoSessionSkillInstalled` / `EchoGlobalSkillInstalled` | `EchoSessionPluginInstalled` / `EchoGlobalPluginInstalled` | Scope routing from plugin. |
| `UmbrellaInstalled` | `UmbrellaPluginInstalled` | Effective-dep-set union: plugin-level deps + every contained skill's deps land under `bin/cli/` and the gateway. |
| `TransitiveClisPresent` | `TransitivePluginClisPresent` | CLI deps from contained skills produce the same `bin/cli/` symlinks as if those skills were bare. |
| `AgentSkillSymlinks` | `AgentPluginSymlinks` | `~/.claude/plugins/<name>` symlink lands; `~/.claude/skills/<name>` does *not* land for any contained skill. |
| `AgentConfigsCorrect` | `AgentConfigsCorrectPlugin` | Codex receives no plugin symlink in v1 (Codex projector handles bare skills only); `~/.codex/skills/<contained-skill>` does *not* appear. |
| `OwnershipRecorded` | `OwnershipRecordedPlugin` | `installed/<name>.json` records `kind=plugin`, the right `install_source`, sha, and the union of dep names. |
| `SkillSynced` | `PluginSynced` | `sync` re-registers plugin's effective MCP deps after gateway loss. |
| `SkillUninstalled` | `PluginUninstalled` | Uninstalling a plugin tears down every dep registered from its contained skills (re-walk verification). No orphan MCP / CLI rows survive. |
| `McpToolInvoked` | `McpToolInvokedFromPlugin` | A tool registered from inside a plugin is invokable through the gateway with the same flow as a skill-registered tool. |
| `McpToolLoadsBundled` / `McpToolLoadsInstalled` | `McpToolLoadsFromPlugin` | Bundle install works for plugin-declared MCP deps. |
| `SearchFinds` | (registry behavior unchanged this round — search remains as-is) | — |
| `SemverEnforced` / `ImmutabilityEnforced` | `SemverEnforcedPlugin` / `ImmutabilityEnforcedPlugin` | Version policy applies to plugins. |
| `SourceFixturePublished` / `SourceFixtureInstalled` | `SourceFixturePluginPublished` / `SourceFixturePluginInstalled` | Source-tracking provenance for plugins. |
| `SourceSyncMergesClean` / `...ProducesConflict` / `...RefusesOnDirty` / `...RefusesWithoutFrom` | `...PluginMergesClean` / `...PluginProducesConflict` / `...PluginRefusesOnDirty` / `...PluginRefusesWithoutFrom` | git-tracked sync semantics for plugins. |
| `SourceSyncAllAggregates` | `SourceSyncAllAggregatesPlugin` | Aggregated sync handles a mix of plugin + skill installs in one pass. |

#### New nodes (no skill parallel — these are plugin-specific)

| Node | What it proves |
| --- | --- |
| `PluginContainedSkillNotAddressable` | `skill-manager install <contained-skill-name>` errors with "not found" after the parent plugin is installed; the contained skill is reachable only through the plugin. |
| `PluginUninstallReWalkPreventsOrphan` | After installing a plugin whose contained skill declared an MCP dep, uninstalling the plugin removes that MCP server. Without the re-walk, this test would leak. |
| `SkillReferencesPlugin` | A bare skill with `skill_references = ["plugin:..."]` triggers a transitive plugin install. |
| `PluginReferencesSkill` | A plugin with `references = ["skill:..."]` triggers a transitive skill install. |
| `PluginCycleDetected` | Cycle plugin-A → plugin-B → plugin-A is reported at plan time with the offending chain. |
| `PluginPluginJsonDriftWarns` | Plugin where `plugin.json.name` and `skill-manager-plugin.toml.[plugin].name` disagree → install proceeds with a warning, toml wins. |
| `PluginMcpDoubleDeclarationWarns` | Plugin declaring the same MCP server in both `.mcp.json` and `skill-manager-plugin.toml` → warning emitted, only the toml entry registers with the gateway. |
| `PluginEmptyTomlInstalls` | Plugin with no `skill-manager-plugin.toml` (only `plugin.json` + skills) installs cleanly; only effect is the projector symlink. |
| `LockReproducesInstallSet` | After installing a mix of plugins + skills, deleting the store, and `sync --lock <path>`, the resulting tree byte-matches the original. |
| `LockUnchangedOnPartialUpgradeFailure` | A planned upgrade for two units fails on the second; the lock is unchanged from its pre-upgrade state, the first unit is rolled back. |
| `MixedKindInstallSetTopologicalOrder` | `install plugin:p1 skill:s1 plugin:p2` with cross-kind references produces a single Program with effects topologically ordered; intermediate state during install never has a unit visible to the agent before its deps are. |

### Layer 2 — Unit tests (fast combinatorial contract coverage)

**Purpose.** Layer 1 (test_graph) exercises the system end-to-end the
way it'll run in production — real binaries, real gateway, real git.
That's slow and high-fidelity. Layer 2 has the opposite job: blow
through hundreds of permutations per second to verify the *contracts*
hold across every reasonable combination of inputs, and crucially
across every reasonable failure injection. IO is mocked at the edges
so a single test runs in milliseconds; we trade fidelity for throughput
and use that throughput to cover combinatorics Layer 1 can't afford.

The test_graph stays the source of truth for "does this actually work
on a real machine." Layer 2 is the source of truth for "does this
respect its contract under every shape of input I can throw at it."

#### Mock boundary (what's faked, what's real)

```
                ┌────────────────────────────────────────┐
                │           Real (in-process)            │
                │  parsers, resolver, planner,           │
                │  Program/then composer,                │
                │  effect interpreter dispatch,          │
                │  decoders, audit, conflict detection,  │
                │  lock read/write, policy gating        │
                └────────────────────────────────────────┘
                                 ↑↓
                ┌────────────────────────────────────────┐
                │          Faked at the edge             │
                │  Filesystem      → InMemoryFs          │
                │  Gateway HTTP    → FakeGateway         │
                │  Registry HTTP   → FakeRegistry        │
                │  Git ops         → FakeGit             │
                │  Subprocess      → FakeProcessRunner   │
                │  Clock / sleep   → FakeClock           │
                └────────────────────────────────────────┘
```

Everything above the line runs for real. The seam is the same set of
adapters `LiveInterpreter` already uses — we just wire the Fake side
in instead. The existing `DryRunInterpreter` is *not* what these tests
use: dry-run skips effects entirely. Layer 2 needs the effects to
*execute against fakes* so we observe state transitions (lock written,
unit-store map updated, MCP register/unregister calls captured,
journal entries emitted, compensations triggered).

The fakes are deterministic and inspectable — every fake exposes its
recorded calls plus a builder for canned responses, so a test can say
"FakeGateway, return 503 on the third register call" and observe the
compensation cascade.

#### Permutation axes

The combinatorial space worth covering. A single `CommandScenarioTest`
parameterizes across these axes and asserts contracts at the end:

| Axis | Values |
| --- | --- |
| **Command** | `install`, `upgrade`, `uninstall`, `sync`, `sync --lock`, `remove` |
| **Unit kind** | `SKILL`, `PLUGIN` |
| **Install source** | `REGISTRY`, `GIT`, `LOCAL_FILE` |
| **Pre-state** | empty store, already-installed-same-version, already-installed-different-version, installed-with-`MERGE_CONFLICT`-error, installed-with-`GATEWAY_UNAVAILABLE`-error, installed-from-different-source |
| **Reference shape** | none, refs-to-skill, refs-to-plugin, plugin→skill→plugin chain, diamond, cycle |
| **Dep mix** | none, CLI-only, MCP-only, CLI+MCP, CLI-conflict, MCP same-name-different-load |
| **Lock state** | absent, in-sync, ahead-of-disk, behind-disk, conflicting-rows |
| **Policy** | each `policy.install.*` flag on/off |
| **Failure injection** | none, `clone`-fails, `checkout`-fails, `cli-install`-fails, `mcp-register`-fails, `projector`-fails, `lock-write`-fails (× at which step in the sequence) |
| **Yes flag** | `--yes`, interactive-confirm, interactive-deny |

Most tests don't sweep the full Cartesian product (~hundreds of
millions of cells). A scenario picks a focused subset — typically two
or three axes at full sweep with the rest pinned — chosen so the
contract under test is exercised across every value that could
plausibly affect it. Tags (`@Tag("install")`, `@Tag("rollback")`)
let CI run subsets quickly while a nightly job runs the whole sweep.

#### Contracts being verified

The contracts the unit tests pin down. Each contract has at least one
test that sweeps the relevant axes:

1. **Effect-graph shape invariance.** For equivalent inputs, the
   emitted `Program.effects()` sequence is identical across `UnitKind`,
   modulo the four kind-divergence points (store dir, projector,
   scaffold, uninstall re-walk). This is the substitutability claim
   from the previous version — preserved as one contract among many.
2. **Compensation pairing.** Every effect emitted on the success path
   has a registered compensation, and every compensation undoes its
   counterpart's observable state on the fakes. Sweep: failure
   injection at every step, assert journal walks back cleanly.
3. **Lock atomicity.** The lock file is byte-identical to its
   pre-command state if and only if the command did not commit. Sweep:
   inject failure at each effect index, assert
   `units.lock.toml-before == units.lock.toml-after`.
4. **No orphan registrations.** After uninstall (any source kind, any
   pre-state with deps held by other units, any unit kind), the
   FakeGateway has *exactly* the MCP servers claimed by the surviving
   units. Sweep: pre-state × ref-shape × unit-kind.
5. **Plan policy gating.** `--yes` cannot bypass `!`-marked policy
   lines. Sweep: every policy flag × every dep mix that triggers it.
6. **Resolver determinism.** Same coord + same registry state →
   same descriptor (sha-pinned). Sweep: kind filter × ambiguous-name
   pre-state × source override.
7. **Heterogeneous reference walk.** Plugin↔skill reference chains
   topologically order correctly; cycles surface at plan time, not at
   exec time. Sweep: ref-shape × unit-kind at the cycle node.
8. **Idempotence of `sync --lock`.** Running `sync --lock <path>`
   twice produces identical disk state on the second run. Sweep:
   unit-kind × pre-state × dep mix.
9. **Migration safety.** Legacy `sources/<name>.json` → new
   `installed/<name>.json`, with kind defaulting to `SKILL`, runs
   exactly once per file. Sweep: pre-state × file content variants.

#### Test substrate

```
src/test/java/dev/skillmanager/
├── _lib/
│   ├── fakes/
│   │   ├── InMemoryFs.java               # rooted at a virtual /; supports symlinks
│   │   ├── FakeGateway.java              # records register/unregister, scriptable failures
│   │   ├── FakeRegistry.java             # canned descriptors per (name, version)
│   │   ├── FakeGit.java                  # clone/fetch/checkout against in-memory repos
│   │   ├── FakeProcessRunner.java        # CLI install backends route through here
│   │   └── FakeClock.java
│   ├── fixtures/
│   │   ├── DepSpec.java                  # CLI / MCP / refs description, kind-agnostic
│   │   ├── UnitFixtures.java             # buildEquivalent(kind, DepSpec) → AgentUnit
│   │   ├── PreStates.java                # canned UnitStore + journal pre-states
│   │   └── Scenarios.java                # named permutation slices for tests to consume
│   ├── harness/
│   │   ├── TestHarness.java              # wires Fakes into LiveInterpreter, returns observable receipts/state
│   │   └── ContractAssertions.java       # assertEffectShapeInvariant, assertNoOrphans, assertLockAtomic, ...
│   └── matrix/
│       └── ScenarioMatrix.java           # JUnit 5 ArgumentsProvider that yields the permutation slices
├── model/
│   ├── PluginParserTest.java
│   └── EffectiveDepUnionTest.java
├── resolve/
│   ├── ResolverKindFilterTest.java
│   └── ResolverHeterogeneousRefsTest.java
├── plan/
│   ├── PlanShapeInvariantTest.java        # contract #1
│   ├── CycleDetectionTest.java            # contract #7
│   └── PolicyGatingTest.java              # contract #5
├── effects/
│   ├── CompensationPairingTest.java       # contract #2
│   ├── HandlerSubstitutabilityTest.java   # contract #1, handler granularity
│   ├── ProgramComposabilityTest.java
│   └── KindAwareDispatchTest.java         # the four divergence points are pinned
├── command/
│   ├── InstallScenarioTest.java           # full ScenarioMatrix sweep over install
│   ├── UpgradeScenarioTest.java           # full sweep over upgrade
│   ├── UninstallScenarioTest.java         # full sweep, asserts contract #4
│   ├── SyncScenarioTest.java
│   └── SyncFromLockScenarioTest.java      # contract #8
├── lock/
│   ├── LockReadWriteTest.java
│   ├── LockAtomicityTest.java             # contract #3
│   └── LockDiffTest.java
├── store/
│   └── MigrationFromSkillSourceTest.java  # contract #9
└── project/
    ├── ClaudeProjectorTest.java
    └── CodexProjectorTest.java
```

#### What a permutation test looks like

```java
@ParameterizedTest
@ArgumentsSource(ScenarioMatrix.InstallSlice.class)
void installRespectsContracts(Scenario s) {
    var harness = TestHarness.from(s);

    var result = harness.run(s.command());

    ContractAssertions.assertEffectShapeInvariant(harness, s);
    ContractAssertions.assertCompensationsPaired(harness);
    ContractAssertions.assertLockAtomic(harness, s);
    ContractAssertions.assertNoOrphanRegistrations(harness);
    ContractAssertions.assertExitCodeMatches(s.expectedOutcome(), result);
}
```

`ScenarioMatrix.InstallSlice` yields one `Scenario` per axis cell
relevant to install. A single test method covers thousands of cells
and reports failures with the full scenario as the test display name
(`install / PLUGIN / GIT / dep:CLI+MCP / pre:empty / fail-injection:none`)
so a regression maps unambiguously to a row.

#### Failure-injection sweep

The most expensive contract — and the most valuable. For each command,
for each step index in its emitted effect sequence, force that step's
fake to fail and assert:

- The journal walked back fully (no half-applied state on any fake).
- The lock is byte-identical to pre-command.
- The unit-store map is byte-identical to pre-command.
- No MCP server is registered with the gateway that wasn't there
  before.
- No CLI tool is in `bin/cli/` that wasn't there before.
- The exit code matches the failure category (the rollback was
  intentional, not an additional crash).

This is mechanical to write once and prevents a whole class of
regressions where a future effect addition forgets its compensation.

#### Speed budget

- Per-test: <10ms median, <50ms p99.
- Per scenario sweep (install / upgrade / uninstall / sync): <5s on
  developer laptop.
- Full Layer 2 suite: <30s.

These budgets are how we keep the layer useful. If a test starts
needing real IO, it belongs in the test_graph, not Layer 2.

#### What we explicitly don't unit-test

- **End-to-end behavior of registry HTTP, gateway HTTP, git, package
  managers.** The test_graph (Layer 1) covers those.
- **Policy file parsing edge cases beyond what command flow exercises.**
  Add focused TOML parser tests if real bugs appear.
- **CLI flag parsing.** Picocli is well-tested; we test command flow,
  not flag plumbing.

### Test-fixture build helpers

The `_lib/fixtures/` package builds the `AgentUnit`s and pre-states
both layers consume:

```java
public final class UnitFixtures {
    public static AgentUnit buildEquivalent(UnitKind kind, DepSpec deps);
    public static AgentUnit plugin(String name, ContainedSkillSpec... contained);
    public static AgentUnit skill(String name, DepSpec deps);
    public static Path materializeOnDisk(InMemoryFs fs, AgentUnit unit);
}
```

For Layer 1 (test_graph), a parallel `test_graph/fixtures/_lib/`
exposes scaffold helpers that write real directories:

```java
public final class PluginFixture {
    public static Path scaffold(Path tempRoot, String name, ContainedSkillSpec... contained);
}
public final class SkillFixture {
    public static Path scaffold(Path tempRoot, String name, DepSpec deps);
}
```

`DepSpec` and `ContainedSkillSpec` are shared between the two layers
(packaged so test_graph JBang nodes can also `import` them). This is
how we keep the two layers aligned: a single source-of-truth for what
"a skill with a CLI dep and an MCP dep" looks like, materialized
either in-memory (Layer 2) or on disk (Layer 1).

### Scope and ordering

The tests are added alongside the implementation, not after. Each step
in the implementation order owns its tests:

- Step 1 (`AgentUnit` + manifest model) → `model/PluginParserTest`,
  `EffectiveDepUnionTest`.
- Step 5 (Resolver) → `resolve/*Test`.
- Step 6 (Planner) → `plan/PlanShapeInvariantTest`, `CycleDetectionTest`.
- Step 7 (Effect-layer widening) → `effects/HandlerSubstitutabilityTest`
  added incrementally as each effect is widened (leaf effects first).
- Step 8 (Effects + journal) → `effects/CompensationOrphanTest`,
  `ProgramComposabilityTest`.
- Step 9 (Projector) → `project/*Test`.
- Step 4 / migration → `store/MigrationFromSkillSourceTest`.
- Step 8 / lock → `lock/*Test`.

Test_graph parallel nodes are landed in the same step that introduces
their behavior; the smoke graph stays green at every commit by gating
the new nodes behind a `kind=plugin` tag until the plugin install path
is functional.

## Migration notes

- `~/.skill-manager/sources/<name>.json` → `installed/<name>.json` on
  first reconcile after upgrade.
- Existing installed skills get `kind=skill` (their existing
  `install_source` enum is preserved).
- The lock is generated lazily on first install/upgrade after upgrade —
  prior installs are folded in at that point.
- Old `~/.claude/skills/<name>` symlinks are unchanged. New plugin
  installs add `~/.claude/plugins/<name>` symlinks alongside.

## Risks and mitigations

- **Coord ambiguity.** Mitigated by deterministic grammar + explicit
  disambiguation errors.
- **`skill-manager-plugin.toml` drift from `plugin.json`.** Warn on
  mismatch; toml wins.
- **MCP double-registration.** Warn at plan time. Author's
  responsibility to dedupe.
- **`~/.claude/plugins/` shape changes upstream.** Projector abstraction
  isolates the blast radius — one file change to follow Claude's path.
- **Lock churn** with branch-tracking installs: only `install`,
  `upgrade`, `sync --refresh` advance shas in the lock. Plain `sync`
  reconciles toward the lock without moving it, so a checked-in lock
  stays stable.

## Designed-around (deferred)

These aren't in scope this round but the architecture leaves room:

- **Multi-source / marketplace composition.** Resolver is already
  pure; adding a `Source` indirection later means injecting it before
  the existing registry path, not refactoring everything downstream.
- **Archive transport.** `InstalledUnit` records `install_source` and
  `origin`; adding `archive` as a fourth value + a fetch helper is a
  local change.
- **Per-agent layout transformation.** Projector is the only thing that
  touches agent directories; non-Claude plugin support is a new
  projector impl.
- **Marketplace trust policy.** Lands inside `policy` when sources
  arrive.

## Decisions captured (previously open)

- **Lockfile location for project-vendored locks.** Repo root —
  `units.lock.toml` lives next to the project's other lockfiles. The
  per-machine default is still `$SKILL_MANAGER_HOME/units.lock.toml`;
  `--lock <path>` overrides for project use.
- **Contained skill addressability.** Bundle-internal. Parsed for
  deps, never addressable, never separately installable, never
  depend-on-able. The harness sees them via the plugin's projected
  directory; that's fine because they're part of the plugin, not a
  separate installable surface.
