---
type: regex
pattern: "([Dd]oes\\s*n[o'’]t|[Dd]o\\s*n[o'’]t|[Ww]on[’']?t|[Ww]ill\\s+not|[Nn]ot|NOT)\\s+(actually\\s+|really\\s+|fully\\s+)?([Ss]andbox|[Ii]solate|[Rr]edirect)"
weight: 2
---

The final response says exporting SKILL_MANAGER_HOME does not sandbox, isolate
or redirect the install. Reads the response only; a hedged "it might not
isolate" also matches, which the llm grader below separates.
