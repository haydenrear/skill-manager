# HIS-7 — goal contribution

## What this turned out to be

The ticket was filed twice on wrong root causes and re-founded after an owner
sanity check. Chasing the corrected one found something worse than a bad log
line: **a `build` inside a cloned home wrote into its parent home.**

```
$ SKILL_MANAGER_HOME=<clone> skill-manager build --force cli-shim:skill-script/computeq
✓ cli: installed computeq -> <clone>/.skill-manager/bin/cli/computeq
  built  cli-shim:skill-script/computeq

$ head -3 ~/.skill-manager/bin/cli/computeq          # the PARENT's file
export MONITORING_DEPLOY_CDC_ROOT="/private/tmp/.../clone-probe/.skill-manager/skills/deploy-helm"
exec "/private/tmp/.../clone-probe/.skill-manager/cache/.../venv/bin/computeq" "$@"
```

Producers write with `cat > "$SKILL_MANAGER_BIN_DIR/<name>"`, and **`cat >`
follows a symlink**. A cloned home inherits `bin/cli/<name>` as an absolute link
into its parent — by design — so the producer never wrote the clone's shim; it
overwrote the *parent's*, with a wrapper containing the *clone's* absolute
paths.

The operator's root home was damaged twice while measuring this. `tlc2` survived
only because its wrapper happens to be `BIN_DIR`-relative — luck, not design.
`computeq` did not, and was repaired (`build --force` in the root home; verified
`computeq --help` exit 0, venv target present, `home verify` clean, and a sweep
confirming no root shim references a temporary path).

## Three defects, one cause

**Presence was decided by runnability, not ownership.**
`CliPresence.alreadyProvided` → `CliArtifact.inHome` asks *"will this run from
this home"*. A symlink into another home runs. So:

1. `build` skipped the write and reported `built` — a command announcing a write
   it did not perform (#133 finding 3's shape, one layer down);
2. when the producer *did* run, it wrote through the link into the parent;
3. and `home clone` refused the resulting home for holding paths the build had
   just declined to replace.

## The fix

| change | what it does |
| --- | --- |
| `CliArtifact.inspect` | a path resolving into another home is **not provided here**, so presence answers ownership as `verify`/`clone` always did |
| `InstallerRegistry.installOne` | clears a foreign shim **before** the producer runs, so `cat >` cannot follow it into the parent — placed at the single dispatch, mirroring the `relativizeShims` normalization directly below it |
| `HomeCloner.sanctionedParentShim` | a **copy inherits its source's sanction**: the question is asked of the source, because the destination is a home that does not exist yet |

`LaunchEnv.looksLikeStoreRoot` throughout — the same spelling
`HomeCloner.foreignHomeReachedBy` walks, because #24 is what happens when two
spellings disagree about which homes matter.

## Measured after

```
clone into a fresh home, then build inside it:
  clone's shim   REAL LOCAL FILE, its own paths        ✓
  root home      UNTOUCHED (mtime unchanged)           ✓
  home clone     completes, no FOREIGN_HOME            ✓
  bootstrap-home.sh --root <worktree>
                 4 of 4 skills projected, home verified, launcher written  ✓
```

The last line is the acceptance criterion: **a usable ticket home can now be
produced**, so `wt new` / `skt ticket new` work again.

⚠️ **With the CLI that carries the fix.** `bootstrap-home.sh` resolves its CLI
from the root home, whose pin is 0.23.0 and predates this. Until that pin moves,
the flow needs `SKILL_MANAGER_CLI=<this build>`. That is a release/sync matter,
not a code one, but it means the fix is not live for other checkouts yet.

## Goals

**GOAL-home-invariants (direct)** — the invariant HIS-5 must state is now
concrete: *an artifact reported built is one whose output this home owns*. Both
halves are covered by tests and both were **checked for vacuity**: with the
inheritance disabled, `a COPY of a sanctioned child inherits the sanction` fails.

**GOAL-no-spurious-holdback (guard)** — held, and asserted rather than assumed.
`inheritance needs a source that was itself sanctioned, not merely a source`
proves cloning is **not a laundering step**: a copy of an unsanctioned home is
still unsanctioned, so "clone it once more" cannot turn a foreign path into a
blessed one. The three pre-existing sanction tests still pass unchanged.

## Validation

| lane | command | result |
| --- | --- | --- |
| repository_unit | `jbang RunTests.java` | ALL PASSED |
| spec_graph | `python skills/test_graph/scripts/run.py home-integrity` | BUILD SUCCESSFUL (3m47s) |
| manual | clone → build → parent untouched; `bootstrap-home.sh` produces a usable home | ✓ |
