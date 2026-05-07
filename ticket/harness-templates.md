# Harness templates

## TL;DR

A **harness template** is a named, reusable bundle of `AgentUnit`s (plugins
+ bare skills) plus MCP server selections, materialized into an
isolated harness directory so a single agent can be launched against
exactly that selection. Skill-manager learns to author, store, list,
share, and instantiate harness templates. Both humans and agents can
create templates; the same machinery powers eval sweeps over different
harnesses, ad-hoc experimentation, and sharing harnesses through user
profiles.

## Motivation

Agent topologies often want each participant agent to see a different
slice of the skill/plugin/MCP universe. A code-review agent and a
planner agent shouldn't necessarily share the same toolbelt; an
adversarial-eval setup wants two structurally identical agents that
differ only in their harness. Today, skill-manager installs into one
place — `$SKILL_MANAGER_HOME` and the per-agent symlink trees — and
there is no way to declaratively express "here is the set of units +
MCP tools agent X should see, distinct from agent Y's set."

A harness template is the missing primitive. It captures the selection
once, can be applied repeatedly, and travels (publish / share / fork)
the same way a plugin does.

## Concepts

| Term | What it is |
| --- | --- |
| **Harness template** | A named, versioned manifest declaring a set of `AgentUnit` coords + MCP server selections from the gateway. Template, not artifact — instantiation is what produces a harness directory. |
| **Harness instance** | The on-disk materialization of a template into an isolated directory tree (`plugins/`, `skills/`, agent symlink dirs, an `.mcp.json` selecting the chosen gateway tools). One template can be instantiated many times. |
| **Sandbox root** | The directory under which harness instances live (default: `$SKILL_MANAGER_HOME/harnesses/`). Each instance is a self-contained subtree an agent can be pointed at via `CLAUDE_CONFIG_DIR` / equivalent. |
| **Profile harness** | A harness template shipped as part of a user profile (see `user-profiles` work) so it travels with the user across machines. |

A harness template is itself a skill-manager-managed object — it has a
manifest, a name, a version, references to other units, and a sha
when published. The natural fit is **`AgentUnit`'s third permit**:

```java
public sealed interface AgentUnit permits PluginUnit, SkillUnit, HarnessUnit { ... }
```

`HarnessUnit` carries the selection manifest. Most existing effects
(`FetchUnit`, `InstallUnitIntoStore`, `WriteInstalledUnit`,
`UpdateUnitsLock`, projector dispatch) widen across all three kinds the
same way they did for plugins. Only the **projector** branches —
materializing a `HarnessUnit` is a different operation from symlinking a
plugin.

## Manifest model

`harness.toml` at the harness-template root:

```toml
[harness]
name = "code-reviewer-harness"
version = "0.1.0"
description = "Harness for the reviewer role in our review/critic topology."

# Units the harness should expose to its agent. Resolved as ordinary
# UnitReference values — same coord grammar as plugins/skills today.
units = [
  "plugin:repo-intelligence@0.4.2",
  "skill:diff-narrative",
  "skill:test-graph",
]

# Which gateway-registered MCP tools the agent should see. Names must
# match servers registered with the virtual-mcp-gateway (either by the
# units above or independently). Tool-level filtering is via the
# existing gateway allowlist surface.
[[mcp_tools]]
server = "shared-mcp"
tools  = ["search", "get"]      # omit `tools` to expose all

[[mcp_tools]]
server = "test-graph-runner"

# Per-agent overrides. Keyed by agent id; same names the projector uses.
[agents.claude]
extra_units = ["skill:claude-only-helper"]

[agents.codex]
exclude_units = ["plugin:repo-intelligence"]   # if the agent can't load it
```

A harness template carries no executable contents of its own — it's a
manifest that names other units. This matters for two reasons:

1. The plan/effect graph for installing a harness template is just the
   transitive install of its referenced units (already supported), plus
   one `MaterializeHarness` effect.
2. The blast radius of harness templates is small: nothing new to
   sandbox or trust, since trust applies to the referenced units.

## Sandbox layout

A harness instance is a self-contained directory:

```
$SKILL_MANAGER_HOME/harnesses/<instance-id>/
├── harness.lock.toml           # the resolved unit graph + shas
├── claude/                     # per-agent harness root for Claude
│   ├── plugins/<name> -> $SKILL_MANAGER_HOME/plugins/<name>
│   ├── skills/<name>  -> $SKILL_MANAGER_HOME/skills/<name>
│   └── settings.json           # gateway/tool config bound to this harness
├── codex/
│   └── ...
└── .mcp.json                   # gateway endpoint with this harness's
                                # tool allowlist applied
```

The harness root is the directory an agent process is launched against
(`CLAUDE_CONFIG_DIR=...`, equivalent for other agents). Instances are
cheap — they're symlink trees over the shared `$SKILL_MANAGER_HOME`
store, not copies. Multiple instances of the same template can coexist
(useful for eval sweeps and parallel topologies).

`<instance-id>` defaults to `<template-name>-<short-sha>` and can be
overridden with `--id`.

## CLI surface

