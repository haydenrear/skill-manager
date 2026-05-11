---
name: skill-publisher
description: Author a skill or plugin so skill-manager can install it. Covers the two-file contract (SKILL.md + skill-manager.toml), the plugin layout (skill-manager-plugin.toml + .claude-plugin/plugin.json + skills/<contained>/), all five CLI-dependency backends including skill-script (private CLIs built from inside the skill itself), MCP dependencies registered with the local virtual-mcp-gateway, and the distribution model — your unit IS a git repo and installs over `skill-manager install github:owner/repo` / `git+https://…` / a local path. The skill-manager-server registry is optional and only matters for centralized search. Use whenever the user wants to make a directory installable by skill-manager, add a CLI / MCP dep to an existing manifest, scaffold a plugin, or wire a private CLI in via skill-script.
---

# skill-publisher

Use this skill when the user wants to turn a directory of agent docs and
tooling into something **skill-manager can install**. The job has three
moving parts:

1. **Manifest authoring** — write `SKILL.md` + `skill-manager.toml` (for
   a skill) or `.claude-plugin/plugin.json` + `skill-manager-plugin.toml`
   + `skills/<contained>/` (for a plugin) so skill-manager knows the
   unit's deps, version, and shape.
2. **Distribution** — push it to a git repo. `skill-manager install
   github:owner/repo` (or `git+https://…`, or a local path) clones the
   bytes directly. **No server required.** If you also want the unit to
   show up in `skill-manager search`, a skill-manager-server registry is
   one extra `skill-manager publish` step at the end — see the sidebar.
3. **Validation** — `skill-manager publish --dry-run` to preview the
   tarball locally, then `skill-manager install <source>` from a fresh
   working directory to prove the round-trip.

The virtual MCP gateway — the local process `skill-manager gateway up`
runs that fronts every MCP server you declare — is in scope: declaring
an MCP dep is how you get a server registered. The skill-manager-server
(the central metadata registry) is **out of scope** here except for the
optional publish step.

## When to use

- The user has a directory (a tools repo, a research project, an MCP
  helper) and wants `skill-manager install` to handle it.
- The user wants to bundle several related skills as a plugin so the
  harness loads them as one capability.
- The user wants to ship a CLI that can't go on pip / npm / brew (e.g.
  a private repo they have to clone-and-build) — that's what the
  `skill-script` backend is for.
- The user wants to add a new MCP server to the virtual gateway —
  the only way to register one is by declaring it in a unit's manifest
  and installing the unit.
- The user wants a clean-room test (Docker, fresh shell, etc.) of the
  install-from-git round-trip.
- Something is wrong with publish or install and you need to map the
  failure back to a manifest field.

If the user already has a `skill-manager.toml` and just wants to push a
new version to a registry, jump straight to the **Optional: publish to a
registry** sidebar below — no authoring work needed.

## Two unit kinds

skill-manager treats two shapes as installable units:

| Kind | When to use | Marker on disk |
|---|---|---|
| **Skill** | One self-contained capability. The common case. | `SKILL.md` at the root |
| **Plugin** | A bundle of one or more skills with shared metadata, hooks, commands, agents, or MCP deps. The harness (claude / codex) treats it as a single registration unit. | `.claude-plugin/plugin.json` at the root |

When in doubt, write a **skill**. Reach for a plugin only when you have
either (a) multiple related skills that should ship and version
together, or (b) hooks / commands / agents the harness's plugin
machinery needs to register.

## Skills: the two-file contract

A skill is any directory containing both:

| File | Purpose | Audience |
|---|---|---|
| `SKILL.md` | Agent-facing spec (frontmatter + body) | The agent runtime |
| `skill-manager.toml` | Tooling metadata (deps, version, refs) | skill-manager only |

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
    "some-published-skill@1.2.0",       # registry lookup (only resolves
                                         # when a registry is configured)
    "file:./sub-skill",                  # local dir (relative to this skill)
    "github:owner/sub-skill-repo",       # another git repo
]

[[cli_dependencies]]
spec = "pip:my-tool==1.4.0"              # backend:package[==version]
on_path = "my-tool"                      # binary name to check
min_version = "1.4.0"                    # optional version constraint

