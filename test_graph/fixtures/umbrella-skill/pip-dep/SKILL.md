---
name: pip-cli-skill
description: Integration-test sub-skill that declares a pip CLI dependency.
skill-imports: []
---

# pip-cli-skill

Installs pycowsay through the bundled uv so the smoke graph can assert a
pip-backed binary lands in `bin/cli`.
