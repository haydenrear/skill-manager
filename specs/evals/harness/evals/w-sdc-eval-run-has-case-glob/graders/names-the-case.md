---
type: regex
pattern: "plugin eval[^\\n]*--case"
weight: 3
---

THE BEHAVIOUR. Discovery is `<eval dir>/**/case.yaml` and follows symlinked
units into their own eval directories, so a command with no `--case` runs, and
bills, every case it can reach (plugin_evals.md §2, verified in b5cfb09).

Reads the FINAL RESPONSE only: the agent was told not to run the command, so
the command it would issue exists only in its reply.
