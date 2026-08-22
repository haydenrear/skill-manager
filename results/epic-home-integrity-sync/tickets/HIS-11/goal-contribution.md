# HIS-11 — goal contribution

Issue #186. Branch `feature/186-his-11`, base `epic/home-integrity-sync` at
`ddca9be`. Wave 5, promotion order 9, predecessor HIS-12.

Goal: **GOAL-no-destructive-recovery**, contribution `direct`.
Declared expected effect: *"0 pre-existing unit bytes survive a failed install →
all of them survive."*

This ticket owns **CLAUSE 1** of that goal — *"a failed resolve/install into a
non-empty home leaves every previously installed unit byte-identical to what it
was before, proved by a fixture that fails when the move-aside is removed"* —
and the per-home isolation half of #186. **CLAUSE 2** (detect and repair) is
HIS-13's; **CLAUSE 3** (the guard is not a new hold-back) is measured against
GOAL-no-spurious-holdback's fixture and is discussed under *What I am unsure
about*.

---

## 1. The defect, and what it actually cost

`Executor.java:307`, verbatim as it stood:

```java
case SkillEffect.CommitUnitsToStore e -> List.of();
```

An **empty** pre-state compensation, in a switch whose own javadoc explains that
it is exhaustive precisely so nobody can absorb an effect as "no compensation"
by accident — for the one effect in the table that overwrites bytes already in
the home.

The forward path (`LiveInterpreter.commitUnits`) is delete-destination-then-copy:

```java
Path dst = ctx.store().unitDir(r.name(), k);
if (Files.exists(dst)) Fs.deleteRecursive(dst);
Fs.copyRecursive(unitRoot, dst);
```

The reverse path (`compensationsFor`) is one `DeleteUnitDir` per unit that
emitted a `SkillCommitted` fact. That pair is the correct inverse of a commit
into an **empty** destination and the wrong inverse of every other case. Resolve
into a non-empty home whose closure overwrites installed unit G; let anything
downstream fail — `UpdateUnitsLock`, an MCP register, a transitive install; the
walk-back deletes G's directory and restores nothing, **because nothing was ever
captured**. G is not rolled back to its previous version. G is gone.

Severity scales with the tier, which is why this sits in this epic: the root
home has the most installed units and the least coverage.

### Measured, not asserted

The goal's metric (a) is *pre-existing unit bytes surviving a failed
resolve/install into a non-empty home*. Read straight off the vacuity run, where
each digest entry is `<sha256>/<size>B`
(`probes/his-11/vacuity-checks.txt`, V1):

| fixture | before the failed resolve | after the walk-back, **before** this ticket | after the walk-back, **after** |
| --- | --- | --- | --- |
| skill `gamma` | 3 files, **120 bytes** | 0 files, **0 bytes** | 3 files, **120 bytes**, digests identical |
| plugin `gamma` | 6 files, **376 bytes** | 0 files, **0 bytes** | 6 files, **376 bytes**, digests identical |

`but was <{}>` — the map is empty, not different. **0 of 120 and 0 of 376
bytes survived; now 120 of 120 and 376 of 376 do.**

The second half of #186 — no `HomeLock` anywhere on the resolve/install path —
had no bytes to count. Its before/after is a behaviour: two programs against one
home used to interleave staging and commit freely, and the second now either
waits (saying so) or refuses (naming the home). See §4.

---

## 2. What I did NOT build, and why that is the main design decision

**I did not write an escrow.** HIS-4 shipped one three commits ago
(`MaterializationEscrow`, `15fd561`), and shipping a second because it lives in
a different package would be this epic's signature defect — two spellings of one
decision — with my ticket's name on it.

The mechanism is the same one, and I want to be exact about what "the same"
means, because "they both move files" would not be a good enough argument:

| | HIS-4's use | HIS-11's use |
| --- | --- | --- |
| what is lifted | the dereferenced store links inside one unit | the whole unit directory a commit will overwrite |
| where parked | `<home>/cache/.materialization-escrow-*` | the same |
| how moved | `Files.move` — a rename, same filesystem | the same |
| restore | delete whatever now stands there (recursively), move the held tree back | the same code, unchanged |
| **when restored** | unconditionally, at the end of the sync | **only if the program walks back** |
| **on success** | n/a — there is no success edge | **`discard()`** |

The first four rows are one mechanism. The last two are policy, and policy is
the caller's. So the class gained exactly two things:

