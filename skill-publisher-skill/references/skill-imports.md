---
skill-imports:
  - skill: skill-manager
    path: references/skill-imports.md
    reason: Defines the canonical skill-imports syntax and validation behavior.
    section: fields
---

# Skill imports for authored units

Use `skill-imports` when a markdown file in a skill, plugin, doc-repo,
or harness needs to point at a specific file inside an installed skill.
The edge is semantic: it tells the agent where related context lives and
why it matters.

Starter markdown should include frontmatter even when no imports are
needed yet:

```markdown
---
skill-imports: []
# Example import syntax:
# skill-imports:
#   - skill: skill-manager
#     path: references/skill-imports.md
#     reason: Explains semantic markdown imports between installed skills.
---
```

When you add a real import, also add a manifest reference so install can
resolve the target skill before validation:

```toml
skill_references = [
  "skill:skill-manager",
]
```

For plugins, put the manifest reference on the contained skill when only
that skill's markdown needs the import. Use plugin-level `references`
only when plugin-level markdown or several contained skills share the
same imported skill.

For doc-repos, imports are valid in any declared source markdown, but
doc-repo manifests do not currently carry transitive references. Make
sure the imported skill is already installed or composed by the harness
that binds the doc-repo.
