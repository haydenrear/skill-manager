# HIS-4 — goal contribution

Issue #216. Branch `feature/216-his-4`, based on epic tip `dc97f2a`, worktree
`../wt-216-his-4` — the first ticket in this epic that could actually use its
declared worktree, because HIS-10 is what made a clone usable.

## The headline

**The defect is reproduced at the project tier, synthetically, and it is fixed.**
The graph node this ticket was told to redden is **red without the fix**
(`BUILD FAILED in 59s`, runId `20260821-191159`) and **green with it**
(`BUILD SUCCESSFUL in 1m 14s`, runId `20260821-184449`), with the production
diff as the only variable. The green run was measured *first*, because the
shared lock freed mid-restore; the order is recorded below rather than tidied.

The mechanism, stated once: `ChildHomeMaterializer` dereferences an in-unit
symlink that points into the parent store into a real directory, because that is
what makes a child home independent (CHM-5). It does so **inside a git working
tree** that tracks that path at mode `120000`. `git status` reads it as a
deletion; `sync` reads a deletion as "extra local changes"; and from that moment
the unit is never syncable again.

**And the sharpest thing I found is not in the issue at all: a `sync --merge`
that SUCCEEDS — exit 0, `✓ synced 1 unit(s) — 1 merged` — silently restores the
symlink the child home was materialized to replace.** Where the provider link is
absolute — which `prepare_provider_bindings` emits for a `skill-root` provider
(`target = str(source)`) rather than a workspace-relative one — that restored
link points **back into the parent store**, and the child home is writing
through into the home it was materialized from. That is the isolation break
CHM-5 exists to prevent, reintroduced by a command reporting success and
reporting nothing else. In the other upstream shape it does not restore the tree
at all: it **deletes** it. Both measured; see the section below and **DEF-014**.

## What I changed

| file | what |
| --- | --- |
| `source/DereferencedStoreLinks.java` (new) | one **re-derived** predicate: which paths are a dereferenced store link rather than somebody's work |
| `source/GitOps.java` | `indexEntries` (mode-aware), `blobText`, `isMidMerge`, `hasStash`, `checkoutPaths` |
| `effects/SyncGitHandler.java` | the dirty gate asks about **authored** changes; the merge **escrows** the materialized trees; a failed stash pop **rolls the merge back** |
| `app/ReportUseCase.java` | the `MERGE_CONFLICT` remedy is chosen by looking at the store, so it is one that clears the state it is printed for |
| `effects/ConsoleProgramRenderer.java` | routed through that single definition; the second spelling is gone |
| `effects/SyncFromLocalDirHandler.java` | the **second sync path**, which asked the raw question and would have gone on refusing a materialized copy in identical words |
| `SyncPathsAgreeAboutDirtyTest` (new) | the oracle that fails when a **third** sync path spells it a third way |

**No new persisted state and no schema change.** The predicate reads the index's
mode for the path, the shape on disk, and the link target git holds in the blob
— all live, all now. A `dereferencedPaths[]` field in the materialization record
would have been the next instance of this epic's own recurring defect.

### CLAUSE 2 WAS ALREADY TRUE. I ADDED NOTHING, AND THAT IS THE DELIVERABLE.

Clause 2 reads: *"an installed-record error is re-derived from the live tree, so
a condition that has cleared stops being reported without anyone editing the
record by hand."*