- `liftPaths(storeDir, homeRoot, relPaths, restoreTrackedShape)` — the existing
  body, with the caller naming the paths; `lift(...)` is now a one-line wrapper
  that asks `DereferencedStoreLinks` for them. No behaviour change for HIS-4's
  two call sites.
- `discard()` — new, because an escrow that *always* restores never needed a
  "the operation succeeded, let the bytes go" edge. Without it the home would
  grow one held unit tree per resolve under `cache/`, which is **exactly** the
  residue the adversarial review of #231 caught in the first version of that
  class, one directory further down. V2 is the assertion that would have caught
  me repeating it.

Two review findings from HIS-4 applied directly and were honoured rather than
rediscovered:

1. **Not in the units namespace.** The bytes go under `<home>/cache/`, never
   `<home>/skills/`. Free, because I reused the class that already decided this.
2. **`restore()` must not assume an empty destination.** Its
   `DirectoryNotEmptyException` bug was already fixed to a recursive delete —
   which is what makes it correct for me, since a commit that failed mid-copy
   leaves a partial tree at the destination that no `SkillCommitted` fact
   accounts for and that `DeleteUnitDir` therefore never removes.

### One small hardening to the shared class

`liftPaths` now **skips a named path that is not there** rather than throwing
into the best-effort catch. HIS-4's caller could not hit it (`DereferencedStoreLinks`
only ever returns paths that exist), mine can, and the difference matters: "there
was nothing to protect" and "the protection broke" should not produce the same
warning. It also now cleans up its own holding directory when nothing ended up
lifted, so the empty-directory version of the residue cannot happen either.

---

## 3. Half one: the pre-image

`Executor.preStateCompensations` now routes `CommitUnitsToStore` to
`escrowOverwrittenUnits`, which for each resolved unit whose destination
**already exists** lifts that directory aside and records a new compensation,
`Compensation.RestoreUnitDir(name, kind, escrow)`.

Three properties worth stating because they are load-bearing:

**The destructive step stops being destructive.** The lift happens *before* the
handler runs, so `commitUnits`' own `if (exists(dst)) deleteRecursive(dst)` finds
nothing to delete. This is not a race that is won; there is no longer a delete of
the operator's bytes at all.

**The order of the walk-back is not incidental.** `runStage` records pre-state
compensations before post-state ones and `RollbackJournal.pendingLifo()` reverses,
so the walk applies `DeleteUnitDir` (drop what the commit wrote) and *then*
`RestoreUnitDir` (move the pre-image back), at the same path. Reversed, the
restore would be immediately deleted.

**A move, not a copy.** `cache/` and `skills/` are the same home and so the same
filesystem; the rename is constant-time and makes no second copy of a unit that
can be hundreds of megabytes on a disk that is routinely near full. The window in
which the bytes exist only in `cache/` is not a regression: what used to happen
in that window was `deleteRecursive`.

**`preStateCompensations` now has a side effect**, which nothing else in it does.
That is inherent and I have said so in the javadoc rather than hidden it: a
pre-image of a *directory* cannot be a value read out of the store the way an
installed-record (`snapshotInstalled`) or a lock file (`RestoreUnitsLock`) can,
and capturing it after the commit is capturing it after the delete. The call site
already runs immediately before the effect, which is exactly when the move has to
happen.

---

## 4. Half two: the home lock

`HomeLock` already existed — HIS-3 built it for `home sync` — and nothing on the
resolve/install path took it. Two resolves into one home each delete the
destination unit directory and copy their own tree in, so the loser's
half-written unit and the winner's are the same bytes on disk, and each one's
rollback journal describes a home the other has already moved past.

**I took it in `Executor`, not in the commands.** `runStaged` and
`runWithContext` each wrap their whole body in `try (HomeLock ignored =
homeLock(operationId))`. Install, upgrade, sync, onboard, bind, uninstall,
project-resolve and the child-home harness installer all reach the store through
this class; taking it in each of them would have been six spellings of one
decision, which is the thing this epic keeps paying for. `HomeLock` is re-entrant
per thread, so the one path that nests an `Executor` inside another
(`ChildHomeHarnessInstaller`, inner executor against the parent store) does not
deadlock on itself. Read-only paths never come through here — a dry run is a
`DryRunInterpreter` — so this does not turn a report into a write.

