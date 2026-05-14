---
name: inner-impl
description: Contained skill of umbrella-plugin fixture — carries its own CLI + MCP deps that the plugin's install pipeline walks and registers.
version: 0.0.1
skill-imports: []
---

# inner-impl

Test fixture body. The contained-skill's `skill-manager.toml` declares
its own CLI dep (cowsay) and MCP dep (a distinct server_id). The
plugin's install pipeline unions these with the plugin-level deps in
`../skill-manager-plugin.toml`, so both ends register independently
with the gateway / cli-lock.
