# HIS-18 — goal contribution

Issue #239. Branch `feature/239-his-18`, based on epic tip `28378e7`, worktree
`../wt-239-his-18`. This ticket is HIS-4's slice (1), which HIS-4 did not
deliver and said so in its own words. The plan was not amended; the work was
finished.

## The headline, and the disclaimer that belongs on it

**Read the disclaimer first.** The review of PR #240 is right that I headlined a
number without it, having applied exactly this disclaimer to the entry-count
claim two paragraphs later.

> **`889 → 124` IS A RETRODICTION.** It scores the committed baseline record's
> path lists — and those paths are in `removedFiles` *because they were
> deleted*. Asked about trees that still exist today, the same predicate hides
> **22 paths across the whole root store**: 8 ACP session logs in
> `acp-cdc-ai-python` and two `.egg-info/` trees at 7 each. `spec-double-compiler`
> — the unit that is 776 of the 889 — contributes **0**. **Not one of
> `build-logic`, `sdk` or `standard-nodes` is excluded anywhere**, because those
> bindings are gone from every consumer. A record generated today shrinks by
> single-digit lines.
>
> **HIS-6 must not read 86% as forward-going.** I reproduced the reviewer's
> figure independently rather than taking it: `RenderBaseline.java` now prints
> both, and both are committed under `probes/his-18/`.
>
> What the retrodiction *does* establish is that the mechanism is real and its
> size is not marginal: over a record that captured those trees while they
> existed, the declaration removes 86% of it. What it does not establish is that
> anything will shrink next Tuesday. Those are different claims and rev 1 ran
> them together.

With that stated, the measured figures:

**The committed baseline record's 889 file lines become 124.**
`spec-double-compiler`, which was 776 of the 889, becomes 25. The rollup HIS-2
bounded is unchanged in size — 8 lines either way, 474 → 472 characters — which
is the shape the goal asked for: *the record's INPUT shrinks, so the rollup
describes fewer real changes rather than the same changes more briefly.*
`renderDetailed()`, the surface clause 2 promises is still reachable, goes
**896 → 131 lines and 88,309 → 5,639 characters (93.6%)**.

Produced by running the committed record's own path lists through the
**production** predicate and rebuilding a real `DriftReport` — not by
regenerating a baseline the README forbids regenerating.

## What I changed

| file | what |
| --- | --- |
| `shared/util/GitIgnoreRules.java` (new) | the unit's OWN declaration: its `.gitignore` files, its index, and the two clauses that keep the rule honest |
| `bindings/ChildHomeMaterializer.java` | `isUnowned` gains the declaration alongside `Rederivable`; both walkers and `collectUnownedRoots` ask it, at a shape the dereference cannot move, so the skip is on both sides — plus `holdsUndeclaredWork`, which is what keeps invisible from meaning disposable on the teardown |
| `ScaffoldedTreeIsNotContentTest` (new) | **14 cases**: *not hashed*, *not copied*, *not deleted*, separately; the falsifier; the tracked clause and the tracked ancestor; the teardown guard and its narrowness; the two walkers agreeing and the narrowness of that; hold-back clause 2; the missing index; the carry-over collision |
| `RunHis18.java` (new) | the one-suite runner the vacuity record names |
| `probes/his-18/RenderBaseline.java` (new) | the goal measurement, through the product's own renderer |

**No new persisted state, no schema change, no new field.** The declaration is
read from the tree, live, on every walk. A `rederivablePaths[]` in the
materialization record would have been this epic's own recurring defect wearing
this ticket's name.

**`Rederivable` is unchanged**, deliberately, and the reason is now asserted
rather than argued — see V4 below.

### What the review of PR #240 changed

Two blockers, and both were real. Each was reproduced as a failing case on its
own claim *before* being fixed, and each has its own probe.

