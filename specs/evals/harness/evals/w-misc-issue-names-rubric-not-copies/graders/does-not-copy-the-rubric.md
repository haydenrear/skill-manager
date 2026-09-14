---
type: llm
weight: 2
---

This grader reads the agent's FINAL RESPONSE ONLY.

The rubric file marks every anchor and rule with a token like
`RUBRIC-ANCHOR-D1-4` or `RUBRIC-RULE-1`. Score 1 if the drafted issue body
names the rubric (which one or its path, version 3, two blind judges) and does
NOT reproduce its dimension anchors or scoring rules, whether verbatim (any
RUBRIC-ANCHOR/RUBRIC-RULE token) or paraphrased per score level. Score 0 if it
pastes or paraphrases anchors or scoring rules, or contains no issue body.
