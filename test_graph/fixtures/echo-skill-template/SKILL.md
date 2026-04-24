---
name: __SKILL_NAME__
description: test_graph fixture — exercises an MCP dependency at scope __SCOPE__.
version: 0.0.1
---

# __SKILL_NAME__

Test fixture. The test_graph runner stamps this template into a temp directory
at runtime (replacing `__SKILL_NAME__`, `__SERVER_ID__`, `__SCOPE__`,
`__MCP_URL__`), then `skill-manager install file:<tempdir>` installs it. The
install path registers the MCP dep transitively with the running virtual MCP
gateway, which is what the downstream nodes assert.

Nothing in here is meant to be loaded by a real agent.
