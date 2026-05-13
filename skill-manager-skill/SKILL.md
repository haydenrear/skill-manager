---
name: skill-manager
description: 'Search, install, bind, sync, and remove skill-manager-managed units: skills, plugins, doc-repos, and harnesses. Use when the user asks to find, add, remove, inspect, bind, unbind, instantiate, sync, or upgrade one of those units, or to manage, deploy, or invoke an MCP tool that came from an installed unit. CLI syntax is authoritative in `skill-manager --help`; gateway operations are authoritative in `references/virtual-mcp-gateway.md`; agent workflow routing lives in `references/workflows.md`.'
---

# skill-manager

Use `skill-manager` as the package manager for agent capability units:
skills, plugins, doc-repos, harnesses, CLI tools, and MCP servers.

This skill should not mirror the whole CLI manual. For exact flags and
current command syntax, run:

```bash
skill-manager --help
skill-manager <command> --help
```

Some subcommands currently print usage after a validation or "unknown
option" banner; the usage text is still the source of truth.

## When to use

- The user asks what skills, plugins, doc-repos, or harnesses are
  available.
- The user asks to install, remove, publish, upgrade, sync, inspect, or
  scaffold a unit.
- The user asks to bind or unbind a unit into a project, especially
  doc-repo markdown into `CLAUDE.md` / `AGENTS.md`.
- The user asks to instantiate, list, sync, or tear down a harness
  profile.
- The user asks to add, describe, deploy, or invoke an MCP server/tool
  that came from skill-manager.
- A task needs a CLI tool or MCP server that is not currently available,
  and a skill-manager unit may provide it.

Narrate the plan before commands that modify state. Install, uninstall,
publish, sync, bind, unbind, rebind, upgrade, and harness instantiate/rm
all change disk state or external registry state.

## Unit Model

skill-manager installs four unit kinds:

| Kind | Use when | Store path |
| --- | --- | --- |
| Skill | One focused agent capability. | `$SKILL_MANAGER_HOME/skills/<name>/` |
| Plugin | A bundle of skills plus plugin runtime surface such as hooks, commands, or agents. | `$SKILL_MANAGER_HOME/plugins/<name>/` |
| Doc-repo | Versioned markdown sources that bind into project `CLAUDE.md` / `AGENTS.md`. | `$SKILL_MANAGER_HOME/docs/<name>/` |
| Harness | A reusable project/agent profile composing skills, plugins, docs, and MCP tool selections. | `$SKILL_MANAGER_HOME/harnesses/<name>/` |

Use `skill-manager list` to see installed units, their kind, source, and
resolved git SHA. Use `skill-manager show <name>` for kind-specific
metadata and dependency attribution.

For authoring unit manifests, scaffolding, TOML anatomy, and examples,
use the `skill-publisher` skill rather than this one.

## References

Load the focused reference instead of searching this file for detailed
flows:

- `references/workflows.md` - agent decision flows for install, bind,
  harness, sync, publish, CLI tools, and gateway-backed MCP tools.
- `references/virtual-mcp-gateway.md` - the gateway architecture,
  virtual tool surface, deployment scopes, disclosure gate, and MCP
  troubleshooting.
- `scripts/env.sh` / `scripts/env.py` - resolve absolute paths for
  installed CLI dependencies and agent-visible skill paths.

## CLI Boundaries

Use the CLI for install state, local projections, registry operations,
gateway process lifecycle, and lock maintenance. Prefer checking help
before relying on remembered flags:

```bash
skill-manager --help
skill-manager install --help
skill-manager sync --help
skill-manager bind --help
skill-manager harness --help
skill-manager publish --help
```

Do not duplicate long command tables here. The CLI help already covers:

- Source forms such as registry names, `skill:`, `plugin:`, `doc:`,
  `harness:`, `github:owner/repo`, `git+https://...`, and local paths.
- Install planning, policy gates, and store/projection side effects.
- `sync`, `upgrade`, `lock`, `bind`, `unbind`, `rebind`, `bindings`,
  `harness`, `publish`, `registry`, `gateway`, `policy`, `pm`, and
  `cli` subcommands.

Keep skill-specific guidance to the things the CLI cannot decide for
the agent: which workflow to choose, what to inspect before mutating
state, and when to use gateway MCP tools instead of shell commands.

## MCP and CLI Tools

When a unit is installed, declared tools are resolved transitively:

- CLI dependencies land under `$SKILL_MANAGER_HOME/bin/cli/`.
- MCP dependencies register with the `virtual-mcp-gateway`.
- Plugins contribute deps from both `skill-manager-plugin.toml` and
  contained skill manifests.
- Harnesses install the referenced skills/plugins/doc-repos before
  materializing an instance.

For CLI dependencies, do not rely on the user's `PATH`. Ask the helper
for absolute paths:

```bash
<skill-manager-skill>/scripts/env.sh --pretty
<skill-manager-skill>/scripts/env.sh --skills <name> --for claude
```

The helper reports installed skill paths, agent symlinks, bundled
package-manager paths, installed CLI binaries, and missing declared
tools. It never mutates shell state.

For MCP dependencies, there is no CLI equivalent for discovering,
deploying, describing, or invoking downstream tools. Use the
`virtual-mcp-gateway` MCP server's virtual tools. The short rule:

1. `skill-manager list` confirms which units are skill-manager-managed.
2. `browse_mcp_servers` shows registered downstream servers.
3. `deploy_mcp_server` starts a registered server when needed.
4. `browse_active_tools` or `search_tools` finds callable tools.
5. `describe_tool` discloses schema and satisfies the per-session gate.
6. `invoke_tool` calls the downstream tool.

See `references/virtual-mcp-gateway.md` for parameters, scopes, failure
modes, and the disclosure gate.

## Bindings and Harnesses

Install puts bytes in the store. Binding projects a unit into a target
root and records a reversible ledger under
`$SKILL_MANAGER_HOME/installed/<unit>.projections.json`.

- Skill/plugin binds create a symlink at the target root.
- Doc-repo binds copy tracked markdown under `docs/agents/` and insert
  managed imports into `CLAUDE.md` and/or `AGENTS.md`.
- Harness instantiation creates a named profile instance with its own
  bindings for skills, plugins, docs, and selected MCP exposure.

Use CLI help for exact flags:

```bash
skill-manager bind --help
skill-manager bindings --help
skill-manager harness --help
```

Use `references/workflows.md` for the agent-level decision flow: when to
install only, when to bind, when to instantiate, and how to clean up.

## Lock, Policy, And Auth

`units.lock.toml` is updated atomically by install, sync, upgrade, and
uninstall. Use `skill-manager lock --help` and `skill-manager sync
--help` for current lock reconciliation flags.

Never bypass policy with `--yes` blindly. The plan output is the
security surface. If a plan reports `BLOCKED`, `CONFLICT`, or a
policy-gated category, surface it to the user and do not loosen
`~/.skill-manager/policy.toml` without explicit instruction.

Most reads work without login. Mutating registry operations such as
publish require authentication. If the CLI prints this banner, relay it
verbatim and pause until the user signs in:

```text
ACTION_REQUIRED: skill-manager login
Reason: <specifics>
Ask the user to run the following in their terminal, then retry the task:

    skill-manager login
```

Do not try to complete the browser login on the user's behalf.
