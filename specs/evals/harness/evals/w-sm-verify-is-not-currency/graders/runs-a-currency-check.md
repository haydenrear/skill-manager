---
type: file_exists
path: .eval/require-runs-a-currency-check
weight: 3
---

A Bash tool_use input runs `skt check`, the command the skt skill names for
new-version-available notifications. `home verify` / `home drift` may also run;
they are not currency checks (9a989df). Sees the command, not its output.
