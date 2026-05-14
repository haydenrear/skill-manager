---
skill-imports: []
---

# Skill imports

`skill-imports` are semantic edges from one markdown file to a specific
file inside an installed skill. They let an agent discover shared
instructions lazily without copying those instructions into every unit.

Imports are frontmatter-only. Inline import syntax is not supported.

```markdown
---
skill-imports:
  - skill: skill-manager
    path: references/mcp.md
    reason: Explains how MCP servers are exposed through the virtual gateway.
    section: mcp-dependencies
---
```

## Fields

- `skill` is required and must name an installed skill.
- `path` is required and must point to a regular file inside that skill.
- `reason` is required. It explains why the edge exists and helps the
  agent decide whether to traverse it.
- `section` is optional and advisory. It is a navigation hint, not a
  validated anchor.

## Semantics

An import means: this file depends on or extends behavior documented in
the referenced file. It is not a text include and it is not an execution
dependency by itself.

For install-time availability, also declare the owning skill as a unit
reference in the manifest:

```toml
skill_references = [
  "skill:skill-manager",
]
```

Plugins can declare the reference at the plugin level or in the
contained skill that owns the markdown. Doc-repos can use imports in
their markdown sources, but the referenced skill still has to be
installed before validation runs.

## Validation

Install, publish, and sync validate every markdown file under the unit
root. Validation checks that each target skill exists, each target path
stays inside that skill directory, and the target file exists. Failures
are explicit and actionable; there are no silent skips for malformed
imports.
