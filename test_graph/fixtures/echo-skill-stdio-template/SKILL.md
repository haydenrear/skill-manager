---
name: __SKILL_NAME__
description: test_graph fixture — exercises a stdio MCP dependency at scope __SCOPE__.
version: 0.0.1
---

# __SKILL_NAME__

Test fixture. The test_graph runner stamps this template into a temp directory
at runtime (replacing `__SKILL_NAME__`, `__SERVER_ID__`, `__SCOPE__`,
`__VENV_PYTHON__`, `__FIXTURE_PATH__`), then `skill-manager install
file:<tempdir>` installs it. The install path registers the MCP dep
transitively with the running virtual MCP gateway, which spawns the python
fixture as a stdio subprocess.

Nothing in here is meant to be loaded by a real agent — exists solely to
exercise `StdioMCPClient` end-to-end through the gateway.
