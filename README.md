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
| `skill-manager` | Java (JBang) | The CLI users run locally — install / sync / upgrade / publish / search skills |
| `server-java/` | Java (Spring Boot) | Skill registry + embedded OAuth2 authorization server — publish, serve, search, auth |
| `virtual-mcp-gateway/` | Python (FastAPI + MCP SDK) | MCP gateway that fronts every downstream MCP server for the agents |
| `skill-manager-server` | Bash | JBang wrapper that runs the registry locally (`./skill-manager-server`) |

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

## Run the registry

The skill registry is a Spring Boot service in `server-java/`. It owns
publish/download/search, plus an embedded OAuth2 authorization server
that mints JWTs for the CLI's `skill-manager login`. Two ways to run it.

### Option A — local with `./skill-manager-server`

```bash
./skill-manager-server                                # default port 8080
SKILL_REGISTRY_ALLOW_FILE_UPLOAD=TRUE ./skill-manager-server   # also accept --upload-tarball
```

Data lives at `~/.skill-registry/` (persistence-friendly: keystore,
schema, files). Postgres is bundled via H2 in this mode, suitable for
local dev only.

`SKILL_REGISTRY_ALLOW_FILE_UPLOAD=TRUE` enables the legacy multipart
publish backend — needed when you want to push a local skill directly
without going through the github-pointer flow (see "Local publish +
install round-trip" below).

Point the CLI at the local server with `--registry http://localhost:8080`
(or persist it via `skill-manager registry set http://localhost:8080`).
The server in this mode is the same one as the docker image — same
endpoints, same auth, same code.

### Option B — self-host with `docker-compose-ghcr.yml`

For a Postgres-backed deployment (closer to production), use the GHCR
image alongside Postgres:

```bash
docker compose -f docker-compose-ghcr.yml up -d
docker compose -f docker-compose-ghcr.yml pull   # bump to latest tag
```

That stack:

- pulls `ghcr.io/haydenrear/skill-manager-server:latest`,
- runs Postgres 16 with a healthcheck so the registry waits for it,
- persists everything under `./data/` (gitignored): `./data/postgres/`
  for the database, `./data/registry/` for the registry root (keystore,
  uploaded tarballs, etc.),
- pre-sets `SKILL_REGISTRY_ALLOW_FILE_UPLOAD=TRUE`, so file-upload
  publishes work out of the box.

Override Postgres credentials, the keystore password, and the CI client
secret via env vars (see the compose file for the full list).

### A note on the embedded auth server and keystore

The registry's auth surface is a real Spring Security OAuth2 authorization
server: PKCE for the CLI's `authorization_code` flow, refresh tokens, JWT
access tokens with RS256 signing, JWKS published at `/oauth2/jwks`. It's
production-shaped — the only caveat worth understanding is the **signing
keystore**.

On first boot the server generates a 2048-bit RSA keypair and persists it
at `${SKILL_REGISTRY_ROOT}/jwt-keystore.p12` (default
`~/.skill-registry/jwt-keystore.p12` locally; `./data/registry/jwt-keystore.p12`
under docker compose). Subsequent boots load the same keypair, so JWTs
stay valid across restarts as long as the registry root is persisted.

What this means in practice:

- **Local + `./data/registry/` mounted** — keystore survives restarts;
  cached `skill-manager login` tokens keep working.
- **Wipe `~/.skill-registry/` (or `./data/registry/`)** — a fresh keypair
  is generated on next boot. Every JWT in the wild is now signed by a
  retired key and is rejected by JWKS validation; users have to re-run
  `skill-manager login`.
- **Productionizing** — you'd typically inject the keystore externally
  rather than rely on auto-generation: set
  `SKILL_REGISTRY_KEYSTORE_PASSWORD` to your real password and pre-seed
  `${SKILL_REGISTRY_ROOT}/jwt-keystore.p12` from a secret store before
  the container starts. Everything else (token issuance, validation,
  rotation by replacing the file and restarting) is already wired.

## Authenticate against the registry

Mutating operations — `publish`, creating campaigns, `ads` — need a
bearer token. Reads (`search`, `show`, `list`, public skill installs)
do not. Both commands below require the registry server to be running
and reachable, and they both accept `--registry <url>` if you haven't
persisted it yet.

### Create an account

```bash
# server must be running first — Option A or B above.
./skill-manager create-account \
    --username my-user \
    --email me@example.com \
    --display-name "My User" \
    --registry http://localhost:8080
# password is prompted (>=10 chars). Use --password=<pw> to skip the prompt
# in scripts; never paste real secrets into shell history.
```

### Log in

```bash
./skill-manager login --registry http://localhost:8080
```

Opens a browser to the registry's authorization endpoint, runs the
PKCE-protected `authorization_code` flow against a loopback callback
on `127.0.0.1:8765`, and writes the resulting access + refresh tokens
to `~/.skill-manager/auth.token`. From then on every authed CLI call
attaches the bearer transparently.

`--no-browser` prints the authorize URL instead of launching a
browser (useful over SSH or in headless containers); paste it into a
local browser, complete the flow, and the loopback callback still
catches the redirect on the machine that ran `login`.

The CLI silently refreshes the access token from the cached refresh
token (7-day TTL) — in steady state you log in once a week. When the
refresh token is also expired or rejected, the CLI exits with code
`7` and a stable banner asking the user to re-run `skill-manager
login`. Subcommands:

```bash
./skill-manager login show     # print the cached identity (/auth/me)
./skill-manager login logout   # forget the cached tokens
```

## Local publish + install round-trip

Once the registry is up locally and the CLI is pointed at it, you can
package a skill on disk and round-trip it through the registry without
touching GitHub:

```bash
# 1. (Once per shell) start the local registry with file upload enabled
SKILL_REGISTRY_ALLOW_FILE_UPLOAD=TRUE ./skill-manager-server &

# 2. Authenticate (publish needs a bearer token; install doesn't).
#    Skip if you've already created an account and logged in.
./skill-manager create-account \
    --username my-user --email me@example.com \
    --registry http://localhost:8080
./skill-manager login --registry http://localhost:8080

# 3. Publish a local skill directory as a tarball into the registry
./skill-manager publish ../hyper-experiments-skill \
    --upload-tarball \
    --registry http://localhost:8080

# 4. Install it back by name from a fresh cwd
./skill-manager install hyper-experiments \
    --registry http://localhost:8080
```

Swap `hyper-experiments` for any skill name. The `--upload-tarball`
flag uses the multipart backend that `SKILL_REGISTRY_ALLOW_FILE_UPLOAD`
enables; without it, `publish` defaults to a github-pointer publish that
needs a real remote.

Once you've persisted the registry URL (`skill-manager registry set
http://localhost:8080`), the `--registry` flag becomes optional.

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

Most modifying commands take `--registry <url>` to override the
persisted registry for one invocation (typically
`--registry http://localhost:8080` while developing against a local
`./skill-manager-server`).

### Skill management

| Command | Purpose |
| --- | --- |
| `skill-manager list` | List installed skills |
| `skill-manager install <name>[@version]` | Install from the registry |
| `skill-manager install <./path>` | Install from a local directory |
| `skill-manager install github:user/repo` | Install from a git repo |
| `skill-manager install file:<path>` | Install from an absolute local path |
| `skill-manager sync [<name>]` | Re-run install side effects (MCP register, agent symlinks) without re-fetching |
| `skill-manager upgrade <name> \| --all` | Upgrade installed skills; rolls back on install failure |
| `skill-manager upgrade --self` | Upgrade the CLI itself via `brew upgrade haydenrear/skill-manager/skill-manager` |
| `skill-manager uninstall <name>` | Full uninstall — store entry, all agent symlinks, orphan MCP servers |
| `skill-manager remove <name>` | Lower-level: remove the store entry only |
| `skill-manager show <name>` | Metadata + deps |
| `skill-manager deps [name]` | Transitive dep tree |

### Registry (publish / search / server config)

| Command | Purpose |
| --- | --- |
| `skill-manager registry set <url>` | Persist the registry URL |
| `skill-manager registry status` | Reachability |
| `skill-manager publish [<path>]` | Package + upload (path defaults to CWD) |
| `skill-manager publish [<path>] --upload-tarball` | Multipart-publish into a `SKILL_REGISTRY_ALLOW_FILE_UPLOAD=TRUE` server |
| `skill-manager publish --dry-run` | Just build the tarball |
| `skill-manager search "<query>"` | Lexical search |

### Authentication

| Command | Purpose |
| --- | --- |
| `skill-manager create-account --username <u> --email <e>` | Register a new user (server must be running) |
| `skill-manager login` | Browser PKCE flow; cache tokens at `~/.skill-manager/auth.token` |
| `skill-manager login --no-browser` | Print the authorize URL instead of opening a browser |
| `skill-manager login show` | Print the cached identity (`/auth/me`) |
| `skill-manager login logout` | Forget the cached tokens |

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
