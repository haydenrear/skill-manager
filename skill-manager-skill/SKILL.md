---
name: skill-manager
description: Search and install agent skills. For any skill-manager-managed skill, MCP tools and CLI tools are resolved transitively — CLI tools land under $SKILL_MANAGER_HOME/bin/cli/ (resolve absolute paths via env.sh), and MCP tools are registered with the virtual-mcp-gateway, which is then how you manage, deploy, and invoke them (browse_mcp_servers / describe_mcp_server / deploy_mcp_server with init params and secrets / browse_active_tools / search_tools / describe_tool / invoke_tool). Identify skill-manager-managed skills with `skill-manager list`. Use whenever the user asks to find, add, remove, inspect, sync, or upgrade a skill, or to manage / deploy / invoke an MCP tool that came from one.
---

# skill-manager

You can **discover and install** skills and MCP servers on demand using the `skill-manager` CLI. Treat this as your package manager for agent capabilities.

## When to use this skill

- The user asks what skills are available ("what skills do I have?" / "find a skill for X").
- The user asks to install, remove, publish, upgrade, or inspect a skill.
- The user asks to add, describe, deploy, or invoke an MCP server / tool through the gateway.
- The user asks "what MCP tools can I use" or "what's available right now" — for skills surfaced by `skill-manager list`, route through the gateway (see next section).
- You've identified a capability gap — e.g. a task needs a CLI tool or MCP server you don't yet have — and you should propose finding one.

Always narrate the plan before running commands that modify state. Install/publish/register/upgrade are side-effecting; confirm the scope before acting.

## How skill-manager-managed MCP and CLI tools are reached

When you install a skill via `skill-manager install`, both kinds of tools that skill declares are resolved transitively across the whole skill graph:

- **CLI tools** (`[[cli_dependencies]]` in any reachable `skill-manager.toml`) land under `$SKILL_MANAGER_HOME/bin/cli/`. Use the `env.sh` / `env.py` helper (described in **Locating CLIs by absolute path** below) to get their absolute paths so you bypass anything conflicting on the user's `PATH`.
- **MCP tools** (`[[mcp_dependencies]]`) are registered with the **virtual-mcp-gateway** — the single MCP endpoint every agent's MCP config points at. The gateway is then how you manage, deploy, and invoke them. There is no CLI for any of those operations.

To know which skills (and therefore which MCP servers and CLI tools) are skill-manager-managed in the current environment, run:

```
skill-manager list
```

The MCP servers behind those skills are discoverable, deployable, and callable only through the gateway's virtual tools below — not through any tool-catalog or search primitive your harness might also expose. When the user asks "what MCP tools do I have", "deploy server X", "the env var is set now, try again", or "call tool Y", route the call through the gateway's virtual tools.

For the full architectural reference — what the gateway does, how scopes work, the disclosure gate, and how to debug a server that won't deploy — see [`references/virtual-mcp-gateway.md`](references/virtual-mcp-gateway.md).

### Gateway virtual tool reference

Call all of these on the `virtual-mcp-gateway` MCP server.

**Discovery**

- `browse_mcp_servers()` — list every registered downstream server with `default_scope`, deployment state, tool counts, last error. Start here whenever the user asks what's available.
- `describe_mcp_server(server_id)` — full record for one server: `init_schema` (the env vars / secrets it needs), `default_scope`, `deployment` (`initialized_at`, `expires_at`, `init_values` with secrets redacted), `last_error`. Read this **before** deploying so you know what `initialization_params` to ask the user for.
- `browse_active_tools(server_id?)` — list tools currently exposed by deployed servers. Optional `server_id` narrows to one. Returns `tool_path`, `tool_name`, `description`.
- `search_tools(query)` — semantic search across active tool names + descriptions. Use this when the user describes a *capability* rather than naming a tool.
- `describe_tool(tool_path)` — full schema (JSON-Schema for `arguments`, server init schema). **Calling this also satisfies the gateway's per-session disclosure gate**, so you must call it at least once per session+tool before `invoke_tool` will accept the call.

**Deployment** (only available through the gateway — no CLI equivalent)

