# Plugins — author's deep dive

A plugin is a bundle skill-manager treats as one installable unit but
that contains one or more skills, plus optional plugin-level hooks,
commands, agents, and shared MCP / CLI dependencies. This document
covers what the parent SKILL.md only summarizes:

- When a plugin is the right shape (vs. multiple skills + a SKILL.md
  cross-reference)
- Full layout, manifest schemas (`plugin.json` + `skill-manager-plugin.toml`)
- How plugins register with the harness (Claude / Codex) at install time
- Contained-skill semantics (addressability, dep unioning, install/uninstall)
- Hooks, commands, agents — the harness surface plugins unlock
- Distribution and validation

The local starter is `examples/plugin/` in this skill. Clone the layout
when in doubt.

## When a plugin (not a skill)

Use a **skill** when you have one capability with one set of agent
docs. Most authoring fits here.

Use a **plugin** when at least one of the following is true:

1. **Multiple related skills should ship and version together.** You
   have two or more skills that always go together — a "client" skill
   and a "fixtures" skill, an "author" skill and a "publish" skill. A
   plugin gives them one version, one install, one uninstall.
2. **You want to ship hooks / commands / agents to the harness.**
   Claude Code's plugin runtime supports hooks (run shell on
   pre-prompt / post-tool / etc.), slash commands, and agent
   definitions. Those only register through a plugin — a bare skill
   can't expose them.
3. **You want plugin-level shared MCP or CLI deps.** A plugin-level
   `skill-manager-plugin.toml` lets two contained skills share one
   MCP server registration. (Otherwise both skills would register the
   same server, racing on init params.)
4. **The harness is the primary surface.** If users interact via
   `/slash` commands more than agent prompts, the harness's plugin
   runtime is the right home.

If none of those apply, write a skill. Skills are cheaper to author
and don't need the harness round-trip on install (`claude plugin
install …`, marketplace registration, etc.).

## Full layout

```
my-plugin/                                # repo root
├── .claude-plugin/
│   └── plugin.json                       # REQUIRED — Claude's runtime manifest
├── skill-manager-plugin.toml             # OPTIONAL — skill-manager sidecar
├── skills/
│   ├── client-skill/
│   │   ├── SKILL.md
│   │   ├── skill-manager.toml
│   │   ├── tools/
│   │   │   ├── cli.md
│   │   │   └── mcp.md
│   │   └── ...
│   └── fixtures-skill/
│       ├── SKILL.md
│       └── skill-manager.toml
├── hooks/                                # OPTIONAL — claude plugin hooks
│   └── pre-prompt.sh
├── commands/                             # OPTIONAL — slash commands
│   └── my-command.md
├── agents/                               # OPTIONAL — agent definitions
│   └── my-agent.json
└── README.md                             # repo-level docs (not the plugin spec)
```

The required files are exactly two:

- `.claude-plugin/plugin.json` — the marker that this directory is a
  plugin (vs. a skill at the root).
- At least one of: a contained skill in `skills/<name>/`, or harness
  surface in `hooks/` / `commands/` / `agents/`. A plugin that
  contains nothing isn't useful.
- Markdown anywhere in the plugin can declare frontmatter
  `skill-imports`. Add a manifest reference only when the imported
  unit must also be installed transitively. See
  `references/skill-imports.md`.

## `plugin.json`

Claude Code's runtime manifest. Minimum viable:

```json
{
  "name": "my-plugin",
  "version": "0.1.0",
  "description": "Short blurb for the plugin marketplace UI."
}
```

The harness reads additional fields (hook entry points, command
manifests, agent definitions, etc.) — see Claude Code's plugin docs
for the full schema. From skill-manager's perspective, the only fields
that matter are `name` + `version`, which must agree with
`skill-manager-plugin.toml`'s `[plugin].name` + `version` if that
sidecar exists.

## `skill-manager-plugin.toml`

Optional. Sits alongside `plugin.json`. skill-manager reads it; the
harness ignores it. When omitted, the plugin still installs cleanly —
the only side effect on top of bytes-on-disk is the marketplace +
harness registration.

Full structure:

```toml
[plugin]
name = "my-plugin"                   # MUST match plugin.json
version = "0.1.0"                    # MUST match plugin.json
description = "Short blurb."

