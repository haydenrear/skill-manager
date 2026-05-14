---
name: skill-script-skill
description: Integration-test fixture that declares a skill-script CLI dependency. The bundled install script touches a sentinel file under $SKILL_MANAGER_BIN_DIR so the smoke graph can assert the backend ran end-to-end.
skill-imports: []
---

# skill-script-skill

Used by the smoke graph to validate the `skill-script:` CLI backend —
the escape hatch for tools that can't be published to pip / npm / brew /
a public tarball, where the skill ships its own installer script.

The script lives at `skill-scripts/install.sh` and runs `touch
"$SKILL_MANAGER_BIN_DIR/skill-script-touched"`. The smoke node asserts
that file exists after install.
