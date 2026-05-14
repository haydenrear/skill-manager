---
name: skill-script-inner
description: Inner sub-skill of skill-script-umbrella-skill. Declares a skill-script CLI dep so the smoke graph can assert the backend runs transitively when only the umbrella is installed.
skill-imports: []
---

# skill-script-inner

Declares a `skill-script:` CLI dep whose script touches
`$SKILL_MANAGER_BIN_DIR/skill-script-transitive-touched`.

Distinct sentinel from the standalone `skill-script-skill` fixture so
the transitive test can't accidentally pass on the direct test's
side-effect.
