# The virtual MCP gateway

This is the detailed reference for MCP tools registered by
skill-manager-managed units. Use it when you need to discover, deploy,
describe, invoke, or troubleshoot a downstream MCP server.

The top-level skill file gives only a summary. This file is the
authoritative agent-side gateway reference.

## What the gateway is

Agents see one MCP server named `virtual-mcp-gateway`. The gateway fronts
the real downstream MCP servers declared by installed units:

- Skills declare MCP deps in `skill-manager.toml`.
- Plugins declare MCP deps in `skill-manager-plugin.toml` and contained
  skill manifests.
- Harnesses install and compose referenced skills/plugins; those
  referenced units provide the MCP registrations.
- Doc-repos do not register MCP servers directly.

On install/sync, skill-manager registers every reachable
`[[mcp_dependencies]]` entry with the gateway. The gateway then exposes a
fixed virtual tool surface for discovery, deployment, schema disclosure,
and invocation. Downstream servers can be backed by npm, uv, docker,
binary, shell, or streamable-http load types according to their
manifests.

The CLI owns only local lifecycle and registration side effects:

```bash
skill-manager gateway up
skill-manager gateway down
skill-manager gateway status
skill-manager sync
```

The CLI does not discover or invoke downstream tools. Use the virtual MCP
tools below for that.

## First checks

Start with installed state:

```bash
skill-manager list
```

If a server or tool is missing, common causes are:

1. The owning unit is not installed.
2. The unit is installed but sync has not re-registered the current MCP
   manifest.
3. The server is registered but not deployed because required
   `init_schema` values were missing.
4. The gateway process is not running.

Use `skill-manager sync` after installing/updating units or after
restarting the agent with newly exported environment variables.

## Virtual Tool Surface

Call these on the `virtual-mcp-gateway` MCP server.

### Discovery

- `browse_mcp_servers()` lists registered downstream servers, deployed or
  not. Start here when the user asks what MCP servers/tools are
  available.
- `describe_mcp_server(server_id)` returns the full server record:
  load type, default scope, init schema, deployment status, redacted init
  values, and last error.
- `browse_active_tools(server_id?)` lists callable tools from deployed
  servers. Pass `server_id` to narrow to one server.
- `search_tools(query)` searches active tool names and descriptions
  when the user describes a capability rather than a specific tool.
- `describe_tool(tool_path)` returns the tool schema and satisfies the
  per-session disclosure gate required before invocation.

### Deployment

- `deploy_mcp_server(server_id, scope?, initialization_params?,
  reuse_last_initialization?)` spawns or re-spawns a registered server.
  Pass `initialization_params` as a JSON object for required fields such
  as API keys or endpoints.
- `refresh_registry()` forces the gateway to reload registration state.
  This is rarely needed because install and sync already refresh the
  registry.

Use `reuse_last_initialization=true` when a previously successful
global-sticky server timed out or was killed and the same redacted
secret set should be reused.

### Invocation

- `invoke_tool(tool_path, arguments)` calls a downstream tool.

`tool_path` is always `<server_id>/<tool_name>`. Get it from
`browse_active_tools` or `search_tools`, then call `describe_tool` before
the first `invoke_tool` in the current session.

## Disclosure Gate

The gateway refuses `invoke_tool` until the tool has been disclosed in
the current session:

```text
1. browse_active_tools(server_id="X")
2. describe_tool(tool_path="X/tool")
3. invoke_tool(tool_path="X/tool", arguments={...})
```

The session is keyed by the MCP client's session header. Most agents use
one session per process, so one `describe_tool` call per tool per agent
launch is normally enough.

If invocation fails with a message like "Call describe_tool first", call
`describe_tool` again in the same session and retry.

## Scopes And Initialization

Each MCP dependency declares a `default_scope`:

- `global-sticky`: one shared deployment across sessions. Successful
  init values are persisted by the gateway and can be reused.
- `global`: one shared deployment, but init values are memory-only.
- `session`: one deployment per agent session; init values are discarded
  with that session.

At install/sync time, skill-manager may auto-deploy non-session servers
whose required init fields are satisfied by the process environment.
Otherwise the server remains registered but undeployed until an agent
calls `deploy_mcp_server`.

When a required secret is missing, ask the user for it. Do not invent,
guess, or hardcode credentials in a manifest.

## Adding A Server

Do not start downstream MCP servers manually from the agent with
`npx`, `uv`, or `docker`. Add the server to a skill or plugin manifest
as an `[[mcp_dependencies]]` entry, then install or sync that unit.

That keeps runtime selection, transitive ownership, init schema, gateway
registration, and later cleanup in one place. Use the `skill-publisher`
skill for manifest examples and supported load types.

## Verification

| Question | Use |
| --- | --- |
| Is the gateway running? | `skill-manager gateway status` |
| What servers are registered? | `browse_mcp_servers()` |
| What does server X need? | `describe_mcp_server(server_id="X")` |
| Is server X exposing tools? | `browse_active_tools(server_id="X")` |
| What is the argument schema? | `describe_tool(tool_path="X/tool")` |
| Does the tool work end-to-end? | `invoke_tool(tool_path="X/tool", arguments={...})` |

If `browse_mcp_servers` shows a server but `browse_active_tools` shows
no tools, the server is either undeployed or its downstream `tools/list`
returned no tools. Use `describe_mcp_server` for status and last error,
then redeploy if appropriate.

## Failure Modes

- `Tool invocation denied. Call describe_tool first...`: disclosure gate.
  Describe the tool in the same session, then retry.
- `server_id not deployed`: the server is registered but has no live
  deployment. Describe it, collect missing init params, then deploy.
- `stdio downstream error` or `streamable-http downstream error`:
  transport failure talking to the downstream server. Redeploy; if it
  persists, inspect `~/.skill-manager/gateway.log`.
- Server is visible but has zero tools: the downstream server may have
  crashed after initialization or returned an empty `tools/list`.
  Redeploy and inspect the gateway log if it persists.
- Newly installed server is not visible: run `skill-manager sync`, then
  `refresh_registry()` if the current gateway session still sees stale
  state.

## Cross-References

- `../SKILL.md` - when to use skill-manager and where CLI help is
  authoritative.
- `workflows.md` - agent decision flows that combine CLI state and
  gateway operations.
- `skill-publisher` skill - how to author MCP dependencies in skills and
  plugins.
