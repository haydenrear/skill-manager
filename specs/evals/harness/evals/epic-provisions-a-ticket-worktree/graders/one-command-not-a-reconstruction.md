---
type: tool_used
tool: Bash
max: 3
weight: 2
---

COST, and the reason this suite exists. The front door for this is a single
command. Three is a generous ceiling that still fails an agent which reads the
worktree script and reassembles its steps by hand.

WHAT THIS CANNOT SEE: which command ran. `tool_used` matches a tool NAME only --
measured, a run whose only Bash call was `echo hello` scored `Bash` 1x and
`Bash(echo:*)` 0x. So a green here means "few calls", not "the right call".
Read trace.jsonl for what was actually run; that is where a `cat` of the script
shows up.