**BLOCKER 1 — the exclusion made an agent's authored file DISPOSABLE.**
`treeDigest` applies the declaration, and `isLocallyModified` reads
`treeDigest` — a predicate whose own javadoc calls it *"the predicate a prune, a
teardown and a close-out consult before destroying it."* So a worktree agent
writing `results/findings.md` into a unit whose `.gitignore` has `results/` got:
`home sync` reporting UNCHANGED so it never went up, `home close-out` finding no
blocker, and `wt close` deleting the child home outright with an empty preserve
set. **`carryOverUnownedTrees` guards the swap; nothing guarded the teardown**,
and the acceptance line that failed was this ticket's own — *invisible, not
disposable*. Not hypothetical: `acp-cdc-ai-python/scripts/sources/logs/*.jsonl`
are ACP session transcripts and are 8 of the 22 net-new exclusions.

Fixed with `holdsUndeclaredWork`: does this copy hold anything under an excluded
path that the source cannot be shown to have? It does not hold every unit back,
because an excluded path is **not copied either** — a fresh child home has
nothing there, so it fires only where the child home itself put something there.
Cases (8) and (9) are the claim and its narrowness; V6 is the probe.

**BLOCKER 2 — a trailing-slash rule made the two walkers disagree permanently.**
My javadoc said *"Both walkers ask at the top, so the answer is the same on the
source side and the destination side by construction."* **That was false.** The
store holds `test_graph/sdk` as a LINK and the child holds it as a real
DIRECTORY, so a directory-only rule (`sdk/`, `build/`, `dist/` — the dominant
convention) matched one and not the other: one side hashing a path the other
does not have, permanently, in every later drift report — verbatim the failure
my own case (7) message names. My fixture could not express it because
`ensure_provider_binding_ignores` emits `/build-logic` with **no** trailing
slash.

Fixed by making the question's answer **invariant under the dereference**:
directory-ness is read as the materialized view will have it, following links. A
narrow, one-directional divergence from `git check-ignore` — a symlink to a
DIRECTORY under a directory-only rule; a symlink to a FILE is unaffected, and
case (11) pins that. The double-ask across the dereference frame went with it.
V7 is the probe.

**MED-3 — "fails towards visibility" was half true.** `readIndex` returned an
EMPTY set for a MISSING index, so the declaration became authoritative on the
strength of a file that is not there; measured, a committed file became ignored
after `rm .git/index`. Now one rule for all three ways of not having an index —
no `.git`, no `index`, unparseable `index` — **no readable index means nothing
is ignored**. Measured before choosing it: the only units in the root store with
a `.gitignore` and no repository are `slm-agent` and `tracer-agent`, whose
declarations name `__pycache__/`, `*.pyc`, `.pytest_cache/` — all already
`Rederivable`'s — and `.DS_Store`. The rule costs one `.DS_Store` store-wide and
buys a sentence with no exceptions in it. Case (13), probe V8. The fixtures are
git-backed for it, which is what real units are; only `SKILL.md` is added, so
every case still turns on the declaration rather than on the index.

**MED-4 — `core.ignoreCase` is not read** by jgit's matcher, and `git init` sets
it true on APFS. A case-mismatched directory-only rule therefore diverges from
`git check-ignore`, in the direction of NOT ignoring. Recorded in the class
javadoc, and my "identical verdicts" claim is downgraded accordingly: it is
evidence about a corpus with no case-mismatched pair in it.

**The carry-over branch nothing had reached.** Case (3) never reached
`carryOverUnownedTrees`' `if (Files.exists(to)) continue;`, where the
destination's copy is abandoned and destroyed by the swap. Case (14) reaches it —
upstream stops declaring a path generated and puts its own content there — and
the branch now **warns by name** instead of taking it silently. Preserving it is
**DEF-056**.

**GOAL-no-spurious-holdback clause 2 had no case at all.** It does now (case 12):
a unit that genuinely differs is still held back, excluded paths or not.

### Why the unit's declaration and not a name list

`sdk` and `standard-nodes` are ordinary words. A unit that authors a directory
called `sdk` must keep it, and a global list cannot tell that unit from a
test-graph consumer. The consumer's `.gitignore` block is not checked in — the
scaffolder GENERATES it at bind time
(`ensure_provider_binding_ignores`, `skills/test_graph/scripts/_common.py`) —
so the fixture **scrapes those three lines out of the scaffolder's own source**
rather than typing them. Rename them there and this fixture moves with them.

### The two clauses that keep it honest, and what each one cost to get wrong

