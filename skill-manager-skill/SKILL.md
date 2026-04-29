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
| Install by name | `skill-manager install <name>[@<version>]` |
| Install from local path | `skill-manager install ./path/to/skill` |
| Install from a git repo | `skill-manager install github:user/repo` |
| List installed | `skill-manager list` |
| Show an installed skill | `skill-manager show <name>` |
| Show transitive deps | `skill-manager deps <name>` |
| Remove | `skill-manager remove <name>` |

`add` always builds a plan first — fetches the skill + every transitive reference into staging, then prints what will happen (fetches, CLI installs, MCP registrations). Nothing is committed to the store until consent is given.

### Where skills land on disk

Every installed skill gets a directory at `$SKILL_MANAGER_HOME/skills/<name>/` (defaults to `~/.skill-manager/skills/<name>/`). On a successful `add`, the CLI prints one line per newly-installed skill in a stable, parseable shape:

```
INSTALLED: hello-skill@0.1.0 -> /Users/you/.skill-manager/skills/hello-skill
```

Read those lines to find the `SKILL.md` you just acquired — no agent restart needed. The directory contains the skill's `SKILL.md`, any referenced assets, and the `skill-manager.toml` manifest.

`install` also drops a symlink into every known agent's skills directory, pointing back at the store path:

```
~/.claude/skills/<name> -> ~/.skill-manager/skills/<name>
~/.codex/skills/<name>  -> ~/.skill-manager/skills/<name>
```

Without those symlinks the agent runtime can't see the skill, so this happens unconditionally on every `install`. Use `env.sh --for claude` (or `--for codex`) to ask for the agent-visible path; default output reports the original store path.

### Locating CLIs by absolute path (avoiding PATH conflicts)

Installed CLI tools land in `$SKILL_MANAGER_HOME/bin/cli/`, but skill-manager does **not** mutate your PATH. To invoke a skill's CLI dependency without colliding with whatever the user already has on PATH (different `npm`, different `uv`, etc.), call `env.sh` to get absolute paths:

```
<skill-manager-skill>/scripts/env.sh --skills hello-skill pip-cli-skill
# or omit --skills to dump every installed skill
<skill-manager-skill>/scripts/env.sh --pretty
```

`env.sh` is a thin wrapper that locates `uv` (skill-manager's bundled copy under `$SKILL_MANAGER_HOME/pm/uv/current/bin/uv` first, then system PATH) and runs `env.py` via `uv run --script`, so the right Python is guaranteed without requiring the user's interpreter to be 3.11+. If `uv` cannot be found in either location, `env.sh` exits with code 3 and a clear install hint.

It returns JSON with these keys you'll typically use:

- `skills` — per-skill paths, keyed by skill name. Each entry has `path` (the path you should use), `original` (always the store path under `~/.skill-manager/skills/<name>`), and `agents` (a dict of every agent symlink that exists on disk, e.g. `claude` and `codex`). Pass `--for claude` or `--for codex` to set `path` to that agent's symlink (with original-path fallback if no symlink exists). Default `--for` is unset, so `path` equals `original`.
- `package_managers` — absolute paths to bundled `uv`, `node`, `npm`, `npx` (from `~/.skill-manager/pm/<id>/current/bin/<tool>`), with system-PATH fallback. `brew` is system-only.
- `clis` — absolute path to each declared CLI dependency that is actually installed under `bin/cli/`, keyed by binary name.
- `missing` — declared CLI deps that are not on disk; each entry includes the `candidate_names` checked, so the agent can decide whether to re-install or fail loudly.

The script never mutates PATH or any shell state — just reports. Invoke the returned `path` directly (`/abs/path/to/cowsay --moo`) to bypass any conflicting tool on the user's PATH.

### Authentication

Most reads (`search`, `show`, `list`, fetching a public skill) work without logging in. Mutating operations (`publish`, creating campaigns) require a bearer token that's cached at `$SKILL_MANAGER_HOME/auth.token` after the user runs `skill-manager login`.

The CLI refreshes its access token silently from the saved refresh token — in practice the user logs in once a week (7-day refresh TTL) and never sees an auth prompt during normal work.

When the refresh token is also expired or rejected, the CLI exits with code `7` and emits a stable banner on stderr:

```
ACTION_REQUIRED: skill-manager login
Reason: <specifics>
Ask the user to run the following in their terminal, then retry the task:

    skill-manager login
```

**When you see `ACTION_REQUIRED: skill-manager login`, relay it to the user verbatim** (including the `skill-manager login` line so they can copy/paste), pause the task, and retry only after they confirm they've signed in. Never try to auth on their behalf — the browser flow needs their input.

### Working with the MCP gateway

The gateway fronts every MCP server; agents only ever see one MCP endpoint. The CLI owns only the gateway process lifecycle — everything else happens over MCP.

| Step | Command |
| --- | --- |
| Start / stop | `skill-manager gateway up` / `gateway down` |
| See URL + health | `skill-manager gateway status` |

**How MCP servers get into the gateway**: by declaring them as `[[mcp_dependencies]]` in a skill's `skill-manager.toml` and installing that skill (`skill-manager install <skill>`). Registration is a side effect of install — there is no CLI to register an MCP server directly.

**How agents use them**: call `browse_mcp_servers`, `describe_mcp_server`, `deploy_mcp_server`, `search_tools`, `describe_tool`, `invoke_tool` over MCP. These are the gateway's built-in virtual tools.

After a skill's MCP deps are registered, they persist across gateway restarts. Prefer asking the user for init params (secrets, tokens) at deploy time rather than hardcoding them in the skill.

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
skill-manager install <name> --dry-run     # review the plan
skill-manager install <name> --yes
```

**"What MCP tools can I use right now?"**

Call `browse_active_tools` or `search_tools` over MCP against the
`virtual-mcp-gateway` entry in your MCP config. There is no CLI equivalent.

**"Add a new MCP server"**

Make a skill (even a one-off) that declares the server as an
`[[mcp_dependencies]]` entry in its `skill-manager.toml`, then
`skill-manager install <skill>`. Registration with the gateway happens
transitively. See "MCP dependencies" in the spec for the supported
`load` types (docker, binary) and `default_scope` options.

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
