---
name: skill-dev-skill
description: 'Use for DELIBERATE, iterative development of one installed skill-manager unit through a project-local `skill-dev/<unit>` worktree and the skill-dev CLI (open/sync/close --merge) — a long-lived editing session against the store copy. NOT the front door for "I edited a skill during a ticket and it must survive": that is `skt publish` (home sync one tier up, then unit publish), and ticket worktrees themselves are `skt ticket` / git-issue-workflow''s `wt`, not skill-dev.'
skill-imports:
  - unit: skill-manager
    path: references/cli.md
    reason: Defines sync --from, force-scripts replay, and managed CLI cleanup for skill-dev worktrees.
    section: runtime
---

# skill-dev-skill

Use `skill-dev` when a user wants to edit an installed skill-manager skill
or plugin without directly modifying the installed store copy.

## Where this sits next to skt

Two flows overlap here; the split is by intent, decided at the skt epic
(haydenrear/skill-manager-integration-repository#74):

- **Incidental edit during a ticket** — you improved a skill inside a
  ticket worktree's home and the edit must survive teardown: `skt publish`
  owns that (home sync one tier up, then `unit publish` to the skill's own
  repo). Do not open a skill-dev worktree for it.
- **Deliberate development session** — the unit itself is the work: THIS
  skill. `skill-dev open <unit>` gives a long-lived project-local worktree
  with merge-back semantics `skt publish` does not provide.

`skt status` reports which units are installed and change-managed; start
there when unsure which flow applies.

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

If a worktree has a `skill-script:` CLI installer and you need to replay
it without changing script bytes, run the delegated command directly
with `skill-manager sync <unit> --from skill-dev/<unit> --merge --yes
--force-scripts`. Named sync forces scripts for that unit only; script
stdout/stderr is written under `$SKILL_MANAGER_HOME/logs/skill-scripts/`.

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

## Modeled CLI Workflow Coverage

These workflow ids are shared with the CLI metadata catalog and the
TLA+ program model. Use this table as routing guidance, then run the
help command for exact syntax.

| Workflow id | Use when | Help |
| --- | --- | --- |
| `force-skill-scripts` | replaying an unchanged worktree installer for one unit | `skill-manager sync --help` |
| `install-local-unit` | installing the local worktree as a managed unit | `skill-manager install --help` |
| `project-env` | syncing project-local envs while developing skills | `skill-manager env sync --help` |
| `sync-from-local-source` | applying a worktree back to the installed unit | `skill-manager sync --help` |
