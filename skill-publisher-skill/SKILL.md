---
name: skill-publisher
description: Onboard a directory to skill-manager — declare CLI deps, MCP servers, and skill references in skill-manager.toml; verify with --dry-run; publish to the registry; install from another working directory to validate the round-trip. Use when the user wants to make a skill installable via skill-manager, add MCP server compatibility, or write a Dockerized end-to-end publish/install test.
---

# skill-publisher

Use this skill when the user wants to take an existing directory of agent
docs and tooling and turn it into a **skill-manager-installable skill**:
declare its dependencies, validate the manifest, publish it to a
registry, and confirm the install works from a clean working directory.

The reference implementation of this onboarding flow is
`hyper-experiments-skill` (sibling repo, parent of this `libs/skill-manager`
checkout). When in doubt, model new skills on its `SKILL.md` +
`skill-manager.toml` pair.

## When to use

- The user has a directory (a tools repo, a research project, an MCP
  helper) and wants it to be installable via `skill-manager install`.
- The user wants to add a new MCP server to the virtual MCP gateway
  through skill-manager (the only way to register one is by installing
  a skill that declares it).
- The user wants a clean-room test (Docker, fresh shell, etc.) of the
  publish→install round-trip.
- Something is wrong with publish or install and you need to map the
  failure back to a manifest field.

If the user already has a `skill-manager.toml` and just wants to push a
new version, jump straight to the **Publish** recipe below — no
onboarding work needed.

## The two-file contract

A skill-manager-compatible directory is anything containing both:

| File | Purpose | Audience |
|------|---------|----------|
| `SKILL.md` | Agent-facing spec (frontmatter + body) | The agent runtime |
| `skill-manager.toml` | Tooling metadata (deps, version, MCP) | skill-manager only |

The agent runtime never reads `skill-manager.toml`. skill-manager never
parses anything beyond the `name` / `description` frontmatter of
`SKILL.md`. The split is deliberate: structured config doesn't burn
tokens, and skill-manager can evolve its schema without breaking
existing skills' agent prompts.

### `SKILL.md` requirements

```markdown
---
name: my-skill
description: One sentence (or two) describing exactly when an agent should invoke this skill. The runtime uses this to gate skill activation, so be specific about triggers — "use when the user asks to X" beats "general utilities for Y".
---

# My skill

(everything else is freeform agent-facing docs)
```

The frontmatter `name` must be a single token (slug-style:
`hyper-experiments`, not `Hyper Experiments`). It must match
`[skill].name` in `skill-manager.toml`.

### `skill-manager.toml` anatomy

The full structure, with all currently-supported sections:

```toml
# Top-level arrays must come BEFORE the [skill] table — otherwise
# they get scoped under [skill] and skill-manager fails to parse them.

skill_references = [
    "some-published-skill@1.2.0",   # registry lookup
    "file:./sub-skill",              # local dir (relative to this skill)
]

[[cli_dependencies]]
spec = "pip:my-tool==1.4.0"          # backend:package[==version]
on_path = "my-tool"                  # binary name to check
min_version = "1.4.0"                # optional version constraint
# version_check = "my-tool --version"  # optional override

[[cli_dependencies]]
spec = "tar:rg"                      # download + extract per-platform
name = "rg"
on_path = "rg"
[cli_dependencies.install.darwin-arm64]
url = "https://…/rg-darwin.tar.gz"
archive = "tar.gz"
binary = "ripgrep-14/rg"
[cli_dependencies.install.linux-x64]
url = "https://…/rg-linux.tar.gz"
archive = "tar.gz"
binary = "ripgrep-14/rg"

[[mcp_dependencies]]
name = "my-mcp"
display_name = "My MCP"
description = "What this MCP server does."
default_scope = "global-sticky"      # session | global | global-sticky
load = { type = "docker", image = "ghcr.io/me/my-mcp:latest", args = ["--stdio"] }
init_schema = [
    { name = "API_KEY", type = "string", required = true, secret = true, description = "…" },
]

[skill]
name = "my-skill"
version = "0.1.0"
```

### CLI dependency backends

| Prefix | Backend | When to use |
|--------|---------|-------------|
| `pip:` | uv (fallback to pip) | Python-distributed CLIs |
| `npm:` | bundled node/npm | Node-distributed CLIs |
| `brew:` | Homebrew (macOS/Linux) | OS-managed binaries |
| `tar:` | direct download + extract | Pre-built binaries; needs platform `[cli_dependencies.install.<platform>]` blocks |

For a `tar:` entry, every supported platform you care about needs its
own install block. Common keys: `darwin-arm64`, `darwin-x64`,
`linux-x64`, `linux-arm64`. If the install block for the current
platform is missing, install fails with a clear error.

### MCP `load` types

Five are supported by the gateway provisioner; pick the most specific
match for your server:

