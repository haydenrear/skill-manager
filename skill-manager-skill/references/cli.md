---
skill-imports: []
---

# CLI dependencies

Use this reference when a skill or plugin declares CLI tools in
`skill-manager.toml` or `skill-manager-plugin.toml`.

## Authoring

Declare CLI tools in `[[cli_dependencies]]`. The `spec` prefix chooses
the installer backend:

- `pip:<package>[==version]` installs with bundled `uv`.
- `npm:<package>[@version]` installs with bundled Node/npm.
- `brew:<formula>` installs through Homebrew and links into the
  skill-manager CLI bin.
- `tar:<name>` downloads and extracts a pinned per-platform archive.
- `skill-script:<name>` runs a bundled private install script.

Always set `on_path` to the command that proves the tool is available.
Pin versions and hashes whenever the backend supports it.

## Runtime

Do not assume a declared CLI dependency is on the user's shell `PATH`.
Resolve skill-manager managed binaries with:

```bash
<skill-manager-skill>/scripts/env.sh --pretty
<skill-manager-skill>/scripts/env.sh --skills <unit-or-skill-name>
```

Use the returned path directly in commands. If a binary is missing,
sync or reinstall the owning unit before falling back to a system copy.
For `skill-script:` deps, `install --force-scripts` and
`sync --force-scripts` rerun the bundled script even when the saved
fingerprint matches and the declared binary already exists. These flags
do not bypass policy approval for CLI installers.

`skill-manager uninstall <unit>` removes managed CLI binaries and
`cli-lock.toml` rows only when they are orphaned. If another installed
skill or plugin still claims the same backend/tool, uninstall preserves
the artifact and rewrites ownership for the surviving claim.

When the current directory is inside a skill project, the same helper
also reports passive project context: the manifest path, project name,
declared envs, project child Skill Manager home, and child-local agent
homes that already exist. Use that to choose `SKILL_MANAGER_HOME`,
`CODEX_HOME`, `CLAUDE_HOME`, and `GEMINI_HOME` for project-local agent
launches.

## Validation

Run install with a dry run first to inspect planned CLI actions:

```bash
skill-manager install file:///abs/path/to/unit --dry-run
skill-manager install file:///abs/path/to/unit --yes
skill-manager install --force-scripts file:///abs/path/to/unit --yes
skill-manager sync <unit-name> --from /abs/path/to/unit --dry-run
skill-manager sync --force-scripts <unit-name> --yes
```

Policy may require explicit approval for CLI installers. Do not bypass a
blocked plan without user instruction.