```
skill-manager harness new <name>                       # scaffold a harness.toml
skill-manager harness publish [<dir>]                  # publish a harness template
skill-manager harness install <coord>                  # install template into the unit store
skill-manager harness instantiate <name> [--id <id>]   # materialize a harness instance
skill-manager harness list                             # list templates + instances
skill-manager harness show <name>                      # resolved unit graph + tools
skill-manager harness rm <instance-id>                 # tear down an instance
skill-manager harness diff <a> <b>                     # diff two templates' selections
```

`install` / `instantiate` are split deliberately: install brings the
template + all referenced units into the store and writes
`units.lock.toml`; instantiate is the cheap, repeatable step that
materializes a harness root.

`harness instantiate` is the hot path agents and topology builders
actually invoke — it should be deterministic and fast (symlink-only on
the happy path).

## Plan / effect model

A harness install reuses the existing planner and adds two effects:

| Effect | Compensation |
| --- | --- |
| `MaterializeHarness(template, instanceDir)` | `TeardownHarness(instanceDir)` |
| `BindGatewayAllowlist(instanceId, mcpTools)` | `UnbindGatewayAllowlist(instanceId)` |

`MaterializeHarness` populates the instance directory; on failure,
`TeardownHarness` removes it. `BindGatewayAllowlist` registers the
template's selection with the virtual-mcp-gateway under the harness
instance id so per-instance tool views are independent.

Because the harness manifest references other units, the planner emits
their install effects upstream of `MaterializeHarness` exactly the way
it emits a plugin's transitive deps today. Substitutability holds: a
harness template referencing a skill that turns into a plugin upstream
produces the same effect-graph backbone.

## Gateway integration

The virtual-mcp-gateway already mediates MCP tool calls. The harness
selection becomes a per-instance allowlist: when the harness's agent
invokes a tool through its `.mcp.json`, the gateway checks the
allowlist for that instance id and allows / rejects accordingly.

Implementation: a new `HarnessAllowlist` table keyed by instance id
holds `(server, tool)` pairs. The gateway's existing `invoke_tool`
path consults it before routing. No new transport.

## Sharing and profiles

Harness templates are publishable like any other `AgentUnit`. The user
profile work tracks them under `profile.harnesses` so a user's chosen
harnesses move with them across machines. An agent that creates a
harness can publish it to the registry under the same flow as
publishing a plugin.

Concretely, the profile is a list of harness coords; on profile sync,
each is `harness install`ed and `harness instantiate`d into the
configured sandbox root. Authoring is interactive (`harness new`) or
agent-driven (the agent writes a `harness.toml` and runs
`harness publish`).

## Eval and experimentation

The same template can be instantiated multiple times with different
overlays for eval sweeps:

```
skill-manager harness instantiate code-reviewer-harness \
  --id codereview-baseline
skill-manager harness instantiate code-reviewer-harness \
  --id codereview-with-extra-tool \
  --extra-units skill:experimental-summarizer
```

Two fully isolated harness roots, sharing the underlying store. The eval
runner launches agents against each id and compares outputs. Tearing
down an instance removes only the instance directory and its
allowlist — the store is untouched.

## Out of scope (this ticket)

- **Topology orchestration.** Spawning, wiring, and managing the agent
  processes that consume harnesses. A harness template is a primitive
  the orchestrator can rely on; the orchestrator itself is separate
  work.
- **Per-instance unit pinning that diverges from the global lock.**
  Harness instances follow the units in `units.lock.toml`. If a
  harness wants a different sha for a unit than the rest of the
  system, that's a future feature.
- **CLAUDE.md / AGENTS.md as `AgentUnit`.** Tracked in
  `agent-docs-as-agent-unit.md` — deferred. Once that lands, harness
  templates can also reference doc units, giving each harness's agent
  its own system-prompt-shaped instructions.
- **Trust policy across templates from third parties.** Lands when the
  multi-source / marketplace work does; until then, harness templates
  carry the same trust assumptions as the plugins/skills they
  reference.

## Risks

- **Symlink fan-out.** A single store unit referenced by many harness
  instances produces many symlinks. Acceptable — symlinks are cheap and
  the projector already handles per-agent fan-out.
- **Gateway allowlist drift.** If a harness template is upgraded but a
  long-lived instance isn't re-instantiated, its allowlist may name
  servers/tools that no longer exist. `harness show` should surface
  drift; `harness instantiate --refresh` re-applies.
- **Agent-authored harness explosion.** Agents creating templates ad hoc
  could fill the store with unmanaged templates. Mitigation:
  agent-authored templates default to a `local:` install source and
  aren't published to the registry unless explicitly promoted.

## Implementation order

1. **`HarnessUnit`** — third permit on `AgentUnit`. Parser for
   `harness.toml`. Resolver detects via on-disk shape (`harness.toml`
   at root → harness).
2. **`MaterializeHarness` / `TeardownHarness` effects** + `HarnessProjector`
   that owns the instance directory layout.
3. **CLI verbs** (`harness new|publish|install|instantiate|list|show|rm|diff`).
4. **Gateway allowlist** — `HarnessAllowlist` table + plumbing in the
   existing `invoke_tool` path.
5. **Profile integration** — add `profile.harnesses` to the profile
   schema and wire into profile sync.
6. **Tests** — Layer 1 (test_graph) parallels for `harness install`,
   `instantiate`, `rm`, eval-sweep multi-instantiate; Layer 2
   permutation cells for the new effect pair and allowlist binding.
