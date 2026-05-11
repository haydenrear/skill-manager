# `skill-script:` — author's deep dive

The `skill-script:` CLI backend lets a skill ship its own installer
script, run when the skill is installed, that lands a binary in
`$SKILL_MANAGER_HOME/bin/cli/`. It's the escape hatch for CLIs you
can't publish to pip / npm / brew / a public tarball — typically a
private repo you have to clone-and-build, or a binary the skill itself
knows how to produce.

This document covers what the parent SKILL.md only summarizes:

- Exact manifest shape and field semantics
- Env vars the script receives, in detail
- The fingerprint-based re-run gate (why a script edit re-fires the
  install, but a `SKILL.md` edit does not)
- Idempotency rules across the four flows
  (`install` / `sync` / `upgrade` / `uninstall + install`)
- Security model and policy gating
- Worked recipes

## Manifest shape

```toml
[[cli_dependencies]]
spec = "skill-script:<tool-name>"
on_path = "<tool-name>"        # optional, name to check on $PATH

[cli_dependencies.install.any]
script = "install.sh"          # path under <skill>/skill-scripts/
binary = "<tool-name>"         # optional; verified to exist after script runs
args = ["--prefix", "$SKILL_MANAGER_BIN_DIR"]   # optional
```

Per-platform variants follow the same shape as `tar:` deps — replace
`any` with `darwin-arm64`, `linux-x64`, etc. Platform-specific entries
take precedence over `any`; if no current-platform entry exists, `any`
is used.

### Field semantics

| Field | Required? | What it does |
|---|---|---|
| `script` | yes | Path under `<skill>/skill-scripts/`, resolved against that directory. `..` traversal is rejected outright (security: a manifest can't read or run files outside the scripts dir). |
| `binary` | no | If set, skill-manager verifies `$SKILL_MANAGER_BIN_DIR/<binary>` exists and is executable *after* the script returns 0. Highly recommended — without it, a script that silently does nothing claims success. |
| `args` | no | List of strings passed as argv to the script. Variables like `$SKILL_MANAGER_BIN_DIR` are NOT expanded by skill-manager — the script's shell handles expansion when it dereferences `$1`, `$2`, etc. |

### Skill layout

```
my-skill/
├── SKILL.md
├── skill-manager.toml
└── skill-scripts/
    ├── install.sh                 # the script named in manifest
    └── helpers/                   # sibling files are fine, recursive
        └── build-step.sh
```

Everything under `skill-scripts/` is part of the **fingerprint**
(see below). Don't put random other files in there — they'll trigger
spurious re-runs when edited.

## Env vars passed to the script

| Variable | Value | Use for |
|---|---|---|
| `SKILL_MANAGER_BIN_DIR` | `$SKILL_MANAGER_HOME/bin/cli/` | Drop your binary here (`cp`, `install -m 0755`, `ln -s`). This dir is on the user's PATH. |
| `SKILL_DIR` | The skill's root in the store | Read anything the skill ships (other scripts, embedded data). |
| `SKILL_SCRIPTS_DIR` | `$SKILL_DIR/skill-scripts/` | Source sibling scripts: `source "$SKILL_SCRIPTS_DIR/helpers/build-step.sh"`. |
| `SKILL_NAME` | The skill's name | Logging / diagnostics. |
| `SKILL_MANAGER_HOME` | The store root (default `~/.skill-manager`) | Read other store state if you need it. |
| `SKILL_MANAGER_CACHE_DIR` | `$SKILL_MANAGER_HOME/cache/` | Safe scratch space — clone here, build here, then `install -m 0755 build/out "$SKILL_MANAGER_BIN_DIR/<bin>"`. |
| `SKILL_PLATFORM` | `darwin-arm64` / `linux-x64` / etc. | Branch in the script for cross-platform handling without needing separate `install.<platform>` entries. |

The script's `cwd` is unspecified (don't depend on it). Use absolute
paths via the env vars.

## The re-run gate (fingerprint mechanics)

The natural temptation is "run on every install/sync/upgrade just in
case". That's expensive — most installs touch unrelated state. The
natural opposite is "run once, never again". That's broken — a script
edit needs to rebuild.

skill-manager threads the needle with a **content fingerprint**:

1. After a successful script run, skill-manager computes a SHA-256
   over every byte under `<skill>/skill-scripts/` (recursive,
   lexical-sorted file list, content-hashed), plus the `script` field
   and the `args` list, and persists it as `install_fingerprint` in
   the unit's `cli-lock.toml` entry.

2. On the *next* install / sync / upgrade pass, the backend recomputes
   the same hash and compares.

3. Decision tree:

   | State | Action |
   |---|---|
   | Fingerprint matches AND declared `binary` is present | **Skip** (no re-run, log says "scripts unchanged since last install") |
   | Fingerprint matches AND `binary` is missing | **Re-run** (recovery — user deleted the binary) |
   | Fingerprint matches AND no `binary` declared | **Skip** (nothing to verify, trust the fingerprint) |
   | Fingerprint differs (any file under `skill-scripts/` changed, or `script` / `args` changed) | **Re-run** |
   | No prior fingerprint in lock | **Re-run** (first install) |

This means:

- A `sync` after upstream advances re-runs the script **iff** any byte
  under `skill-scripts/` actually changed. Edits to `SKILL.md` or other
  parts of the skill don't trigger a CLI rebuild.
- Editing the script locally and running `sync` triggers a rerun.
- Adding a sibling helper script under `skill-scripts/` triggers a
  rerun.
- Manually removing `$SKILL_MANAGER_BIN_DIR/<binary>` triggers a rerun
  on the next sync (assuming you declared `binary`).

## Idempotency across the four flows