**That is already what this build does, on every command, and here is the
proof.** `ReconcileUseCase.buildProgram` emits a `ValidateAndClearError` for
every error on every installed unit; `SkillManagerCli.tryReconcile` runs that
program at the start of **every invocation** (its own javadoc calls it "the
program that runs at the start of every command");
`LiveInterpreter.validateAndClear` probes the live store dir and clears. The
probe is an exhaustive switch with no `default`, so a new `ErrorKind` cannot be
added without somebody deciding whether it gets one.

Measured, on the pre-fix build, in the project-tier fixture:

```
sync --merge        -> errors [{"kind": "MERGE_CONFLICT", "message": "stash pop
                        conflict after merging ...", "firstSeenAt": "..."}]
next command        -> reconcile: onboarded=0 cleared=1
                       errors []
```

No hand edit, no second sync, no ack. The record was re-derived from the tree
and retired.

So **I wrote no re-derivation for this ticket.** The instruction was to read
`settledWithoutARecord` first and not add a second spelling of a question that
already has one, and the answer that reading produced was that the question has
one and it is not in `ChildHomeMaterializer` at all — it is in the reconcile
pass. Adding a `resolvedAt` field, or a second probe on the read path, would
have produced two answers to one question, which is this epic's recurring
defect wearing this ticket's name. It would also have *looked* like delivery.

**This is a correction to the issue text, and I want it read at full strength.**
#216(b) says: *"No `resolvedAt`, and nothing re-derives it. After the git state
was cleared, `skt check` **still** reported the conflict, because the state is
recorded, not observed."* On this build the second sentence is false: it **is**
observed, on every command. I could not reproduce the sticky-record symptom in
any of the four fixture modes and I am not claiming a fix for it. If `skt check`
really did keep reporting it after the git state was clean, the cause is
somewhere `skt` reads the record without the CLI running its reconcile — which
is a `skt` question, not a product one, and not something I can measure from
here.

What I found in that neighbourhood instead is the **opposite** failure, and it
is the more interesting one: the probe for `MERGE_CONFLICT` asks only
`GitOps.unmergedFiles(dir).isEmpty()`, and the state it is recorded for can
leave **no unmerged files at all** — so the record went quiet on the next
command while `stash@{0}` still held the local work and the baseline was still
stranded behind a committed merge. **The record cleared; the damage did not.**

That is **DEF-015**, deferred rather than absorbed, and it is filed as one
finding with **DEF-012** rather than as its own: `home verify` returning exit 0
on a home whose pinned CLI is a deleted Cellar path is the same defect in a
different instrument — a probe whose scope is narrower than the condition it is
trusted to decide, reporting healthy over damage. Two instruments, one shape,
found in one wave. HIS-13 should see the pattern rather than two tickets.

## The measurement

`results/epic-home-integrity-sync/probes/his-4/repro.sh`, four modes, same
fixture, the production diff as the only variable.

| | A | B | C | D (agent edit) |
| --- | --- | --- | --- | --- |
| `sync` exit — **before** | 7 | 7 | 7 | 7 |
| `sync` exit — **after** | **0** | **0** | **0** | **7** — correct |
| record `gitHash` == upstream HEAD after | yes | yes | yes | — |
| `MERGE_CONFLICT` recorded | none | none | none | none |
| stash abandoned | none | none | none | none |
| materialized tree after | DIRECTORY | DIRECTORY | DIRECTORY | DIRECTORY |
| second sync | 0 | 0 | 0 | — |

**Before**, the same fixture:

- `sync` exit 7, forever — *"has extra local changes (working tree edits, or
  commits ahead of the installed baseline) — sync would overwrite them"*, and
  the second sync identical;
- the printed remedy `sync --merge` exit 8, `MERGE_CONFLICT` recorded,
  `stash@{0}` abandoned, `ls-files -u` **empty**, no `MERGE_HEAD`, and
  **HEAD `f52ffb7c` against record `3dd070e5`** — link 3, the same shape as the
  operator's `eab28837` against `c72d03a6`;
