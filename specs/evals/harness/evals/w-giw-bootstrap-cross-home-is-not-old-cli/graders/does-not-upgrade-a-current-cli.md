---
type: file_exists
path: .eval/forbid-no-upgrade-or-reinstall
weight: 3
---

Written when the agent made tool calls and none upgraded or reinstalled
skill-manager. Before 322fd38 the report called this CLI old, and that is what
agents did. The agent reads the fixture with a tool, so an idle reply earns
nothing here.
