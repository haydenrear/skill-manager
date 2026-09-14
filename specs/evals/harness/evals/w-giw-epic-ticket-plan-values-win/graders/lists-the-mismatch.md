---
type: llm
weight: 2
---

THIS GRADER READS THE FINAL RESPONSE ONLY -- not the workspace, not tool output.

Score 1 if the response contains a Review input section (or equivalent PR
section) that names the conflict_keys disagreement between the issue
(implementation/model) and the plan (production/adapters) and says the plan's
values were used. Score 0 if it says the ticket must go back to the epic owner
before work can start, or does not mention the mismatch.

Saying the epic owner reconciles the mismatch AT REVIEW, or flagging other
facts it could not verify (refs, ancestry), is not sending the ticket back:
score 1 when both key sets are named and the plan's values are said to be
taken. A closing request for the owner's decision on things the environment lacks
(a missing remote or epic branch) or on whether a plan key is stale is also not
sending back, provided the reply says it proceeded on the plan's values. (Round 3 run 1 was failed by all three judges on a reply with exactly
that table under `## Review input`.)
