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

## Validation

Run install with a dry run first to inspect planned CLI actions:

```bash
skill-manager install file:///abs/path/to/unit --dry-run
skill-manager install file:///abs/path/to/unit --yes
```

Policy may require explicit approval for CLI installers. Do not bypass a
blocked plan without user instruction.