[[cli_dependencies]]
spec = "tar:rg"                          # download + extract per-platform
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

[[cli_dependencies]]                     # skill-script — see section below
spec = "skill-script:my-private-cli"
on_path = "my-private-cli"
[cli_dependencies.install.any]
script = "build-and-install.sh"          # path under <skill>/skill-scripts/
binary = "my-private-cli"

[[mcp_dependencies]]
name = "my-mcp"
display_name = "My MCP"
description = "What this MCP server does."
default_scope = "global-sticky"          # session | global | global-sticky
load = { type = "docker", image = "ghcr.io/me/my-mcp:latest", args = ["--stdio"] }
init_schema = [
    { name = "API_KEY", type = "string", required = true, secret = true, description = "…" },
]

[skill]
name = "my-skill"
version = "0.1.0"
```

## Plugins: layout

A plugin is a directory whose root carries `.claude-plugin/plugin.json`.
That file is the **only** thing skill-manager checks to decide "this is
a plugin, not a skill". The full layout:

```
my-plugin/
├── .claude-plugin/
│   └── plugin.json                    # REQUIRED — Claude Code's runtime manifest
├── skill-manager-plugin.toml          # OPTIONAL — skill-manager's sidecar
└── skills/
    ├── first-contained-skill/
    │   ├── SKILL.md
    │   └── skill-manager.toml
    └── second-contained-skill/
        ├── SKILL.md
        └── skill-manager.toml
```

**`plugin.json`** is owned by the harness (Claude Code). Minimum shape:

```json
{
  "name": "my-plugin",
  "version": "0.1.0",
  "description": "Short blurb."
}
```

**`skill-manager-plugin.toml`** is optional. When present, it can declare
plugin-level `[[cli_dependencies]]` / `[[mcp_dependencies]]` / `references`
that get unioned with every contained skill's deps at install time, so
the install pipeline registers them all in one pass. Keep it slim
unless the plugin genuinely has its own deps:

```toml
[plugin]
name = "my-plugin"
version = "0.1.0"
description = "Short blurb."

# Plugin-level deps are rare; usually the contained skills carry them.
# [[cli_dependencies]] / [[mcp_dependencies]] / references = [...]
# are all valid here with the same shape as in skill-manager.toml.
```

The `[plugin].name` + `version` must match `plugin.json`'s `name` +
`version`. skill-manager warns at install time if they drift.

**Contained skills inside a plugin are not separately addressable.**
`skill-manager install <contained-skill-name>` fails after the parent
plugin is installed; the contained skill ships and uninstalls only
through the plugin. Don't try to register a contained skill in a
registry as if it were a top-level skill — its identity belongs to the
plugin.

Worked example: `examples/hello-plugin/` in the skill-manager repo —
plugin.json + minimal skill-manager-plugin.toml + one contained skill.

For deeper plugin authoring detail (when to bundle vs split, harness
registration semantics, hooks/commands/agents), see
[`references/plugins.md`](references/plugins.md).

## CLI dependency backends

Every `[[cli_dependencies]]` entry's `spec = "<backend>:<rest>"` chooses
how the binary lands in `$SKILL_MANAGER_HOME/bin/cli/`. Five backends
are supported:

| Prefix | Backend | When to use | Severity in plan |
|---|---|---|---|
| `pip:` | bundled `uv` | Python-distributed CLIs | WARN |
| `npm:` | bundled `node`/`npm` | Node-distributed CLIs | WARN |
| `brew:` | Homebrew (macOS/Linux) | OS-managed binaries | WARN |
| `tar:` | direct download + extract | Pre-built binaries from a known URL | NOTICE if sha256, DANGER without |
| `skill-script:` | run a script bundled inside the skill | Private CLIs you have to build yourself | DANGER |

For a `tar:` entry, every supported platform needs its own
`[cli_dependencies.install.<platform>]` block. Common keys:
`darwin-arm64`, `darwin-x64`, `linux-x64`, `linux-arm64`. If the
install block for the current platform is missing, install fails with
a clear error.

### skill-script — private CLIs built from inside the skill

`skill-script:` is the escape hatch for CLIs you can't publish to pip /
npm / brew / a public tarball — a private repo you have to clone-and-
build, a binary generated by code that ships with the skill itself.
The skill carries an install script under a conventional
`skill-scripts/` directory; skill-manager runs it and the script is
responsible for landing a binary under `$SKILL_MANAGER_BIN_DIR`.

Manifest shape:

```toml
[[cli_dependencies]]
spec = "skill-script:my-private-cli"
on_path = "my-private-cli"

