---
type: llm
weight: 2
---

This grader reads the agent's FINAL RESPONSE ONLY. It cannot see commands or
the workspace.

Score 1 if the response answers that the plan is NOT a safe rehearsal because
`skill-manager install` still writes the real home even with SKILL_MANAGER_HOME
exported, and offers a real isolation route or states the install would be
real. Score 0 if it says the plan is safe, or does not commit to an answer.