| Flow | Script runs? |
|---|---|
| `install` (first time) | **Yes** (no prior fingerprint) |
| `sync` with no upstream changes | **No** (fingerprint matches) |
| `sync` after `git pull` that didn't touch `skill-scripts/` | **No** (fingerprint matches) |
| `sync` after `git pull` that did touch `skill-scripts/` | **Yes** (fingerprint flips) |
| `sync` after local edit to the script | **Yes** (fingerprint flips) |
| `upgrade` | Same as `sync` — only on `skill-scripts/` change |
| Manual `rm $SKILL_MANAGER_BIN_DIR/<binary>` then `sync` | **Yes** (recovery) |
| `uninstall <skill>` then `install <skill>` | **No** (today) — see footnote |

> **Footnote on uninstall + install**: `skill-manager uninstall` does
> not currently prune `bin/cli/` (a known limitation flagged in the
> codebase as deferred). So after `uninstall` the binary lingers; after
> the subsequent `install`, the fingerprint matches and the binary
> exists, so the script skips. To force a rebuild, edit the script or
> manually `rm` the binary.

## Plan-output severity and policy

`skill-script:` deps surface as **DANGER** in the install plan because
they run arbitrary shell from the skill. The user sees a line like:

```
DANGER  [skill-script] my-private-cli  (skill-script:my-private-cli)
       · needed by: my-skill
       · runs skill-scripts/install.sh from inside the skill — arbitrary shell
```

`~/.skill-manager/policy.toml` has an `allowed_backends` list. The
default includes `"skill-script"` so installs work out of the box, but
an operator can remove it to block all `skill-script:` deps globally.
When blocked, the install plan shows `BLOCKED` and `--yes` does not
bypass — the user has to amend policy.

## Recipes

### Build-and-install a CLI from a private git repo

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${SKILL_MANAGER_BIN_DIR:?}"
: "${SKILL_MANAGER_CACHE_DIR:?}"
: "${SKILL_NAME:?}"

WORK="$SKILL_MANAGER_CACHE_DIR/skill-script-$SKILL_NAME"
rm -rf "$WORK"
git clone --depth 1 git@gitlab.internal:team/my-private-cli.git "$WORK"
cd "$WORK"
make build                              # produces ./bin/my-private-cli
install -m 0755 ./bin/my-private-cli "$SKILL_MANAGER_BIN_DIR/my-private-cli"
```

Manifest:

```toml
[[cli_dependencies]]
spec = "skill-script:my-private-cli"
on_path = "my-private-cli"

[cli_dependencies.install.any]
script = "build.sh"
binary = "my-private-cli"
```

### Per-platform branching inside one script

If the build differs by OS but the high-level steps are the same, branch
inside one script using `$SKILL_PLATFORM` instead of declaring three
separate `install.<platform>` entries:

```bash
case "$SKILL_PLATFORM" in
  darwin-arm64) target="aarch64-apple-darwin" ;;
  darwin-x64)   target="x86_64-apple-darwin" ;;
  linux-x64)    target="x86_64-unknown-linux-gnu" ;;
  *) echo "unsupported platform: $SKILL_PLATFORM" >&2; exit 2 ;;
esac
cargo build --release --target="$target"
install -m 0755 "target/$target/release/my-cli" "$SKILL_MANAGER_BIN_DIR/my-cli"
```

### Verify the binary exists post-run

Set `binary` so skill-manager fails fast when the script silently
no-ops:

```toml
[cli_dependencies.install.any]
script = "install.sh"
binary = "my-cli"        # ← script that exits 0 without producing this fails the install
```

Without `binary`, skill-manager would record a successful install even
if the script touched nothing.

### Source a sibling helper script

```
skill-scripts/
├── install.sh
└── helpers/
    └── detect-toolchain.sh
```

```bash
# install.sh
source "$SKILL_SCRIPTS_DIR/helpers/detect-toolchain.sh"
```

Both files are part of the fingerprint, so editing either one
triggers a rerun on the next `sync`.

## Authoring rules (condensed)

Before committing a skill that uses `skill-script:`:

- [ ] Script lives under `<skill>/skill-scripts/`.
- [ ] Script is executable in the repo (`chmod +x`); skill-manager also
      forces +x at run time, but the executable bit is the clearest
      signal.
- [ ] Script uses `set -euo pipefail` (or equivalent) — silent failures
      are bad.
- [ ] Script validates required env vars early (`: "${SKILL_MANAGER_BIN_DIR:?}"`).
- [ ] Manifest declares `binary` so post-run verification catches no-ops.
- [ ] Manifest declares `on_path` so users see a sensible name in
      plan output.
- [ ] Scripts under `skill-scripts/` don't include build artifacts,
      `node_modules/`, `.venv/`, etc. — they're part of the fingerprint
      and a stray byte would re-trigger the script every install.

## Anti-patterns

- **Putting the binary in `skill-scripts/`.** That binary becomes part
  of the fingerprint and the source bytes the user installs. If the
  binary can ship as-is, use `tar:` (with an `install.<platform>.url`
  pointing at a release tarball) — much cheaper than a build step.
- **Running `apt-get install` / `brew install` from the script.** Use
  the dedicated backends (`brew:`, `pip:`) — they integrate with the
  install plan, the lock, and the conflict resolver. `skill-script:`
  bypasses all of that.
- **Editing the script without bumping anything user-visible.**
  skill-manager will rerun on next sync (the fingerprint flips), but
  users who only `install` won't see the change until the install
  cache invalidates. Prefer `[skill].version` bumps for user-visible
  behavior changes.
- **Cloning into `$SKILL_DIR`.** That's read-only-ish — anything you
  write there will dirty the store skill's git tree (if it was
  installed from git) and break `sync`. Use `$SKILL_MANAGER_CACHE_DIR`.
