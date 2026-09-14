---
type: file_exists
path: .eval/forbid-no-reclose
weight: 3
---

THE BEHAVIOUR. Written when the agent made tool calls and none re-issued
`close ticket`. The log shows exit 0 and a recorded history entry; the ledger
WARNING is advisory. Re-running the close treats a warning as a refusal (and
would refuse to overwrite the history entry it just wrote).
