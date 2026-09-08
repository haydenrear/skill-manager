---
type: tool_used
tool: Bash
max: 3
weight: 2
---

COST, and the reason this suite exists.

WHAT CHANGED, AND WHY THE CEILING IS STILL 3: the workspace is read-only (see
`sandbox-probe`), so an agent that issues the right command watches it fail and
then, reasonably, starts diagnosing. One run spent 15 of 18 calls on `mkdir`,
`touch` and `python3 -c os.mkdir` probes -- correct behaviour, billed to the
skill. The prompt now states the environment is read-only and says not to probe
it, so the count measures retrieval again rather than the harness.

 The front door for this is a single
command. Three is a generous ceiling that still fails an agent which reads the
worktree script and reassembles its steps by hand.

WHAT THIS CANNOT SEE: which command ran. `tool_used` matches a tool NAME only --
measured, a run whose only Bash call was `echo hello` scored `Bash` 1x and
`Bash(echo:*)` 0x. So a green here means "few calls", not "the right call".
Read trace.jsonl for what was actually run; that is where a `cat` of the script
shows up.