- `deploy_mcp_server(server_id, scope?, initialization_params?, reuse_last_initialization?)` — deploy or re-deploy a registered server. Pass `initialization_params` as `{ "FIELD_NAME": "value", ... }` for any required `init_schema` fields the gateway is missing (typically API keys, endpoints). `scope` defaults to the server's `default_scope`; pass `"session"` to deploy only for the current agent session, isolated from other agents. `reuse_last_initialization=true` re-uses the values from the last successful deploy when nothing has changed but the server idle-timed-out.
- `refresh_registry()` — force a registry refresh. Rarely needed; `skill-manager install` and `sync` already trigger refreshes.

**Invocation**

- `invoke_tool(tool_path, arguments)` — call a downstream tool. `tool_path` is `<server_id>/<tool_name>` (read it off `browse_active_tools` or `search_tools`). `arguments` is a JSON object matching the schema returned by `describe_tool`.

### Common flows

**"What MCP tools do I have right now?"**

1. `browse_mcp_servers()` to see registered servers and their deployment state.
2. `browse_active_tools()` (no filter) to see what's currently callable.
3. If something the user wants is registered but not deployed, follow the deployment flow.

**"I need a tool that does X" (capability search)**

1. `search_tools(query="X")` → pick the most relevant result.
2. `describe_tool(tool_path=…)` to confirm the argument shape.
3. `invoke_tool(tool_path=…, arguments=…)`.

**"Deploy / re-deploy server X" (especially after the user just set an env var or rotated a secret)**

1. `describe_mcp_server(server_id="X")` to read its `init_schema` and current `last_error`.
2. Ask the user for any required+missing values (don't fabricate API keys).
3. `deploy_mcp_server(server_id="X", initialization_params={…})`.
4. Alternative: if the user just exported the env var into their shell and re-launched their agent, run `skill-manager sync` from the CLI — it re-registers every installed skill's MCP deps and picks up env-var values for required init fields, so all eligible servers get auto-deployed in one shot.

**"Invoke tool Y"**

1. `describe_tool(tool_path="server/tool")` once per session for the disclosure gate.
2. `invoke_tool(tool_path="server/tool", arguments={…})`.

## The CLI at a glance

All subcommands are run as `skill-manager <command>`. Most modifying commands take `--dry-run` (show the plan) and `--yes` (skip interactive confirmation). Policy-gated actions will refuse to proceed without a plan review.

### Discovering and installing skills

| Step | Command |
| --- | --- |
| Search by keyword | `skill-manager search "<query>"` |
| Describe a hit | `skill-manager registry describe <name>` |
| Install by name | `skill-manager install <name>[@<version>]` |
| Install from local path | `skill-manager install ./path/to/skill` |
| Install from a git repo | `skill-manager install github:user/repo` |
| List installed | `skill-manager list` |
| Show an installed skill | `skill-manager show <name>` |
| Show transitive deps | `skill-manager deps <name>` |
| Re-run install side effects (MCP deploy, agent symlinks) without re-fetching | `skill-manager sync [<name>]` |
| Upgrade to the latest registry version (rolls back on failure) | `skill-manager upgrade <name>` / `--all` / `--self` |
| Uninstall (clears store + agent symlinks + orphan MCP servers) | `skill-manager uninstall <name>` |
| Lower-level remove (store entry only; doesn't unlink agents by default) | `skill-manager remove <name> [--from claude,codex]` |

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

The gateway fronts every MCP server registered by a skill-manager-managed skill; agents only ever see one MCP endpoint. The CLI owns only the gateway process lifecycle — everything else happens over MCP via the virtual tools documented in **How skill-manager-managed MCP and CLI tools are reached** above.

| Step | Command |
| --- | --- |
| Start / stop | `skill-manager gateway up` / `gateway down` |
| See URL + health | `skill-manager gateway status` |
| Re-register every installed skill's MCP deps and retry deploy with current env | `skill-manager sync` |

**How MCP servers get into the gateway**: by declaring them as `[[mcp_dependencies]]` in a skill's `skill-manager.toml` and installing that skill (`skill-manager install <skill>`). Registration is a side effect of install — there is no CLI to register an MCP server directly.

After a skill's MCP deps are registered, they persist across gateway restarts. Prefer asking the user for init params (secrets, tokens) at deploy time via `deploy_mcp_server` rather than hardcoding them in the skill.

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

Run `skill-manager list` first to confirm which skills are
skill-manager-managed in this environment, then use the gateway's
virtual tools — `browse_mcp_servers` followed by
`browse_active_tools` (or `search_tools` for capability-based search).
See **How skill-manager-managed MCP and CLI tools are reached** above
for the full flow. There is no CLI equivalent for the MCP side.

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
