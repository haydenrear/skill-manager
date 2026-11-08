# skill-manager

A JBang-based CLI + Python services that acts as a **build tool for agent
skills and plugins**. Like `uv` / `cargo` / `gradle`, but for both unit
kinds: install from git or local sources, manage dependencies, and register
downstream MCP servers. Plugins flow through a skill-manager-owned local
marketplace and `claude plugin install` / `codex plugin marketplace add`
so harness hooks/commands actually load.

## Anatomy of a skill

Two files live side-by-side in a skill directory:

```
my-skill/
  SKILL.md                 # what the agent sees (standard spec)
  skill-manager.toml       # what the tooling sees (invisible to the agent)
```

`SKILL.md` only carries the standard `name` / `description` (base spec).
`skill-manager.toml` carries everything else — CLI deps, skill references,
MCP servers, version metadata. Keeping structured config out of SKILL.md
means the agent doesn't spend tokens parsing it.

### skill-manager.toml

```toml
[skill]
name = "my-skill"
version = "0.1.0"

# CLI deps pick an installer backend via the `spec` prefix:
#   pip:<pkg>     platform-independent (prefers `uv tool install`, falls back to pip)
#   npm:<pkg>     platform-independent (npm -g)
#   brew:<pkg>    macOS/Linux (Homebrew)
#   tar:<name>    download + extract using install targets
[[cli_dependencies]]
spec = "pip:ruff"
on_path = "ruff"

[[cli_dependencies]]
spec = "tar:rg"
name = "rg"
on_path = "rg"
[cli_dependencies.install.darwin-arm64]
url = "https://…/rg.tar.gz"
binary = "rg-dir/rg"

# Transitive deps, by git or local coordinate.
skill_references = ["github:acme/base-skill", "git+https://example.com/shared-prompts.git"]
# Or, for local development:
# [[skill_references]]
# path = "../shared-prompts"

[[mcp_dependencies]]
name = "deepwiki"
[mcp_dependencies.load]
type = "docker"
image = "ghcr.io/acme/deepwiki-mcp:latest"
args = ["--stdio"]
```

## Plugins (alongside skills)

Plugins are skills' bigger sibling — a unit that bundles one or more
contained skills plus shared metadata. Use a plugin when you want to
ship a coherent set of skills together; use a bare skill for the
single-purpose case.

```
my-plugin/
  .claude-plugin/plugin.json       # Claude's runtime manifest
  skill-manager-plugin.toml        # tooling-side metadata (parallel to skill-manager.toml)
  skills/
    do-the-thing/
      SKILL.md
      skill-manager.toml
    do-another-thing/
      SKILL.md
      skill-manager.toml
```

Plugin-level `skill-manager-plugin.toml` is parallel to a skill's
`skill-manager.toml` — same `[[cli_dependencies]]` / `[[mcp_dependencies]]`
/ `references` shape. The plugin's effective dep set is the **union**
of plugin-level deps and every contained skill's deps; `skill-manager
show <plugin>` renders the union with attribution back to where each
entry was declared.

The `[plugin]` table mirrors `[skill]`:

```toml
[plugin]
name = "my-plugin"
version = "0.1.0"
description = "What the plugin does."
```

A reference example lives at [`examples/hello-plugin/`](examples/hello-plugin/).

### Coordinates

Units install from, and reference each other by, a git or local
coordinate:

| Form | Resolves to |
| --- | --- |
| `github:user/repo` | git source (kind detected at clone time) |
| `git+https://…`, `….git`, `git@host:…`, `ssh://…` | git URL (kind detected at clone time) |
| `file:./path`, `./path`, `/abs/path` | local source (kind detected at parse time) |

A bare name (`hello-skill`, `skill:hello-skill`, `plugin:repo-intelligence`)
is not an install source: it used to resolve through the skill-manager
registry, which was removed. An install or reference that names one fails
and says which of the forms above to use instead.

### Lockfile (`units.lock.toml`)

Every install / sync / upgrade flips `~/.skill-manager/units.lock.toml`
atomically at commit. The lock records `(name, kind, version,
install_source, origin, ref, resolved_sha)` for every installed unit
so a vendored lock can reproduce the install set byte-for-byte.

| Command | Purpose |
| --- | --- |
| `skill-manager lock status` | Show drift between the lock and the live install set. |
| `skill-manager sync --lock <path>` | Reconcile disk toward the supplied lock; idempotent. |
| `skill-manager sync --refresh` | Re-write the lock from the live install set (after out-of-band edits). |

## Migration notes

First run after upgrade does these in-place migrations automatically;
no user action required:

- `~/.skill-manager/sources/` → `~/.skill-manager/installed/` (one-time
  rename of the per-unit metadata dir; legacy records pick up
  `unit_kind = "skill"` by default).
- `units.lock.toml` is born lazy — generated on first install /
  upgrade after the upgrade. Run `skill-manager sync --refresh` to
  bootstrap it from the existing install set explicitly.

## Services

| Piece | Language | Purpose |
| --- | --- | --- |
| `skill-manager` | Java (JBang) | The CLI users run locally — install / sync / upgrade units |
| `virtual-mcp-gateway/` | Python (FastAPI + MCP SDK) | MCP gateway that fronts every downstream MCP server for the agents |

## Install the CLI

The supported install path is Homebrew via this repo's tap:

```bash
brew tap haydenrear/skill-manager
brew install haydenrear/skill-manager/skill-manager
```

That puts `skill-manager` on `PATH`. Verify:

```bash
skill-manager --help
skill-manager gateway up   # bring up the MCP gateway
```

To upgrade later, either run `skill-manager upgrade --self` or
`brew upgrade haydenrear/skill-manager/skill-manager` directly — the
former wraps the latter.

