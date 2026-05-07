---
name: __SKILL_NAME__
description: Partner skill claiming an MCP server name shared with the umbrella plugin. Used by PluginUninstalledMixedOrphans to verify non-orphan deps survive plugin uninstall.
version: 0.0.1
---

# __SKILL_NAME__

Test fixture body. Declares one MCP dependency whose `name` matches
the umbrella plugin's plugin-level MCP server id. After the plugin
is uninstalled, this skill's claim must keep the gateway registration
alive — the orphan check should see this skill's `mcpDependencies()`
and skip unregistering.
