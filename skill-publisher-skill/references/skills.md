# Skills

A skill is the default unit shape: one agent-facing capability plus
tooling metadata.

Use a skill when:

- The agent should load a focused set of instructions for one
  capability.
- The unit does not need plugin hooks/commands/agents.
- The content is not project-bound markdown for `CLAUDE.md` / `AGENTS.md`
  and does not need to compose a full project agent profile.

## Layout

```
my-skill/
├── SKILL.md
└── skill-manager.toml
```

`SKILL.md` is read by the agent runtime. `skill-manager.toml` is read by
skill-manager only.

## SKILL.md frontmatter

```markdown
---
name: my-skill
description: Use when the user asks to do the specific thing this skill supports.
skill-imports: []
---

# my-skill

Agent-facing instructions go here.
```

Rules:

- `name` is a single slug-like token.
- `description` is the activation hook. It should state when the agent
  should use the skill, not just what the repo contains.
- `skill-imports` is optional but recommended on starter markdown so
  imports can be filled in without changing file shape. See
  `references/skill-imports.md`.
- Frontmatter `name` must match `[skill].name`.

Quote the YAML description when it contains `:` or other YAML-sensitive
characters:

```yaml
description: 'Use when authoring installable units: skills, plugins, doc-repos, and harnesses.'
```

## skill-manager.toml

Keep `[skill]` at the bottom. TOML scoping means arrays placed after
`[skill]` become fields inside `[skill]`, which is usually wrong.

```toml
skill_references = [
  "github:owner/base-skill",
  "file:./local-helper",
  "skill:published-helper@1.2.0",
]

[[cli_dependencies]]
spec = "pip:my-tool==1.4.0"
on_path = "my-tool"

[[mcp_dependencies]]
name = "my-mcp"
display_name = "My MCP"
description = "What this MCP server does."
default_scope = "global-sticky"
load = { type = "docker", image = "ghcr.io/me/my-mcp:latest", args = ["--stdio"] }

[skill]
name = "my-skill"
version = "0.1.0"
description = "Short tooling-side description."
```

Supported top-level sections:

- `skill_references`: transitive unit refs. See
  `references/coords-and-distribution.md`. Add one only when the
  referenced unit must be installed transitively; markdown
  `skill-imports` alone are semantic links to installed units.
- `[[cli_dependencies]]`: CLI tools installed into
  `$SKILL_MANAGER_HOME/bin/cli/`. See `references/dependencies.md`.
- `[[mcp_dependencies]]`: MCP servers registered with the virtual
  gateway. See `references/dependencies.md`.
- `[skill]`: unit identity and version.

## Versioning and distribution

Put the skill at the root of its own git repo. Users install from the
git/local source:

```bash
skill-manager install github:owner/my-skill
skill-manager install git+https://host/org/my-skill.git
skill-manager install file:///abs/path/to/my-skill
```

Registry publish is optional metadata/search for supported unit kinds.
The git repo remains the source of truth.

## Validation

```bash
skill-manager install file:///abs/path/to/my-skill --dry-run
skill-manager install file:///abs/path/to/my-skill --yes
skill-manager show my-skill
skill-manager publish /abs/path/to/my-skill --dry-run
```

For a local starter, see `examples/skill/`.