[cli_dependencies.install.any]
script = "build-and-install.sh"       # path under <skill>/skill-scripts/
binary = "my-private-cli"              # optional — verified after script exits
args = ["--prefix", "$SKILL_MANAGER_BIN_DIR"]   # optional
```

Skill layout:

```
my-skill/
├── SKILL.md
├── skill-manager.toml
└── skill-scripts/
    └── build-and-install.sh           # script declared above
```

The script receives these env vars and is expected to drop a binary
into `$SKILL_MANAGER_BIN_DIR` (typically by cloning a private repo,
building, then `cp` or `install`):

| Env var | Value |
|---|---|
| `SKILL_MANAGER_BIN_DIR` | `$SKILL_MANAGER_HOME/bin/cli/` — write your binary here |
| `SKILL_DIR` | The installed skill's root in the store |
| `SKILL_SCRIPTS_DIR` | `$SKILL_DIR/skill-scripts/` — where your script's sibling files live |
| `SKILL_NAME` | The skill name |
| `SKILL_MANAGER_HOME` | The store root |
| `SKILL_MANAGER_CACHE_DIR` | Scratch space — safe to clone into |
| `SKILL_PLATFORM` | `darwin-arm64`, `linux-x64`, etc. |

**Security model**: runs arbitrary shell from the skill, so plan output
flags `skill-script` deps as `DANGER`. The policy file's
`allowed_backends` list gates it — installs from skills declaring
`skill-script:` deps are blocked when the operator has stripped
`skill-script` from `allowed_backends` in `~/.skill-manager/policy.toml`.

**Re-run semantics** — this is the subtle part:

- skill-manager computes a SHA-256 fingerprint over every byte under
  `<skill>/skill-scripts/`, plus the `script` field and the `args`
  list, and persists it in `cli-lock.toml` after a successful run.
- On the next install / sync / upgrade, if the fingerprint matches AND
  the declared `binary` is still present, the script is skipped (no
  unnecessary rebuild).
- If anything under `skill-scripts/` changed — including the script
  itself or any sibling file the script sources — the fingerprint
  flips and the script reruns. Edits to `SKILL.md` or other parts of
  the skill do **not** trigger a CLI rebuild.
- If the declared binary is manually deleted from `bin/cli/`, the
  script reruns (recovery path).
- `uninstall` + `install` currently does **not** rerun the script —
  uninstall doesn't prune `bin/cli/` today, so the binary lingers and
  the fingerprint still matches. Force a rebuild by editing the script
  or removing the binary.

Path safety: `script` is resolved relative to `<skill>/skill-scripts/`
and `..` traversal is rejected, so a manifest can't reach outside the
scripts dir.

Deep dive — env vars, fingerprint mechanics, idempotency rules, and
worked recipes for cloning-and-building: see
[`references/skill-scripts.md`](references/skill-scripts.md).

## MCP dependencies

`[[mcp_dependencies]]` entries get registered with the **virtual MCP
gateway** — a local process the user's CLI runs (`skill-manager
gateway up`). It is not a remote service; it lives entirely on the
operator's machine and is the single MCP endpoint every agent's MCP
config points at. Registering a server with the gateway is the only
way to expose MCP tools through skill-manager.

Five `load` types are supported by the provisioner; pick the most
specific match for your server:

**docker** — vendor publishes a container image.
```toml
load = { type = "docker", image = "<image>", args = [...], command = [...], env = { KEY = "value-or-blank-for-passthrough" } }
```
- Gateway runs `docker run -i --rm -e KEY=value … <image> <command…> <args…>` for stdio servers.
- Set `transport = "streamable-http"` + `url = "…"` for HTTP-only docker servers (gateway fronts the URL instead of spawning the container).
- `docker` must be on the gateway's PATH; skill-manager logs an error at install time if it's missing.

**npm** — vendor publishes an npm package (`@vendor/mcp-server`).
```toml
load = { type = "npm", package = "@vendor/mcp-server", version = "latest", args = [...], env = { API_KEY = "" } }
```
- Gateway spawns `npx -y <package>@<version> <args…>` over stdio.
- skill-manager bundles Node automatically at install time, so the gateway does not depend on a system Node install.

**uv** — vendor publishes a Python package (PyPI).
```toml
load = { type = "uv", package = "tb-query-mcp", version = "1.0.0", entry_point = "tb-query-mcp", args = [...], env = { ... } }
```
- Gateway spawns `uv tool run --from <pkg>==<ver> <entry_point> <args…>` over stdio (or `uv tool run <pkg> <args…>` if no version is pinned).
- `entry_point` is optional — defaults to the package name.

**binary** — vendor publishes pre-built binaries per platform.
```toml
load = { type = "binary", transport = "stdio", install = { darwin-arm64 = { url = "…", archive = "tar.gz", binary = "bin/server" } } }
```
- Provisioner downloads + extracts the archive and runs the binary over stdio.
- Use `transport = "streamable-http"` + `url = "…"` to skip the download and treat the entry as a remote HTTP MCP server.

**shell** — escape hatch for a local script or self-built executable.
```toml
load = { type = "shell", command = ["my-mcp-script.sh", "--mcp"], env = { ... } }
```
- Gateway runs `command` verbatim. No bundling, no resolution, no PATH magic.
- Plan output flags `shell` loads as DANGER because skill-manager can't validate the prerequisites.

### Host-env passthrough (for secrets)

Across all load types, an `env` entry with an empty-string value is
treated as **host-env passthrough**: the gateway pulls the value from
its own process environment at registration time. Use this for secrets
that should not be hard-coded in the manifest:

```toml
load = { ..., env = { API_KEY = "" } }   # gateway forwards $API_KEY
```

Operators export the secret in the shell that runs `skill-manager
gateway up`. Pair with an `init_schema` entry naming the same key
(with `secret = true`) so the requirement is discoverable.

### init_schema

`init_schema` declares parameters the agent supplies at deploy time
(via the gateway's `deploy_mcp_server` virtual tool). Each field:

```toml
{ name = "API_KEY", type = "string", required = true, secret = true, description = "…" }
```

- `secret = true` causes the gateway to redact the value when describing the deployment.
- `required = true` makes the agent prompt for the value if it isn't already cached for the chosen scope.
- For docker / npm / uv `load.env` entries with empty values (host-env passthrough), declaring the same name in `init_schema` documents the requirement to operators even though the value flows through the process environment, not through `init_values`.

For a worked end-to-end example of MCP authoring — including the npm
load type and host-env passthrough — see
[`references/runpod-mcp-onboarding.md`](references/runpod-mcp-onboarding.md).

## Distribution: your unit is its own git repo

`skill-manager install <source>` accepts several source forms. They
are equivalent — they all clone-or-copy the unit's bytes into the
store; the difference is purely where the bytes come from. **No
registry is involved unless you point at one explicitly.**

| Source form | Example | When to use |
|---|---|---|
| `github:owner/repo` | `skill-manager install github:haydenrear/my-skill` | The skill lives at the root of a GitHub repo (the common case) |
| `git+https://…` / `git+ssh://…` / `…/repo.git` | `skill-manager install git+https://gitlab.internal/me/skill.git` | Non-GitHub git remotes, including private internal hosts |
| `file://<abs>` or a relative path | `skill-manager install ./my-skill` | Local development; mounted volume; clean-room test |
| `<name>[@<version>]` | `skill-manager install ripgrep-skill@1.2.0` | Look up the name in a registry (only when one is configured) |

