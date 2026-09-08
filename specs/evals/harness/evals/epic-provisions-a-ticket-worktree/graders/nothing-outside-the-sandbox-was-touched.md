---
type: file_exists
path: .eval/source-undamaged
weight: 1
---

THE SAFETY ASSERTION, and it is about the HARNESS, not the skill. The Stop hook
runs unsandboxed as the operator, so replaying an eval subject's command is the
one genuinely dangerous thing here. It replays only a string matching the
front-door pattern, only inside a throwaway `mktemp -d`, and this grader
compares the fixture's tree before and after.

Red means the containment leaked and the replay reached the real fixture. Stop
and fix the hook before reading any other score.
