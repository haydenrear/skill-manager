# Virtual MCP Gateway

A Python gateway that exposes a small, stable MCP tool surface over a dynamic set of downstream MCP servers.

## Why this exists

Current MCP integrations are good at exposing a server's full tool list, but much weaker at controlled, progressive disclosure.

- `tools.listChanged` and related tool-change notification handling are uneven across clients and servers, so relying on dynamic tool-surface updates is fragile in practice.
- Skill systems generally do not have a first-class way to reference a tool from a skill, defer schema disclosure until it is actually needed, and then invoke that tool through the same reference.
- That often forces people to copy JSON schemas into skills or prompts by hand and keep them updated manually, even though the authoritative contract already exists on an MCP server.

This gateway solves that by putting a stable virtual registry in front of downstream servers. A skill can reference the gateway plus a virtual tool path, discover or search for tools cheaply, disclose the real schema on demand with `describe_tool`, and then invoke the tool once that path has been explicitly disclosed for the session.

## Features

- Frontend transport: Streamable HTTP
- Downstream transports: stdio, Streamable HTTP, SSE
- Stable virtual tool registry with path-based progressive disclosure
- Session-gated invocation via `describe_tool` + session header path disclosure
- On-demand schema disclosure, so skills do not need to inline and manually maintain downstream tool schemas
- Header-aware filtering and header forwarding on every downstream tool call
- Background downstream refresh with cached tool lists
- Explicit `refresh_registry` control instead of depending on clients to handle downstream tool-change notifications well
- Semantic fallback suggestions using spaCy when a requested tool path does not exist

## Virtual tools exposed by the gateway

- `browse_tools`
- `search_tools`
- `describe_tool`
- `invoke_tool`
- `refresh_registry`

## Run

```bash
python -m venv .venv
source .venv/bin/activate
pip install -e .
python -m gateway.server --config sample-config.json --host 127.0.0.1 --port 8080
```

## Notes

- The gateway MCP endpoint is `POST /mcp`.
- The initial tool surface stays small and stable; downstream tool schemas are revealed through `describe_tool` only when a client or skill asks for a specific path.
- The gateway forwards request headers to downstream tool calls, excluding a small set of hop-by-hop HTTP headers.
- Use `X-Session-Id` to control disclosure state explicitly. If omitted, the transport `Mcp-Session-Id` is used.
- `refresh_registry` is available when you need deterministic pickup of downstream tool changes without relying on `tools.listChanged` handling across the whole stack.
- When a tool is missing, the gateway returns semantic suggestions based on path/name/description similarity.

## Example JSON-RPC calls

### Initialize

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-05","capabilities":{"tools":{"listChanged":true}},"clientInfo":{"name":"demo-client","version":"0.1.0"}}}
```

### List tools

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

### Describe a virtual tool

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"describe_tool","arguments":{"tool_path":"http/remote/github/search_repos"}}}
```

### Invoke a virtual tool

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"invoke_tool","arguments":{"tool_path":"http/remote/github/search_repos","arguments":{"query":"model context protocol"}}}}
```


# MCP Marketplace

This gives you the opportunity for agents to discovery MCP servers and deploy them themselves. Then they can show the schema for only one tool.

The main pain-point is the disclosure of every tool, every resource, etc. This allows that pain point to be relieved by using the agent's own tool registry and instructions on progressive disclosure. So now an agent can, in a skill, discover a reference to an MCP server that they can configure and show only the schema for one tool, with their own initialization parameters.