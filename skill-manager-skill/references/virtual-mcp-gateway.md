# The virtual MCP gateway

This is the architectural reference for how `skill-manager`-managed
skills expose MCP servers to agents. Read it whenever you need to
explain the gateway, debug a missing or undeployed server, or decide
whether a new capability belongs in a skill.

The CLI side is documented in [`SKILL.md`](../SKILL.md). This file
covers the agent-visible surface — the gateway and its virtual tools.

## What the gateway is, and why it's there

When you install a skill with `skill-manager install`, it doesn't add
each MCP server to your agent's MCP config independently. Instead it:

1. Starts (or reuses) the **virtual MCP gateway** — a small FastAPI/MCP
   process living under `~/.skill-manager/`.
2. Registers every MCP dependency declared by every installed skill
   with the gateway, transitively across the whole skill graph.
3. Writes a single `virtual-mcp-gateway` entry into the agent's MCP
   config (`~/.claude.json`, `~/.codex/config.toml`, …) pointing at
   the gateway's HTTP endpoint.

So agents see **one** MCP server. The gateway exposes a fixed, virtual
tool surface for discovering, deploying, and invoking the real
downstream tools. The downstream servers themselves can be anything
the skill manifest declares — `npm` packages, `uv`-spawned Python
servers, docker images, binaries, shell commands, or `streamable-http`
URLs — and they're transparent to the agent.

The gateway is a proxy: it never modifies arguments or results.
Authentication, rate-limiting, and side effects all happen at the
downstream level.

## Knowing which skills are skill-manager-managed

Run:

```
skill-manager list
```

That prints every skill currently in the store at
`$SKILL_MANAGER_HOME/skills/<name>/`. Any MCP server reachable through
the gateway's virtual tools came from one of those skills (or a
transitive `skill_references` of one of them).

If a tool you expect isn't visible, the most likely causes are:

1. The owning skill isn't installed — `skill-manager install <name>`.
2. The MCP dep is registered but not yet deployed (e.g. its
   `init_schema` declares a required env var that wasn't set at
   install time). Either supply it via `deploy_mcp_server` or
   re-run `skill-manager sync` after exporting the var.
3. The gateway isn't running — `skill-manager gateway up`.

## Virtual tool surface

Call all of these on the `virtual-mcp-gateway` MCP server.

| Virtual tool          | Use it to …                                                                         |
|-----------------------|-------------------------------------------------------------------------------------|
| `browse_mcp_servers`  | List every registered MCP server, deployed or not.                                  |
| `describe_mcp_server` | Look up `init_schema`, `default_scope`, `load_type`, current deployment for one.    |
| `deploy_mcp_server`   | Spawn / re-spawn a server (with `initialization_params` for required init fields).  |
| `browse_active_tools` | List the tools currently active downstream (filterable by `server_id`).             |
| `search_tools`        | Lexical / semantic search across active tools when you don't know the exact name.   |
| `describe_tool`       | Disclose a tool's schema. Required before `invoke_tool` (gateway gates calls).      |
| `invoke_tool`         | Call a downstream tool by `tool_path` (`<server_id>/<tool_name>`).                  |

`tool_path` is always `<server_id>/<tool_name>`. The `server_id` comes
from `browse_mcp_servers`; the `tool_name` from `browse_active_tools`
or `search_tools`.

## The disclosure gate

The gateway refuses `invoke_tool` for tools that haven't been disclosed
in the current session. **Calling `describe_tool` once per session
covers every subsequent invoke of the same tool** — you don't need to
re-describe before each call.

Concretely the per-session-and-tool flow is:

```text
1. browse_active_tools(server_id="X")             # confirm X is up
2. describe_tool(tool_path="X/some-tool")         # schema + disclosure
3. invoke_tool(tool_path="X/some-tool",           # actual call
               arguments={...})
```

The session is keyed by the `x-session-id` HTTP header your MCP client
sends. Most agents reuse one session per process, so you only pay step
2 once per tool per launch.

## Scopes and deployment

Each MCP dep declares a `default_scope` — how the gateway hosts it:

- **`global-sticky`** *(default)* — one shared deployment across every
  agent session; `init_values` persisted to disk and auto-redeployed
  when the gateway restarts. Best for stable shared services.