**docker** — vendor publishes a container image.
```toml
load = { type = "docker", image = "<image>", args = [...], command = [...], env = { KEY = "value-or-blank-for-passthrough" } }
```
- The gateway runs `docker run -i --rm -e KEY=value … <image> <command…> <args…>` for stdio servers.
- Set `transport = "streamable-http"` + `url = "…"` for HTTP-only docker servers (the gateway fronts the URL instead of spawning the container).
- `docker` must be on the gateway's PATH; skill-manager logs an error
  at install time if it's missing (it can't bootstrap docker for you).

**npm** — vendor publishes an npm package (`@vendor/mcp-server`).
```toml
load = { type = "npm", package = "@vendor/mcp-server", version = "latest", args = [...], env = { API_KEY = "" } }
```
- The gateway spawns `npx -y <package>@<version> <args…>` over stdio.
- skill-manager bundles Node automatically at install time (same
  `pm/node/current/bin/npx` it uses for `npm:` CLI deps), so the
  gateway does not depend on a system Node install.

**uv** — vendor publishes a Python package (PyPI).
```toml
load = { type = "uv", package = "tb-query-mcp", version = "1.0.0", entry_point = "tb-query-mcp", args = [...], env = { ... } }
```
- The gateway spawns `uv tool run --from <pkg>==<ver> <entry_point> <args…>` over stdio (or `uv tool run <pkg> <args…>` if no version is pinned).
- `entry_point` is optional — defaults to the package name.
- skill-manager bundles uv automatically at install time (same
  `pm/uv/current/bin/uv` it uses for `pip:` CLI deps).

**binary** — vendor publishes pre-built binaries per platform.
```toml
load = { type = "binary", transport = "stdio", install = { darwin-arm64 = { url = "…", archive = "tar.gz", binary = "bin/server" } } }
```
- The provisioner downloads + extracts the archive and runs the binary over stdio.
- Use `transport = "streamable-http"` + `url = "…"` to skip the download and treat the entry as a remote HTTP MCP server.

**shell** — escape hatch for a local script or self-built executable.
```toml
load = { type = "shell", command = ["my-mcp-script.sh", "--mcp"], env = { ... } }
```
- The gateway runs `command` verbatim. No bundling, no resolution, no
  PATH magic — `command[0]` must already exist on the gateway. Plan
  output flags `shell` loads as DANGER because skill-manager can't
  validate the prerequisites.

#### Host-env passthrough

Across all load types, an `env` entry with an empty-string value is
treated as **host-env passthrough**: the gateway pulls the value from
its own process environment at registration time. Use this for secrets
that should not be hard-coded in the manifest:

```toml
load = { ..., env = { API_KEY = "" } }   # gateway forwards $API_KEY
```

Operators export the secret in the shell that runs `skill-manager
gateway up`; nothing sensitive is committed. Pair with an
`init_schema` entry naming the same key (with `secret = true`) so the
requirement is discoverable.

### init_schema

`init_schema` declares parameters the agent supplies at deploy time
(via `deploy_mcp_server`). Each field:

```toml
{ name = "API_KEY", type = "string", required = true, secret = true, description = "…" }
```

- `secret = true` causes the gateway to redact the value when describing
  the deployment.
- `required = true` makes the agent prompt for the value if it isn't
  already cached for the chosen scope.
- For docker `load.env` entries with empty values (host-env
  passthrough), declaring the same name in `init_schema` documents the
  requirement to operators even though the value flows through the
  process environment, not through `init_values`.

## Onboarding workflow

### 1. Decide the skill identity

Pick a slug-style name. Confirm the registry hasn't claimed it:

```bash
skill-manager search "<name>"
skill-manager registry describe <name>   # 404 = available
```

### 2. Author `SKILL.md`

Frontmatter `name` + `description`. Body: when to use, recipes,
operating rules. The description is the single most important field —
it is what the runtime uses to decide whether to load the skill. Lead
with concrete trigger phrases.

### 3. Author `skill-manager.toml`

Start from the [hello-skill example][1]:

```bash
cp libs/skill-manager/examples/hello-skill/skill-manager.toml \
   <my-skill>/skill-manager.toml
# then edit: change name/version, replace cli/mcp deps with yours
```

Cross-check:

- Top-level arrays (`skill_references`, `[[cli_dependencies]]`,
  `[[mcp_dependencies]]`) come **before** `[skill]`.
- Every `[[cli_dependencies]]` entry has both `spec` and `on_path`.
- Every `[[mcp_dependencies]]` entry has `name` and exactly one
  `load` block.
- `[skill].name` matches `SKILL.md`'s frontmatter `name`.

### 4. Validate with `--dry-run`

```bash
skill-manager publish <my-skill> --dry-run
```

This runs the full publish pipeline locally except the upload — produces
a tarball under `~/.skill-manager/cache/publish/`, prints sha256 + size
+ included files. Read the file list: anything dotfile-ish is excluded
automatically, but stray `node_modules/` or `.venv/` directories will
inflate the tarball (move them outside the skill dir, or delete).

### 5. Publish

```bash
# First time: authenticate against the registry.
skill-manager login

skill-manager publish <my-skill>
# -> hyper-experiments@0.1.0 (sha256=<…>, <bytes> bytes)
```

