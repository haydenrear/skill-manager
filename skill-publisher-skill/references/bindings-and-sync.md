# Bindings and sync

Install and bind are separate operations.

- **Install** copies unit bytes into the skill-manager store and runs
  dependency side effects such as CLI installation and MCP registration.
- **Bind** projects an installed unit into a target root and records the
  exact filesystem projections in
  `$SKILL_MANAGER_HOME/installed/<unit>.projections.json`.
- **Sync** refreshes installed bytes and reconciles the side effects and
  binding records that belong to those bytes.

This split matters because a unit can be installed once and projected
into multiple targets: default agent dirs, explicit project roots, or
harness instances.

## Projection ledger

Each binding records:

- Binding id.
- Unit name and kind.
- Optional sub-element, such as one doc-repo source id.
- Target root.
- Conflict policy.
- Owner source: explicit user bind, default-agent bind, or harness bind.
- Projections to reverse later.

Inspect:

```bash
skill-manager bindings list
skill-manager bindings list --unit <unit>
skill-manager bindings list --root <path-fragment>
skill-manager bindings show <bindingId>
```

Remove:

```bash
skill-manager unbind <bindingId>
```

Move a whole-unit skill/plugin binding:

```bash
skill-manager rebind <bindingId> --to <newRoot>
```

Sub-element rebinds, such as doc-repo source bindings, should be handled
as unbind + bind.

## Binding shapes

Skills and plugins:

```bash
skill-manager bind skill:reviewer --to /path/to/root
skill-manager bind plugin:repo-tools --to /path/to/root
```

Result: symlink at `/path/to/root/<name>`.

Doc-repos:

```bash
skill-manager bind doc:team-prompts --to /path/to/project
skill-manager bind doc:team-prompts/review-stance --to /path/to/project
```

Result: tracked copies under `/path/to/project/docs/agents/` plus
managed imports in `CLAUDE.md` and/or `AGENTS.md`.

Harnesses:

```bash
skill-manager harness instantiate code-reviewer --id repo-review
```

Result: multiple harness-owned bindings, each with an id prefixed by
`harness:<instanceId>:`.

## Conflict policies

`skill-manager bind` accepts:

- `--policy error`: fail if the destination exists.
- `--policy rename`: move an existing destination to a backup path before
  writing the projection.
- `--policy skip`: keep the existing destination and record no
  replacement write.
- `--policy overwrite`: overwrite the destination.

Defaults:

- Skills/plugins default to `error`.
- Doc-repos default to `rename`, because a project often already has
  `CLAUDE.md` or `AGENTS.md`.

## Sync by kind

No-arg sync walks every installed unit:

```bash
skill-manager sync
```

Skill/plugin sync:

- Updates from the installed source or registry/git ref.
- Re-runs CLI/MCP/plugin marketplace side effects.
- Reconciles default-agent bindings.

Doc-repo sync:

```bash
skill-manager sync team-prompts
skill-manager sync team-prompts --force
```

- Refreshes managed copies for each doc binding.
- Preserves local edits by default.
- Uses `--force` to clobber local edits with upstream bytes.
- Reports stale bindings when a source was removed upstream.

Harness sync:

```bash
skill-manager sync harness:code-reviewer
```

- Finds live harness instances by `harness:<instanceId>:` binding ids.
- Reuses `.harness-instance.json` lock paths.
- Re-plans the instance bindings using current installed units.

## Authoring implications

- Do not assume install makes docs visible in a project. Doc-repos must
  be bound or included in a harness.
- Do not assume a harness uninstall removes projected instance files.
  Remove instances with `skill-manager harness rm <id>`.
- Use explicit `--to`, `--project-dir`, `--claude-config-dir`, and
  `--codex-home` in validation so test outputs are deterministic.
- When writing docs for a unit, distinguish installation validation from
  projection validation.
