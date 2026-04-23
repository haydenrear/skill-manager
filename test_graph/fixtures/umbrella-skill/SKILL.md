---
name: umbrella-skill
description: Integration-test umbrella skill that transitively depends on two sub-skills via file references, each carrying a different CLI backend.
---

# umbrella-skill (integration fixture)

Exists only to drive the smoke graph. Verifies: transitive skill refs via
`file:./…`, plus pip and npm CLI backends round-trip through the registry.
