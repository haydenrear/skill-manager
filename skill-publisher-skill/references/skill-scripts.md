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
- Idempotency rules across install, sync, upgrade, force replay, and
  uninstall/reinstall
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

The script's `cwd` is unspecified (don't depend on it). Use the env
vars to find things — but read the next section before you copy one of
them into the wrapper you generate.

## Writing a wrapper that survives being copied

**The rule: a shim resolves its unit's path from the home the shim
lives in — prefer this home's copy, fall back to the pinned one.**

Every variable in the table above is an absolute path *into the home
that happens to be installing right now*. A wrapper that writes one of
them into its own body has resolved the path once and frozen the
answer:

```bash
# WRONG — this is skill-manager#262, written down.
cat > "$SKILL_MANAGER_BIN_DIR/my-cli" <<SH
#!/bin/sh
exec "$PY" "$SKILL_DIR/src/cli.py" "\$@"
SH
```

Homes get copied. A project home is cloned from the root home, a ticket
worktree gets its own home, a child home symlinks its parent's
`bin/cli/` entries. The wrapper above goes with them and keeps naming
the **first** home — so the home it now lives in runs somebody else's
copy of the unit while holding its own, unused. That fails at the worst
possible moment: an agent edits the skill in its own home, runs the CLI
to check, sees the old behaviour, and concludes the edit was wrong.

Measured on one machine: 19 `(home, shim)` pairs crossing into another
home, and in every single one the home had its own copy sitting there.

Write it like `bin/launch/*` does instead — derive the home from the
shim's own location and keep everything under it **relative**:

```bash
# Install time: the entrypoint's path RELATIVE TO A HOME. That is the
# part that is the same in every home; $SKILL_DIR is this home's
# spelling of it and is exactly what must not be baked in.
home="$(cd -- "${SKILL_MANAGER_HOME:-$SKILL_MANAGER_BIN_DIR/../..}" && pwd -P)"
skill_real="$(cd -- "$SKILL_DIR" && pwd -P)"
rel="${skill_real#"$home"/}/src/cli.py"

# Two heredocs: the first EXPANDED for install-time facts, the second
# QUOTED for the body. One expanded heredoc is how $SKILL_DIR ends up
# frozen in the first place.
cat > "$SKILL_MANAGER_BIN_DIR/my-cli" <<SH
#!/usr/bin/env bash
set -euo pipefail
py="$PY"
rel="$rel"
SH
cat >> "$SKILL_MANAGER_BIN_DIR/my-cli" <<'SH'
# `pwd -P` resolves the DIRECTORY's own symlinks, never the shim's, so a
# child home whose bin/cli entry is a link into its parent still answers
# with itself here.
shim_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
home="$(cd -- "$shim_dir/../.." && pwd -P)"
exec "$py" "$home/$rel" "$@"
SH
chmod 0755 "$SKILL_MANAGER_BIN_DIR/my-cli"
```

Three details worth knowing:

- **`bin/cli/<tool>` is always `<home>/bin/cli/<tool>`,** so `../..`
  from the shim's directory is the home. That is the same derivation
  `bin/launch/*` uses; there is no second convention to learn.
- **A path outside every home may be pinned.** The interpreter you
  probed for, a build you resolved — those don't move when the shim
  moves, so freezing them is not the defect. It's specifically home
  paths that must not be baked in.
- **The fallback is followed, never stored.** A home that legitimately
  holds no copy of the unit has to reach the home it was pinned to.
  Find that home at run time — follow the shim's own symlink — rather
  than writing its path into the body, because a stored path is taken
  by a home that *does* have its own copy, which is the bug again:

  ```bash
  target="$home/$rel"
  if [ ! -f "$target" ]; then
    link="${BASH_SOURCE[0]}"
    for _ in 1 2 3 4 5 6 7 8; do
      [ -L "$link" ] || break
      t="$(readlink "$link")"
      case "$t" in
        /*) link="$t" ;;
        *)  link="$(cd -- "$(dirname -- "$link")" && pwd -P)/$t" ;;
      esac
      pinned="$(cd -- "$(dirname -- "$link")/../.." 2>/dev/null && pwd -P)" || continue
      if [ -f "$pinned/$rel" ]; then target="$pinned/$rel"; break; fi
    done
  fi
  ```

Naming the tree **home-relative** also keeps the artifact ledger
working: `bin/cli/<tool>` naming `cache/skill-script-<unit>-<tool>/…`
is the only evidence a home has of which install wrote that directory,
and it is what lets `uninstall` prune the tree instead of leaving it
behind forever. skill-manager reads both the absolute and the relative
spelling for that reason — but only the relative one survives a copy.

skill-manager cannot rewrite what your script writes (it forks the
script and reads none of those bytes), so it does the one thing it can:
after the script runs it **reads the shims the run touched and warns**
when one has the installing home's absolute path baked in. If you see

```
cli: skill-script my-cli wrote bin/cli/my-cli with this home's absolute
path baked in (skills/my-skill/src/cli.py). …
```

that is this section, addressed to you.

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
| `install --force-scripts <source>` | **Yes** for `skill-script:` deps in the install graph (policy still applies) |
| `sync <skill> --force-scripts` | **Yes** for `skill-script:` deps owned by the named target (policy still applies) |
| `sync --force-scripts` | **Yes** for all installed `skill-script:` deps because every installed unit is a target |
| `uninstall <skill>` then `install <skill>` | **Yes** when uninstall orphaned the dependency; **No** when another installed unit still claims it |

On uninstall, skill-manager re-walks the effective CLI deps for the
unit being removed. It deletes the managed `bin/cli/<binary>` artifact
and the matching `cli-lock.toml` row only when no surviving installed
unit still claims that backend/tool. If another skill or plugin still
claims it, uninstall keeps the artifact and rewrites ownership to the
surviving claim.

Use `--force-scripts` when you need an explicit replay without changing
the script bytes or deleting the binary. On named sync, the replay scope
is the named unit or units; unrelated installed units are not forced.
The flag changes rerun eligibility only; it does not loosen the policy
gate for running author-supplied shell.

Script stdout and stderr are written to timestamped logs under
`$SKILL_MANAGER_HOME/logs/skill-scripts/`. The CLI prints the log path
when the script starts. If the script fails, the error includes the log
path and a tail of recent output so agent transcripts do not get flooded
by successful installer output.

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
- [ ] The wrapper the script generates derives its home from its own
      location and names everything under it relative — no
      `$SKILL_DIR`, `$SKILL_MANAGER_HOME` or `$SKILL_MANAGER_CACHE_DIR`
      expanded into the generated body. See "Writing a wrapper that
      survives being copied".
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
- **Baking `$SKILL_DIR` / `$SKILL_MANAGER_CACHE_DIR` into the wrapper
  you generate.** The wrapper outlives the home that installed it —
  homes are cloned, and child homes symlink their parent's `bin/cli`
  entries. A frozen path makes the copy run the original home's code
  while its own sits unused, and the failure shows up as "my edit did
  nothing". See "Writing a wrapper that survives being copied"; using
  those variables *in the script* is exactly right, it is writing them
  into the generated body that is wrong.
- **Cloning into `$SKILL_DIR`.** That's read-only-ish — anything you
  write there will dirty the store skill's git tree (if it was
  installed from git) and break `sync`. Use `$SKILL_MANAGER_CACHE_DIR`.
