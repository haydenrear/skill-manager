---
name: skill-dev-skill
description: Use when developing or iterating on installed skill-manager skills and plugins through project-local worktrees and the skill-dev CLI.
skill-imports:
  - unit: skill-manager
    path: references/cli.md
    reason: Defines sync --from, force-scripts replay, and managed CLI cleanup for skill-dev worktrees.
    section: runtime
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

`skill-dev sync <unit>` delegates to:

```bash
skill-manager sync <unit> --from <project>/skill-dev/<unit> --merge --yes
```

If the worktree changes files under `skill-scripts/`, normal sync reruns
the corresponding `skill-script:` CLI dependency because the fingerprint
changes. To replay an unchanged script from the worktree, call the
underlying command directly with `--force-scripts`:

```bash
skill-manager sync <unit> --from <project>/skill-dev/<unit> --merge --yes --force-scripts
```

This skill installs the `skill-dev` binary through a `skill-script:`
dependency. Uninstalling `skill-dev-skill` removes that managed binary
and `cli-lock.toml` row only when no other installed unit claims the
same backend/tool.
