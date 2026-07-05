---
skill-imports:
  - unit: skill-publisher
    path: references/coords-and-distribution.md
    reason: Install-source and reference coords follow one rule (git coords only, no registry configured); stated once there.
    section: coord-forms
  - unit: skill-publisher
    path: references/coords-and-distribution.md
    reason: Canonical distribution default — push to a GitHub repo with a license and install from the git source, not from a file.
    section: source-of-truth
---

# skill-manager workflows

This reference captures agent decision flows. It intentionally avoids
long flag tables; use CLI help for exact syntax:

```bash
skill-manager --help
skill-manager <command> --help
```

Some subcommands currently print usage after a validation or "unknown
option" banner. Treat the usage text as authoritative.

## Choose the workflow

- Install only when the user wants bytes in the skill-manager store and
  default agent exposure is enough.
- Bind when installed bytes need to be projected into a specific root.
  This is required for doc-repo markdown to affect a project.
- Instantiate a harness when the user wants a complete project/agent
  profile with skills, plugins, docs, and selected MCP tools.
- Use a skill project when the repository itself declares its required
  skills, plugins, doc-repos, harnesses, envs, libs, CLI deps, and MCP
  deps in `skill-project.toml` or `skill-manager-project.toml`.
- Sync when an installed git-backed unit should pull the latest commit,
  re-run side effects, or reconcile bindings.
- Upgrade when the user wants the newest registry-published version.
- Publish only when the user wants registry/search discoverability;
  direct git/local installs do not require publish.

## Modeled CLI Workflow Coverage

This table is keyed by the workflow ids in the CLI metadata catalog and
the TLA+ program model. It is a routing map, not an option reference:
run the listed help command for exact syntax before mutating state.

| Workflow id | Start with | Help |
| --- | --- | --- |
| `account-auth` | `login` | `skill-manager login --help` |
| `ads-manage` | `ads` | `skill-manager ads --help` |
| `bind-projection` | `bind` | `skill-manager bind --help` |
| `cli-lock-inspect` | `cli` | `skill-manager cli --help` |
| `discover-installed-units` | `list` | `skill-manager list --help` |
| `force-skill-scripts` | `sync` | `skill-manager sync --help` |
| `gateway-lifecycle` | `gateway` | `skill-manager gateway --help` |
| `harness-instantiate` | `harness instantiate` | `skill-manager harness instantiate --help` |
| `harness-remove` | `harness rm` | `skill-manager harness rm --help` |
| `inspect-unit` | `show` | `skill-manager show --help` |
| `install-git-unit` | `install` | `skill-manager install --help` |
| `install-local-unit` | `install` | `skill-manager install --help` |
| `install-registry-unit` | `install` | `skill-manager install --help` |
| `onboard-default-skills` | `onboard` | `skill-manager onboard --help` |
| `package-manager-bootstrap` | `pm` | `skill-manager pm --help` |
| `policy-inspect` | `policy` | `skill-manager policy --help` |
| `project-env` | `env sync` | `skill-manager env sync --help` |
| `project-profile-resolve` | `project profiles` | `skill-manager project profiles --help` |
| `project-register` | `project register` | `skill-manager project register --help` |
| `project-resolve` | `project resolve` | `skill-manager project resolve --help` |
| `publish-unit` | `publish` | `skill-manager publish --help` |
| `rebind-projection` | `rebind` | `skill-manager rebind --help` |
| `refresh-lockfile` | `sync` | `skill-manager sync --help` |
| `registry-lifecycle` | `registry` | `skill-manager registry --help` |
| `remove-installed-unit` | `remove` | `skill-manager remove --help` |
| `sync-all-units` | `sync` | `skill-manager sync --help` |
| `sync-from-local-source` | `sync` | `skill-manager sync --help` |
| `sync-lockfile` | `sync` | `skill-manager sync --help` |
| `sync-one-unit` | `sync` | `skill-manager sync --help` |
| `unbind-projection` | `unbind` | `skill-manager unbind --help` |
| `upgrade-units` | `upgrade` | `skill-manager upgrade --help` |

For agent-facing context while a workflow is running, add
`--agent-context` to a command or set `SKILL_MANAGER_AGENT_CONTEXT=1`.
The context block is written to stderr and includes the matched workflow
id, related skill docs, next commands, and log locations.

## Find and install a unit

1. Search or inspect:
   `skill-manager search "<query>"`, `skill-manager registry --help`,
   `skill-manager show <name>`, or `skill-manager list`.
2. Prefer a skill unless the user explicitly asks for a plugin,
   doc-repo, or harness, or the result is shaped that way.
3. Run install with `--dry-run` first for unfamiliar or policy-relevant
   units.
4. Read the plan: fetched sources, git SHA, CLI deps, MCP deps, hooks,
   bindings, and policy gates.
5. Proceed only after the plan is acceptable.

Check exact source forms with `skill-manager install --help`. Common
sources include registry names, kind-pinned coords, GitHub shorthand,
arbitrary git URLs, and local paths. **In practice use git coords
(`github:owner/repo`, `git+…`, `file:…`): no registry is configured, so
registry-name and kind-pinned coords cannot resolve** — see the imported
`coords-and-distribution.md` for the single statement of this rule.

