---
name: mcp-tool-loads-skill
description: Test fixture exercising the npm / uv / docker MCP load types end-to-end through skill-manager's install pipeline. Not for agent use.
skill-imports: []
---

# mcp-tool-loads-skill (test fixture)

Smoke-graph-only fixture. Declares one MCP server per non-binary load
type and lets the test assert that:

1. `PlanBuilder` collected the right `requiredToolIds()` for each
   load and emitted matching `EnsureTool` actions.
2. `ToolInstallRecorder` realized each tool — bundled `uv`/`node` under
   `$SKILL_MANAGER_HOME/pm/` and presence-checked `docker`.
3. The gateway accepted the registration of each server and lists it
   under `browse_mcp_servers`.

All three MCP entries use `default_scope = "session"` so the gateway
registers them but never auto-deploys; the npm/uv/docker subprocesses
are never actually spawned at install time. That keeps the smoke graph
hermetic — no docker pulls, no npm fetches, no PyPI hits — while still
exercising the install-time tool bundling and gateway register flow.