The first three need **no** skill-manager-server. The CLI clones / copies
the bytes, parses the manifest, runs the install pipeline. Distribution
== `git push` to a remote your operators can reach.

### One unit per repo

The recommended layout: a skill (or plugin) sits at the **root** of its
own git repo, alongside the rest of the repo's contents. Example:

```
my-skill-repo/                          # github.com/owner/my-skill-repo
├── SKILL.md                            # skill-manager looks here
├── skill-manager.toml
├── skill-scripts/                      # if you use skill-script:
│   └── install.sh
├── README.md                           # repo-level docs (not the skill spec)
└── …other files in the repo…
```

`skill-manager install github:owner/my-skill-repo` clones the repo and
detects the skill at its root because `SKILL.md` is there. The skill's
*identity* (its `name`) comes from the manifest, not the repo name —
the repo can be named anything.

A repo can host more than one skill via the `git+https://…#subdir=…`
escape hatch, but it's friction for both the publisher (version
coupling) and the consumer (extra typing). Prefer one unit per repo.

### `skill-manager onboard` — bundled skills, same mechanism

`skill-manager onboard` is a one-shot bootstrap that installs the two
bundled units (`skill-manager-skill` and `skill-publisher-skill`)
fresh. By default it installs them from
`github:haydenrear/skill-manager-skill` and
`github:haydenrear/skill-publisher-skill` — exactly the same install
path any other skill uses. Pass `--install-dir <path>` to install from
local working trees instead (in-tree dev / tests).

