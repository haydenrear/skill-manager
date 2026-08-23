# HIS-17 — goal contribution

Issue #238. Branch `feature/238-his-17`, base `epic/home-integrity-sync` at
`a978bb0`. Wave 8, promotion order 14, predecessor HIS-18.

Goal: **GOAL-one-home-one-answer** (`direct`).

Declared expected effect: *"four readings of one rule, three of them in tests,
become one reading plus three cross-checks against it."*

---

## 0. What this ticket does NOT deliver, up front

**Read this before §1.**

**The expected effect is only two-thirds delivered, and the shortfall is
structural.** The three private readings were not collapsed into one. They were
each given a cross-check, and the *cross-check* was collapsed into one shared
class (`test_graph/sources/lib/HomeIsolation.java`). The walks themselves stay,
deliberately — a node whose only reading of the filesystem is production's own
report cannot catch production being wrong, and `home clone` exiting 0 is a
claim this epic has caught being false. So the honest count is **one owner plus
three independent readings that now argue with it out loud**, not one reading.

**One of the three reds had a different cause than #238 stated, and I did not
fix it into agreement.** `artifact-dag`'s
`home_verify_names_the_build_that_completes_the_clone` was predicted to be a
verify-TEXT drift from HIS-12/HIS-14. Re-measured, it is not: **ARTI-07 landed**,
`home verify` no longer refuses a lazy clone at all, and the assertion became
unsatisfiable rather than merely stale. It was **removed**, with the measurement
and reasoning in the class javadoc. That is an assertion deleted from a graph by
a ticket that does not own that ticket's contract, and a reviewer should look at
it directly.

**Review of #242 found the first version's substitute oracle was on a disjoint
branch, and that was a miss, not a disclosure.** The walk was widened on the
**regular-file** branch; the decoy added for it was a **symlink**, which
`verifyRoots` decides *before* the regular-file walk runs and *without*
consulting descent records or byte accounting. So the descent-record accounting
was exercised in **neither direction**, and "asserted in both directions on
every run" read as if it covered the widening. It did not. **V5 now runs the
exact failure that got through** — production's accounting loosened to a
filename check, *the same shape the graph itself had shipped* — and every
assertion in the first version of this PR is in the pass column. Fixed two ways
(§2), and V5/V6 measure both.

**The independent-scan assertion still cannot fail when production's exemption is
removed** — V1 proves it, and that is this ticket's own defect reproduced rather
than a gap in the probe. What changed after review: the acceptance is no longer
satisfied only *at node level*. `the_walk_and_production_agree_about_this_clone`
reddens **by name** under V1, so the disagreement is asserted rather than
deduced from two separate reds.

**`artifact-dag` is still red at hand-off**, on a node that is not mine and is
ARTI-08's declared red: DEF-066. **`onboard` is green** — review of #242
overturned the first version's decision to file-and-revert the budget fix, and
all four budgets ship here with DEF-065 left open on the ~14 s floor beneath
them.

---

## 1. Re-measured first, and one of four had moved

#238 measured on `640080c`; HIS-16 and HIS-18 landed after that, and HIS-16
wired `HomeMembershipLaw` into 24 graphs including two of mine. Everything below
is re-measured on `a978bb0`.

