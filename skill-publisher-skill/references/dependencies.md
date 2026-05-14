---
skill-imports:
  - skill: skill-manager
    path: references/cli.md
    reason: Defines runtime handling for skill-manager managed CLI dependencies.
    section: runtime
  - skill: skill-manager
    path: references/mcp.md
    reason: Defines runtime handling for gateway-backed MCP dependencies.
    section: runtime
---

# Dependencies

Dependencies live in manifests and are resolved during install/sync.

- CLI dependencies land under `$SKILL_MANAGER_HOME/bin/cli/`.
- MCP dependencies register with the virtual MCP gateway.
- Transitive unit references are fetched and installed before the
  top-level install commits.

## CLI dependencies

Every CLI dependency uses:

```toml
[[cli_dependencies]]
spec = "<backend>:<package-or-name>"
on_path = "<binary>"
```

Backends:

| Backend | Example | Resolution |
|---|---|---|
| `pip:` | `pip:ruff==0.6.9` | Installed with bundled `uv` into skill-manager's CLI bin. |
| `npm:` | `npm:typescript@5.4.5` | Installed with bundled node/npm into skill-manager's CLI bin. |
| `brew:` | `brew:fd` | Installed via Homebrew and linked into skill-manager's CLI bin. |
| `tar:` | `tar:rg` | Downloaded/extracted from per-platform URLs. |
| `skill-script:` | `skill-script:private-tool` | Runs a script bundled under `skill-scripts/`; high risk, see `skill-scripts.md`. |

Python CLI:

```toml
[[cli_dependencies]]
spec = "pip:ruff==0.6.9"
on_path = "ruff"
min_version = "0.6.0"
```

Node CLI:

```toml
[[cli_dependencies]]
spec = "npm:typescript@5.4.5"
on_path = "tsc"
```

Tarball CLI:

```toml
[[cli_dependencies]]
spec = "tar:rg"
name = "rg"
on_path = "rg"

[cli_dependencies.install.darwin-arm64]
url = "https://example.com/rg-darwin-arm64.tar.gz"
archive = "tar.gz"
binary = "rg"
sha256 = "TODO"

[cli_dependencies.install.linux-x64]
url = "https://example.com/rg-linux-x64.tar.gz"
archive = "tar.gz"
binary = "rg"
sha256 = "TODO"
```

Private CLI:

```toml
[[cli_dependencies]]
spec = "skill-script:private-tool"
on_path = "private-tool"

[cli_dependencies.install.any]
script = "install-private-tool.sh"
binary = "private-tool"
```

Load `references/skill-scripts.md` before using `skill-script:`.

## MCP dependencies

MCP dependencies register downstream servers with the virtual gateway:

```toml
[[mcp_dependencies]]
name = "my-server"
display_name = "My Server"
description = "What this server exposes."
default_scope = "global-sticky"
load = { type = "npm", package = "@vendor/mcp-server", version = "latest" }
```

Common `load` types:

### npm MCP

```toml
[[mcp_dependencies]]
name = "runpod"
display_name = "Runpod"
description = "Runpod operations over MCP."
default_scope = "global-sticky"
load = { type = "npm", package = "@runpod/mcp-server", version = "latest", env = { RUNPOD_API_KEY = "" } }
init_schema = [
  { name = "RUNPOD_API_KEY", type = "string", required = true, secret = true, description = "Runpod API key." },
]
```

Resolution: skill-manager ensures bundled node/npm is available; the
gateway spawns the npm package when deployed.

### uv MCP

```toml
[[mcp_dependencies]]
name = "tb-query"
display_name = "TensorBoard Query"
description = "Query TensorBoard logs."
default_scope = "session"
load = { type = "uv", package = "tb-query-mcp", version = "1.0.0", entry_point = "tb-query-mcp" }
```

Resolution: skill-manager ensures bundled `uv` is available; the gateway
uses `uv tool run` to spawn the package.

### docker MCP

```toml
[[mcp_dependencies]]
name = "sequential-thinking"
display_name = "Sequential Thinking"
description = "Structured thinking tools."
default_scope = "global-sticky"
load = { type = "docker", image = "mcp/sequentialthinking:latest", args = ["--stdio"] }
```

Resolution: gateway runs Docker. skill-manager does not install Docker;
the operator must have it available.

### binary MCP

```toml
[[mcp_dependencies]]
name = "binary-mcp"
display_name = "Binary MCP"
description = "MCP server from a downloaded binary."
default_scope = "session"
load = { type = "binary", transport = "stdio", install = { darwin-arm64 = { url = "https://example.com/server.tar.gz", archive = "tar.gz", binary = "server", sha256 = "TODO" } } }
```

Resolution: provisioner downloads/extracts the archive and the gateway
spawns the binary.

### shell MCP

```toml
[[mcp_dependencies]]
name = "local-shell-mcp"
display_name = "Local shell MCP"
description = "Runs a local script as an MCP server."
default_scope = "session"
load = { type = "shell", command = ["/abs/path/to/server", "--stdio"] }
```

Resolution: gateway runs the command verbatim. This is high risk and
plan output flags it as dangerous because prerequisites are not
validated.

## Host-env passthrough and init schema

An empty string in `load.env` means "read from the gateway process
environment":

```toml
load = { type = "npm", package = "@vendor/mcp", env = { API_KEY = "" } }
```

Pair that with `init_schema` so the requirement is visible to agents:

```toml
init_schema = [
  { name = "API_KEY", type = "string", required = true, secret = true, description = "Service API key." },
]
```

The gateway redacts `secret = true` fields when described.

## Where deps belong

| Unit | Where to declare deps |
|---|---|
| Skill | `skill-manager.toml` |
| Plugin shared dep | `skill-manager-plugin.toml` |
| Plugin contained-skill dep | `skills/<name>/skill-manager.toml` |
| Doc-repo | No CLI/MCP deps; it is markdown sources only. |
| Harness | References units and docs; CLI/MCP deps come from referenced skills/plugins. |

Declare deps as close as possible to the capability that uses them.
Use plugin-level deps only when shared across contained skills.
