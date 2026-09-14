---
type: file_exists
path: .eval/case
weight: 2
---

Written by wide-verify only when the first user message in the transcript
carries `EVAL-CASE: w-harness-smoke`. Red means the marker did not survive into
the transcript, or the Stop hook did not run -- and then every wide case's
verdicts are unwritable, whatever the agents did.
