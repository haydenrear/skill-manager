---
name: skill-manager
description: Search the skill registry, install skills, and register MCP servers from the agent's context. Use when the user asks to find, add, remove, or inspect skills and MCP servers.
---

# skill-manager

You can **discover and install** skills and MCP servers on demand using the `skill-manager` CLI. Treat this as your package manager for agent capabilities.

## When to use this skill

- The user asks what skills are available ("what skills do I have?" / "find a skill for X").
- The user asks to install, remove, publish, or inspect a skill.
- The user asks to add, describe, deploy, or invoke an MCP server / tool through the gateway.
- You've identified a capability gap — e.g. a task needs a CLI tool or MCP server you don't yet have — and you should propose finding one.

Always narrate the plan before running commands that modify state. Install/publish/register are side-effecting; confirm the scope before acting.

## The CLI at a glance

All subcommands are run as `skill-manager <command>`. Most modifying commands take `--dry-run` (show the plan) and `--yes` (skip interactive confirmation). Policy-gated actions will refuse to proceed without a plan review.

### Discovering and installing skills

| Step | Command |
| --- | --- |
| Search by keyword | `skill-manager search "<query>"` |
| Describe a hit | `skill-manager registry describe <name>` (also reachable via `curl <registry>/skills/<name>`) |
| Install by name | `skill-manager add <name>[@<version>]` |
| Install from local path | `skill-manager add ./path/to/skill` |
| Install from a git repo | `skill-manager add github:user/repo` |
| List installed | `skill-manager list` |
| Show an installed skill | `skill-manager show <name>` |
| Show transitive deps | `skill-manager deps <name>` |
| Remove | `skill-manager remove <name>` |

`add` always builds a plan first — fetches the skill + every transitive reference into staging, then prints what will happen (fetches, CLI installs, MCP registrations). Nothing is committed to the store until consent is given.

### Working with the MCP gateway

The gateway fronts every MCP server; agents only ever see one MCP endpoint. Most skill-manager MCP operations are executed via the official **Java MCP SDK** client so protocol negotiation happens properly.

| Step | Command |
| --- | --- |
| Start / stop | `skill-manager gateway up` / `gateway down` |
| See URL + health | `skill-manager gateway status` |
| Register a server (REST) | `skill-manager gateway register <id> --docker <image>` or `--url <endpoint>` |
| List registered | `skill-manager gateway servers` |
| Describe a server | `skill-manager gateway describe-server <id>` |
| Deploy / undeploy | `skill-manager gateway deploy <id>` / `undeploy <id>` |
| Browse active tools | `skill-manager gateway tools [--prefix <p>]` |
| Semantic search on tools | `skill-manager gateway search "<query>"` |
| Describe a tool | `skill-manager gateway describe-tool <path>` |
| Invoke a tool | `skill-manager gateway invoke <path> --args '{"…":"…"}'` |

After an MCP dep is registered, it persists across gateway restarts — no restart reminder needed. Prefer asking the user for init params (secrets, tokens) at deploy time rather than hardcoding them.

### Publishing your own skills

| Step | Command |
| --- | --- |
| Inspect registry config | `skill-manager registry status` |
| Package + upload | `skill-manager publish [<skill-dir>]` |
| Package only (no upload) | `skill-manager publish <skill-dir> --dry-run` |

A skill directory is any dir with:

- `SKILL.md` — the spec the agent reads (frontmatter + body).
- `skill-manager.toml` — tooling-only metadata: CLI deps, MCP deps, skill references, version. Invisible to the agent runtime.

### Safety and policy

Never bypass policy with `--yes` blindly. The plan output is the security surface — read it. Blocked items (`BLOCKED` / `CONFLICT`) won't run even with `--yes`; the user must explicitly loosen policy at `~/.skill-manager/policy.toml` to unblock.

If a command produces a `CONFLICT [pip] <tool>` line, two installed skills want different versions of the same CLI tool. Resolve by aligning versions in one of the skill manifests, or removing the conflicting row from `~/.skill-manager/cli-lock.toml` if the pinned version is wrong.

## Recipes

**"Find a skill that does X and install it"**

```
skill-manager search "X"
# pick a hit, then:
skill-manager add <name> --dry-run     # review the plan
skill-manager add <name> --yes
```

**"What MCP tools can I use right now?"**

```
skill-manager gateway tools
# or: skill-manager gateway search "<query>"
```

**"Add a new MCP server from a docker image"**

```
skill-manager gateway register <server-id> \
  --docker ghcr.io/publisher/image:tag --arg --stdio
skill-manager gateway deploy <server-id> --init token=...
```

**"Publish the skill I just edited"**

```
cd /path/to/my-skill
skill-manager publish --dry-run      # sanity check
skill-manager publish                # actual upload
```

## Model notes

- If a command fails because the gateway or registry is down, say so — don't retry silently.
- Plans show sizes + sha256 for fetched bundles; quote them when summarizing "this will download X".
- `skill-manager.toml` keys are `skill_references`, `cli_dependencies`, `mcp_dependencies`, `skill`. Top-level arrays must come **before** the `[skill]` table or they get scoped under it.
