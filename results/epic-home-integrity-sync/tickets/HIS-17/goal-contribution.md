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

**The independent-scan assertion cannot fail when production's exemption is
removed, and no probe here makes it.** V1 proves it: the mutation reddens the
node, through the cross-check and through `home clone`'s own report, while
`independent_scan_finds_no_owned_surface_naming_the_source` stays green. The
clone still writes the record; the scan still exempts it; the bytes are
identical either way. This is the ticket's own defect reproduced, not a gap in
the probe — but it means the acceptance's *"each fixed assertion must fail when
the exemption is removed"* is satisfied **at node level, not per assertion**.

**`artifact-dag` and `onboard` are still red at hand-off**, for causes that are
not mine and are filed with measurements: DEF-066 and DEF-065.

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
`home verify` has no `--json` and this ticket declares no `production` conflict
keys, so there was no third option. **A text expectation is exactly what broke
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

All on `5e4a3e2`, the tip of this branch, in worktree `wt-238-his-17`.

### Declared graphs

```
python skills/test_graph/scripts/run.py home-clone
  BUILD SUCCESSFUL in 5m 1s        run 20260823-170314
  14 nodes, 96 assertions, ALL PASSED
  home.cloned.into.project: leakCount=0  toleratedContentReferences=1
                            sanctionedDescentRecords=1  verifyExitCode=1

python skills/test_graph/scripts/run.py artifact-dag
  BUILD FAILED in 3m 16s           run 20260823-163904
  lazy.clone.declares.without.building  PASSED  12/12 assertions
  cold.artifact.refusal.names.build     PASSED  (was skipped behind it; ARTI-07's
                                                 second half, written red, now green)
  uninstall.prunes.the.subgraph         FAILED  <- DEF-066, ARTI-08's declared red,
                                                   revealed by unblocking the node
                                                   in front of it. Not mine.
```

`verifyExitCode=1` is expected and is the reason the cross-check does not read
the exit code: it is the fixture's deliberate dangling shim, the provisioning
half. See §2.

### The second set, and why these graphs

The assignment requires naming any graph whose fixtures exercise the sources
edited, with the reason. `HomeClonedIntoProject.java` and `HomeCloneSupport.java`
are listed by **`checkout-home`** as well as `home-clone` — the same node file,
a second graph — and `onboard` was #238's fourth red:

```
python skills/test_graph/scripts/run.py checkout-home
  BUILD SUCCESSFUL in 3m 3s        run 20260823-170813
  8 nodes, 55 assertions, ALL PASSED

python skills/test_graph/scripts/run.py onboard
  BUILD FAILED in 3m 28s           run 20260823-164343
  onboard.skills.installed errored: timed out after 10s across 3 attempts
                                    <- DEF-065, budget below the graph's own
                                       fixed per-node cost. Not mine, not this
                                       epic's, and not HomeLock.
  with the three 10s budgets at 120s, reverted after measuring:
  BUILD SUCCESSFUL in 4m 45s       run 20260823-165545     15/15 nodes
```

`--all` was NOT run: it belongs to HIS-6, which owns the one terminal sweep with
the goal scorecard.

### Unit

```
jbang RunTests.java              1363 passed, 0 failed        ALL PASSED
uv run pytest specs/               38 passed in 1.80s
```

### TLC

`N/A` per the assignment: this ticket states no new invariant, and HIS-5 carries
the model work plus every regression cfg.

### Before / after

| | before (`a978bb0`) | after (`5e4a3e2`) |
| --- | --- | --- |
| `home-clone` | **FAILED**, 1 node red, **9 skipped** | **14/14, 96 assertions** |
| `checkout-home` | **FAILED**, same node | **8/8, 55 assertions** |
| `artifact-dag` | FAILED at `lazy.clone…`, **4 skipped** | `lazy.clone…` **12/12**; `cold.artifact…` **green**; fails 2 nodes later on DEF-066 |
| `onboard` | FAILED, 6 skipped | unchanged — DEF-065, filed with a verified remedy |

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
| DEF-065 | major | three `onboard` budgets are below the graph's ~13.3–14.7 s fixed per-node cost; remedy verified, not shipped | HIS-6 |
| DEF-066 | minor | `artifact-dag`'s `uninstall.prunes.the.subgraph` — ARTI-08's declared red, revealed by unblocking the node in front of it | HIS-6 |

Budget was 5; four used.

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
4. **`onboard` filed rather than fixed.** Acceptance allows either. I chose
   filed because shipping three numbers hides a ~14 s fixed cost that a
   29-graph sweep pays ~15 times per graph. If the owner wants the graph green
   now, the change is three characters and is verified.
5. **The decoy plants into a live fixture.** Removal is in a `finally` and
   asserted, and it has never survived across five runs — but a node that
   crashes between plant and revert would leave a symlink for eight downstream
   nodes. It is named `his17-decoy-into-source` so that is attributable on
   sight rather than mistaken for a product defect.
