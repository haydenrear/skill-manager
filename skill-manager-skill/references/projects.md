# Skill Projects

Use this reference when a repository contains `skill-project.toml` or
`skill-manager-project.toml`, or when the user asks for a project-specific
agent harness.

## Mental Model

A skill project manifest is portable intent for one repository. It can
declare skills, plugins, doc-repos, harnesses, envs, libs, CLI deps, and
MCP deps. The generated files under the checkout are realized state, not
the source of truth.

Project resolution also treats the checkout as a harness descriptor:

- The parent `$SKILL_MANAGER_HOME` records the registered project and
  project lock under `projects/<name>/`.
- `<project>/.skill-manager` is scaffolded as a child Skill Manager home.
- `<project>/.claude`, `<project>/.codex`, and `<project>/.gemini` are
  scaffolded as child-local agent homes.
- Resolved units are projected from the parent store into the child
  `.skill-manager` store.
- Parent child-home records claim the units while the project child home
  exists, so removals stay conservative.

Any Skill Manager home can itself be a parent. Do not assume there is a
distinguished global root.

## Discovery

From a checkout, start with:

```bash
skill-manager project show <name>
skill-manager project list
<skill-manager-skill>/scripts/env.sh --pretty
```

`env.sh` reports the active `SKILL_MANAGER_HOME`, installed skills and
CLI shims, and, when run inside a skill project, passive project context:
manifest path, declared env names, child home path, and child-local agent
homes that exist.

## Register And Resolve

Use registration when you want the parent home to remember the manifest
intent:

```bash
skill-manager project register --project-dir <project>
```

Use resolve when dependencies should be installed, locked, bound, and
projected into the project child home:

```bash
skill-manager project resolve --project-dir <project>
```

After resolve, project-local agent launches should point at the child
home and child-local agent homes:

```bash
SKILL_MANAGER_HOME=<project>/.skill-manager
CODEX_HOME=<project>/.codex
CLAUDE_HOME=<project>/.claude
GEMINI_HOME=<project>/.gemini
```

Use CLI help for exact flags such as JSON output, gateway skipping, lib
resolution, and custom manifest paths.

## Project Envs

Project envs are declared in the manifest and materialized under
`.skill-manager/envs/<env>/` as uv projects. Use:

```bash
skill-manager env sync <env> --project-dir <project>
skill-manager env run <env> --project-dir <project> -- <command>
```

Generated `.skill-manager/env.md`, env `pyproject.toml`, vendor
checkouts, and tool shims are derived from the manifest and lock. Update
the manifest, then re-run the CLI instead of editing generated env files
by hand.

## Cleanup

Use the owning CLI flow to remove generated state:

- `skill-manager harness rm <id>` for harness child homes.
- Re-run `skill-manager project resolve` after removing project
  dependencies from the manifest so stale child units and claims are
  pruned.
- Use `skill-manager bindings`, `unbind`, and `rebind` for doc bindings
  instead of deleting managed import blocks manually.

If `remove` says a unit is still claimed by a project or child home,
inspect the project lock and child-home record before deleting anything.
