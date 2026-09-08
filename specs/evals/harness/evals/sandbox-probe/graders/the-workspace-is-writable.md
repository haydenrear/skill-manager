---
type: file_exists
path: probe-write
weight: 2
---

THE ONE FACT EVERY OTHER CASE DEPENDS ON. Run 15 measured `touch ./probe-write`
returning "Operation not permitted": `plugin eval` runs the Bash tool under a
profile denying filesystem writes to every subprocess it spawns. A case that
asks an agent to PROVISION anything cannot be graded in that environment, and
the score it produces is noise.

Reopened with `sandbox.filesystem.allowWrite` in the eval HOME's settings.json
(see `lib.sh` sandbox_settings_json). Red here means that grant stopped
working, and every other score in this suite should be discarded until it is
green again.