- **`global`** — one shared deployment, but `init_values` only kept in
  memory; lost across gateway restarts.
- **`session`** — fresh deployment per agent session; `init_values`
  scoped to that session and discarded when it ends. Use for
  per-tenant credentials or workspaces.

At install time, skill-manager auto-deploys any server whose scope is
not `session` and whose `init_schema` has no required fields missing
from the install-process environment. Anything else is registered but
not deployed; the agent picks it up later via `deploy_mcp_server`.

When the user reports that an MCP server isn't responding (idle
timeout, killed subprocess, env-var rotation), `deploy_mcp_server`
with `reuse_last_initialization=true` re-spawns it without making the
user re-enter secrets, as long as the server is `global-sticky` and
the previous init succeeded.

## Adding a new MCP server to the gateway

Don't shell out to `npx`/`uv`/`docker` from the agent to bring up an
MCP server directly. Author a skill (or extend one the user already
has) that declares the server under `[[mcp_dependencies]]` in its
`skill-manager.toml`, then:

1. Install (or re-install) the skill — `skill-manager install <skill>`.
2. The gateway picks the new server up transitively; if it has no
   missing required init, it auto-deploys.
3. Discover its tools via `browse_active_tools(server_id="<name>")`.

Registering through skill-manager guarantees the gateway gets a clean
spec, the right runtime (`npx`, `uv`, `docker`, …) is bundled if
needed, and `init_schema`-declared secrets follow the env-init path
without ever being committed to disk. See the skill-publisher skill
for the full authoring flow.

## Verifying gateway state

The gateway's MCP virtual tools *are* the verification surface — use
them directly rather than reaching for shell or HTTP probes.

| Question                                              | Call                                                      |
|-------------------------------------------------------|-----------------------------------------------------------|
| Is the gateway running, what's its URL?               | `skill-manager gateway status` (CLI lifecycle)            |
| What's registered?                                    | `browse_mcp_servers()`                                    |
| Is server X deployed and what does it want?           | `describe_mcp_server(server_id="X")`                      |
| What tools is X exposing right now?                   | `browse_active_tools(server_id="X")`                      |
| Does invoke actually work end-to-end?                 | `describe_tool(tool_path="X/T")` then `invoke_tool(...)`  |

The CLI never proxies tool discovery or invocation; only the MCP
virtual tools do. If `browse_mcp_servers` shows a server but
`browse_active_tools(server_id="X")` shows zero tools, the subprocess
is reachable but its `tools/list` came back empty — re-deploy with
`deploy_mcp_server` and try again.

## Failure modes worth recognizing

- **`Tool invocation denied. Call describe_tool first…`** — disclosure
  gate. Call `describe_tool(tool_path=…)` in the same session, then
  retry. Re-deploying a server resets the gate.
- **`server_id not deployed`** — the server is registered but no
  subprocess is alive. Call `describe_mcp_server` to see what's
  missing (often a required init field), then `deploy_mcp_server`
  with `initialization_params={…}`.
- **`stdio downstream error`** / **`streamable-http downstream
  error`** — transport-layer failure talking to a downstream server.
  The gateway retries once on a fresh session; persistent failures
  indicate the subprocess crashed or the URL is unreachable. Inspect
  the gateway log under `~/.skill-manager/gateway.log`.
- **Tool list shows a server but no tools** — the subprocess is
  reachable but its `tools/list` response is empty (often a buggy
  server build, or the subprocess crashed after the initialize
  handshake). Re-deploying with `deploy_mcp_server` is usually
  enough.

## Cross-references

- [`SKILL.md`](../SKILL.md) — CLI-side install/sync/upgrade flows and
  the per-tool quick reference.
- `skill-publisher-skill/SKILL.md` — how to author a skill that
  declares an MCP dep with the right `load` type and `init_schema`.
- `virtual-mcp-gateway/gateway/` (in the skill-manager source repo)
  — the gateway implementation, including
  `clients.py` (downstream transports), `provisioning.py` (how
  manifest specs become `ClientConfig`s), and `server.py` (the
  virtual tool definitions).