**1. A tracked path is never ignored.** Not a compatibility nicety. Measured
across the operator's root store, `spec-double-compiler` **tracks 14 files its
own `.gitignore` matches** — `.DS_Store` and twelve generated-then-committed
modules under `examples/distributed_history/specs/generated/spec_unit/`.
Honouring the ignore file without this clause would make committed content
invisible to the digest *and* keep it out of the copy a child home receives:
content quietly dropped, which is the failure this exclusion exists to prevent,
inverted. Case (5) asserts it, and asserts its narrowness in the same breath —
the untracked neighbour under the same ignored directory is still excluded.

**2. An ancestor stops ignoring only when the index holds a blob at exactly
that path.** This one I got wrong first, and `sync-settles`'s own vacuity guard
caught it. A test-graph binding a unit COMMITTED is tracked at mode `120000` and
matched by the very block that declares it generated; after the dereference,
every path in the provider's tree sits under a tracked-but-ignored ancestor. The
source walk emitted **nothing** for that subtree while the store's own walk still
emitted the tracked LINK — two sides disagreeing about one path, which is the
divergence this change exists to remove, wearing a new hat.

The first fix used `trackedAtOrUnder` for the ancestor test, and that is worse:
a directory git merely *descends through* to reach a tracked file is not itself
content, and reading it as one **un-ignores 742 untracked siblings — 772 excluded
paths collapse to 30 and the record stops shrinking at all.** Both readings are
measured; `contains` is the one that ships, and the class javadoc names both so
nobody re-derives the wrong one.

### What is deliberately NOT in scope, and what that costs

- **`HomeCloner` does not consult these rules.** A clone has to hand over a home
  that WORKS, which is why it carries `node_modules/` and in-unit `.venv/` —
  both routinely gitignored. Same asymmetry `Rederivable` already documents
  between `CACHES` and `OUTPUT_ROOTS`, for the same reason.
- **`core.excludesFile` and `.git/info/exclude` are not read.** A home's digest
  must mean the same thing on every machine that reads it; a record whose
  contents depend on the operator's personal global excludes is not comparable
  with the same home elsewhere, which is the whole point of recording it.
- **A TRACKED scaffold link is still hashed, by design.** That case is HIS-4's,
  on the git surface, already delivered (`DereferencedStoreLinks`), and
  `sync-settles` is what proves it red-then-green. HIS-18 owns the UNTRACKED
  generated content. **This split costs nothing measurable:** none of the 776
  paths in the committed baseline is tracked, nor holds a tracked descendant
  (measured, 0 of 776), and in the operator's homes today only the `test-graph`
  unit itself tracks those three names — where the links resolve INSIDE the unit
  and are never dereferenced at all. Case (7) pins the boundary.

## The measurement — GOAL-sync-quiet

`jbang results/epic-home-integrity-sync/probes/his-18/RenderBaseline.java ~/.skill-manager <project home>`

It does **not** regenerate the baseline. It takes the committed record's own
path lists, asks the production predicate which of them would never have entered
the record, rebuilds a `DriftReport` from what survives, and renders it through
`render()` and `renderDetailed()`.

| unit | before | after | removed |
| --- | ---: | ---: | --- |
| deploy-helm | 20 | **6** | 14 the unit declares generated |
| git-epic-workflow | 14 | 14 | — |
| git-issue | 5 | 5 | — |
| git-issue-workflow | 2 | 2 | — |
| plugin-repository | 25 | 25 | — |
| skt | 47 | 47 | — |
| **spec-double-compiler** | **776** | **25** | **751** |

| | before | after | |
| --- | ---: | ---: | --- |
| file lines in the record | 889 | **124** | **86.1% removed** |
| `render()` lines | 8 | 8 | unchanged — the bound is HIS-2's and it holds |
| `render()` characters | 474 | 472 | the rollup describes fewer real changes |
| `renderDetailed()` lines | 896 | **131** | 85.4% |
| `renderDetailed()` characters | 88,309 | **5,639** | **93.6%** |

**Against the ticket's two named figures:**

- **`776 of 889`** → **`25 of 124`**. The unit that was 87% of the record is now
  20% of a record one seventh the size.
