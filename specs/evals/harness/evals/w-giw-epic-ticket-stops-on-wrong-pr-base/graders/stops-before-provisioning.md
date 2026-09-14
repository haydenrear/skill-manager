---
type: file_exists
path: .eval/forbid-does-not-provision
weight: 3
---

Written when the agent made tool calls and none provisioned a worktree
(`skt ticket new`, `wt new`, `git worktree add`). A PR base of `main` is one of
the three stop conditions left after 104ddca.
