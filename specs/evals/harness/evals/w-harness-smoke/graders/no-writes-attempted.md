---
type: file_exists
path: .eval/forbid-no-writes
weight: 1
---

Written when the agent made at least one tool call and none was Write or Edit.
The prompt dictates a single read; a write attempt means the agent improvised,
which is a fact about the prompt the rest of the lane copies.