## Bind project docs

Use this when an installed doc-repo should affect one project.

1. Confirm the doc-repo is installed with `skill-manager list` and
   `skill-manager show <name>`.
2. Bind either the full doc-repo or one source to the project root.
3. Inspect `docs/agents/`, `CLAUDE.md`, and `AGENTS.md`.
4. Use `skill-manager bindings` to inspect the ledger.
5. Use `unbind` or `rebind` rather than manually deleting managed
   files.

Exact flags and conflict policies are in `skill-manager bind --help`.
Doc-repo binds preserve existing project instructions by default and add
managed imports for the agents declared by each source.

## Instantiate a harness

Use this when the user wants a full project-specific agent profile:
reviewer, coder, migration planner, release operator, and similar.

1. Install the harness template.
2. Inspect it with the harness show command.
3. Instantiate with explicit target dirs when you need predictable
   output: Claude config dir, Codex home, and project dir.
4. Inspect the project root for doc bindings and the agent config dirs
   for skill/plugin projections.
5. Use harness list to see live instances.
6. Use harness rm to tear down an instance; it reverses the owned
   bindings and removes the instance sandbox.

Exact subcommands are in `skill-manager harness --help`.

## Resolve a skill project

Use this when the current checkout has a `skill-project.toml` or
`skill-manager-project.toml`.

1. Inspect the manifest and use `skill-manager project show <name>` or
   `skill-manager project list` when the project is already registered.
2. Register the project when the parent home should remember manifest
   intent.
3. Resolve the project to install declared units, write the project lock,
   scaffold `<project>/.skill-manager` as a child Skill Manager home,
   and create child-local `.claude`, `.codex`, and `.gemini` homes.
4. Launch agents with `SKILL_MANAGER_HOME=<project>/.skill-manager` and
   the matching `CODEX_HOME`, `CLAUDE_HOME`, or `GEMINI_HOME`.
5. Use `skill-manager env sync` / `skill-manager env run` for declared
   project envs.

Details live in `references/projects.md`.

## Use an installed CLI dependency

Do not assume the right binary is on `PATH`. Resolve absolute paths with
the helper shipped in this skill:

```bash
<skill-manager-skill>/scripts/env.sh --pretty
<skill-manager-skill>/scripts/env.sh --skills <unit-or-skill-name>
```

Use the returned `clis` path directly. If a declared CLI is missing,
sync or reinstall the owning unit before falling back to a system
binary.

## Use a gateway-backed MCP tool

The CLI manages gateway lifecycle, but it does not proxy downstream tool
discovery or invocation.

1. Run `skill-manager list` to verify the owning unit is installed.
2. Use gateway virtual tools:
   `browse_mcp_servers`, `describe_mcp_server`,
   `deploy_mcp_server`, `browse_active_tools`, `search_tools`,
   `describe_tool`, and `invoke_tool`.
3. Always call `describe_tool` before `invoke_tool` in the current
   session.
4. Ask the user for required secrets from `init_schema`; never invent
   them.

Details live in `references/virtual-mcp-gateway.md`.

## Sync and upgrade

Use sync for reconciliation:

- Re-run install side effects after environment changes.
- Re-register MCP deps and re-plan bindings.
- Pull latest commits for git-backed units when the sync flags request
  it.
- Reconcile a vendored lock or refresh the lock from live state.

Use upgrade to advance to a registry-published version. Check
`skill-manager sync --help`, `skill-manager upgrade --help`, and
`skill-manager lock --help` before choosing flags.

## Publish

Publishing is optional registry metadata/search discoverability.
Installing from a GitHub repo, arbitrary git URL, or local path does not
require publishing.

Favor a durable git-backed install: create a GitHub repo with a
`LICENSE`, push the unit, and install from `github:owner/repo`. A
`file://` install is for local validation only; do not leave it as the
published result unless the user explicitly asks for a local-only
install. The default publishing model is authoritative in skill-publisher
`references/coords-and-distribution.md`.

Before publishing:

1. Use the `skill-publisher` skill for manifest anatomy and examples.
2. Ensure the unit root is a git repo and contains the correct marker
   file for exactly one top-level unit.
3. Run the publish dry run.
4. If the CLI asks for login, relay the `ACTION_REQUIRED` banner and
   pause.

Use `skill-manager publish --help` for the current supported shapes and
flags.

## Troubleshooting

- If exact command syntax is unclear, run the command's help first.
- If an MCP server is missing, verify the owning unit is installed, then
  inspect the gateway reference.
- If a plugin hook or command does not load, run sync after confirming
  the Claude/Codex CLI is on `PATH`.
- If a bind looks wrong, use `bindings list` / `bindings show` before
  editing files manually.
- If a project launch cannot see expected skills or tools, verify the
  agent process is pointed at the project child home, not the parent
  home.
- If the plan reports `BLOCKED` or `CONFLICT`, surface the policy or
  lock issue to the user instead of forcing the command.
