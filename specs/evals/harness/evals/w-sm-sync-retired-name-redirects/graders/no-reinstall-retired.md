---
type: file_exists
path: .eval/forbid-no-reinstall-retired
weight: 3
---

THE BUG. No Bash call installs `skill-manager` / `skill-manager-skill` again.
Reinstalling the retired standalone is what 863d8f09 found a sync doing on its
own, and what agents did by hand after "not installed: skill-manager".
Unearned by a run that made no tool call at all.
