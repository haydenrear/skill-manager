---
type: file_exists
path: probe-write
weight: 2
---

EXPECTED RED, AND IT IS THE POINT. `plugin eval` forbids the Bash tool to write
anywhere in a run: measured at cwd (`touch ./probe-write`), `$TMPDIR`, `$HOME`
and under `.git` -- four probes, two cases, all "Operation not permitted" AFTER
the permission gate was passed with an exact `--allow-tools 'Bash(<cmd>:*)'`
grant. That is a property of the harness, not of this machine.

Kept as a standing red so the pair below reads as one sentence: the agent
cannot write, a hook can. If this ever turns GREEN, `plugin eval` has changed
and every write-dependent case can be graded directly again -- which would be
good news, and nobody would notice without this grader.