### Sidebar — Optional: publish to a registry for centralized search

The skill-manager-server is a **separate component** from the CLI. It
exists for one reason: a central place to search across skills and
plugins published by many authors (`skill-manager search "<query>"`).
You only need it if:

- You want the unit to appear in `skill-manager search` for users who
  haven't memorized the repo name.
- Your org wants centralized metadata (versions, ownership, audit).

To publish:

```bash
skill-manager registry status        # confirm a server is configured
skill-manager login                  # one-time auth
skill-manager publish <skill-dir> --dry-run   # preview the tarball
skill-manager publish <skill-dir>             # push metadata + pin git_sha
```

Behind the scenes, modern publish records the unit's git URL + commit
SHA with the registry; install-by-name then clones from your repo
directly. The registry holds metadata only — it is not the source of
truth for the bytes, your git repo is. **Publishing is purely opt-in
for discoverability.** Operators who already know the install string
(`github:you/your-skill`) need no registry at all.

If the registry rejects the publish, the most common causes are:
- Name collision (someone else owns the slug).
- Version already published (bump `[skill].version` and retry).
- Auth refused (`ACTION_REQUIRED: skill-manager login` — relay verbatim to the user; the browser flow needs them).

## Onboarding workflow

### 1. Decide the skill / plugin identity

Pick a slug-style name. If you plan to publish to a registry, confirm
it's available:

```bash
skill-manager search "<name>"
skill-manager registry describe <name>   # 404 = available
```

If you're not publishing, the name only needs to be unique in the local
store — two skills with the same name can't coexist.

### 2. Author `SKILL.md` (skills) or `plugin.json` + skills (plugins)

For a skill: frontmatter `name` + `description`. Body: when to use,
recipes, operating rules. The description is the single most important
field — it is what the agent runtime uses to decide whether to load
the skill. Lead with concrete trigger phrases.

For a plugin: write `.claude-plugin/plugin.json` (`name`, `version`,
`description`), then author each contained skill under
`skills/<contained>/`.

### 3. Author the manifest(s)

Start from the in-tree examples:

```bash
# Skills
cp examples/hello-skill/skill-manager.toml <my-skill>/skill-manager.toml

# Plugins
cp -r examples/hello-plugin <my-plugin>
```

Cross-check:

- Top-level arrays (`skill_references`, `[[cli_dependencies]]`, `[[mcp_dependencies]]`) come **before** `[skill]` / `[plugin]`.
- Every `[[cli_dependencies]]` entry has both `spec` and `on_path`.
- Every `[[mcp_dependencies]]` entry has `name` and exactly one `load` block.
- `[skill].name` matches `SKILL.md`'s frontmatter `name` (skills), or `[plugin].name` matches `plugin.json`'s `name` (plugins).

### 4. Validate locally with `--dry-run`

```bash
skill-manager publish <my-skill> --dry-run
```

Produces a tarball under `~/.skill-manager/cache/publish/`, prints
sha256 + size + the included file list. Read the file list: anything
dotfile-ish is excluded automatically, but stray `node_modules/` or
`.venv/` directories will inflate the tarball — move them outside the
skill dir, or delete.

