---
name: skill-publisher
description: 'Author installable skill-manager units: skills, plugins, doc-repos, and harnesses. Use when making a directory installable by skill-manager, choosing a unit kind, scaffolding a unit, writing or reviewing unit manifests/TOML, adding CLI or MCP dependencies, wiring references, validating install/bind/instantiate round-trips, or preparing optional registry metadata. Detailed schemas live in references for skills, plugins, doc-repos, harnesses, scaffolding, coordinates/distribution, dependencies, bindings/sync, and skill-script.'
skill-imports:
  - skill: skill-manager
    path: references/skill-imports.md
    reason: Defines semantic markdown imports used by authored unit scaffolds.
    section: semantics
---

# skill-publisher

Use this skill when the user wants to turn a directory of agent docs,
tooling, or project profiles into something **skill-manager can
install**.

The main job:

1. Pick the right unit kind.
2. Scaffold or copy a minimal example.
3. Author the unit-specific manifest.
4. Validate install and any projection behavior (`bind` or
   `harness instantiate`).
5. Push the unit as a git repo. Registry publish is optional metadata
   for supported shapes, not the primary distribution path.

The virtual MCP gateway is in scope: declaring `[[mcp_dependencies]]` in
a skill/plugin manifest is how an MCP server becomes registered. Running
or hosting a skill-manager-server registry is out of scope except for
the optional publish step.

## When to use

- The user has a directory and wants `skill-manager install` to handle
  it.
- The user wants to create or review a skill, plugin, doc-repo, or
  harness.
- The user wants to scaffold a new unit.
- The user wants to add references, CLI deps, or MCP deps.
- The user wants to validate install, bind, sync, or harness
  instantiation.
- Something is wrong with publish/install/sync and you need to map the
  failure back to a manifest field.

## Unit Kinds

| Kind | Use when | Root marker | Details |
|---|---|---|---|
| Skill | One focused agent capability. | `SKILL.md` + `skill-manager.toml` with `[skill]` | `references/skills.md` |
| Plugin | Bundle skills with hooks, commands, agents, or shared deps. | `.claude-plugin/plugin.json` | `references/plugins.md` |
| Doc-repo | Version markdown sources bound into project `CLAUDE.md` / `AGENTS.md`. | `skill-manager.toml` with `[doc-repo]` | `references/doc-repos.md` |
| Harness | Everything an agent needs for a project role: reviewer, coder, release agent, etc. | `harness.toml` with `[harness]` | `references/harnesses.md` |

When in doubt, write a skill. Use a plugin for plugin runtime surface or
versioned bundles. Use a doc-repo for markdown that should bind into a
project. Use a harness for a full project-specific agent profile that
composes skills, plugins, docs, and MCP tool selections.

## Reference Map

Load only the reference needed for the current task:

- `references/scaffolding.md` — `skill-manager create`, current scaffold
  support, example starters, and initial validation.
- `references/skills.md` — bare skill layout, `SKILL.md` frontmatter,
  `[skill]` TOML, and skill validation.
- `references/plugins.md` — plugin layout, `.claude-plugin/plugin.json`,
  `skill-manager-plugin.toml`, contained skills, hooks/commands/agents,
  and plugin validation.
- `references/doc-repos.md` — `[doc-repo]`, `[[sources]]`, source ids,
  `agents`, bind coords, doc binding, and doc sync behavior.
- `references/skill-imports.md` — semantic markdown imports,
  frontmatter syntax, and when to add manifest references for imported
  skills.
- `references/harnesses.md` — `harness.toml` as a full
  project-specific agent profile, transitive refs, `harness
  instantiate`, `--project-dir` doc/`CLAUDE.md`/`AGENTS.md` binding,
  instance locks, sync, and rm.
- `references/coords-and-distribution.md` — coord grammar,
  `github:`/`git+`/`file:` sources, one unit per git repo root,
  references, git sync behavior, and registry metadata.
- `references/dependencies.md` — CLI and MCP dependency TOML examples
  and resolution behavior for pip/npm/brew/tar/skill-script and
  npm/uv/docker/binary/shell MCP load types.
- `references/bindings-and-sync.md` — install vs bind, projection
  ledgers, conflict policies, unbind/rebind, doc sync, and harness sync.
- `references/skill-scripts.md` — private CLI install scripts, env vars,
  fingerprinting, rerun semantics, and security model.
- `references/runpod-mcp-onboarding.md` — worked MCP onboarding case
  study with npm load type and host-env passthrough.

## Examples

Example starters live under `examples/`:

- `examples/skill/`
- `examples/plugin/`
- `examples/doc-repo/`
- `examples/harness/`

Copy one when the CLI does not scaffold that shape yet or when you need
a minimal known-good layout:

```bash
cp -r <skill-publisher-skill>/examples/skill <my-skill>
cp -r <skill-publisher-skill>/examples/plugin <my-plugin>
cp -r <skill-publisher-skill>/examples/doc-repo <my-doc-repo>
cp -r <skill-publisher-skill>/examples/harness <my-harness>
```

## Operating Workflow

1. Choose the unit kind from the table above.
2. Load the matching reference and `references/scaffolding.md`.
3. Ensure the unit is at the root of its own git repo. Do not put
   multiple top-level units in one repo today.
4. Use `references/coords-and-distribution.md` when adding references or
   deciding between `github:`, `git+`, `file:`, and registry/name coords.
5. Use `references/dependencies.md` when adding CLI or MCP deps.
6. Validate locally from outside the source directory:

```bash
skill-manager install file:///abs/path/to/unit --dry-run
skill-manager install file:///abs/path/to/unit --yes
skill-manager show <name>
skill-manager list
```

Extra validation:

- Skill/plugin: `skill-manager publish <dir> --dry-run` for the current
  registry package path.
- Doc-repo: bind into a disposable project and inspect `docs/agents/`,
  `CLAUDE.md`, and `AGENTS.md`.
- Harness: instantiate with explicit `--claude-config-dir`,
  `--codex-home`, and `--project-dir`, then run `skill-manager harness
  list` and `skill-manager harness rm <id>`.

## Boundaries

- This skill does not publish anything itself. It explains how; the
  agent runs `skill-manager publish` or `git push` when ready.
- This skill does not modify `~/.skill-manager/cli-lock.toml`,
  `policy.toml`, or `registry.properties`. Surface conflicts and let the
  user decide.
- This skill does not generate API keys or secrets. Prompt the user for
  required `init_schema` secrets; never invent them.
- This skill does not cover hosting skill-manager-server. Use the main
  skill-manager README for registry operations.
