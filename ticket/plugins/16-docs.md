# 16 — Docs

**Phase**: F — Verification + ship
**Depends on**: 15
**Spec**: § "Migration notes" and the spec as a whole.

## Goal

Update user-facing docs to describe the plugin install flow,
`units.lock.toml`, the new commands, and the migration path for
existing skill installs.

## What to do

### `skill-manager-skill/SKILL.md`

The skill doc surfaces what an agent should know. Updates:

- "Plugins and skills" — how an agent decides what to install given a
  user request.
- New coord forms (`plugin:`, `skill:`) and when each is appropriate.
- `units.lock.toml` — when to suggest `sync --lock` vs `upgrade`.
- `lock status` for diagnosing drift.
- Plugin install side effects (one symlink in `~/.claude/plugins/`,
  no separate skill symlinks for contained skills).
- Updated CLI table including new verbs.

### `README.md` (project root)

- New "Plugins" section describing the layout and the
  `skill-manager-plugin.toml` sidecar.
- Migration notes: what happens to existing installs on first run
  after upgrade (`sources/` → `installed/`, lock generated lazily).
- Pointer to the ticket dir for implementers.

### Examples

Add an example plugin under `examples/` showing:
- `.claude-plugin/plugin.json`
- `skill-manager-plugin.toml`
- One contained skill with its own `skill-manager.toml`
- A `commands/` and a `hooks/` entry for completeness

### `CHANGELOG.md`

A breaking-changes-callout-free entry under the next minor version:

```
- Plugins are now installable units alongside bare skills.
- units.lock.toml shipped — install/upgrade/uninstall flip the lock atomically.
- Internal: SkillSource → InstalledUnit (auto-migration on first run).
- Internal: ~/.skill-manager/sources/ → installed/ (auto-migration).
```

## Out of scope

- Multi-source / marketplace docs (deferred until that lands).
- Tutorial-style "build your first plugin" guide (defer to a follow-up
  if there's demand).

## Acceptance

- `SKILL.md` mentions plugins on first read; an agent reading it can
  pick the right coord form.
- README explains the plugin layout and points readers at the
  example.
- Example plugin under `examples/` installs cleanly via
  `skill-manager install ./examples/<name>`.
- Changelog reflects the changes.
