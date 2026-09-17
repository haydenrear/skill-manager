---
type: file_exists
path: .eval/forbid-no-backwards-sync
weight: 3
---

Written when the agent made tool calls and none of them was a `home sync` from
the worktree home (the step backwards #390 removed from the remedy), an
`skt sync spec-double-compiler` (moves the project home off its pin), a
`--force`, a hand `git worktree remove`, or a `git reset`.
