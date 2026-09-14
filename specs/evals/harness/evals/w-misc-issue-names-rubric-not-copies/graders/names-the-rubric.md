---
type: regex
pattern: "[Rr]ubric\\.md|[Rr]ubric[^\\n]{0,40}([Vv]([Ee][Rr][Ss][Ii][Oo][Nn])?\\s*3)"
weight: 1
---

The final response names the rubric by path or by name and version. Supporting
only: naming it is necessary, and the behaviour that matters (not copying it)
is decided by the llm grader. Reads the response only.
