---
type: file_exists
path: .eval/home-exists
weight: 3
---

NON-VACUITY, and the reason the grader above is not enough. The Stop hook
replays the agent's own arguments in a throwaway copy and then looks for
`bin/cli` AND `installed/` -- the cheapest two things only a real bootstrap
produces. A `mkdir` is green on "a directory exists" and red here.