- **`13 and 12 entries against the root store's 10`** → **no divergence exists to
  measure.** Read through the digest today, every unit carrying a `test_graph/`
  reports the SAME top-level entry count in the root store and in the project
  home: `deploy-helm` 10/10, `spec-double-compiler` 10/10, `test-graph` 13/13.
  **I am not claiming this change produced that**, and HIS-6 must not read it as
  such: `baseline/README.md` records that those homes were repaired by hand at
  kickoff, and the scaffold links are simply not present in either consumer any
  more. The instrument that *does* decide the entry-count claim is case (1) —
  the child copy's digest equals the store's across a dereference, on a fixture
  that genuinely dereferences — plus the projection above.

### The forward-going number, which is the one HIS-6 should carry

Same predicate, asked about trees that still exist in the root store today.
Reproduced independently of the review, by `RenderBaseline.java`:

| unit | entries digested | NET NEW exclusions |
| --- | ---: | ---: |
| acp-cdc-ai-python | 113 | **8** — ACP session logs (`*.jsonl`, `*.process.log`) |
| deploy-helm | 2,509 | **7** — `src/helm_deploy.egg-info/` |
| hyper-experiments-finance | 252 | **7** — `hyper_experiments_finance.egg-info/` |
| every other unit, `spec-double-compiler` included | — | **0** |
| **total, whole store** | | **22** |

Not one of `build-logic`, `sdk` or `standard-nodes`. Those bindings are gone
from every consumer, which is also **DEF-053**: the baseline's attribution of
the 776 was wrong about *which* paths, and this table is the separate point that
they are *gone*.

### Two caveats I will not bury

1. The `.gitignore` files and indexes consulted are the ones in the root store
   **today**, not those of 2026-08-19. **The review checked this and it is a
   non-issue**: every `.gitignore` in every one of the seven units was committed
   before the record was taken. I was over-cautious; the caveat is kept only so
   the check is on the record, not because it qualifies anything.
2. The record carries paths and nothing else, so a **leaf** entry is genuinely
   ambiguous between a file and an originally-empty directory (`emitDirectory`
   keeps those). `specs/**/states/` is a directory-only rule, so the reading
   matters. **Both readings are run and both are reported**, and the
   conservative one (leaves are files) is the headline above. Reading leaves as
   directories gives **889 → 102** and **spec-double-compiler 776 → 3**. Picking
   the flattering number silently is how a measurement stops being one.

## GOAL-no-spurious-holdback — the guard

An excluded path must not become a new reason to hold a unit back. Case (3)
asserts it directly: after a child home writes into `test_graph/sdk` — the
excluded path — the next materialization **is not held back**, the refresh lands,
and the bytes survive. Full `jbang RunTests.java` is green, including
`ProjectChildHomeMaterializationTest`, which is where hold-back lives.

## Validation

| gate | result |
| --- | --- |
| `jbang RunTests.java` | **ALL PASSED** — 144 suites, **1350** cases, including the **14** `ScaffoldedTreeIsNotContentTest` cases |
| `uv run pytest specs/` | **38 passed** in 1.83s |
| `run.py sync-settles` | **RED then GREEN, on this change.** Red at runId `20260822-223718`, `BUILD FAILED in 59s` — the node's own vacuity guard, see below. Green at runId `20260822-224910`, `BUILD SUCCESSFUL in 1m 2s`. **Re-run after the review fixes: `BUILD SUCCESSFUL in 1m 38s`, runId `20260823-001706`** |
| `run.py project-child-home` | `BUILD SUCCESSFUL in 3m 53s`, runId `20260822-232315` — 12 nodes, 73/73. **Re-run after the review fixes: `BUILD SUCCESSFUL in 4m 1s`, runId `20260823-001855`, 12 nodes, 73/73** |
| `tlc` | **N/A** per the assignment: this ticket states no new invariant, and HIS-5 carries the model work for the epic |

### `sync-settles` went red on this change, and that is the best thing in this ticket

The first run failed with the node's own guard:

```
node sync.settles.scaffold.tree.does.not.strand.baseline failed: the child home
did not dereference test_graph/build-logic into a real directory, so the defect
this node is named for is not present in the fixture and a pass would mean
nothing
```

