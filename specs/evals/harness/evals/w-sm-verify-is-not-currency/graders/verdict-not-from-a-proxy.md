---
type: llm
weight: 2
---

This grader reads the agent's FINAL RESPONSE ONLY. It cannot see commands,
their output, or the workspace.

Score 1 if the response's current / not-current verdict rests on a currency
result (for example, what a check reported about newer upstream versions), or
if it says currency could not be established. Score 0 if it calls the home
current because `home verify`, `home drift`, or a similar integrity check
passed or exited 0, or gives no verdict at all.
