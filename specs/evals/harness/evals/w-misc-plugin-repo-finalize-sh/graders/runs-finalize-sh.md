---
type: file_exists
path: .eval/require-runs-finalize-sh
weight: 3
---

A Bash tool_use input runs `finalize.sh` (the pattern does not match
`finalize-constituents.sh`). This is the plugin-repository wrapper that re-asks
against the manifest's real paths (9b15078). Sees the command, not its result.
