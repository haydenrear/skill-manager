---
type: regex
pattern: "plugin eval(?:[^\\n]|\\\\\\n)*--allow-tools(?:[^\\n]|\\\\\\n)*Write"
weight: 2
---

`allowed_tools:` in a case grants nothing; the operator's `--allow-tools` must
name every gated tool or the run scores 0 with "not granted" (plugin_evals.md
§2). Reads the FINAL RESPONSE only, on the same line as `plugin eval`.
