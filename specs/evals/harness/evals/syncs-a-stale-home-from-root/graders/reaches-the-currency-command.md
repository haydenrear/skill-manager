---
type: file_exists
path: .eval/front-door
weight: 3
---

`skt check` / `skill-manager sync`. Written by the Stop hook from the
transcript's tool_use inputs.

THE DISTINCTION THIS IS ABOUT, and it is a real trap in this product:
`home drift` answers "did anything change IN this home" and exits 0 on a stale
one; `home verify` answers "does everything in it RESOLVE" and also exits 0.
Neither answers "is this home CURRENT". An agent that runs one of those, sees
exit 0 and reports the home healthy has produced a confident wrong answer --
and its Bash count looks excellent.

REPLAY is off for this case: re-issuing a sync would reach the network and the
unit repositories, which is not something a grader should do on its own.
