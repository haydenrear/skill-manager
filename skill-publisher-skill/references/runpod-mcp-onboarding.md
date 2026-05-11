# Case study — onboarding `hyper-experiments-skill` with runpod-mcp

This is a worked example of the manifest-authoring side of the
skill-publisher workflow: take an existing directory of agent docs
(`hyper-experiments-skill`), declare its dependencies in
`skill-manager.toml`, register an npm-distributed MCP server
(`@runpod/mcp-server`) with the local **virtual MCP gateway**, and
validate the install round-trip in a clean Docker container.

The interesting part is the *manifest*. Distribution (push to GitHub
and run `skill-manager install github:…`) and the optional registry
publish are both straightforward; this document focuses on the two
manifest patterns that needed first-class support to land this skill:
**npm-distributed MCP servers** and **host-env passthrough for
secrets**. Both are features of the local virtual gateway, not of any
remote service.

The artifacts referenced below all live in this monorepo:

- `hyper-experiments-skill/SKILL.md` — agent-facing spec (already
  existed before onboarding; nothing to change).
- `hyper-experiments-skill/skill-manager.toml` — added during
  onboarding (the file at the heart of this case study).
- `hyper-experiments-skill/Dockerfile.skill-test` +
  `hyper-experiments-skill/scripts/skill_test.sh` — the round-trip
  validator.
- `libs/skill-manager/virtual-mcp-gateway/gateway/provisioning.py` — the
  gateway change that made host-env passthrough possible.

## What was new

Two patterns surfaced during this onboarding that weren't covered by the
hello-skill example:

1. **An MCP server distributed only as an npm package** (no docker
   image, no precompiled binary). Specifically `@runpod/mcp-server`,
   which expects to be invoked as `npx -y @runpod/mcp-server@latest`
   over stdio.
2. **A required secret that should not live in the manifest**.
   `RUNPOD_API_KEY` must reach the spawned process, but burning it into
   `skill-manager.toml` would publish it to every operator.

The solutions:

1. **Use the `npm` load type.** skill-manager grew first-class support
   for npm-distributed MCP servers (alongside docker, binary, uv, and
   shell). The manifest declares the package name and the gateway
   spawns `npx -y <package>@<version>` over stdio, using the bundled
   Node skill-manager already installs for `npm:` CLI deps:
   ```toml
   load = { type = "npm", package = "@runpod/mcp-server", version = "latest" }
   ```
   When the skill is installed, `McpWriter.ensureGatewayPrerequisites`
   runs `PackageManagerRuntime.ensureBundled("npx")` so
   `$SKILL_MANAGER_HOME/pm/node/current/bin/npx` exists before the
   first deploy. The same bundled Node serves both CLI deps and MCP
   loads — no duplicate Node installs.

2. **Use empty-value env entries as host-env passthrough.**
   ```toml
   load = { ..., env = { RUNPOD_API_KEY = "" } }
   ```
   In the gateway provisioner, an empty-value env entry is resolved
   from the gateway process's environment at registration time
   (`_materialize_env_passthrough` in `provisioning.py`). Operators
   export the secret in the shell that runs `skill-manager gateway
   up`; nothing sensitive is committed.

`init_schema` for `RUNPOD_API_KEY` is still declared with `required =
true` and `secret = true` — even though the value flows through the
process environment rather than `init_values`, the schema entry is the
canonical place for an operator to discover the requirement.

### When to choose which load type

| Load type | When to use | Bundled by skill-manager? |
|-----------|-------------|---------------------------|
| `docker`  | Vendor publishes a container image | No — docker must be on PATH (skill-manager logs an error if missing) |
| `npm`     | Vendor publishes an npm package (e.g. `@vendor/mcp-server`) | Yes — `pm.ensureBundled("npx")` runs at install time |
| `uv`      | Vendor publishes a Python package (e.g. `tb-query-mcp`) | Yes — `pm.ensureBundled("uv")` runs at install time |
| `binary`  | Vendor publishes pre-built binaries per platform | Self-contained; provisioner downloads the archive |
| `shell`   | None of the above; you have a local script or self-built executable | No — `command[0]` must already be reachable |

Prefer the most specific type that fits. `shell` is an escape hatch:
it declares no prerequisites, so a missing executable becomes an
opaque deploy-time error rather than a clear install-time one. Plan
output flags `shell` loads as DANGER for the same reason.