- the remedy printed for that state was *"resolve in …, then `git add` + `git
  commit`"* over a tree with nothing to add and no merge to commit.

**Against the goal metric** — *change-managed units left in `MERGE_CONFLICT`
after a sync of a unit whose store copy contains symlinks into the parent store*
— the synthetic fixture leaves **0**, and `sync` completes. Baseline was 2 of 21
on the operator's project home. That baseline is not re-measurable (it was
cleared by hand at kickoff), so this is the synthetic reading the ticket asked
for, not a re-measurement of the live one.

## Reproducing it was harder than fixing it, and here is the variable

The node passed before because **the root tier never dereferences anything**.
Only `ChildHomeMaterializer` does, and it only runs when a home is materialized
from another home. Two root-tier reproductions had already been tried and ruled
out; the remaining variable was the tier, and with it three ingredients the old
fixture had no way to know it needed:

1. a **provider unit installed alongside** the consumer, because `walk()`
   dereferences only links resolving *inside the parent store* and *outside the
   unit* — with no provider in the store, nothing is dereferenced and the
   fixture is inert;
2. `project resolve` doing the dereference, not the node;
3. upstream **re-pointing** a store link the unit's `.gitignore` does **not**
   cover — with only the ignored path present, `--merge` does not conflict at
   all. It succeeds, and silently reverts the materialization.

## Vacuity checks — every new assertion, run against broken code

Five probes, each reddening a *different* case; the build was restored and
re-run green after each.

| probe | what was disabled | which case reddened, and its message |
| --- | --- | --- |
| 1 | `DereferencedStoreLinks.in` returns `Set.of()` — the pre-HIS-4 world | *a dereferenced store link is named…* — `not true: the dereferenced store link is recognised, got []` |
| 2 | the `escapesUnit` clause dropped — the **over-broad** fix | *a link resolving inside the unit…* — `expected false: a link that resolves inside the unit is never a materialization artifact` |
| 3 | `hasAuthoredWorktreeChanges` returns false whenever any deref is present — the **over-reach** | *a real edit alongside the materialization…* — `not true: the edit is still there to protect` |
| 4 | the old single-sentence remedy restored | *the remedy for a stash-pop residue…* — `expected <resolve in …, then `git add` + `git commit`> to contain <already clear>` |
| 5 | `SyncFromLocalDirHandler` put back on `GitOps.isDirty` — the divergence itself | *no effects handler asks the raw dirty question* — ``a sync path is asking the raw dirty question instead of DereferencedStoreLinks.isAuthoredDirty, so it will refuse a materialized child home forever while its sibling does not: [SyncFromLocalDirHandler.java asks GitOps.isDirty(]``, and *both sync paths reach the one definition* reddened with it |
| 6 | `isAuthoredDirty` → `return hasAuthoredWorktreeChanges(storeDir)` — **review HIGH-3's exact disable** | *a commit ahead of the baseline is dirty even with a clean tree* — ``a store copy whose HEAD is past the installed baseline has work a sync would overwrite, and the worktree half cannot see it`` |
| 7b | `MaterializationEscrow.restore()` made a no-op — **review HIGH-1's data loss** | *a wholesale replace does not destroy the materialized tree* — ``the path is NOT a symlink again — a child home that links back out of itself has lost the independence CHM-5 gave it`` |
| 8 | `restore()` back on `Files.deleteIfExists` — **review MED-4** | *restore replaces a path upstream converted to real content* — ``the escrowed tree replaced the non-empty directory rather than being stranded in the cache``, with the warn line naming the cache path the bytes were stranded at |
| 9 | the rolled-back remedy branch removed — **review HIGH-2** | *a rolled-back conflict is not reported as already clear* — ``a conflict that was rolled back is NOT 'already clear': already clear in <store> — the record has not caught up…`` |

Probe **7b** is the one worth reading twice. Its first form disabled `lift()`
and reddened only the *"the escrow really took something"* guard — proving the
guard worked, never reaching the data-loss assertion. Disabling `restore()`
instead reddened the byte-level claim, **and in doing so exposed that my own
test was vacuous**: the "upstream" snapshot was taken after the dereference, so
the copy carried the child's bytes back on its own. A probe that only reddens a
precondition is not a probe of the thing.

Probes 2 and 3 are the ones that matter: they redden against *plausible fixes*,
not against no fix. The cheap version of this change ("a deleted symlink is
never a local change") passes probe 1 and fails 2 and 3, and it silently
discards an agent's work.

Probe 5 is a different kind and worth its own note. There are **two** sync paths
— `SyncGitHandler` against a git remote, `SyncFromLocalDirHandler` against a
local directory — and **both refuse with the same sentence**. Fixing only the
first would have left `sync --from` refusing a materialized child copy forever,
in identical words, for a reason already fixed next door, with nothing to detect
it because both look correct in isolation. That is **two readings of one rule**:
CHM-15's shape, DEF-004's shape, the shape this epic meets every wave. So the
question has **one definition** (`DereferencedStoreLinks.isAuthoredDirty`) and
`SyncPathsAgreeAboutDirtyTest` is a **source scan**, not a behavioural test —
deliberately, because the failure it prevents is *a handler nobody has written
yet*, and a behavioural test can only cover the ones somebody remembered. It
proves itself sensitive every run (it asserts it walked ≥ 5 files and that its
matcher still recognises the banned shape), so a scan that quietly stopped
matching cannot pass as compliance. Modelled on `SandboxEnvContract`.

## The graph — and the order the runs actually happened in

**Read this before the result, because the order is not the one the narrative
would suggest and tidying it up would be the second thing this epic had to
correct after the fact.**

The intended sequence was red-then-green: run the node against the unfixed
build, see it fail, restore, see it pass. That is *not* what happened. The
shared graph lock freed while I was restoring the fix into the working tree, so
the run that had been queued for the red measurement **acquired the lock with
the fixed build in the tree** and produced the GREEN result instead:

| | |
| --- | --- |
| runId | `20260821-184449` |
| build in tree | **fixed** (`88a4346`'s content) |
| result | `BUILD SUCCESSFUL in 1m 14s`; node `"status": "passed"`, 20.7 s |

The red run was then started against the reverted build, was killed twice by a
wrapper defect (below), and finally completed:

| | |
| --- | --- |
| runId | `20260821-191159` |
| build in tree | **reverted** to epic tip `dc97f2a` — five files, plus deleting `DereferencedStoreLinks.java` |
| result | `BUILD FAILED in 59s`; node `"status": "failed"` |

**Four failures, verbatim from the envelope, and they are the four links:**

```
sync exited 7; a unit whose only divergence is the child home's own dereference
  must stay syncable at every tier
