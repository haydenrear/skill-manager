---
skill-imports: []
---

# Skill imports

`skill-imports` are semantic edges from one markdown file to a specific
file inside an installed unit: a skill, plugin, doc-repo, or harness.
They let an agent discover shared instructions lazily without copying
those instructions into every unit.

Imports are frontmatter-only. Inline import syntax is not supported.

```markdown
---
skill-imports:
  - unit: skill-manager
    path: references/mcp.md
    reason: Explains how MCP servers are exposed through the virtual gateway.
    section: mcp-dependencies
---
```

## Fields

- `unit` is required and must name an installed unit. The older `skill`
  key is still accepted for compatibility, but the value may name any
  installed unit kind.
- `path` is required and must point to a regular file inside that unit.
- `reason` is required. It explains why the edge exists and helps the
  agent decide whether to traverse it.
- `section` is optional and advisory. It is a navigation hint, not a
  validated anchor.

## Semantics

An import means: this file depends on or extends behavior documented in
the referenced file. It is not a text include, it is not an execution
dependency, and it does not automatically install the target.

If the target must be installed transitively, declare it separately as a
manifest reference using an explicit unit coord:

```toml
skill_references = [
  "github:owner/shared-unit",
]
```

Do not add a manifest reference just because a markdown import points at
an already-installed or separately bundled unit. Plugins can declare
install-time references at the plugin level or in the contained skill
that owns the dependency. Doc-repos and harnesses may import markdown
from any installed unit; their install-time composition is handled by
the unit or harness manifest.

## Validation

Install, publish, and sync validate every markdown file under the unit
root. Validation checks that each target unit exists, each target path
stays inside that unit directory, and the target file exists. Failures
are explicit and actionable; there are no silent skips for malformed
imports.
