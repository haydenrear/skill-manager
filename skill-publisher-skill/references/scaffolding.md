---
skill-imports:
  - unit: skill-manager
    path: references/skill-imports.md
    reason: Defines the frontmatter import syntax used in starter markdown.
    section: fields
---

# Scaffolding units

Start with the CLI scaffolder when it supports the unit shape, then use
the examples in this skill for shapes that do not yet have first-class
scaffold commands.

## CLI scaffold command

```bash
skill-manager create <name>                 # bare skill
skill-manager create <name> --kind plugin   # plugin
skill-manager create --help                 # current flags and generated next steps
```

Current CLI scaffolding support is `skill` and `plugin`.

Generated skill/plugin directories are starter templates. Always review
and edit:

- Runtime/frontmatter descriptions.
- Version.
- Manifest references. Keep them for install-time dependencies only;
  markdown `skill-imports` are semantic links to files in installed
  units and do not require a matching TOML reference by default.
- CLI/MCP dependencies.
- Plugin contained skills and plugin runtime files.

## Example starters

This skill ships examples for every unit kind:

```bash
cp -r <skill-publisher-skill>/examples/skill <my-skill>
cp -r <skill-publisher-skill>/examples/plugin <my-plugin>
cp -r <skill-publisher-skill>/examples/doc-repo <my-doc-repo>
cp -r <skill-publisher-skill>/examples/harness <my-harness>
```

Use the examples when:

- The CLI does not scaffold that unit kind yet.
- You want a small known-good layout to diff against a user's existing
  directory.
- You need commented dependency/reference examples.

## One unit per repo

Scaffold the unit at the root of its own git repo. The resolver detects
the unit from root marker files and does not support a "monorepo of
multiple top-level units" workflow yet.

Root markers:

- Skill: `SKILL.md` and `skill-manager.toml`.
- Plugin: `.claude-plugin/plugin.json`.
- Doc-repo: `skill-manager.toml` with `[doc-repo]`.
- Harness: `harness.toml` with `[harness]`.

If a capability set needs multiple units, choose one:

- Bundle them as one plugin when they are runtime plugin surface or
  version together.
- Compose them with a harness when they are a project-specific agent
  profile.
- Put each unit in its own repo and reference the others by coord.

## Minimal validation

From outside the source directory:

```bash
skill-manager install file:///abs/path/to/unit --dry-run
skill-manager install file:///abs/path/to/unit --yes
skill-manager show <name>
skill-manager list
```

Extra validation by kind:

- Skill/plugin: `skill-manager publish <dir> --dry-run` validates the
  registry package path for currently supported publish shapes.
- Doc-repo: bind into a disposable project and inspect `docs/agents/`,
  `CLAUDE.md`, and `AGENTS.md`.
- Harness: instantiate with explicit `--claude-config-dir`,
  `--codex-home`, and `--project-dir`, then run `skill-manager harness
  list` and `skill-manager harness rm <id>`.

## Next references

- Bare skill schema: `references/skills.md`.
- Plugin schema: `references/plugins.md`.
- Doc-repo schema: `references/doc-repos.md`.
- Harness schema: `references/harnesses.md`.
- Markdown imports: `references/skill-imports.md`.
- Coordinates/distribution: `references/coords-and-distribution.md`.
- CLI/MCP dependencies: `references/dependencies.md`.