If the registry rejects the publish, the most common causes are:
- Name collision (someone else owns the slug).
- Version already published (bump `[skill].version` and retry).
- Auth refused (`ACTION_REQUIRED: skill-manager login` — relay verbatim
  to the user; the browser flow needs them).

### 6. Validate the round-trip

From a **fresh working directory** (not the skill source), install:

```bash
cd /tmp/work
skill-manager install <my-skill>
skill-manager show <my-skill>
skill-manager list
```

`show` prints the manifest as-resolved; `list` should include the new
slug. Then verify each declared dependency landed:

- CLI: `ls $SKILL_MANAGER_HOME/bin/cli/<binary>` (or run the binary).
  Use `<skill-manager-skill>/scripts/env.sh --skills <my-skill>` to get
  absolute paths without PATH conflicts.
- MCP: call `browse_mcp_servers` over MCP against the gateway. The new
  server should be in the result with the right `default_scope`.

### 7. Document the install command in `SKILL.md`

Future operators will run something like:

```bash
skill-manager install <my-skill>
```

Including this line in the `SKILL.md` body helps the agent surface it
when asked "how do I add this skill?".

## Recipes

### Publish a fresh version

```bash
# Bump [skill].version in skill-manager.toml first.
skill-manager publish <my-skill> --dry-run
skill-manager publish <my-skill>
```

### Add an npm-distributed MCP server

```toml
[[mcp_dependencies]]
name = "<server-name>"
display_name = "<Display Name>"
description = "…"
default_scope = "global-sticky"
load = { type = "docker", image = "node:22-slim", command = ["npx"], args = ["-y", "@vendor/mcp-server@latest"], env = { VENDOR_API_KEY = "" } }
init_schema = [
    { name = "VENDOR_API_KEY", type = "string", required = true, secret = true, description = "Where to obtain the key (vendor URL)." },
]
```

The `env = { VENDOR_API_KEY = "" }` line uses host-env passthrough:
operators export `VENDOR_API_KEY` in the shell that runs
`skill-manager gateway up`, and docker forwards it into the spawned
container. This avoids embedding secrets in the manifest while keeping
the deploy call argument-free.

### End-to-end test in Docker

Reference implementation: `hyper-experiments-skill/Dockerfile.skill-test`
(at the parent of this `libs/skill-manager` checkout) and
`hyper-experiments-skill/scripts/skill_test.sh`. The test harness:

1. Builds an image with JDK 21, JBang, Python 3.11, uv, Node 20.
2. Brings up postgres + the registry.
3. Authenticates with `skill-manager login --client-id skill-manager-ci …`.
4. Publishes `hyper-experiments-skill` from one directory, then
   `cd /tmp/work` and installs by name.
5. Verifies `skill-manager list` includes the slug, the CLI binary is
   on disk under `$SKILL_MANAGER_HOME/bin/cli/`, and the MCP server is
   registered in the gateway.

Adapt the Dockerfile + script to other skills by changing only the
`SKILL_DIR` and `SKILL_NAME` env vars at the top of the script.

## Where things land

```
$SKILL_MANAGER_HOME/                  # default ~/.skill-manager
  skills/<name>/                      # canonical install location
    SKILL.md
    skill-manager.toml
  bin/cli/<binary>                    # tar/pip/npm-installed CLIs
  cli-lock.toml                       # CLI version pins (conflict source)
  cache/                              # publish staging + downloads
  registry.properties                 # active registry URL
  gateway.properties                  # active gateway URL
  auth.token                          # OAuth bearer cache
```

The two files most likely to surprise during onboarding:

- `cli-lock.toml` — if your skill pins a CLI version that conflicts
  with another installed skill's pin, install fails with `CONFLICT [pip]
  <tool>`. Resolve by aligning versions or removing the offending lock
  row.
- `auth.token` — short-lived; CLI refreshes silently from the cached
  refresh token. When refresh expires (default 7d), CLI exits 7 with
  an `ACTION_REQUIRED: skill-manager login` banner — relay it to the
  user verbatim.

## Boundaries

- This skill **does not** publish anything itself. It explains how, and
  the agent runs `skill-manager publish` when ready. Always preview
  with `--dry-run` and read the planned upload before the real publish.
- This skill **does not** modify `~/.skill-manager/cli-lock.toml` or
  `policy.toml` — those are operator decisions. Surface conflicts and
  let the user decide.
- This skill **does not** generate API keys or other secrets. When a
  manifest declares `init_schema` fields with `secret = true`, the
  agent must prompt the user, never invent values.

## Cross-references

- `libs/skill-manager/README.md` — full skill-manager design overview.
- `libs/skill-manager/skill-manager-skill/SKILL.md` — runtime CLI usage
  (search, install, gateway lifecycle, login).
- `libs/skill-manager/examples/hello-skill/skill-manager.toml` —
  canonical reference manifest with both CLI and MCP dependencies.
- `references/runpod-mcp-onboarding.md` (this skill) — full case study
  walking through the hyper-experiments-skill onboarding, including the
  npm-as-docker pattern and the host-env passthrough trick.

[1]: ../../examples/hello-skill/skill-manager.toml