## Manifest, annotated

The full file is `hyper-experiments-skill/skill-manager.toml`. Key
sections:

```toml
[[cli_dependencies]]
spec = "pip:tb-query"
on_path = "tb-query"
```

`tb-query` is a pure-Python CLI on PyPI. The pip backend lands the
binary under `$SKILL_MANAGER_HOME/bin/cli/tb-query` after install.
`on_path` is the binary name skill-manager checks for after the install
step; if it isn't present, the install marks the dep as failed.

```toml
[[mcp_dependencies]]
name = "runpod"
display_name = "RunPod"
description = "Manage RunPod GPU pods, serverless endpoints, and storage."
default_scope = "global-sticky"
load = { type = "npm", package = "@runpod/mcp-server", version = "latest", env = { RUNPOD_API_KEY = "" } }
init_schema = [
    { name = "RUNPOD_API_KEY", type = "string", required = true, secret = true, description = "RunPod API key from https://www.runpod.io/console/user/settings." },
]
```

`default_scope = "global-sticky"` is the right choice for runpod
because (a) the API key is global to the user, not per-session, and
(b) we want the registration to survive gateway restarts. The agent can
still override at deploy time.

## Validation — what to look for after install

Install from the git source (no registry needed):

```bash
skill-manager install github:<owner>/hyper-experiments-skill
```

— or from a local checkout, if you're authoring against this skill:

```bash
skill-manager install /path/to/hyper-experiments-skill
```

After install:

1. `skill-manager list` — the slug `hyper-experiments` appears.
2. `skill-manager show hyper-experiments` — the full manifest is
   resolved and printed; `cli_dependencies` shows tb-query as
   installed; `mcp_dependencies` shows runpod as registered.
3. `$SKILL_MANAGER_HOME/skills/hyper-experiments/SKILL.md` — the
   agent-facing copy.
4. `$SKILL_MANAGER_HOME/bin/cli/tb-query` — the CLI binary.
5. `browse_mcp_servers` over MCP (against the local virtual gateway) —
   runpod is in the result with the right `default_scope`.
6. `describe_mcp_server runpod` — the load spec is preserved verbatim,
   `init_schema` carries the `RUNPOD_API_KEY` description, and the
   server is registered but not yet deployed.

## What was NOT tested in the docker round-trip

- **Actual deployment of runpod-mcp** with a real API key. The docker
  test verifies registration only. Deploying would require:
  - `RUNPOD_API_KEY` exported in the gateway's environment,
  - docker-in-docker (the gateway runs `docker run` to spawn the
    container, which doesn't work inside a docker container without
    mounting `/var/run/docker.sock` or running rootless DinD),
  - network access to npm + the runpod API.

  None of these is hard to add — they were excluded only because the
  user's task was "register and validate compatibility", not "exercise
  end-to-end runpod calls". When you do extend the test, mount
  `/var/run/docker.sock:/var/run/docker.sock` into the test container
  and pass `RUNPOD_API_KEY` via `--env`.

- **Cross-platform CLI install for tb-query.** The pip backend is
  platform-independent, so the only thing that varies is the Python
  interpreter the bundled `uv` finds. Test only ran on linux-x64.

## Generalizing

To onboard another skill that needs an npm-distributed MCP server:

1. Copy `hyper-experiments-skill/skill-manager.toml` as the starting
   template.
2. Replace `[skill].name`, `[skill].version`, the cli_dependencies, and
   the mcp_dependencies block.
3. For npm-distributed MCP, change the package name in `args = ["-y",
   "@vendor/mcp-server@latest"]`. Pin a version (`@1.2.3`) if the
   server's surface is unstable.
4. For each secret, add an `env = { KEY = "" }` entry to `load.env`
   AND a corresponding `init_schema` entry with `secret = true`.
5. Run the docker test (adapt `scripts/skill_test.sh`'s `SKILL_DIR` /
   `SKILL_NAME` env vars).

## Pull-through requirements

The host-env passthrough behavior depends on the gateway provisioner
treating empty-value env entries as `-e KEY` rather than `-e KEY=`.
This was added in `provisioning.py:_provision_docker`. If you fork an
older revision of `virtual-mcp-gateway`, port the change forward or use
a non-empty placeholder value (which will become a literal env value in
the container — wrong if the value is supposed to be a secret).