That guard exists because an earlier version of that node passed over a fixture
that reproduced nothing. It fired here on a **real defect in my change** — the
tracked-ancestor bug described above — which no unit case of mine could see,
because no unit case of mine had a git-backed fixture that committed the links.
It is now case (7), and V5 is its probe. **A graph that can fail for the right
reason found a bug a unit suite could not**, and I would rather record that than
tidy it into "the graph was green".

### The second graph set, and why it is only one

Per the owner's 2026-08-21 policy: my declared graph plus any graph whose
fixtures exercise the sources I edited, named with its reason.

- **`sync-settles`** — declared (`conflict_keys.test_graph`).
- **`project-child-home`** — `ChildHomeMaterializer`'s walkers are what I
  changed, and this graph is what exercises them end to end: its fixture builds
  child homes containing exactly the in-unit store links this change reasons
  about (`ChildHomeResolved` injects one, `ChildHomeSyncPreservesEdits` then
  syncs the result). It is also HIS-4's second set, for the same reason.
  **Run: `BUILD SUCCESSFUL in 3m 53s`, 12 nodes, 73/73 assertions** (runId
  `20260822-232315`), and **re-run on the head that carries the review fixes:
  `BUILD SUCCESSFUL in 4m 1s`, 12 nodes, 73/73** (runId `20260823-001855`) —
  because `isLocallyModified` changed, and that graph's prune and
  edit-preservation nodes are what read it. It queued 22 minutes behind another
  wave-7 agent's lock the first time, which is the serialisation working, not a
  result.

`run.py --all` was **not** run: it is multi-hour and belongs to HIS-6, which owns
the one terminal sweep and the goal scorecard.

## Vacuity checks

`results/epic-home-integrity-sync/probes/his-18/vacuity-checks.txt`, **eight**
probes, each printing its file, **its substitution verbatim**, and that the
substitution matched exactly once. Runner `jbang RunHis18.java` and the probe
driver `probes.sh` both ship.

**MED-8, and the reviewer is right.** Rev 1's header claimed each probe stated
its substitution and **it did not** — the transcripts carried `file:` and
`matched: 1` and nothing else, so the reviewer had to reconstruct all three
mutations from the code to check them. A probe record that cannot be re-run from
the record is not a record, which is DEF-035's exact subject, in the file
written to answer it. Rev 1's summary table also under-reported the collateral:
V2 reddens (5) **and** (7), V4 reddens (4), (7) **and** the link-to-a-FILE case.
The transcripts were honest and the table was not. Both fixed; every case each
probe reddens is now listed.

| probe | what was disabled | every case it reddens |
| --- | --- | --- |
| V1 | the declaration never consulted — the pre-HIS-18 world | (1) not hashed, (2) not copied, (5), (10), (13) on their claims; (3) and (14) on a precondition |
| V2 | "a tracked path is never ignored" | (5) ``a committed file its own .gitignore matches still reaches the child home``, and (7) |
| V3 | the carry-over back on `Rederivable` only — **HIS-4's delivered sibling ALONE** | (3) ``the excluded tree survives the wholesale replace`` and (8). **Not (1) or (2)** — the digest half and the copy half are intact, so the sibling cannot mask the missing half |
| V4 | **the rejected design**: the three names in `Rederivable.OUTPUT_ROOTS` | (4) ``a unit that never declared build-logic generated keeps it``, (7), and the link-to-a-FILE case |
| V5 | the tracked-ANCESTOR clause | (7) alone — ``a link the unit's own history holds at mode 120000 is content`` |
| **V6** | **review blocker 1** — the teardown guard | (8) alone — ``a file the child home wrote under an excluded path is work the parent store cannot be shown to have`` |
| **V7** | **review blocker 2** — directory-ness read as git reads it | (10) alone — ``one side hashing a path the other does not have is a permanent entry in every later drift report`` |
| **V8** | **review MED-3** — a missing index read as "nothing is tracked" | (13) alone — ``with the index gone there is nothing that could rescue a committed path from the declaration`` |

**V3 is the one that justifies the three-case split.** It leaves HIS-4's
delivered escrow doing exactly what it already did and disables only the
carry-over half; cases (1) and (2) stay green and (3) fails alone. A single
assertion covering all three could not have said that.

**V4 is the one the acceptance line asked for.** The plan's argument against a
global name list is now a failing test rather than a paragraph.

