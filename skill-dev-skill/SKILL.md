---
name: skill-dev-skill
description: Use when developing or iterating on installed skill-manager skills and plugins through project-local worktrees and the skill-dev CLI.
skill-imports: []
---

# skill-dev-skill

Use `skill-dev` when a user wants to edit an installed skill-manager skill
or plugin without directly modifying the installed store copy.

The CLI creates a project-local worktree under `skill-dev/<unit>`, keeps
that root ignored by the project's git repository, and delegates merge-back
to skill-manager:

```bash
skill-dev open <unit>
skill-dev status <unit>
skill-dev git <unit> -- status
skill-dev sync <unit>
skill-dev close <unit> --merge
```

Prefer `skill-dev sync <unit>` to apply edits while leaving the worktree
open. Prefer `skill-dev close <unit> --merge` when finishing a development
session.

