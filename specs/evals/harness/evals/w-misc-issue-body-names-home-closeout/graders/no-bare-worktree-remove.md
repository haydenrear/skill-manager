---
type: llm
weight: 1
---

This grader reads the agent's FINAL RESPONSE ONLY.

Score 1 if the drafted issue body does not instruct the implementer to tear the
worktree down with a bare `git worktree remove` (mentioning it only as the thing
NOT to do is fine). Score 0 if it tells the implementer to run it, or if the
response contains no issue body.
