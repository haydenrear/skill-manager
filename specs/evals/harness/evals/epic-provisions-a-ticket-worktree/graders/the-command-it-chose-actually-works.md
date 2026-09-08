---
type: file_exists
path: .eval/front-door-runs
weight: 2
---

NON-VACUITY FOR THE GRADER ABOVE. The Stop hook REPLAYS the agent's own command
-- the string from its transcript, not a canonical one -- in a throwaway copy
of the fixture under $TMPDIR, and writes this only on exit 0.

So a well-formed command with the wrong flags is green above and red here, and
the pair reads as one sentence: it found the door, and the door opened.

The agent cannot do this itself: `plugin eval` denies the Bash tool every write,
measured at cwd, $TMPDIR, $HOME and under .git. Only a hook runs outside that.