installed baseline was stranded: record says ea137889 (was ea137889), upstream
  HEAD is baa68c1a. A merge that lands without its record makes every later sync
  report 'commits ahead of the installed baseline' forever
a second sync with nothing changed exited 7; the home never reaches a settled
  state
the printed remedy (`sync --merge`) exited 8 — a remedy that does not clear the
  state it is printed for is how this defect stayed alive for nine days
```

The second is **link 3**, measured through the node rather than by hand: the
record did not move at all (`ea137889` before, `ea137889` after) while upstream
stands at `baa68c1a`. The third is the permanence. The fourth is the remedy.

**So the node is red without the fix and green with it, and the production diff
is the only variable** — same node, same fixture, same machine, 27 minutes
apart. That is the ticket's first acceptance item, discharged.

**The order remains what it was**, and it is worth being plain about the limit:
green was measured first, red second. Nothing about the comparison depends on
the order — the node and fixture are byte-identical across both runs — but the
record says green-then-red because that is what happened.

### Why it took three attempts — none of them a product signal

The wrapper's lock failed three times, and no failure was a real collision:

1. My own green run finished in 1m 14s but its release trap only fires on a
   clean shell exit; the process ended without one, leaving the lock directory
   with **no holder**. My next invocation then queued behind *myself* for 13
   minutes. The coordinator fixed the wrapper (the holder now records its PID
   and a waiter reclaims a lock whose holder is gone) and cleared the lock.
2. The clearing left an **empty** lock directory — no `owner`, no `pid` — which
   the fixed wrapper **cannot reclaim either**, because its reclaim branch
   requires a non-empty `pid` file. Verified orphaned: no `run.py`, no
   `GradleWrapperMain`, no `skill-manager` compose containers, directory
   created at the moment of the clearing. It was blocking **HIS-9's**
   `home-integrity` waiter as well as mine.

3. The wrapper had been edited **in place** while invocations were mid-flight.
   Bash reads a script incrementally by byte offset, so running shells resumed
   into changed bytes and died; one killed between `mkdir` and the `owner`/`pid`
   writes left a lock nothing could attribute.

I did not remove any lock — reported instead, which is the standing instruction,
and the one time I tried to clear a verified-empty orphan the permission layer
refused, which was the right answer. All three were fixed in the harness (no
`exec`, so the release trap fires; unattributed locks reclaimed after 60s;
edits written temp-then-renamed). **None of this is evidence about the product**,
and it is recorded here only so the three failed attempts in the run log are not
read as flakiness in `sync-settles`. The run that finally measured the red took
60s of waiting and 59s of work.

## The defect the issue did not name: a *successful* `--merge` destroys the materialization

Not a side note, and not something I only mentioned in passing — it is measured,
fixed on the sync path, asserted by the node, and deferred as **DEF-014** for
the part I do not own.

Before this change, `sync --merge` did not always conflict. When it **succeeded**
it reported `✓ synced 1 unit(s) — 1 merged`, exit 0, and left the child home
holding something other than what `ChildHomeMaterializer` put there:

| upstream did | `test_graph/build-logic` in the child home afterwards |
| --- | --- |
| moved content (mode A) | **reverted to a symlink** — the child home stopped being independent |
| untracked the link, per the managed-bindings migration (mode B) | **deleted outright** — the merge took the directory with it |

Nothing reported either. This is `carryOverUnownedTrees`'s rule — *not compared,
not copied, **not destroyed*** — broken on the git surface rather than the
digest surface, and it is the reason the fix **escrows** the trees across the
stash/merge/pop window instead of merely excluding them from the dirty gate.
Excluding alone would have fixed the refusal and left the destruction in place,
which is exactly the CHM-24 trap the ticket warned about.

**Why it matters more than "a directory came back as a link":** in mode A the
restored link happened to resolve *inside* the child home, because the provider
unit was materialized there too and the link is relative. With an **absolute**
provider link — which `prepare_provider_bindings` emits for a `skill-root`
provider (`target = str(source)`) rather than a workspace-relative one — the
restored link points back into the **parent store**, and the child home is
writing through into the home it was materialized from. That is the isolation
break CHM-5 exists to prevent, reintroduced by a command that reports success.

Fixed here for the sync path only (`MaterializationEscrow` in
`SyncGitHandler.runMerge`), asserted by the node's "is a symlink again after the
sync" / "was destroyed by the sync" checks, and measured in all four modes:
**DIRECTORY** afterwards, every time. The general form — *any* git operation an
agent runs by hand in a materialized store copy restores the tracked symlink,
and nothing detects it — is **DEF-014**, candidate owner HIS-13.

## Adversarial review of #231 — three blocking defects, and one of them was mine

Recorded in full because two of the three are defects **this ticket
introduced**, and a goal contribution that reported only the fixes would be the
comfortable version of this document.

### HIGH-1 — I created DEF-014 while deferring it

`sync --from` applies by `Fs.deleteRecursive(storeDir)` then
`Fs.copyRecursive(src, storeDir)`: a wholesale replace with no carry-over.
Before this ticket a materialized copy never reached it — the dereference made
the unit read dirty and the handler refused. **I taught that gate the
dereference is not an author's work, and thereby walked the path straight into
the delete.** Review reproduced it at exit 0: the link came back pointing out of
the unit and the child home's own bytes were gone.

The escrow existed and protected only the *other* sync path, which is exactly
what a private helper inside one handler guarantees. It is now
`source/MaterializationEscrow`, a shared class, used by both. Measured after:

```
sync --from <src> --yes   -> exit 0
build-logic  -> DIRECTORY (correct)
child-only bytes: PRESENT
```

**My own first test of this was vacuous** and probe 7b caught it: I snapshotted
the "upstream" source *after* dereferencing, so the copy carried the child's
bytes back regardless of the escrow. The snapshot is now taken before, and the
reason is written into the test.

### HIGH-2 — a rolled-back conflict reported nothing, over a self-erasing record

My rollback ran `resetHard` → `stashPop` → *then* read `unmergedFiles`, which
reads a tree the rollback has just made clean. Result: `merge conflict in 0
file(s)`, a remedy saying "already clear", and a record whose own probe retires
it on the next command. Two fixes:

1. the conflict set is read **before** the rollback, and passed into both
   `MergeResult` and the remedy selection;
2. on a **successful** rollback **no `MERGE_CONFLICT` is recorded at all** —
   there is no durable git condition to record, and an error whose probe says
   "resolved" the moment it is written is DEF-015's shape manufactured rather
   than deferred. The condition is not lost: the dirty gate re-derives it every
   sync. A failed rollback (local work stuck in a stash) *is* durable and is
   still recorded.

Measured after, on a genuinely conflicting `--merge`:

```
✗ consumer: merge conflict in 1 file(s):
✗     SHARED.md
✗   nothing was changed — the merge was rolled back, so the store is exactly
    where it was, and 1 local file(s) conflict with upstream. Commit or drop the
    local work in <store> (`git status`), then `... sync consumer`
