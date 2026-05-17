---
skill-imports:
  - unit: skill-manager
    path: references/skill-imports.md
    reason: Defines the canonical skill-imports syntax and validation behavior.
    section: fields
---

# Skill imports for authored units

Use `skill-imports` when a markdown file in a skill, plugin, doc-repo,
or harness needs to point at a specific file inside an installed unit:
a skill, plugin, doc-repo, or harness. The edge is semantic: it tells
the agent where related context lives and why it matters.

Starter markdown should include frontmatter even when no imports are
needed yet:

```markdown
---
skill-imports: []
# Example import syntax:
# skill-imports:
#   - unit: skill-manager
#     path: references/skill-imports.md
#     reason: Explains semantic markdown imports between installed units.
---
```

The historical `skill` field is still accepted for compatibility, but
new files should prefer `unit`. The target name is resolved against all
installed unit roots, not just `$SKILL_MANAGER_HOME/skills`.

Only add a manifest reference when the target must be installed
transitively. Use an explicit coord for that install-time dependency:

```toml
skill_references = [
  "github:owner/shared-unit",
]
```

Do not add `skill_references` only because a markdown import points at
an already-installed or separately bundled unit. For plugins, put real
install-time references on the contained skill that needs the dependency
or at plugin level when the whole plugin owns that dependency.

For doc-repos, imports are valid in any declared source markdown, but
doc-repo manifests do not currently carry transitive references. Make
sure imported units are already installed or composed by the harness
that binds the doc-repo.
