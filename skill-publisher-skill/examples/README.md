# skill-publisher examples

Minimal unit shapes for authoring with skill-manager.

- `skill/` — bare skill: `SKILL.md` + `skill-manager.toml`.
- `plugin/` — plugin: `.claude-plugin/plugin.json`,
  `skill-manager-plugin.toml`, and one contained skill.
- `doc-repo/` — doc-repo: `[doc-repo]` manifest and markdown sources.
- `harness/` — harness template: `harness.toml` with commented
  composition refs for a skill, plugin, and doc-repo. It starts empty so
  the example installs as-is; replace the refs before using it for a real
  profile.

Validate examples from a fresh working directory:

```bash
skill-manager install file:///abs/path/to/example --dry-run
skill-manager install file:///abs/path/to/example --yes
```

Doc-repos also need a bind validation. Harnesses also need an
instantiate validation. See `references/doc-repos.md` and
`references/harnesses.md`.
