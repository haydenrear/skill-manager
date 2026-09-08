---
type: file_exists
path: .eval/worktree-removed
weight: 3
---

NON-VACUITY, and both halves are required: the Stop hook replays the agent's own
arguments in a throwaway copy and writes this only when the command exits 0 AND
the worktree is absent afterwards.

Either alone is not the verdict. Exit 0 with the worktree still standing means
the gate refused -- which is the CORRECT outcome when work would be destroyed,
and a wrong one here, where the fixture's work is merged and it should come
down cleanly.