### 5. Push to a git remote

```bash
cd <my-skill>
git init && git add -A && git commit -m "initial"
git remote add origin git@github.com:<owner>/<repo>.git
git push -u origin main
```

### 6. Validate the install round-trip

From a **fresh working directory** (not the skill source), install via
the git source:

```bash
cd /tmp/work
skill-manager install github:<owner>/<repo>
skill-manager show <name>
skill-manager list
```

`show` prints the manifest as-resolved; `list` should include the new
slug. Then verify each declared dependency landed:

- CLI: `ls $SKILL_MANAGER_HOME/bin/cli/<binary>` (or run the binary).
  Use `<skill-manager-skill>/scripts/env.sh --skills <my-skill>` to get
  absolute paths without PATH conflicts.
- MCP: call `browse_mcp_servers` against the local gateway. The new
  server should be in the result with the right `default_scope`.

### 7. (Optional) Publish to a registry

Only if you want centralized search. See the **Sidebar** above.

## Where things land

```
$SKILL_MANAGER_HOME/                    # default ~/.skill-manager
  skills/<name>/                        # skill install location
    SKILL.md
    skill-manager.toml
    skill-scripts/                      # if the skill uses skill-script
  plugins/<name>/                       # plugin install location
    .claude-plugin/plugin.json
    skill-manager-plugin.toml
    skills/<contained>/…
  bin/cli/<binary>                      # every CLI dep (all 5 backends) lands here
  cli-lock.toml                         # CLI version pins + skill-script fingerprints
  cache/                                # publish staging + downloads
  registry.properties                   # active registry URL (if any)
  gateway.properties                    # active gateway URL
  auth.token                            # OAuth bearer cache (registry-only)
```

The two files most likely to surprise during onboarding:

- `cli-lock.toml` — if your skill pins a CLI version that conflicts
  with another installed skill's pin, install fails with `CONFLICT
  [pip] <tool>`. Resolve by aligning versions or removing the offending
  lock row. For `skill-script:` deps, the lock also stores an
  `install_fingerprint` that drives the re-run gate (see
  `references/skill-scripts.md`).
- `auth.token` — short-lived; CLI refreshes silently from the cached
  refresh token. Only matters if you use a registry. When refresh
  expires, CLI exits 7 with an `ACTION_REQUIRED: skill-manager login`
  banner — relay it to the user verbatim.

## Boundaries

- This skill **does not** publish anything itself. It explains how, and
  the agent runs `skill-manager publish` (or `git push`) when ready.
  Always preview with `--dry-run` and read the planned upload first.
- This skill **does not** modify `~/.skill-manager/cli-lock.toml`,
  `policy.toml`, or `registry.properties` — those are operator
  decisions. Surface conflicts and let the user decide.
- This skill **does not** generate API keys or other secrets. When a
  manifest declares `init_schema` fields with `secret = true`, the
  agent must prompt the user, never invent values.
- This skill **does not** cover skill-manager-server operations (`brew
  install skill-manager-server`, running postgres, hosting a registry).
  See the main skill-manager README if the user wants to *run* a
  registry rather than just publish to one.

## Cross-references

- `examples/hello-skill/skill-manager.toml` — canonical reference manifest with CLI + MCP dependencies (in the skill-manager repo).
- `examples/hello-plugin/` — canonical plugin layout with plugin.json + skill-manager-plugin.toml + a contained skill.
- `skill-manager-skill/SKILL.md` — runtime CLI usage (search, install, gateway lifecycle, login). Pair this skill with that one when working on a skill that exercises both authoring and runtime concerns.
- [`references/skill-scripts.md`](references/skill-scripts.md) — deep dive on the `skill-script:` CLI backend: env vars, fingerprint mechanics, idempotency rules, recipes.
- [`references/plugins.md`](references/plugins.md) — deep dive on plugin authoring: when to bundle vs split, harness registration semantics, hooks / commands / agents.
- [`references/runpod-mcp-onboarding.md`](references/runpod-mcp-onboarding.md) — case study walking through the hyper-experiments-skill onboarding, including the npm MCP load type and host-env passthrough for secrets.
