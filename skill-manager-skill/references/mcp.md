---
skill-imports: []
---

# MCP dependencies

Use this reference when a skill or plugin declares MCP servers in
`skill-manager.toml` or `skill-manager-plugin.toml`.

## Authoring

Declare each downstream server in `[[mcp_dependencies]]`. The manifest
identifies the server id, default deployment scope, load type, and any
initialization schema.

Common load types are `npm`, `uv`, `docker`, `binary`, and `shell`.
Prefer package-based load types when possible. Treat `shell` and binary
init scripts as high-risk because they run local commands.

Use `init_schema` for values the gateway should request at deploy time.
Mark secrets with `secret = true`; never hardcode API keys in a
manifest.

## Runtime

Agents see one MCP server: `virtual-mcp-gateway`. The gateway fronts the
real downstream servers declared by installed units. Use gateway tools
to discover, deploy, describe, and invoke downstream tools:

1. `browse_mcp_servers`
2. `describe_mcp_server`
3. `deploy_mcp_server` when the server is not already deployed
4. `browse_active_tools` or `search_tools`
5. `describe_tool`
6. `invoke_tool`

Call `describe_tool` before `invoke_tool` in the current session.

## Validation

After install or sync, verify registration with:

```bash
skill-manager gateway status
skill-manager sync <unit-name>
```

If a server is registered but undeployed, inspect its initialization
schema and ask the user for required secrets.