| graph | node / assertion | #238 said | re-measured `2026-08-23` |
| --- | --- | --- | --- |
| `home-clone` | `home.cloned.into.project` · `independent_scan_finds_no_owned_surface_naming_the_source` | `leakCount: 1` | **confirmed** — `leaks=[STATE home.provenance.json]`, run `20260823-161437`, **9 nodes skipped behind it** (#238 said the leak; it did not say the blast radius) |
| `checkout-home` | same node, second graph | same | **confirmed** — the graph lists `sources/home-clone/HomeClonedIntoProject.java`, so one fix serves both |
| `artifact-dag` | `lazy.clone.declares.without.building` · `home_verify_names_the_build_that_completes_the_clone` | broken by HIS-12/HIS-14 remedy text | **DIFFERENT CAUSE.** `verifyExit=0`. Production prints `✓ every reference … resolves`. There is no refusal to carry a remedy — **ARTI-07 shipped.** Run `20260823-161915` |
| `onboard` | `onboard.skills.installed` | errored, 10 s, twice | **confirmed** — 0-byte stdout across 3 attempts, run `20260823-164343` |

**The `artifact-dag` correction matters beyond this node.** Its sibling
`cold.artifact.refusal.names.build`, written red for ARTI-07 and skipped behind
the failure for however long, **passes now**. Two tickets' acceptance criteria
had been green for an unknown period behind one red node.

## 2. What changed

| file | change |
| --- | --- |
| `test_graph/sources/lib/HomeIsolation.java` | **new.** One place a graph asks production "does anything here name another home", shared by both graphs that now do. Carries the descent-record filename, production's verdict sentence, the decoy plant/remove/assert-gone cycle, and a precondition reader |
| `test_graph/sources/home-clone/HomeCloneSupport.java` | `referencesTo` reports the descent record as `DESCENT` instead of `STATE` — the single filename at the **scanned root**, never a pattern, and **reclassified rather than skipped** so the caller can require it was there |
| `test_graph/sources/home-clone/HomeClonedIntoProject.java` | +4 assertions: the descent precondition, production's isolation verdict, the planted-decoy control, the decoy's removal. Serves `home-clone` **and** `checkout-home` |
| `test_graph/sources/artifact-dag/LazyCloneDeclaresWithoutBuilding.java` | obsolete verify-text assertion removed with its reasoning; `--against` added so the source-reference half of production's check actually runs; the same four cross-check assertions |

### The design decision a reviewer should push on

`a4a95cb` cross-checked with **`home verify` exit 0**. I did not, and the reason
is measured: on `home-clone`'s own clone,
`home verify --home <clone> --against <fixture>` **exits 1** — on
`bin/cli/hc-venv-tool`, the dangling shim that fixture plants *on purpose* so
that "a skipped toolchain root is reported" has something to report. That is the
**provisioning** half of the command, not the isolation half. Binding an
isolation cross-check to the exit code would redden it for a reason the node is
not about, and the only repair for that is widening the fixture until it stops
saying anything.

So the cross-check reads the **isolation verdict** out of the report text.
`home verify` has no `--json`.
**Strike the sentence that said "there was no third option"** — review of #242
named three, and it was right. `home clone --json` already emits `"clean"` from
**the same `verifyRoots` call**, and this node already asserts it as
`home_clone_reports_clean`; the fixture could have been split; or the exit code
could have been checked more narrowly. What the text read buys over the JSON one
is that it is **re-askable of a standing home** rather than produced once at
clone time — but **no probe here shows it catching anything the JSON reading
misses.** Across V1, V2b, V3, V5 and V6 the two move together in every run
except V5, where *both* stay green and the tamper control is what fires. **A text expectation is exactly what broke
`artifact-dag`**, which is not lost on me — the mitigation is that this one is
asserted in **both directions on every run**. If production's sentence ever
moves, the planted-decoy assertion reddens the same day rather than the clean
half going quietly green forever. That property is what makes it different from
the assertion I deleted, and it is the part worth reviewing.

## 3. The sweep — slice item 3

Full list with a disposition for each, **including the eight where nothing
changed**, in `probes/his-17/sweep-private-isolation-scans.md`. Summary: **eleven
implementations, nine live besides the two already known.**

| # | where | disposition |
| --- | --- | --- |
| 1 | `HomeCloneSupport.referencesTo` via `HomeClonedIntoProject` | **FIXED + cross-checked** — the only functional duplicate of the ticket-lifecycle defect |
| 2 | the same helper via `HomeCloneFixtureBuilt` | no change — inverted polarity, and the fixture is never a clone |
| 3–5 | `ChildHomeSupport.storeLinksBelow`, and the same walk copied into `ProjectDependenciesResolved` and `HarnessChildHomeMaterialized` | no change — **symlink targets only, so no record naming a home can trip them**, and all three graphs carry `HomeFixpointLaw`, which is production |
| 6 | `HomeIntegrity.projectionSourceIsDecidable` | no change — record-driven, never enumerates the file |
| 7 | `OnboardingSupport.ledgerTargetsOutside`/`childHomesOutside` | no change — scoped to two record families; the same graph already runs `home verify` bare and `--against` |
| 8 | `HomeSyncRootToProject:90–98` | no change — scoped to three named files |
| 9–10 | `HomeCloneDescriptorResolves:64`, `CheckoutHomeProvisioned:119` | no change — one file each |
| 11 | `TicketLifecycleSupport.filesNaming` | already fixed in `a4a95cb`; the model generalised here |

**There is no fifth instance of the provenance defect.** Instances 3–5 are one
walk written three times, which is the same smell in a different substrate, and
saying so is more useful than touching three graphs on a hunch.

**The residual risk, named rather than left implicit:** `HomeFixpointLaw` covers
24 of 29 graphs but **passes no `--against`**, so the source-reference half of
production's check is never run by the law — production literally prints
`NOT CHECKED` for it. That is why HIS-10's contract change could not be caught
by the law and had to be caught, four waves late, by three private walks going
red one graph at a time. **DEF-064.**

## 4. Vacuity — four probes, one of them confessed

Full record in `probes/his-17/vacuity-probes.md`. All ran on a **committed**
tree (`probe.sh` refuses a dirty one, and did refuse once — DEF-035 working
rather than being remembered), reverted from a saved byte copy and never
`git checkout`, and every mutation asserted `count(pattern) == 1` before editing.

| probe | mutation | reddened | verdict |
| --- | --- | --- | --- |
| **V1** | production's HIS-10 exemption made unmatchable | `production_agrees_no_path_in_the_clone_names_the_source` **(the claim)**, plus `home_clone_reports_clean`, `home_clone_exits_zero` | the cross-check earns its place; the scan assertion **cannot** move, which is §0's admission |
| **V2** | `leaks = new ArrayList<>()` | `home.clone.fixture.built` — **a precondition** | **VACUOUS.** It did not compile (`leaks` is lambda-captured). Mechanism C presenting as mechanism A |
| **V2b** | `leaks.clear()` before the verdict | **exactly one**: `production_still_refuses_a_planted_path_into_the_source` | the clean half stayed green with the gate off — the decoy is the only thing that noticed |
| **V3** | a correct clone made to leave a real state reference | `independent_scan_finds_no_owned_surface_naming_the_source` **(the claim)**, and production agreed | the walk was **narrowed, not blinded** |

**V2 is the one to read.** "I disabled the gate and the graph went red" is true
of that run and means nothing: the mutation never reached the code, the fixture
collapsed, and the node under test never executed. It was caught only because
the record names *which* assertion reddened and because
`home.clone.fixture.built` asserts its own preconditions — mechanisms A and B of
the ledger, doing their job. Without them this would have been the eleventh
instance, in the ticket written not to be one.

**A fifth check fired for real, unplanned.** `HomeIsolation.plantDecoy` refuses a
target that does not exist, so a control cannot degrade into testing the
dangling-reference branch. On the first `artifact-dag` attempt it refused:
`home.policy.toml` does not exist there, because that graph's workspace helper
writes `policy.toml`. **That is HIS-14's V8 finding — neither "frozen" fixture
was frozen, over that same filename — arriving again and caught by a guard this
time.**

Ledger rows I was named for: *"every private 'does anything name another home'
scan cross-checks production"* — **done** for `home-clone`, `checkout-home`,
`artifact-dag`, with the sweep saying why the rest are not instances. *"a
spelling-pair fixture asserts the two spellings are not substring-compatible"* —
**NOT done**, out of this ticket's slice; it belongs with the path-spelling work
(#206) and is still open.

## 5. Validation

All re-run on `9c36cb7` after review of #242, in worktree `wt-238-his-17`.

### Declared graphs

```
python skills/test_graph/scripts/run.py home-clone
  BUILD SUCCESSFUL in 5m 13s       run 20260823-174248
  14 nodes, 100 assertions, ALL PASSED
  home.cloned.into.project now carries 17 assertions (was 13)
    leakCount=0  toleratedContentReferences=1  sanctionedDescentRecords=1
    verifyExitCode=1
  the four added by review:
    the_walk_refuses_a_descent_record_carrying_an_unaccounted_path      PASS
    production_refuses_a_descent_record_carrying_an_unaccounted_path    PASS
    the_tampered_descent_record_is_restored                             PASS
    the_walk_and_production_agree_about_this_clone                      PASS

python skills/test_graph/scripts/run.py artifact-dag
  BUILD FAILED in 3m 39s           run 20260823-180612
  lazy.clone.declares.without.building  PASSED
      verifyBareExitCode=0   <- ARTI-07's question, its own run
      verifyAgainstExitCode=0 <- isolation, its own run; the split review asked for
  cold.artifact.refusal.names.build     PASSED
  uninstall.prunes.the.subgraph         FAILED  <- DEF-066, ARTI-08's declared
                                                   red. Not mine, unchanged.
```

`verifyExitCode=1` on `home-clone` is expected and is the reason the cross-check
does not read the exit code: it is the fixture's deliberate dangling shim, the
provisioning half. See §2.

### The second set, and why these graphs

The assignment requires naming any graph whose fixtures exercise the sources
edited, with the reason. `HomeClonedIntoProject.java` and `HomeCloneSupport.java`
are listed by **`checkout-home`** as well as `home-clone` — the same node file, a
second graph — and **`onboard`** was #238's fourth red and now carries four
budget changes:

```
python skills/test_graph/scripts/run.py checkout-home
  BUILD SUCCESSFUL in 3m 17s       run 20260823-180101
  8 nodes, 59 assertions, ALL PASSED

python skills/test_graph/scripts/run.py onboard
  BUILD SUCCESSFUL in 5m 6s        run 20260823-175600
  15 nodes, 45 assertions, ALL PASSED    (was: errored at node 9, 6 skipped)
```

`--all` was NOT run: it belongs to HIS-6, which owns the one terminal sweep with
the goal scorecard.

### Unit

```
jbang RunTests.java              1363 passed, 0 failed        ALL PASSED
uv run pytest specs/               38 passed in 1.61s
```

**This branch changes no production code at all** — `git diff a978bb0..HEAD -- src/`
is empty. The unit results are therefore unchanged by construction, and are
re-run rather than carried forward because five probes mutated `HomeCloner` and
`HomeProvenance` and the revert has to be provable.

### TLC

`N/A` per the assignment: this ticket states no new invariant, and HIS-5 carries
the model work plus every regression cfg.

### Before / after

| | before (`a978bb0`) | after (`9c36cb7`) |
| --- | --- | --- |
| `home-clone` | **FAILED**, 1 node red, **9 skipped** | **14/14, 100 assertions** |
| `checkout-home` | **FAILED**, same node | **8/8, 59 assertions** |
| `onboard` | **FAILED**, errored at node 9, **6 skipped** | **15/15, 45 assertions** |
| `artifact-dag` | FAILED at `lazy.clone…`, **4 skipped** | `lazy.clone…` **PASS**; `cold.artifact…` **PASS**; fails 2 nodes later on DEF-066 |

Three of the four graphs #238 named are green; the fourth fails on a node that
is ARTI-08's declared red and was never reachable before this ticket.

### Homes

Snapshotted before the first command and diffed after the last. `ROOT`
(`~/.skill-manager`), `PROJECT`
(`IdeaProjects/skill-manager/.skill-manager`) and the operator's `~/.claude`,
`~/.codex`, `~/.gemini` are **byte-unchanged in unit membership**. Every home
this ticket wrote was a synthetic fixture under `$TMPDIR/sm-testgraph-*`, and
those were deleted between runs — the machine was at 99% / 16 GB throughout and
ended there.

## 6. Contribution to `GOAL-one-home-one-answer`

The goal's metric is *distinct verdicts returned by the readers over one fixed
scenario matrix; target one verdict per scenario*. This ticket does not move the
production readers — that is HIS-10's and HIS-13's. **It moves the instruments,
which is where the goal's own failure mode had reproduced itself.**

Before: on one cloned home, `HomeCloner.verifyRoots` said *clean* and three
private walks said *leak*. **Four readers, two answers, on the isolation
question — inside the machinery built to detect exactly that.** It stayed that
way for four waves, because each walk could only be discovered by running its
own graph, and nobody had.

After: one reader, three independent readings, and **every one of them now
requires production's verdict on the same tree in the same run**, in both
directions. A disagreement is a red on the day of the change, not a wave later.

**What that is worth, measured rather than asserted:** V1 is the exact HIS-10
event replayed. Under it the private walk stays green — the failure mode that
cost four waves is fully reproduced — and the node reddens anyway, through the
assertion added here.

**What it is not.** It is one reading fewer than the expected effect promised
(§0), it leaves `HomeFixpointLaw` asking the weaker question in 24 graphs
(DEF-064), and it leaves one classification still spelled twice (DEF-063).

## 7. Deferred

| id | severity | what | owner |
| --- | --- | --- | --- |
| DEF-063 | minor | `surfaceOf` omits production's `PROVISIONED_SEGMENTS` — latent second spelling of `classify` | HIS-6 |
| DEF-064 | minor | `HomeFixpointLaw` never passes `--against`, so the source-reference half is unchecked in 24 graphs | HIS-6 |
| DEF-065 | major | the ~14 s fixed per-node cost itself, and the OTLP `Connection refused` retries inside every budget. **The four budgets are FIXED in this PR**; this stays open on the floor | HIS-6 |
| DEF-066 | minor | `artifact-dag`'s `uninstall.prunes.the.subgraph` — ARTI-08's declared red, revealed by unblocking the node in front of it | HIS-6 |
| DEF-067 | minor | `HomeFixpointLaw` would **launder** a surviving decoy rather than report it — it parses the `FOREIGN_HOME` remedy, runs it, and the pruner deletes the evidence | HIS-6 |

Budget was 5; **five used** — DEF-067 came from review of #242.

**Plan note for the epic owner:** `test_graph/sources/lib/HomeIsolation.java` is
a **new file in the shared `sources/lib/`** that both touched nodes `//SOURCES`,
and it is **not** in this ticket's `conflict_keys.test_graph` (which names
`home-clone/HomeClonedIntoProject`, `home-clone/HomeCloneSupport`,
`artifact-dag/LazyCloneDeclaresWithoutBuilding`). Any concurrent ticket adding to
`sources/lib/` would not have been warned. Flagged by review; the owner is
fixing the plan.

## 8. What I am unsure about

1. **Deleting `home_verify_names_the_build_that_completes_the_clone`.** I am
   confident the assertion is unsatisfiable and that the claim moved to
   `cold.artifact.refusal.names.build`. I am **not** confident that deleting
   another ticket's assertion is a test-graph ticket's call, even with the
   reasoning committed beside it. If the owner disagrees, the alternative is to
   leave it red and let `artifact-dag` stay blocked at that node instead of the
   next one.
2. **Reading a verdict out of report text.** Justified by the two-direction
   assertion, but it is still the mechanism that produced the defect in item 1.
   A `--json` on `home verify` would remove the class entirely; I did not add
   one because the ticket declares no production conflict keys.
3. **Whether instances 3–5 should have been consolidated.** One walk written
   three times is the smell this epic is about. I left them because they are not
   *this* defect and consolidating three graphs' helpers is not this ticket's
   slice — but a reviewer may read that as the same "patch it when it reddens"
   posture #238 criticises.
4. **~~`onboard` filed rather than fixed.~~ Overturned by review of #242, and
   the reviewer was right.** `onboard` is in CORE; leaving a CORE graph red to
   preserve a datapoint costs a signal this epic has already shown it does not
   otherwise get. All four budgets ship; DEF-065 stays open on the floor. The
   revert had also left the fourth budget — `onboard.seeded.by.server`, 15 s
   with `retries(2)`, which my own table mis-recorded as 60 s — in place, and
   that is the one that fails *intermittently*.
5. **Whether a second implementation of the byte accounting is right.** V5 and
   V6 justify it — each side reddens alone while the other is correct, which a
   shared implementation could not do — but it is a fifth spelling of a rule in
   the ticket about spellings, and the defence rests entirely on the agreement
   assertion holding them together.
6. **The decoy and the tamper both mutate a live fixture.** Removal is in a `finally` and
   asserted, and it has never survived across five runs — but a node that
   crashes between plant and revert would leave a symlink for eight downstream
   nodes. It is named `his17-decoy-into-source` so that is attributable on
   sight rather than mistaken for a product defect.
