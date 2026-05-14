---
name: skill-script-umbrella-skill
description: Integration-test fixture that references a nested skill-script-inner sub-skill via file:. Used by the smoke graph to prove that the skill-script CLI backend fires for transitively installed sub-skills, not just the top-level install target.
skill-imports: []
---

# skill-script-umbrella-skill

Has no CLI deps of its own — its child `skill-script-inner` does. After
`skill-manager install` resolves the umbrella, the child's
`skill-scripts/install.sh` should run and drop the sentinel file into
`$SKILL_MANAGER_BIN_DIR`.
