# HIS-18 — goal contribution

Issue #239. Branch `feature/239-his-18`, based on epic tip `28378e7`, worktree
`../wt-239-his-18`. This ticket is HIS-4's slice (1), which HIS-4 did not
deliver and said so in its own words. The plan was not amended; the work was
finished.

## The headline

**889 file lines in the committed baseline record become 124.** One unit —
`spec-double-compiler`, which was 776 of the 889 — becomes 25. The rollup HIS-2
bounded is unchanged in size (8 lines either way, 474 → 472 characters), which
is exactly the shape the goal asked for: *the record's INPUT shrinks, so the
rollup describes fewer real changes rather than the same changes more briefly.*
`renderDetailed()` — the surface clause 2 promises is still reachable — goes
**896 → 131 lines and 88,309 → 5,639 characters, a 93.6% reduction on the
detail surface at unchanged information content**, because what left was never
information.

Both numbers are produced by running the committed record's own path lists
through the **production** predicate and rebuilding a real `DriftReport`, not by
regenerating a baseline the README forbids regenerating.

## What I changed

| file | what |
| --- | --- |
| `shared/util/GitIgnoreRules.java` (new) | the unit's OWN declaration: its `.gitignore` files, its index, and the two clauses that keep the rule honest |
| `bindings/ChildHomeMaterializer.java` | `isUnowned` gains the declaration alongside `Rederivable`; both walkers and `collectUnownedRoots` ask it, so the skip is on both sides |
| `ScaffoldedTreeIsNotContentTest` (new) | 7 cases: *not hashed*, *not copied*, *not deleted*, separately; the falsifier; the tracked clause; the tracked ancestor |
| `RunHis18.java` (new) | the one-suite runner the vacuity record names |
| `probes/his-18/RenderBaseline.java` (new) | the goal measurement, through the product's own renderer |

**No new persisted state, no schema change, no new field.** The declaration is
read from the tree, live, on every walk. A `rederivablePaths[]` in the
materialization record would have been this epic's own recurring defect wearing
this ticket's name.

**`Rederivable` is unchanged**, deliberately, and the reason is now asserted
rather than argued — see V4 below.

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

### Two caveats I will not bury

1. The `.gitignore` files and indexes consulted are the ones in the root store
   **today**, not those of 2026-08-19. A unit whose declaration changed since is
   scored against the new one. Reconstructing seven units' historical ignore
   files is not available, and inventing them would be worse.
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
| `jbang RunTests.java` | **ALL PASSED** — 144 suites, **1342** cases, including the 7 new `ScaffoldedTreeIsNotContentTest` cases |
| `uv run pytest specs/` | **38 passed** in 1.83s |
| `run.py sync-settles` | **RED then GREEN, on this change.** Red at runId `20260822-223718`, `BUILD FAILED in 59s` — the node's own vacuity guard, see below. Green at runId `20260822-224910`, **`BUILD SUCCESSFUL in 1m 2s`** |
| `run.py project-child-home` | see below |
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

`run.py --all` was **not** run: it is multi-hour and belongs to HIS-6, which owns
the one terminal sweep and the goal scorecard.

## Vacuity checks

`results/epic-home-integrity-sync/probes/his-18/vacuity-checks.txt`, five
probes, each stating its file, its substitution, and **that the substitution
matched exactly once**. Runner `jbang RunHis18.java`, shipped.

| probe | what was disabled | which case reddened, and its message |
| --- | --- | --- |
| V1 | the declaration never consulted — the pre-HIS-18 world | (1) *not hashed* — ``expected <f7686970…> but was <c3ed26ba…>``; (2) *not copied*; (5) the tracked clause's narrow half |
| V2 | "a tracked path is never ignored" | (5) — ``a committed file its own .gitignore matches still reaches the child home`` |
| V3 | the carry-over back on `Rederivable` only — **the delivered sibling ALONE** | (3), and ONLY (3) — ``the excluded tree survives the wholesale replace — excluding a path makes it invisible, not disposable`` |
| V4 | **the rejected design**: `sdk`, `standard-nodes`, `build-logic` added to `Rederivable.OUTPUT_ROOTS` | (4) — ``a unit that never declared build-logic generated keeps it, dereferenced into the child home as content`` |
| V5 | the tracked-ANCESTOR clause | (7) — ``a link the unit's own history holds at mode 120000 is content`` |

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

### One precondition is still where it reddens, and it is recorded as such

Under V1, case (3) fails on `precondition: the refresh really replaced the
tree`. In the pre-HIS-18 world the child copy LOOKS edited — the dereferenced
tree is hashed — so the unit is held back and no wholesale replace happens at
all. The claim is unreachable in that world by construction. V3 is the probe
that reddens case (3)'s claim, and it is the sharper one.

### Mechanism B — the fixture really does express the defect

Answered by case (4) rather than by assertion: the SAME fixture with the
scaffolder's block absent keeps the dereferenced trees and its two digests
**diverge**. Case (1)'s equality is therefore not the structural equality of two
identical walks. Case (1) also asserts its own preconditions — the three paths
are symlinks in the store, each resolving to a real tree with content — before
it asserts anything, and case (0) asserts the scrape found the scaffolder's
three lines and not an empty list.

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

## What I am unsure about

1. **The projection is a projection.** It applies today's declarations to a
   record from three days ago and infers directory-ness structurally. I believe
   `889 → 124` within the stated bounds (102 under the other leaf reading), and
   I would not defend a single-figure precision. The bounds are reported for
   that reason.
2. **Whether the tracked/untracked split is where the line belongs.** A unit
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
3. **Cost.** `GitIgnoreRules.forUnit` reads the index through jgit on every
   `plainView`. On a 22,807-entry repository that is milliseconds and no
   subprocess, and the full suite's wall time did not move. I have not profiled
   a home with many large units, and I did not add a cache, because a cache
   keyed on a tree that a swap replaces mid-command is a defect waiting to
   happen.
4. **jgit's ignore semantics are trusted, not re-implemented.** I cross-checked
   `GitIgnoreRules` against `git check-ignore` over all 776 baseline paths and
   got **identical verdicts** (749 ignored as files, 772 as directories). That
   is one corpus, not a proof.
5. **I did not touch `specs/desired_program_model/ticket_plan.yaml`.** HIS-16
   and HIS-17 are in flight against the same file and I stop at PR open; marking
   HIS-18 delivered is the epic owner's step at merge, as it was for HIS-15.