# Plugin-level references — apply to the whole bundle.
references = [
    "skill:some-published-skill@1.2.0",
    "file:./shared-helper-skill",
    "github:owner/utility-skill",
]

# Plugin-level CLI deps — installed once, regardless of which contained
# skill needs them. Same shape as in skill-manager.toml.
[[cli_dependencies]]
spec = "pip:my-tool==1.4.0"
on_path = "my-tool"

# Plugin-level MCP deps — registered once for the plugin, claimed by
# every contained skill that needs the server. Same shape as in
# skill-manager.toml.
[[mcp_dependencies]]
name = "shared-mcp"
display_name = "Shared MCP"
description = "…"
default_scope = "global-sticky"
load = { type = "docker", image = "ghcr.io/me/shared:latest" }
```

### When to put deps at the plugin level vs the contained-skill level

| Scope | Use when |
|---|---|
| Plugin-level | A CLI or MCP server is shared across contained skills (deduplicates registration). |
| Contained-skill-level | Only one skill uses it, OR the skill needs to be installable as a standalone skill in a different context. |

Most plugins keep deps **on the contained skills**, because that keeps
each skill self-describing. Plugin-level deps are useful but uncommon
— they exist for the "two skills share one MCP server" case.

## Install pipeline for a plugin

When the resolver sees `.claude-plugin/plugin.json` at the source
root, it routes through the plugin path:

1. **Detection** — resolver checks the source root for
   `.claude-plugin/plugin.json` and sets `kind = PLUGIN`. Bytes go to
   `$SKILL_MANAGER_HOME/plugins/<name>/` (not `skills/<name>/`).
2. **Manifest union** — plan-build parses
   `skill-manager-plugin.toml` (if present) AND every contained
   skill's `skill-manager.toml`. CLI deps, MCP deps, and references
   from all of them are deduplicated into one combined set with
   attribution (so plan output shows "needed by: my-plugin /
   client-skill").
3. **Policy gating** — same as skills. Each `!`-line in the plan
   output (`! HOOKS / ! MCP / ! CLI`) may block `--yes` based on
   `policy.toml` requirements.
4. **Side effects in order**:
   - CLI deps install into `bin/cli/`.
   - MCP deps register with the local gateway.
   - The skill-manager marketplace at
     `$SKILL_MANAGER_HOME/plugin-marketplace/` regenerates its
     `marketplace.json` and links the new plugin in.
   - If `claude` is on PATH: `claude plugin marketplace add` (idempotent)
     + `claude plugin uninstall <name>@skill-manager` + `claude plugin
     install <name>@skill-manager --scope user`. The uninstall+reinstall
     cycle forces hooks / commands / agents to reload from the new bytes.
   - If `codex` is on PATH: `codex plugin marketplace add` (idempotent).
     Final install in Codex requires the user's interactive `/plugins`
     UI; skill-manager registers the marketplace so the user can
     complete it manually.
   - If either CLI is missing: skill-manager records
     `HARNESS_CLI_UNAVAILABLE` with a `brew install <bin>` hint. The
     error self-clears on the next `sync` once the binary is reachable
     — install of the plugin's bytes still completes regardless.
5. **Lock flip** — `units.lock.toml` updates atomically with
   `kind = "plugin"`.

## Contained-skill semantics

Skills inside a plugin's `skills/<contained>/` are **not separately
addressable** through skill-manager.

- `skill-manager install <contained-skill-name>` fails after the parent
  plugin is installed — the resolver refuses because the name belongs
  to a plugin-contained skill.
- The contained skills' bytes live under
  `$SKILL_MANAGER_HOME/plugins/<plugin-name>/skills/<contained>/`,
  **not** under `skills/<contained>/`. They are not symlinked into
  agent homes as standalone skills — the harness's plugin runtime
  exposes them via the plugin instead.
- Uninstall of the parent plugin removes every contained skill (and
  re-runs orphan-MCP-server checks against the unioned dep set).

This means: **don't try to publish a contained skill as a top-level
skill in a registry.** Its identity is the plugin's. If you want a
skill to also be usable standalone in a different context, ship it as
its own skill repo and have the plugin reference it via `references =
["github:owner/skill-repo"]` instead of bundling its bytes.

## Hooks, commands, agents (the harness surface)

The harness-side files — `hooks/*.sh`, `commands/*.md`,
`agents/*.json` — are owned by Claude Code's plugin runtime, not by
skill-manager. skill-manager's job stops at "the plugin is registered
with the harness via `claude plugin install`"; from there, the harness
loads whatever the plugin manifest declares.

Refer to Claude Code's plugin documentation for the hook / command /
agent schemas. The key authoring constraint from skill-manager's side
is that **a hook can run arbitrary shell at the harness's discretion**
— so anything sensitive (secrets, irreversible ops) should be gated by
the harness's own confirmation flow, not by skill-manager's
`policy.toml`. Policy gates the install; what the plugin does at
runtime is the harness's problem.

## Distribution

A plugin distributes the same way as a skill: it sits at the root of a
git repo, and operators run

```bash
skill-manager install github:owner/plugin-repo
```

The resolver detects `.claude-plugin/plugin.json` and treats it as a
plugin install. No registry is required.

### Layout for the plugin's repo

```
plugin-repo/                            # github.com/owner/plugin-repo
├── .claude-plugin/plugin.json          # at the repo root
├── skill-manager-plugin.toml
├── skills/
│   └── …
├── hooks/                              # optional
├── commands/                           # optional
├── agents/                             # optional
└── README.md
```

The plugin's *identity* (its `name`) comes from `plugin.json`, not the
repo name. The repo can be named anything; common convention is
`<plugin-name>-plugin` to disambiguate from a bare skill at
`<plugin-name>`.

## Validation

The round-trip is the same as for skills, but the install command
detects the plugin shape automatically:

```bash
# from a fresh working directory
cd /tmp/work
skill-manager install github:owner/my-plugin

# verify
skill-manager show my-plugin                  # plugin-shaped detail view
skill-manager list                             # KIND column shows "plugin"
ls ~/.skill-manager/plugins/my-plugin/         # bytes landed
ls ~/.skill-manager/plugin-marketplace/plugins/my-plugin   # symlinked into marketplace
```

If `claude` is on PATH, also check:

```bash
claude plugin list --scope user                 # my-plugin@skill-manager appears
```

For each contained skill's deps, verify the same way you would for a
standalone skill — `ls $SKILL_MANAGER_HOME/bin/cli/<binary>` for CLI
deps, `browse_mcp_servers` against the local gateway for MCP deps.

## Common pitfalls

- **Forgetting `.claude-plugin/plugin.json`.** Without that file at the
  source root, the resolver treats the directory as a skill (if
  `SKILL.md` is at the root) or fails to detect any unit at all.
- **Name drift between `plugin.json` and `skill-manager-plugin.toml`.**
  Both files must agree on `name` and `version`. skill-manager warns
  at install time when they drift; the harness silently picks one
  (usually `plugin.json`), so drift surfaces as confusing version
  mismatches later.
- **Putting a `SKILL.md` at the plugin root.** The resolver checks
  for `plugin.json` first — a plugin with a stray root-level
  `SKILL.md` still installs as a plugin and the root `SKILL.md` is
  never loaded by the agent. Contained-skill `SKILL.md` files (under
  `skills/<contained>/`) are the right place.
- **Publishing a contained skill to a registry independently.** It
  isn't addressable as a top-level skill once the plugin is installed.
  Either remove it from the plugin and publish it standalone, or
  accept that the plugin owns its identity.
- **Hook scripts assuming a shell environment that's not there.** The
  harness runs hooks in whatever environment Claude Code provides;
  don't assume `$SKILL_MANAGER_HOME` is exported. Plugin-level
  install-time setup (PATH adjustments, env-file generation) should
  happen via a `skill-script:` CLI dep on a contained skill, not via
  a hook.
