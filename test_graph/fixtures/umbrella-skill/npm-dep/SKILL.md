---
name: npm-cli-skill
description: Integration-test sub-skill that declares an npm CLI dependency.
---

# npm-cli-skill

Installs cowsay through the bundled node/npm so the smoke graph can assert
an npm-backed binary symlinks into `bin/cli` from the per-skill prefix.
