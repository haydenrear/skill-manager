---
type: file_exists
path: .eval/forbid-no-finalize-constituents
weight: 2
---

`finalize-constituents.sh` is never issued. Its `-- constituents` guard never
fires in a plugin repo, so running it directly is the silent gitlink hazard.
Unearned by a run with no tool calls.