record errors: []          HEAD == record          stash: 0
agent edit still present; materialization still a directory
```

### HIGH-3 — the guard I called load-bearing had zero coverage

`isAuthoredDirty`'s second half, whose javadoc says it "must still stop an
overwrite". Review replaced the body with `return
hasAuthoredWorktreeChanges(storeDir)` and ran everything: **1224 cases, 135
suites, zero new failures.** My probes 2 and 3 pinned the two wrong fixes I
anticipated; this was the third, in the direction I had *named* as load-bearing,
and nothing was watching it. Two cases now cover it — a commit ahead of the
baseline with a clean tree, and the same alongside a dereference.

### What the review confirmed, and one prediction of mine it falsified

It ran `project-child-home` for me: **BUILD SUCCESSFUL in 3m52s, 12/12**,
including the three `preserves.edits` nodes. The gap I disclosed is closed.

But I had predicted that graph was "the closest thing the suite has to my
negative control". **It surfaced none of the three HIGHs.** That prediction was
wrong and the reason is worth keeping: `project-child-home` exercises
materialization and reconcile, not the `sync`/`sync --from` handlers where all
three defects lived. Proximity of *subject* is not proximity of *code path*, and
I used the first as evidence for the second.

## What was cut

**1. The exact index shape is not reproduced.** Stages 1 and 3 at `120000` with
stage 2 absent needs the unit's real pre-migration history, which was cleared by
hand on 2026-08-20. What the fixture produces matches the operator's state in
every respect an operator can act on, and not in the index stages. **DEF-013.**
The consequence is concrete: the new remedy has two branches, and the graph
exercises only the second. The first — the operator's actual state — is covered
by a unit test that plants the residue *with git*, not by the graph.

**2. Only the sync path is repaired** — see the section above. **DEF-014.**

**3. SLICE (1) AND ONE ACCEPTANCE ASSERTION WERE NOT DELIVERED.**

I previously wrote that "the plan assumed the digest question and the git
question are one". **That was wrong, and I am correcting it in my own words
rather than having it overruled.** The plan does not conflate them. Slice (1) is
explicitly and only the digest side — *"whether a unit's own .gitignore, or an
extension to Rederivable, or both, keeps scaffolded re-derivable trees out of
THE DIGEST"* — stated separately from slice (2)'s git atomicity, and it carries
its own acceptance assertion: *"An excluded path is not hashed, not copied, and
not deleted."* The plan was right and my reading of it was not.

The correct statement is: **slice (1) was not delivered, and neither was that
acceptance assertion.** I delivered slices (2), (3) and (4) plus the git-surface
half of "not deleted" — the escrow — but nothing in this diff keeps a scaffolded
tree out of the **digest**. So:

- `13 and 12 entries against the root store's 10` is **unchanged**;
- GOAL-sync-quiet's `776 of 889` lines are **not** removed;
- my `enabling` contribution to GOAL-sync-quiet is **not delivered**.

