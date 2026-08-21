# HIS-4 — goal contribution

Issue #216. Branch `feature/216-his-4`, based on epic tip `dc97f2a`, worktree
`../wt-216-his-4` — the first ticket in this epic that could actually use its
declared worktree, because HIS-10 is what made a clone usable.

## The headline

**The defect is reproduced at the project tier, synthetically, and it is fixed.**
The graph node this ticket was told to redden is red before the change and green
after, with the production diff as the only variable.

The mechanism, stated once: `ChildHomeMaterializer` dereferences an in-unit
symlink that points into the parent store into a real directory, because that is
what makes a child home independent (CHM-5). It does so **inside a git working
tree** that tracks that path at mode `120000`. `git status` reads it as a
deletion; `sync` reads a deletion as "extra local changes"; and from that moment
the unit is never syncable again.

## What I changed

| file | what |
| --- | --- |
| `source/DereferencedStoreLinks.java` (new) | one **re-derived** predicate: which paths are a dereferenced store link rather than somebody's work |
| `source/GitOps.java` | `indexEntries` (mode-aware), `blobText`, `isMidMerge`, `hasStash`, `checkoutPaths` |
| `effects/SyncGitHandler.java` | the dirty gate asks about **authored** changes; the merge **escrows** the materialized trees; a failed stash pop **rolls the merge back** |
| `app/ReportUseCase.java` | the `MERGE_CONFLICT` remedy is chosen by looking at the store, so it is one that clears the state it is printed for |
| `effects/ConsoleProgramRenderer.java` | routed through that single definition; the second spelling is gone |

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
| 5 | the whole production diff reverted (`git checkout` + delete the new class) | the **graph node** — see below |

Probes 2 and 3 are the ones that matter: they redden against *plausible fixes*,
not against no fix. The cheap version of this change ("a deleted symlink is
never a local change") passes probe 1 and fails 2 and 3, and it silently
discards an agent's work.

## GRAPH_RESULTS_PLACEHOLDER

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

## What was cut

**1. The exact index shape is not reproduced.** Stages 1 and 3 at `120000` with
stage 2 absent needs the unit's real pre-migration history, which was cleared by
hand on 2026-08-20. What the fixture produces matches the operator's state in
every respect an operator can act on, and not in the index stages. **DEF-013.**
The consequence is concrete: the new remedy has two branches, and the graph
exercises only the second. The first — the operator's actual state — is covered
by a unit test that plants the residue *with git*, not by the graph.

**2. Only the sync path is repaired** — see the section above. **DEF-014.**

**3. The digest side is untouched.** The ticket's slice (1) offers "the unit's
own `.gitignore`, or an extension to `Rederivable`, or both" for keeping
scaffolded trees **out of the digest**. I did neither. The digest question and
the git question turned out to be genuinely separate: the digest sees these
trees because they are *content the child home holds*, and the sync refuses
because *git tracks a symlink there*. Fixing the second does not touch the
first, so the `13 and 12 entries against the root store's 10` measurement is
unchanged and GOAL-sync-quiet's `776 of 889` lines are **not** removed by this
ticket. My `enabling` contribution to GOAL-sync-quiet is therefore **not
delivered**; HIS-2 owns the rendering bound and this ticket does not shrink the
input to it. Said plainly because the plan's `expected_effect` for that goal
("removes the cause of 776 of the baseline's 889 lines") is not something this
diff can claim.

**4. `Rederivable` is unchanged**, and deliberately: `sdk` and `standard-nodes`
are generic enough to be real content elsewhere, which is the plan's own
argument, and nothing in the git-surface fix needs a name list.

## Validation

| gate | result |
| --- | --- |
| `jbang RunTests.java` | RUNTESTS_PLACEHOLDER |
| `uv run pytest specs/` | **38 passed** in 1.81s |
| `run_tlc.sh HomeIntegrityInternal` | **No error has been found** — 11 states, 10 distinct |
| `run_tlc.sh` regression `_silentdrift` | **`Invariant RecordDescribesItsStoreOrSaysWhy is violated`** — the spec's own control still reddens |
| `run.py sync-settles` | GRAPH_PLACEHOLDER |

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
  PROJECT_CHILD_HOME_PLACEHOLDER

`run.py --all` was **not** run: it is multi-hour and belongs to HIS-6, which
owns the one terminal sweep.