**"Waits or refuses, and says which."** The refusal already said which: an
`IOException` naming the operation, the home, and `is locked by another …`,
surfaced by `SkillManagerCli.printFailure` as the message it is rather than a
stack trace (wrapped in `UncheckedIOException` with the message preserved, which
`describe()` is written to unwrap). The **wait** said nothing at all, which is
what I changed: `HomeLock.acquire` now tries once without blocking and, only if
that fails, prints

```
<operation>: waiting for <home> — another operation in this process holds this home's lock
```

before it starts waiting, once per acquisition rather than once per 25ms poll.
Silence for up to 120 seconds is indistinguishable from a hung command.

---

## 5. Validation

Verbatim, from this branch.

```
$ jbang RunTests.java
ALL PASSED

$ uv run pytest specs/ -q
38 passed in 1.78s

$ jbang RunHis11.java            # the three compensation suites, ~7s
  pre-existing skill: 3 file(s), 120 bytes at …/skills/gamma
  [PASS] the pre-installed skill survives a failure after the commit, byte-for-byte
  pre-existing plugin: 6 file(s), 376 bytes at …/plugins/gamma
  [PASS] the pre-installed plugin survives a failure after the commit, byte-for-byte
  [PASS] a commit that succeeds keeps the NEW bytes and leaves no escrow behind
  [PASS] a commit into an EMPTY destination still rolls back to absent
  [PASS] a second program against the same home WAITS, and says so before it waits
  [PASS] a second program that will not get the home REFUSES, naming it
   → 6 passed, 0 failed
ALL PASSED
```

### Graphs, and why these

`tlc` — **N/A**, per the assignment: this ticket states no new invariant and
HIS-5 carries the model work.

| graph | why it was run |
| --- | --- |
| `home-integrity` | **declared** in the assignment. 15/15 nodes, `BUILD SUCCESSFUL in 5m 15s`, `status: passed`, runId `20260822-134928`. |
| `smoke` | **not declared; run because of what I edited.** The home lock now sits on *every* `Executor` program, which is every mutating command, and `smoke` is the graph that drives real `install` / `resolve` / `sync` / `uninstall` end to end through the CLI. A unit fixture cannot tell me the lock does not deadlock a real command, or that it does not leave `.materialization/` somewhere another reader objects to. **52/52 nodes, `status: passed`, runId `20260822-135511`.** |

Both summaries are copied into `probes/his-11/` (`home-integrity-summary.json`,
`smoke-summary.json`) rather than described, so the counts above can be read
rather than believed.

`python skills/test_graph/scripts/run.py --all` was **not** run: it belongs to
HIS-6, which owns the one terminal sweep with the goal scorecard (owner's
instruction, 2026-08-21). All graph runs went through the wave's shared
`graph-run.sh` lock.

### Vacuity, five ways

Full transcripts in `results/epic-home-integrity-sync/probes/his-11/vacuity-checks.txt`;
re-runnable, because `RunHis11.java` is in the repository rather than described
in a file. Summary of what reddened, and with what message:

| # | mutation | what failed |
| --- | --- | --- |
| V1 | the defect itself put back — `CommitUnitsToStore → List.of()` | both byte-identity cells: `but was <{}>` — 120 B → 0 and 376 B → 0 |
| V2 | `escrow().discard()` removed from the success edge | `expected <[]> but was <[.materialization-escrow-17929589200021633808]>` |
| V3 | `escrow().restore()` removed from the walk-back, lift left in | both byte-identity cells again — proves the *apply* arm is load-bearing, not just the lift |
| V4 | `Executor.homeLock` pointed at a throwaway directory | both lock cells: `the second program did not run while the first held the home`, and `the second program refused rather than proceeding` |
| V5 | `announceWait` suppressed | `expected <> to contain <waiting for>` — captured stdout empty |

**The fixture is able to fail, and I checked that specifically**, because #187 in
this same epic is the standing counter-example: an empty `previousLock` there
made the destructive step a no-op, so the assertion could not detect its removal.
Here the destination is genuinely non-empty (a real unit tree with a nested
marker file), the incoming tree is genuinely different (different `SKILL.md`, no
marker file), and the injected failure lands at step 1 — *after* the commit — so
what is under test is the walk-back and not `commitUnits`' own mid-copy
self-rollback. The non-emptiness is asserted as an explicit **precondition**, and
it is **not** the assertion that reddens under any mutation above; HIS-4's probe
7a is the standing warning about that shape.

