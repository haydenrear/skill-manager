# Harnesses

A harness is an installable template for **everything an agent needs**
to operate in a particular project role. Think of it as a full
project-specific agent profile: a reviewer agent for one repo, a coder
agent for one codebase, a release agent for one service, and so on. It
composes already-authored skills, plugins, doc-repos, and MCP tool
selections into one reusable profile.

It is not a Claude/Codex plugin by itself; it installs into
`$SKILL_MANAGER_HOME/harnesses/<name>/` and is materialized later with
`skill-manager harness instantiate`.

Use a harness when:

- A team needs a repeatable Claude/Codex setup for a project.
- You want a named agent profile for a project role, such as
  `reviewer`, `coder`, `migration-planner`, or `release-operator`.
- Several skills/plugins/doc-repo sources should be installed and bound
  together.
- The agent needs its instructions and project context bound into the
  target repository, not just installed globally.
- The profile should have named instances that can be listed, synced,
  and removed.

Do not use a harness when you only need to ship hooks, commands, or
agents to the harness runtime. That is a plugin.

## Layout

```
code-reviewer-harness/
└── harness.toml
```

`harness.toml`:

```toml
[harness]
name = "code-reviewer"
version = "0.1.0"
description = "Review profile for repo work."

units = [
  "skill:reviewer",
  "plugin:repo-tools",
]

docs = [
  "doc:team-prompts/review-stance",
]

[[mcp_tools]]
server = "repo-mcp"
tools = ["search", "open"]
```

Rules:

- `units` contains skill/plugin coords.
- `docs` contains doc-repo coords or doc source coords.
- Both arrays may be root-level or under `[harness]`; root-level takes
  precedence.
- Direct sources work too: `file:///abs/path`, `github:owner/repo`, and
  `git+https://...` are resolved by on-disk shape.
- Installing a harness walks transitive refs and stores every referenced
  skill, plugin, and doc-repo before committing the harness template.
- `[[mcp_tools]]` records selected tool exposure intent for the harness;
  MCP server registration still comes from `[[mcp_dependencies]]` on the
  referenced skills/plugins.

## Install and instantiate

Install the template:

```bash
skill-manager install file:///abs/path/to/code-reviewer-harness --yes
skill-manager harness show code-reviewer
```

Instantiate it:

```bash
skill-manager harness instantiate code-reviewer \
  --id repo-review \
  --claude-config-dir "$CLAUDE_CONFIG_DIR" \
  --codex-home "$CODEX_HOME" \
  --project-dir /path/to/project
```

Target resolution:

- `--claude-config-dir`, then `CLAUDE_CONFIG_DIR`, then
  `<store>/harnesses/instances/<id>/claude`.
- `--codex-home`, then `CODEX_HOME`, then
  `<store>/harnesses/instances/<id>/codex`.
- `--project-dir`, then `<store>/harnesses/instances/<id>`.

`--project-dir` is the project/repo root for this agent instance. The
doc-repo sources selected by `docs = [...]` are bound into that project
root: tracked markdown copies land under `<project-dir>/docs/agents/`,
and managed imports are written to `<project-dir>/CLAUDE.md` and/or
`<project-dir>/AGENTS.md` according to each doc source's `agents` list.
That is what gives the instantiated agent its project-local
instructions and context in addition to its installed skills, plugins,
and MCP tool selections.

Materialization:

- Skills symlink into both Claude and Codex skill dirs.
- Plugins symlink into Claude's plugin dir only.
- Docs bind into `--project-dir` through the doc-repo binder, including
  `docs/agents/` tracked copies and managed `CLAUDE.md` / `AGENTS.md`
  imports.
- The instance lock at
  `$SKILL_MANAGER_HOME/harnesses/instances/<id>/.harness-instance.json`
  stores the resolved paths for later sync.
- Binding ids are prefixed with `harness:<instanceId>:` so teardown can
  find exactly the projections this instance owns.

## Sync and remove

`skill-manager sync harness:<name>` discovers live instances from the
binding ledger and re-plans each instance using its saved lock paths.

List templates and live instances:

```bash
skill-manager harness list
```

Tear down an instance:

```bash
skill-manager harness rm <instanceId>
```

`harness rm` reverses every `harness:<instanceId>:` projection, removes
the corresponding ledger rows, and deletes the instance sandbox/lock.
It does not uninstall the referenced skills, plugins, or doc-repos;
those remain installed for other harnesses or explicit binds.

Uninstalling the harness template removes
`$SKILL_MANAGER_HOME/harnesses/<name>/` and the installed-record for the
template. It does not remove live instance projections; remove instances
first.

## Validation

Use explicit directories so the result is easy to inspect:

```bash
tmp="$(mktemp -d)"
skill-manager install file:///abs/path/to/code-reviewer-harness --yes
skill-manager harness instantiate code-reviewer \
  --id smoke \
  --claude-config-dir "$tmp/claude" \
  --codex-home "$tmp/codex" \
  --project-dir "$tmp/project"
skill-manager harness list
skill-manager bindings list --root "$tmp"
skill-manager harness rm smoke
```

For an in-repo starter, see `examples/harness/` in this skill.