If you're hacking on the CLI from this repo instead, every `./skill-manager`
invocation in the docs below works the same (JBang resolves dependencies on
first run; subsequent runs are cached).

## Iterate on a skill locally

If you just want to install or update a skill from a directory on
disk (a working tree, a git checkout, an in-flight branch), install it
from the path. Two flows:

### First-time install from a directory

```bash
./skill-manager install ./path/to/my-skill
# or with an absolute path:
./skill-manager install file:/abs/path/to/my-skill
```

`install` resolves local paths directly — it copies the directory into
`$SKILL_MANAGER_HOME/skills/<name>/`, runs CLI-dep installation, and
registers any MCP servers the skill declares with the gateway.

### Refresh an already-installed skill from a directory

After you've made edits in the source dir, push them back into the
installed copy:

```bash
./skill-manager sync my-skill --from ./path/to/my-skill
```

That runs `git diff --no-index` between the store entry and the source
dir, prints the diff to stdout, and prompts before overwriting. Pass
`-y` / `--yes` to skip the prompt in scripts. Once approved, `sync`
copies the source over the store entry, then continues with its
normal side effects (re-register MCP servers, refresh agent symlinks
and MCP-config entries).

If the diff is empty, sync says so and continues straight to the
side-effect refresh.

To publish an edit made inside a home back to the unit's own repository,
use `skill-manager unit publish <name>`.

## Run the MCP gateway

The MCP gateway (`virtual-mcp-gateway/`) is the one MCP server every
agent's MCP config points at. The CLI owns its lifecycle:

```bash
./skill-manager gateway up        # start (bundled venv, picks a free port if needed)
./skill-manager gateway status    # URL, pid, health
./skill-manager gateway down      # stop
```

`gateway up` is idempotent — it reuses a running gateway. MCP servers
declared by installed skills are registered with the gateway transitively
on `skill-manager install` / `sync` / `upgrade`. Agents discover and
invoke them through the gateway's virtual tools (`browse_mcp_servers`,
`deploy_mcp_server`, `invoke_tool`, …); see
[`skill-manager-skill/references/virtual-mcp-gateway.md`](skill-manager-skill/references/virtual-mcp-gateway.md)
for the agent-facing reference.

## CLI reference

### Skill management

| Command | Purpose |
| --- | --- |
| `skill-manager list` | List installed skills |
| `skill-manager install <./path>` | Install from a local directory |
| `skill-manager install github:user/repo` | Install from a git repo |
| `skill-manager install file:<path>` | Install from an absolute local path |
| `skill-manager install <source> --force-scripts` | Install and explicitly rerun `skill-script:` CLI deps instead of using the fingerprint skip |
| `skill-manager sync [<name>]` | Re-run install side effects (MCP register, agent symlinks) without re-fetching |
| `skill-manager sync <name> --from <dir>` | Diff the store against `<dir>`, prompt, apply, then re-run side effects |
| `skill-manager sync <name> --force-scripts` | Sync while explicitly rerunning `skill-script:` CLI deps for `<name>` |
| `skill-manager sync --force-scripts` | Sync all installed units and explicitly rerun all installed `skill-script:` CLI deps |
| `skill-manager upgrade <name> \| --all` | Upgrade installed skills; rolls back on install failure |
| `skill-manager upgrade --self` | Upgrade the CLI itself via `brew upgrade haydenrear/skill-manager/skill-manager` |
| `skill-manager uninstall <name>` | Full uninstall — store entry, all agent symlinks, orphan MCP servers, orphan managed CLI deps |
| `skill-manager remove <name>` | Lower-level: remove the store entry only |
| `skill-manager show <name>` | Metadata + deps |
| `skill-manager deps [name]` | Transitive dep tree |

`sync <name> --force-scripts` scopes forced script replay to the named
sync target, so unrelated installed skills are not forced. A no-name
`sync --force-scripts` treats every installed unit as the target set.
`--force-scripts` still respects installer policy gates. Skill-script
stdout/stderr is written under
`$SKILL_MANAGER_HOME/logs/skill-scripts/`; failures include the log path
and a tail of recent output. Uninstall only removes managed CLI artifacts
when no remaining installed unit claims the same dependency.

### Publishing an edit

| Command | Purpose |
| --- | --- |
| `skill-manager unit publish <name>` | Commit and push a home edit back to the unit's own repository |

### Gateway lifecycle

| Command | Purpose |
| --- | --- |
| `skill-manager gateway up / down` | Start / stop the bundled MCP gateway |
| `skill-manager gateway status` | URL, pid, health |
| `skill-manager gateway set <url>` | Persist the gateway URL |

MCP servers are registered **transitively** when a skill that declares them is
installed — there is no direct "register MCP server" CLI. Agents interact with
the gateway over MCP (browse/describe/deploy/invoke tools); the CLI only owns
the gateway process lifecycle.

## Storage layout

```
~/.skill-manager/
  skills/<name>/          # canonical skill source
  bin/                    # CLI binaries installed via tar backend
  cache/                  # downloads + staging
  gateway.pid|log|config  # gateway process state
  gateway.properties      # persisted gateway URL
```

## Source sugar for `add`

- `./path`, `/path`, `file:/path` → local directory
- `github:user/repo`, `git+https://…`, `ssh://…`, `….git` → git clone
- `<name>[@version]` → refused: the registry was removed; use one of the above

## Status

- TOML-only skill metadata; SKILL.md is base spec only.
- CLI installers: tar / pip / npm / brew, dispatch by `spec` prefix, `platform_independent` honored.
- Skill references: git or local coordinates; local `path =` works for development.
- MCP gateway: docker + binary load specs, dynamic registration at runtime.
