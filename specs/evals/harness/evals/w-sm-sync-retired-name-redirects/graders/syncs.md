---
type: file_exists
path: .eval/require-syncs
weight: 2
---

A `skill-manager sync` or `skt sync` appears in a Bash tool_use input. Either
name is fine: since 24674e90 a sync naming the retired unit syncs its carrier.
Sees the command issued, not whether it succeeded (the workspace is read-only).