Nothing currently owns shrinking that input — HIS-2 owns the rendering bound,
which is the other half. That re-scoping is the epic agent's call, not a defect
in the plan.

The distinction matters beyond bookkeeping: *"the plan was wrong"* invites
amending the plan, and *"a slice was not delivered"* invites finishing the work.
Only the second is true here.

**4. `Rederivable` is unchanged**, and deliberately: `sdk` and `standard-nodes`
are generic enough to be real content elsewhere, which is the plan's own
argument, and nothing in the git-surface fix needs a name list.

## Validation

| gate | result |
| --- | --- |
| `jbang RunTests.java` | **ALL PASSED** — 135 suites, **1230** cases, including the **10** `DereferencedStoreLinkSyncTest` cases and the **4** `SyncPathsAgreeAboutDirtyTest` cases |
| `uv run pytest specs/` | **38 passed** in 1.81s |
| `run_tlc.sh HomeIntegrityInternal` | **No error has been found** — 11 states, 10 distinct |
| `run_tlc.sh` regression `_silentdrift` | **`Invariant RecordDescribesItsStoreOrSaysWhy is violated`** — the spec's own control still reddens |
| `run.py sync-settles` | **RED without the fix** — `BUILD FAILED in 59s`, node `failed`, runId `20260821-191159`. **GREEN with it** — re-run after the review fixes: `BUILD SUCCESSFUL in 1m 1s`, node `passed`, runId `20260821-201708`. |
| `run.py project-child-home` | **BUILD SUCCESSFUL in 3m 49s, 12/12**, runId `20260821-201827` — re-run on my own tip rather than relying on the reviewer's run, because `SyncFromLocalDirHandler` changed after it. |

