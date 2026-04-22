# skill-manager

A JBang-based CLI + Python services that acts as a **build tool for agent
skills**. Like `uv` / `cargo` / `gradle`, but for skills: install, publish,
search, manage dependencies, and register downstream MCP servers.

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

# Transitive deps resolved against the skill registry.
skill_references = ["base-skill", "shared-prompts@1.2.0"]
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

## Services

| Piece | Language | Purpose |
| --- | --- | --- |
| `skill-manager` | Java (JBang) | The CLI users run locally |
| `server/` | Python (FastAPI) | Skill registry — publish + serve + search |
| `virtual-mcp-gateway` | Python (FastAPI + MCP SDK) | MCP gateway that fronts every downstream MCP server for the agents |
| `python/sm_mcp.py` | Python (MCP SDK) | Client helper that `skill-manager gateway …` shells into |

## Install

```bash
# 1. Prereq: JBang for the Java CLI
#    https://www.jbang.dev

# 2. Python services — both live in this repo.
cd virtual-mcp-gateway
python -m venv .venv && .venv/bin/pip install -e . mcp httpx
cd ../../server
.venv/bin/pip install -e .   # shares the venv; or make a separate one

# 3. Start services
.venv/bin/python -m skill_registry.server --host 127.0.0.1 --port 8090 &
./skill-manager gateway up            # starts the MCP gateway
./skill-manager registry set http://127.0.0.1:8090
```

## CLI reference

### Skill management

| Command | Purpose |
| --- | --- |
| `skill-manager list` | List installed skills |
| `skill-manager add <name>[@version]` | Install from the registry |
| `skill-manager add <./path>` | Install from a local directory |
| `skill-manager add github:user/repo` | Install from a git repo |
| `skill-manager remove <name>` | Remove a skill |
| `skill-manager show <name>` | Metadata + deps |
| `skill-manager deps [name]` | Transitive dep tree |
| `skill-manager install` | Install CLI deps (dispatches pip/npm/brew/tar) |
| `skill-manager sync --agent claude,codex` | Sync skills + register MCP with the gateway |

### Registry (publish / search / server config)

| Command | Purpose |
| --- | --- |
| `skill-manager registry set <url>` | Persist the registry URL |
| `skill-manager registry status` | Reachability |
| `skill-manager publish [<path>]` | Package + upload (path defaults to CWD) |
| `skill-manager publish --dry-run` | Just build the tarball |
| `skill-manager search "<query>"` | Lexical search |

### Gateway lifecycle

| Command | Purpose |
| --- | --- |
| `skill-manager gateway up / down` | Start / stop the bundled MCP gateway |
| `skill-manager gateway status` | URL, pid, health |
| `skill-manager gateway set <url>` | Persist the gateway URL |
| `skill-manager gateway push` | Re-register every installed skill's MCP deps |
| `skill-manager gateway unregister <id>` | Remove a dynamic server |

### MCP pass-through (talks MCP to the gateway via the official Python SDK)

| Command | Purpose |
| --- | --- |
| `skill-manager gateway list-tools` | Gateway MCP tool list |
| `skill-manager gateway servers [--deployed]` | List registered MCP servers |
| `skill-manager gateway deploy <id> [--init K=V …]` | Spin up an MCP server |
| `skill-manager gateway undeploy <id>` | Spin it down |
| `skill-manager gateway tools [--prefix P]` | Browse active tools |
| `skill-manager gateway search "<query>"` | Semantic search |
| `skill-manager gateway describe-tool <path>` | Full schema (discloses for session) |
| `skill-manager gateway invoke <path> --args '{…}'` | Call a tool |

## Storage layout

```
~/.skill-manager/
  skills/<name>/          # canonical skill source
  bin/                    # CLI binaries installed via tar backend
  cache/                  # downloads + publish staging
  gateway.pid|log|config  # gateway process state
  registry.properties     # persisted registry URL
  gateway.properties      # persisted gateway URL
```

## Source sugar for `add`

- `<name>[@version]` → **registry lookup** (default)
- `./path`, `/path`, `file:/path` → local directory
- `github:user/repo`, `git+https://…`, `ssh://…`, `….git` → git clone

## Status

- TOML-only skill metadata; SKILL.md is base spec only.
- Registry: filesystem storage, FastAPI server, publish + search + download + versioning.
- CLI installers: tar / pip / npm / brew, dispatch by `spec` prefix, `platform_independent` honored.
- Skill references: name-based via registry; local `path =` works for development.
- MCP gateway: docker + binary load specs, dynamic registration at runtime.
