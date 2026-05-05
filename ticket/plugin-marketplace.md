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

# Public surface: which contained skills are globally addressable.
# Anything not listed here is private — only reachable via the plugin
# install, never via `skill-manager install <name>` directly.
[[exports.skills]]
name = "summarize-repo"
path = "skills/summarize-repo"

[[exports.skills]]
name = "diff-narrative"
path = "skills/diff-narrative"
```

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

- `cli_dependencies` and `mcp_dependencies` from
  `skill-manager-plugin.toml` are unioned with those from each contained
  skill. Conflicts (same name, different version) flow through the
  existing `cli-lock.toml` / `CONFLICT` machinery.
- `references` from `skill-manager-plugin.toml` are unioned with each
  contained skill's `skill_references`. Cycles are detected at plan time.
- Identity precedence: `skill-manager-plugin.toml` > `plugin.json` >
  inferred from directory name.

### Contained skill visibility

```
default                       →  private (only reachable via the plugin)
listed in [[exports.skills]]  →  public (addressable as `skill:<name>`)
```

A private contained skill is still visible to the harness once the plugin
is installed (it lives in `<plugin>/skills/<x>/` and the agent reads
that directory), but `skill-manager install <name>` will not match it.
The only way to install it is to install its parent plugin.

If two installed plugins both export a skill with the same name, the
resolver flags ambiguity and the user must use `<plugin>/<skill>` to
pick (see Coordinate grammar).

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
│   ├── summarize-repo/              # exported (public)
│   │   ├── SKILL.md
│   │   └── skill-manager.toml
│   ├── diff-narrative/              # exported (public)
│   │   └── SKILL.md
│   └── _internal-helpers/           # private (not in [[exports.skills]])
│       └── SKILL.md
├── commands/
├── agents/
└── hooks/
```

## Coordinate grammar

Coordinates are how users name a unit. Grammar:

```
coord       := kinded | bare | direct | local | plugin-skill
kinded      := ("skill:" | "plugin:") name [ "@" version ]
bare        := name [ "@" version ]              # kind inferred at resolve time
plugin-skill:= name "/" name                     # plugin/skill — disambiguates exported-skill collisions
direct      := "github:" owner "/" repo [ "#" ref ] | "git+" url [ "#" ref ]
local       := "file://" abs-path | "./" rel | "../" rel | "/" abs

name        := [a-z0-9][a-z0-9_-]*
version     := semver
```

Resolution rules (deterministic):

| Coord | Resolution |
| --- | --- |
| `hello-skill` | Registry lookup; first match wins. If both a skill and a plugin share the name → error, ask user to disambiguate with `skill:` or `plugin:`. |
| `skill:hello-skill` | Registry lookup; only consider skills (and exported skills inside installed/ resolvable plugins). |
| `plugin:repo-intelligence` | Registry lookup; only consider plugins. |
| `repo-intelligence/summarize-repo` | Disambiguates an exported skill that collides with another export — picks `summarize-repo` from the `repo-intelligence` plugin specifically. |
| `github:me/x` | Direct git, default branch. |
| `git+https://github.com/me/x#v1.2.0` | Direct git, pinned ref. |
| `file:///abs/path` / `./rel` | Local path. |

Ambiguity (multi-kind same name, multiple exported skills with same name)
errors with a list of candidates and the exact pinned coords to use. No
silent wrong-pick path.

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
  + SKILLS        : 2 exported (summarize-repo, diff-narrative)
                  : 1 private (_internal-helpers)
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

1. Plan: `UnprojectIfOrphan` per agent, `UnregisterMcpServerIfOrphan`,
   `UninstallCliDependencyIfOrphan`, `DeleteClone`,
   `RestoreInstalledUnit(none)`, `UpdateUnitsLock`.
2. Execute. (`if orphan` handlers consult the rest of the lock to decide
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

- Plugin → contained skills (public/private split), unioned deps,
  source/ref/sha.
- Skill → unchanged.

## CLI verb summary (delta vs. today)

New:

- `lock status` — show lock vs. disk diff
- `sync --lock <path>` — reproduce from a vendored lock
- `<plugin>/<skill>` and `plugin:<name>` coord forms

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
7. **Effects + journal.** Shape every effect with a compensation. Add
   `RollbackJournal`. Re-route `InstallCommand`, `UpgradeCommand`,
   `UninstallCommand` through the executor.
8. **`units.lock.toml`.** Read/write. `lock status`. `sync --lock`.
   Update `install`/`upgrade`/`uninstall` to flip the lock at commit.
9. **Projector.** `Projector` interface. `ClaudeProjector`,
   `CodexProjector` (skills only for now). Replace direct symlink calls.
10. **Policy.** `policy.install` flags into plan-print.
11. **Publish.** Detect plugin vs bare skill at publish dir. Bundle
    plugin contents.
12. **Search/list/show.** Surface `kind` + `sha` columns. Show contained
    skills (public/private split) for plugins.
13. **Tests.** New test_graph nodes:
    - plugin install from local path
    - plugin install from github
    - private contained skill is not globally addressable
    - exported contained skill is addressable as `skill:<name>`
    - skill referencing a plugin (`skill_references = ["plugin:..."]`)
    - plugin referencing a skill
    - cycle detection across heterogeneous refs
    - upgrade rolls back on failure
    - lock-driven sync reproduces a known-good install set
    - bare-skill install path is byte-identical to today
14. **Docs.** Update `skill-manager-skill/SKILL.md` and `README.md`.

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

## Open questions

1. **Lockfile location and ergonomics for project-vendored locks.** Is
   `units.lock.toml` at repo root the convention, or under
   `.skill-manager/`?
2. **Private contained skill discovery by the agent.** A private
   contained skill is in the plugin dir, so the harness will see it.
   Acceptable, or should we hide private skills via subdirectory naming
   conventions?