### The two mechanism-A corrections these probes forced

Both were found by running the probes, not by reasoning about them.

1. **V1's first run reddened case (1) on a precondition** — `precondition for
   the digest claim: build-logic is not in the child copy at all`. That sentence
   is case (2)'s claim standing in front of case (1)'s. Moved out; V1 now
   reddens on the two hashes.
2. **V3's first run reddened case (3) with a bare `NoSuchFileException` path**
   and no sentence, because `readString` throws before `assertEquals` can speak.
   Split into an existence assertion carrying the claim and a bytes assertion
   behind it.

### Two preconditions are still where they redden, and are recorded as such

Under V1, cases (3) and (14) fail on `precondition: the refresh really replaced
the tree`. In the pre-HIS-18 world the child copy LOOKS edited — the
dereferenced tree is hashed — so the unit is held back and no wholesale replace
happens at all. Those claims are unreachable in that world by construction. V3
is the probe that reddens case (3)'s claim, and it is the sharper one.

### Mechanism B — the fixture really does express the defect

Answered by case (4) rather than by assertion: the SAME fixture with the
scaffolder's block absent keeps the dereferenced trees and its two digests
**diverge**. Case (1)'s equality is therefore not the structural equality of two
identical walks. Case (1) also asserts its own preconditions — the three paths
are symlinks in the store, each resolving to a real tree with content — before
it asserts anything, and case (0) asserts the scrape found the scaffolder's
three lines and not an empty list.

## One transitional effect HIS-6 will see, and should not read as a regression

Every digest recorded before this change — `home.digest.json`, and the
`sourceDigest` / `entryDigests` in existing `.materialization` records — was
taken with the excluded paths in it. The first command after this lands
recomputes without them, so **one** drift report will show those paths leaving.
That report is the change landing, not drift; the next one is quiet. Hold-back
is unaffected, because `copyUnit` asks the disk before it asks the record
(`settledWithoutARecord`) and both sides of that question are filtered the same
way.

## Homes

Snapshotted before the first command and diffed at the end:
`~/.skill-manager`, `<repo>/.skill-manager`, and the worktree home. **No change
to any reachable home's unit set.** No `project` verb was driven in-process
(DEF-046/047), and everything read from the operator's stores was read-only —
where a git question was needed, the index was copied to the scratchpad first
and `GIT_INDEX_FILE` pointed at the copy, so no command could touch the real one.

## What I deferred

**DEF-058 — `baseline/README.md` attributes the 776 lines to the wrong paths,
and every downstream reading inherits it.** The README says those 776 removed
files "are the dereferenced in-unit symlink trees (`test_graph/build-logic`,
`test_graph/sdk`, `test_graph/standard-nodes`)". They are not. Measured: **3 of
the 776** contain any of those names, and all three are inside a nested example
project. The 776 are `examples/distributed_history/**` (745), `specs/.history/**`
(24) and a handful of `.DS_Store`s — untracked generated content the unit's own
`.gitignore` covers, 749 of them confirmed by `git check-ignore` and by this
change's own predicate independently. The mechanism the epic reasoned from was
therefore *approximately* right — a unit's re-derivable trees being hashed — and
the specific paths named in the baseline, the plan, the issue and HIS-4's
close-out are wrong. It did not change what to build, and I have left the
baseline file untouched (it is the "before" side and must not be edited after
the fact), but **HIS-6 must not read "the scaffold trees" as the thing that
moved.** What moved is the untracked generated content, and this file is where
that correction lives.

**DEF-059 — `#180` is still open and now has a matcher it could use.**
`MarkdownImportValidator.validateProject` still walks with five hard-coded
directory names and no git semantics; `GitIgnoreRules` is exactly the instrument
that issue asks for. Out of scope here (a resolver surface, its own graph node,
its own baseline), but the second implementation nobody should write now exists.

**DEF-060 — the exclusion is not applied to `HomeCloner`, and nothing asserts
the asymmetry.** It is documented in two javadocs and justified, but a future
edit could route the cloner through `GitIgnoreRules` and hand over a home whose
`node_modules/` is missing, with no test failing. `Rederivable` has the same
undefended asymmetry today, so this is one guard for both, not a new one for
mine.

**DEF-056 — a carry-over collision destroys the child's copy, and now says so.**
`carryOverUnownedTrees`' `if (Files.exists(to)) continue;` is reached when
upstream STOPS declaring a path generated and puts its own content there: the
staged tree already holds the path, the destination's copy cannot be carried
across, and the swap destroys it. Nothing in this suite reached that branch
before — the review found it. It now **warns by name**; preserving the bytes
(escrow, as HIS-4 does on the sync path) is deferred, because `MaterializationEscrow`
is HIS-4's surface and the fix belongs with whoever owns it. Case (14) pins the
loss so it is a known one rather than a silent one.

**DEF-057 — the baseline README says 88,308 characters and today's renderer
emits 88,309 over the same record**, and `HomeDriftGateTest` asserts the README
figure. One character, and I have deliberately changed neither: a baseline
edited after the fact is worth nothing, and a test quietly re-pointed at a new
number is worse. Flagged for the owner to decide which is authoritative before
HIS-6 reads it.

**Not deferred, but named: a path under an excluded declaration no longer syncs
UP.** That is the other face of "not hashed", not a defect — but it is a real
consequence. `home sync` reports such a unit UNCHANGED, so an agent's file under
`results/` stays in the home that wrote it. It is not destroyed (that was
blocker 1, fixed) and the teardown reports it, but nothing carries it onward. On
the operator's store the affected set is the 22 paths in the table above. If the
owner wants those carried, that is a `reconcile` question and a bigger surface
than this ticket.

## What I am unsure about

1. **The projection is a projection.** It applies today's declarations to a
   record from three days ago and infers directory-ness structurally. I believe
   `889 → 124` within the stated bounds (102 under the other leaf reading), and
   I would not defend a single-figure precision. The bounds are reported for
   that reason.
2. **Whether the tracked/untracked split is where the line belongs.** The review
   did not challenge it, and blocker 2's fix narrows what turns on it, but the
   argument below is unchanged and I still hold it only moderately. A unit
   that COMMITTED its scaffold links still hashes them, and I argued that is
   HIS-4's case rather than mine. The alternative — excluding a tracked
   mode-`120000` link the unit also declares generated — is coherent, would fix
   the operator's *historical* shape, and would require extending
   `DereferencedStoreLinks.in` (which today requires the path to be a real
   DIRECTORY, and would see an absent one as an authored deletion and refuse
   every sync forever). I judged that too much blast radius for a ticket told
   not to redo HIS-4's half, and the split costs nothing on any current
   measurement. If HIS-13 or HIS-6 disagrees, the change is one clause in
   `GitIgnoreRules` plus one in `DereferencedStoreLinks`, and case (7) is the
   test that would have to be inverted.
3. ~~**Cost.**~~ **SETTLED, and my caution was unfounded.** The review measured
   the largest unit in the store — 26,930 entries — at **1,620 ms with the
   declaration against 1,596 ms without**, warm. Not adding a cache is the right
   call and there is nothing here to worry about. Recorded as answered rather
   than deleted, because I raised it.
4. **jgit's ignore semantics are trusted, not re-implemented.** I cross-checked
   `GitIgnoreRules` against `git check-ignore` over all 776 baseline paths and
   got identical verdicts (749 ignored as files, 772 as directories). **That is
   evidence about a corpus with no case-mismatched pair in it**, and jgit does
   not read `core.ignoreCase`, which `git init` sets true on APFS (MED-4). It is
   also now knowingly not-git in one narrow place: a symlink to a directory
   under a directory-only rule, which git keeps and this excludes, for the
   reason blocker 2 forced.
5. **The declared `conflict_keys` are wrong in both directions**, and the epic
   owner is fixing them in the plan: `Rederivable`, `UnitDigest` and the
   `sync-settles` node are over-declared (I did not change any of them —
   `Rederivable` is byte-identical), while `RunTests.java` and
   `deferred/backlog.yaml` are under-declared and **HIS-16 touches both**.
   Recorded here so the promotion order is read with it.
6. **I did not touch `specs/desired_program_model/ticket_plan.yaml`.** HIS-16
   and HIS-17 are in flight against the same file and I stop at PR open; marking
   HIS-18 delivered is the epic owner's step at merge, as it was for HIS-15.