One cell has **no V-number and is not claimed as vacuity-checked**: *"a commit
into an EMPTY destination still rolls back to absent"*. It is a non-regression
guard against the opposite defect — an escrow that restored unconditionally would
look correct on every case above and wrong on a fresh install — and there is no
single-line mutation that reddens it without also reddening V1. Calling it
checked would be a claim I did not measure.

---

## 6. What I cut

- **Every other effect's compensation.** `SyncDocRepo` ("the dest bytes
  themselves are the only rollback target and we don't capture them" — its own
  comment), `SyncHarness`, `SyncFromLocalDir`, `SyncGit`, `RemoveUnitFromStore`
  ("can't un-remove without the bytes") are all the same sentence #186 was.
  Out of slice, in as many words. **DEF-033.**
- **The shape of the compensation framework.** `Compensation` gained one record;
  it did not gain a lifecycle, a `close()`, or a base type for compensations that
  own resources. `Executor.commit` special-cases the one record that does, in
  eleven lines, and says why. A general answer is a design decision this ticket
  is not entitled to make.
- **A copy fallback when the escrow's `Files.move` fails.** Correct, and
  rejected: it adds a branch inside a data-loss guard that this ticket cannot
  make fail on demand, and an untested branch is not coverage. **DEF-031.**
- **A startup sweep for escrows the JVM died on.** **DEF-032.**
- **A typed exit code for a contended home, and any argument about whether 120
  seconds is the right patience.** **DEF-034.**

Four deferrals against a budget of five.

---

## 7. What I am unsure about

**CLAUSE 3, and whether the lock is a hold-back.** The clause says the guard must
not become a new hold-back, and it is written about *staleness*. The pre-image
escrow cannot hold anything back — it adds no refusal. The **home lock** can: it
is now on every mutating command, and a command that queues for two minutes and
then refuses is a hold-back by any reasonable reading, even though the thing it
is holding back is a genuinely concurrent second writer. I believe that is
correct behaviour and not what CLAUSE 3 is about, but I did not measure it
against GOAL-no-spurious-holdback's fixture, because that fixture is about
staleness reporting and this is a different surface. **A reviewer should decide
whether they agree, and DEF-034 is where the timeout question is parked.**

**The blast radius of taking the lock in `Executor`.** This is the change with
the widest reach, and it is deliberate rather than incidental. Every program now
creates `<home>/.materialization/.home.lock` if it does not exist — `acquire`
does `Fs.ensureDir` before it asks the OS for anything — and `home sync
--dry-run`'s `acquireWithoutCreating` exists precisely because that write was
once a defect (#42, a `--dry-run` that laid out `.materialization/` in the
operator's own `~/.skill-manager`).

I did enumerate rather than assume. Thirteen sites construct an `Executor`:
`InstallCommand`, `UpgradeCommand`, `SyncCommand`, `OnboardCommand`,
`UninstallCommand`, `BindCommand`, `UnbindCommand`, `RebindCommand`,
`BuildCommand`, `HarnessCommand` (`instantiate` and `rm` only — `list` and
`show` never build a program), `ProjectDependencyResolver`,
`ProjectRemoveUseCase`, and `ChildHomeHarnessInstaller`. Every verb behind them
is `WRITES_HOME` in `CommandHomeAccess`; **not one of the 37 `READ_ONLY` paths
reaches this class**, and every `--dry-run` goes through `DryRunInterpreter`
instead. So the write is only ever added to invocations that were already going
to write.

What I have *not* enumerated is out-of-tree callers: the server and any embedding
library reach `Executor` directly rather than through the CLI table, and nothing
stops one of those from running a program it thinks of as a probe. That is a
smaller worry than it sounds — a program is by construction a mutation — but it
is the residue.

**Reusing HIS-4's class rather than generalising it further.** `liftPaths` takes
a `storeDir` plus relative paths because that is the shape `lift` already had. My
caller passes the unit directory's *parent* and its file name, which reads
slightly indirect at the call site. The alternative — a `liftOne(Path absolute)`
overload — is cleaner to call and one more entry point on a class that two
tickets now share. I chose fewer entry points. It is a taste call and I could be
talked out of it.

**What a `PARTIAL` commit receipt should do.** `runStage` records compensations
for `PARTIAL` the same as for `OK`, and `commitUnits` never returns `PARTIAL`
today, so the question is currently unreachable. If some future handler does
return it, the pre-image is escrowed for every resolved unit including ones the
commit never reached — which is *safe* (their destinations are restored
unchanged) but wasteful. Noted, not deferred, because there is nothing to
reproduce.
