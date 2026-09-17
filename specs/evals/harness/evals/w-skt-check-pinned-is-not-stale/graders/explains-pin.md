---
type: llm
weight: 2
---

THIS GRADER READS THE FINAL RESPONSE ONLY -- not the workspace, not tool output.

Score 1 if the response says deploy-helm was (or should be) synced and that
spec-double-compiler was left alone because the project's skill-project.toml
pins it at dd2d5176 (so it is not out of date for this project). Score 0 if it
syncs or recommends syncing spec-double-compiler to its latest version.
