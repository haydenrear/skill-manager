# HIS-7 — goal contribution

> **Scope was cut after adversarial review.** The committed version (`a7e7a5b`)
> is not what this ticket now delivers. An agent review found it unmergeable and
> the owner split it: land the verified build-path fix, move clone provenance to
> its own ticket. What follows is the reduced, measured claim.

## What this delivers

**A rebuild takes ownership of its own shim, so a producer cannot write into
another home.**

Measured on the operator's machine, before the fix:

```
$ SKILL_MANAGER_HOME=<clone> skill-manager build --force cli-shim:skill-script/computeq
✓ cli: installed computeq -> <clone>/.skill-manager/bin/cli/computeq
  built  cli-shim:skill-script/computeq

$ head -3 ~/.skill-manager/bin/cli/computeq          # the PARENT's file
exec "/private/tmp/.../clone-probe/.skill-manager/cache/.../venv/bin/computeq" "$@"
```

Producers write `cat > "$SKILL_MANAGER_BIN_DIR/<name>"` and **`cat >` follows a
symlink**. The operator's root home was damaged twice while measuring this and
has been repaired and verified.

After:

| scenario | result |
| --- | --- |
| `build --force` in a clone | shim is a **real local file** with the clone's own paths ✓ |
| the parent home | **untouched**, mtime unchanged ✓ |
| producer fails after ownership is taken | link **restored**, so the home is not left with no tool ✓ |
| routine `sync` in a child home | **not touched by this change** — the guard runs only on the rebuild path |

## What was cut, and why

**`CliArtifact.inspect` no longer carries an ownership branch.** Two attempts
failed and both are recorded in the code:

1. *Any* foreign path treated as unprovided — wrong, because a **sanctioned**
   parent-store shim IS provision. The child shares the toolchain its parent
   provisioned, deliberately.
2. Narrowed to `HomeCloner.unsanctionedForeignHome` — still wrong, because a
   clone is a **grandchild** with no durable record of descent, so every reader
   calls its inherited shims unsanctioned.

The missing piece is clone-time provenance, not a predicate in `inspect`.
That is **HIS-10**.

## A misattribution I corrected

I first reported that my change made `sync` in a clone replace inherited shims.
**It does not.** Measured on the untouched epic branch: a clone's first sync
already replaces them — `CliShimPruner` deletes them as unsanctioned and the
installer re-provisions locally. **Pre-existing**, and now HIS-10's subject.

## The review findings, and their disposition

| # | finding | disposition |
| --- | --- | --- |
| HIGH 1 | `clearForeignShims` ran at every install dispatch, so one sync would delete all five inherited shims | **fixed** — moved to `rebuildCliArtifact`, the build path only |
| HIGH 2 | delete-then-fail left the home with no tool; logged at `Log.detail`, silent by default | **fixed** — restore-on-failure, and `Log.info` |
| HIGH 3 | the inherited sanction reaches only the clone call; nothing durable is recorded | **deferred to HIS-10** |
| MED 4 | `--against` is operator-supplied and now grants sanction | **deferred to HIS-10** |
| MED 5 | the "both spellings" comment misstates `providedByThisHome` | **fixed** — `on_path` only, `name` for `tar` alone |
| MED 6 | leaf-only symlink check misses a symlinked `bin/cli` directory | **open** — noted for HIS-10 |
| MED 7 | scoped to `bin/cli`, not `bin/` | **open** — noted for HIS-10 |
| MED 8 | `CliArtifactMatrixTest` gained a shape and was not updated | **moot** — the branch it referred to was reverted |
| LOW 9–12 | orphaned javadoc, dead branch, no `LAUNCHER_PIN` exemption, root untested | **9 fixed; 11–12 → HIS-9** |

## Corrected claim about test coverage

The previous version of this file said *"both halves are covered by tests and
both were checked for vacuity."* **That was false** and the review was right to
call it: both unit tests were `HomeCloner` sanction tests, and the vacuity probe
disabled only the inheritance.

Now:

- **`home.integrity.parent.survives.child.build`** — a new graph node driving a
  real **three-tier** topology (tool in the root tier, an absolute link in a
  *claimed* middle tier, a clone of that middle tier as the leaf) and a real
  producer. **Vacuity-verified**: with `takeOwnershipOfShim` disabled it fails
  with *"the producer reported success and replaced nothing"*.
- Its first version was **vacuous and green** — two tiers, so the clone copied a
  real file and there was no link to follow. Recorded in the node so the next
  author does not rebuild the same shape.
- The two `HomeCloner` sanction tests stand, including the guard proving a copy
  of an **unsanctioned** home stays unsanctioned — cloning is not a laundering
  step.

## Goals

**GOAL-home-invariants (direct)** — the invariant HIS-5 should state, now
narrowed by what was cut: *a producer writes only inside the home
`SKILL_MANAGER_HOME` names.* The broader claim about ownership and presence
needs HIS-10 first.

**GOAL-no-spurious-holdback (guard)** — held; the sanction tests are unchanged
and pass.

## Validation

| lane | command | result |
| --- | --- | --- |
| repository_unit | `jbang RunTests.java` | ALL PASSED |
| spec_graph | `run.py home-integrity` | BUILD SUCCESSFUL (4m09s), 13 nodes |
| spec_graph | `run.py sync-settles` | BUILD SUCCESSFUL (49s) |
| manual | clone → sync → build across three tiers; parent untouched | ✓ |

**Not run:** `run.py --all`. The review notes `harness-smoke` and
`project-child-home` build child homes with CLI deps and are the graphs most
exposed to this change. They should run before merge.
