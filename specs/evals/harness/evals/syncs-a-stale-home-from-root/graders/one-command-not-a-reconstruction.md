---
type: tool_used
tool: Bash
max: 4
weight: 2
---

COST. Four rather than three: checking and then syncing is legitimately two
commands here, and a ceiling that punishes the correct two-step would be
measuring the ceiling.

The by-hand failure mode this is against is spelled out in git-epic-workflow:
a `for` loop over `installed/*.json` reading each recorded hash with python.
That works, and it is what an agent writes when it cannot find `skt check`.