**No spec change.** `RecordDescribesItsStoreOrSaysWhy ==
(hi_record_revision = hi_store_revision) \/ hi_record_error` already forbids the
state; the old code satisfied it only by the *second* disjunct (a record behind
its store, but with an error recorded). The atomicity fix makes the first
disjunct hold instead — the merge is rolled back, so the record and the store
agree. That is a strictly stronger implementation of an unchanged invariant, so
there was nothing to add to the model.

### The second graph set, and why it is only one

Per the owner's 2026-08-21 policy I ran my declared graph plus **any graph whose
fixtures exercise the sources I edited**, named with its reason:

- **`sync-settles`** — declared (`conflict_keys.test_graph`).
- **`project-child-home`** — `ChildHomeMaterializer` is in my declared slice and
  this graph is what exercises it, and more to the point its fixture builds
  child homes containing *exactly* the in-unit store links this change reasons
  about (`ChildHomeResolved` injects one deliberately, and
  `ChildHomeSyncPreservesEdits` then syncs the result). The edit-preservation
  nodes are the closest thing the suite has to my negative control, so a
  regression in `hasAuthoredWorktreeChanges` should surface there.
  **Run: `BUILD SUCCESSFUL in 3m 49s`, 12/12** (runId `20260821-201827`). It
  passed before the review fixes too — and surfaced none of the three HIGHs,
  which falsified my prediction that it was the closest thing to a negative
  control. See the review section above.

`run.py --all` was **not** run: it is multi-hour and belongs to HIS-6, which
owns the one terminal sweep.
