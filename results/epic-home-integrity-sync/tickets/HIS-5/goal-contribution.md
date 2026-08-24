# HIS-5 — goal contribution

Issue #217. Branch `feature/217-his-5`, base `epic/home-integrity-sync`.
Wave 11, promotion order 17, predecessor HIS-8. Schedule revision 9.

Goal: **GOAL-home-invariants** (`direct`).

Declared expected effect: *"4 invariants / 5 cfgs -> one new invariant per fix,
each with a failing cfg, all 5 originals still failing."*

**Delivered: 4 → 13 invariants, 5 → 14 expected-violation cfgs, plus 1 guard cfg
and 2 reachability probes. All five originals still fail, each on its own
invariant.**

---

## The rule this ticket ended up being about

Written first because it is the part most likely to be undone by someone
tidying up:

> **An expected-violation configuration is only worth having if you have
> measured WHICH invariant it violates. A configuration that fails is not
> evidence; a configuration that fails on the invariant under test, and goes
> green when only that invariant is removed, is.**

The ticket brief warned that mechanism **D** was the likeliest trap here. It is
not the one that nearly caught me. **Mechanism A is**, and in TLA+ it has a
sharper form than the ledger describes: TLC reports the FIRST invariant it finds
violated, in an order the author does not control, so a configuration written to
refute invariant X can quietly be refuting invariant Y for two years and read
identically in every transcript. Every row in the table below therefore carries
two measurements, not one:

1. the regression cfg lists its **neighbouring** invariants as well as its
   target, so a neighbour firing is visible rather than hidden; and
2. the same spec was re-run with the **target removed and the other thirteen
   kept**, and had to come back green.

Fourteen configurations, fourteen greens on the second measurement. Transcript:
`tlc/CROSSCHECK-target-removed.txt`. Without it, "the cfg fails" is a claim
about TLC's iteration order.

---

## 0. Corrected after review of PR #249

**Read this before anything else.** The review found no blockers and reproduced
every number, including rebuilding the cross-check sweep from scratch rather
than trusting this file. It also found that **the strongest sentence in the
first version of this document was false of the code**, and three smaller
claims with it. They are corrected in place below; this list is so the
corrections are not discoverable only by diff.

| # | claim in v1 | status |
| --- | --- | --- |
| M2 | *"`disposable()` was `sourceHeldTheseBytes && recordIsAboutThisSource`"* | **FALSE.** It is a DISJUNCTION and HIS-1 never touched it. §1, invariant 5. |
| M1 | clause 2 stated without its frame condition | **TRAP.** Frame now stated in module + manifest, antecedent guarded, breakage measured. §1, invariant 6. |
| M3 | DEF-087 deferred | **FIXED HERE.** The table is now executed by a test. §3(d). |
| m1 | *"`hi_full_surfacings` is the persisted `surfacedCount`"* | **FALSE.** It is `min(surfacedCount, 1)`. Conclusion survives, reason did not. §1, invariant 7. |
| m2 | HIS-10's sanction re-derives from "the parent's own store" | **NARROWER THAN PRODUCTION.** `isChildOf` has a second, in-home arm. §1, invariant 10. |
| m3 | *"fourteen greens"* presented as one measurement | **10 informative, 3 green by construction, 1 degenerate.** §3(b). |
| m4 | *"every cfg lists its neighbouring invariants"* | **Six of fourteen list only `TypeOK` + target** — they have no neighbours. §3(b). |
| m7 | *"HIS-14 delivered across seven verbs"* | **Twelve** declare `--home`. §6. |
| — | HIS-11 covered by `InFlightMaterializationLeavesTheChildUnitIntact` | **Wrong invariant** — it is guarded on `staging.active`. §4. |

---

## 0b. What this ticket does NOT deliver, up front

**Read this before §1.**

- **Two of the five extra candidates are not stated, and one of them is a
  judgement a reviewer may overturn.** HIS-9 and HIS-11 are argued out in §4.
  The HIS-11 argument is the weaker of the two: I claim an at-rest invariant
  cannot distinguish "restored" from "never touched", which is true of this
  view's variables but would stop being true if a write witness were added, the
  way `External.sync_history` was added for exactly this reason. A reviewer who
  thinks the witness is worth the variables would be making a reasonable case
  and I did not make it.
- **`AnObservationNeverRepairsWhatItObserved` is FALSE of the shipped
  instrument.** DEF-067 is open: `HomeFixpointLaw` parses a refusal's remedy and
  runs it, in 24 of 29 graphs. The invariant is stated against the desired
  observer. That is deliberate and is the same shape as
  `ProjectionResolvesInsideThisHome`, inverted — there a configuration preserves
  a correction, here one preserves a finding that has not been fixed yet — but
  it means `specs/current` now asserts something the product does not do.
- **This ticket adds no executable coverage OF THE PRODUCT.** Nine invariants
  over a bounded model are nine statements about a MODEL. The `home-integrity`
  graph is what checks the same relations against real homes, and this ticket
  did not extend it. If the model and the product drift, nothing here notices.
  What this ticket *did* add — after review folded DEF-087 back in — is
  executable coverage of **the model's own claims**: see §3(d).
- **The state count grew 24× and nothing bounds the next one.** 10 distinct
  states → 24,000. That is inside `budgets.max_distinct_states` (50,000) and it
  is a product, not a sum: the six property groups are disjoint, so a seventh
  group multiplies rather than adds. The next ticket to extend this view will
  cross the budget. Recorded in the manifest under `state_space_note`.

---

## 1. The thirteen invariants and their configurations

Nine are new. `SPEC` names the regression specification; `Violated` is what TLC
reported, verbatim; `Alone?` is the cross-check — target removed, other thirteen
kept, must be green.

| # | invariant | fix | expected-violation cfg | Violated | Alone? |
| --- | --- | --- | --- | --- | --- |
| 1 | `RecordDescribesItsStoreOrSaysWhy` | ARTI-22 | `_regression_silentdrift` | that invariant | green |
| 2 | `AckIsNotStaleOnArrival` | ARTI-22 | `_regression_staleack` | that invariant | green |
| 3 | `AckWritesFallInsideItsBaseline` | ARTI-22 | `_regression_selfrepend` | that invariant | green |
| 4 | `ProjectionSourceIsDecidable` | ARTI-22 | `_regression_foreignprojection` | that invariant | green |
| — | `ProjectionResolvesInsideThisHome` | ARTI-22 correction | `_regression_narrowprojection` | that invariant | green |
| 5 | `AnIdenticalTreeIsDisposableWhateverItsRecordNames` | HIS-1 c1 | `_regression_recordvetoesidenticaltree` | that invariant | green |
| 6 | `ADivergentTreeIsNeverSilentlyReplaced` | HIS-1 c2 | `_regression_everythingdisposable` | that invariant | green |
| 7 | `OneGateIsSurfacedInFullAtMostOnce` | HIS-3 c1 | `_regression_gaterefires` | that invariant | green |
| 8 | `AnUnacknowledgedGateStillRefusesALaunch` | HIS-3 c2 | `_regression_collapsedgateadmits` | that invariant | green |
| 9 | `ARecordedErrorIsAFunctionOfTheLiveTree` | HIS-4 c3 | `_regression_staleerror` | that invariant | green |
| 10 | `SanctionIsRederivedFromTheParentsOwnStore` | HIS-10 | `_regression_selfcertifyingdescent` | that invariant | green |
| 11 | `AHomesTwoAxesNameOneHome` | HIS-14 | `_regression_halfboundhome` | that invariant | green |
| 12 | `AnObservationNeverRepairsWhatItObserved` | HIS-13 / DEF-067 | `_regression_repairingobserver` | that invariant | green |
| 13 | `ADamagedHomeIsNeverReportedClean` | HIS-13 | `_regression_blindobserver` | that invariant | green |

Full transcripts: `tlc/*.out`. Distilled counterexamples, one bullet per state
with only the variables that moved: `tlc/COUNTEREXAMPLES.md`.

### The four the plan asked for

**5 — `(hi_unit_content = hi_live_source) => ~hi_held_back`.** A tree
byte-identical to what the live source is standing on right now is disposable,
whatever its record names.

*The invariant does not mention `hi_record_names_source`, and must not.*
`RefreshTheUnitFromEvidenceOnDisk` lets the record range over BOTH values rather
than pinning it, so TLC must show the property holding whichever way the record
points.

*Counterexample:* one step. `HoldBackAnIdenticalTreeBecauseOfItsRecord` with
`hi_unit_content = hi_live_source = "t_src"` carried from Init and
`hi_record_names_source = FALSE` — sixteen of the operator's eighteen records —
sets `hi_held_back = TRUE`. Nothing else in the state moves. **Violates 5.
`ADivergentTreeIsNeverSilentlyReplaced` is listed in that cfg and holds**, and
that is not an accident of ordering: the modelled broken rule is strictly MORE
conservative than the fixed one, so clause 2 cannot fire on it.

### M2 — the correction, and it is the sharpest thing in this document

**v1 of this file said the invariant refutes "exactly the implementation the
defect was — `sourceHeldTheseBytes && recordIsAboutThisSource`". That
conjunction exists in no version of the code.**
`ChildHomeMaterializer.Disposal.disposable()` is, and was before HIS-1:

```java
return (destUntouched && recordIsAboutThisSource && !mergeResult)
        || sourceHeldTheseBytes;
```

a **disjunction** with `sourceHeldTheseBytes` as an escape arm.
`git log --all -S "sourceHeldTheseBytes ||"` over that file returns **no
commit**. HIS-1 is `8346fef` *"copyUnit asks the disk before it asks the
record"*, and what it actually did was extract `settledWithoutARecord(...)` and
call it from `copyUnit` **before** `disposal(...)` — `reconcile` had had those
three disk answers all along, and the defect was that only **one of the two
paths** asked them.

**The consequence is worth more than the deletion.**
`HoldBackAnIdenticalTreeBecauseOfItsRecord` models a machine **strictly harsher
than the broken one ever was**: `held_back = divergent ∨ ¬record_names_source`
holds back on the record's say-so alone. The real broken `copyUnit` satisfies
clause 1 in the **common case**, because `describesSource`'s third arm is

```java
if (record.entryDigests().equals(src.entries())) return true;
```

— the destination's recorded entry digests against the **live source's**
fingerprint. So a record naming a worktree deleted in July still answers *"yes,
evidence about this source"* whenever the bytes line up, and the unit is rescued
regardless of what `record.source()` names.

**That is vacuity-ledger row 1, one level up.** Row 1 records HIS-1's first
regression test passing *without* the fix — *"`describesSource` rescued it
because both trees were left pristine"*. The single boolean
`hi_record_names_source` **cannot express the distinction that actually decided
that case**: a *stale* record (names elsewhere, digests still match the live
source → rescued) versus a record genuinely not about this source (digests
differ → held back). A faithful model needs two bits there, not one. **So the
TLA+ model reproduces, one level up, the exact vacuity the ledger already
recorded for HIS-1's unit test.**

**The invariant is still a correct pin on delivered behaviour** — production
disposes of every identical tree and this forbids holding one back. What was
wrong was the story about which implementation it refutes, and that story is the
specific claim the PR asked a reviewer to weigh.

**6 — `(hi_unit_pass = "done" /\ hi_unit_content # hi_live_source) => hi_held_back`.**
The guard on 5, and the reason 5 is not the whole property: "everything is
disposable" satisfies 5 completely and destroys every agent edit in the home.
`DisposeOfEveryTreeUnconditionally` refutes it; **5 holds on that trace**,
cross-checked.

### M1 — the frame condition, now written down, and what it costs

**As shipped in v1 this invariant was false of the product** — it held only
because `hi_live_source` is declared a variable, described as *"the bytes the
live SOURCE is standing on right now"*, and **never assigned after `Init`**.
Nothing edits `hi_unit_content` outside a pass either, so divergence and the
judging of it were inseparable by construction and **the frame was written down
nowhere**.

Production genuinely leaves a unit standing on bytes that differ from the live
source without holding it back: an ordinary clean out-of-date copy satisfies
`destUntouched && recordIsAboutThisSource && !mergeResult`, is disposed of, and
is refreshed. **That is what `sync` is for.** The module's own header sets the
standard — *"an invariant a healthy home genuinely violates is a modelling error
and not a bug."*

**Measured**, by adding `TheSourcePublishesANewRevision` — the most ordinary
event in the system — to `HomeIntegrityNext` and running the **correct**
configuration:

| clause 2 | result |
| --- | --- |
| **unguarded** (as shipped in v1) | red at **depth 2** — the source moves; no pass has run |
| **guarded** (as shipped now) | red at **depth 3** — a pass refreshes, then the source moves past it |

**Read those two numbers together, because they say something the review's
proposed fix does not.** Guarding the antecedent **does not make clause 2 true
of a moving source** — it buys one step. What makes it true is the *frame*, and
the guard's job is to stop the invariant claiming anything about states no pass
has judged. **Both are needed and neither substitutes for the other**, so both
landed: the frame condition is stated in the module (a titled block beside
`RefreshTheUnitFromEvidenceOnDisk`) *and* against `hi_live_source` in the
manifest's variable list, and the antecedent is guarded.

The frame block also names the correct repair for whoever models an upstream
revision next: **capture what the pass JUDGED**, do not weaken clause 2. That is
the move this module says it refused, and it would give back HIS-1's second
acceptance assertion. It costs a witness and therefore a guard cfg, which is why
it was not done for a model in which the source cannot move.

**7 — `hi_full_surfacings <= 1`.** One unacknowledged gate is surfaced in full at
most once; a genuinely new change zeroes the counter and re-opens the full
report, which is why `SyncDetectsAChange` resets rather than leaves it.

*m1, corrected.* v1 waived a guard cfg here on the grounds that
`hi_full_surfacings` *"is `home.drift.json`'s persisted `surfacedCount`"`.
**It is not.** `DriftGate.markSurfaced` increments on **every** surfacing, full
or collapsed — `ExecCommand:172` and `HomeCommand:1054` both call it *after* the
`firstSurfacing()` branch — so the persisted field legitimately reaches 3, 4, 5
and `<= 1` is false of it. What this variable models is `min(surfacedCount, 1)`,
equivalently `~firstSurfacing()`, since `DriftGate.firstSurfacing()` is
`surfacedCount <= 0` and is the **only reader of the field anywhere in the
product**. That predicate is persisted, is re-read across processes, and decides
whether the full report is emitted — so **the conclusion stands and the stated
reason did not**.

**8 — `hi_gate_open => ~hi_launch_admitted`.** The half that stops 7 being
decoration. `_regression_collapsedgateadmits` is the demonstration: **7 is GREEN
on the behaviour 8 catches.** The gate is collapsed to one line, the noise
metric is perfect, and the launch that should have exited 8 runs.
`DriftGate.java:39-49` records a spec run of exactly this shape, where the
reporting invariant went vacuously true and TLC answered "No error has been
found" over a gate that had stopped gating. HIS-3 vacuity-checked its two halves
separately for this reason; here the separation is executable.

**9 — `hi_record_error = hi_error_cause_live`.** A reported error is a function
of the live tree, not of history.

*Stated as an equality, not an implication.* The one-directional form permits a
record that forgets a live conflict, which is the same defect facing the other
way.

*The interaction that makes it non-redundant, and it is a finding rather than a
note.* `RecordDescribesItsStoreOrSaysWhy` is **satisfied by a stale error** —
the error is its escape disjunct — so a record whose error has outlived its
cause looks HEALTHY to the older invariant. The older one is propped up by
exactly the state the newer one forbids. The regression action advances the
record's revision as well, so the older invariant holds through the trace by its
FIRST disjunct and cannot be what fires; that is asserted by listing it in the
cfg and confirmed by the cross-check.

### The five the plan did not list — each argued, stated or not

**10 — `hi_shim_sanctioned => hi_parent_claims_home`. STATED.** A clone's descent
is a POINTER, not a GRANT. This is the property the epic converged on and the
one the brief called the most valuable thing here.

*The invariant mentions `hi_descent_record` nowhere, and must not.* Both faces
review rejected are failures of a model that reads the record instead of
re-deriving, and one statement refutes both: the hand-written
`home.provenance.json` naming `clonedFrom: /nowhere`, and the clone-time
SNAPSHOT that keeps sanctioning after the parent revokes. An invariant phrased
over the record — "the record must name a real home" — accepts the second face
completely. Both actions are in one regression specification for that reason: if
someone later narrows the invariant that way, the snapshot face stops being
caught while the cfg still fails.

*It fails closed, and the cost is stated rather than assumed:* evidence that
cannot be re-derived does not sanction. *Not claimed:* forgery-proof.

*m2, corrected.* v1's comment implied the parent's registry is the only witness.
`ChildHomeLink.isChildOf` is `parentClaims(p, c) || childWasMaterializedFrom(c, p)`,
and the second disjunct walks `<child>/.materialization/**/*.json` and trusts its
`source` field — **a file inside the home being judged**. So the live
re-derivation this invariant pins is broader than "the parent's own store says
so", and one of its two arms is in-home evidence. No forgery was demonstrated
and HIS-10 explicitly named that pre-existing trust rather than widening it, so
this is an over-claim in the prose, not a hole. What HIS-10 removed is the
**arbitrary** grant — a record that sanctioned itself with no live question
asked at all.

**HIS-10's own plan row promised GOAL-home-invariants a `direct` contribution —
"an invariant that a home's sanction set is a function of its recorded descent,
not of the command line" — and discharged it against `jbang RunTests.java`.**
This goal's metric counts invariants over the home-integrity VIEW. So the TLA+
half of that promise was outstanding for seven promotion slots and is landing
here. HIS-9's row says the same thing about write confinement and is NOT landing
here — see §4. **HIS-6 should decide whether those two rows were honestly
discharged.** Filed as DEF-085.

**11 — `hi_store_write = hi_agent_write`. STATED.** A home has two axes and a
command binds both or neither.

The reason it is stated rather than dismissed as a command-surface property:
**no module in this directory models the agent axis at all.** External's homes
have one axis. That is not an oversight I am pointing at from outside — DEF-076
measured its consequence, a dangling projection in every ticket worktree's
`.codex` and `.gemini` that `home verify` cannot see because the agent axis is
outside its walk. Stated over *where the bytes landed* rather than over what the
flag parser did, the refusal branch is the state where neither axis was written,
which Init occupies and the invariant admits — so "binds both, or refuses" is
one property and not two.

**12 — `~hi_observer_repaired`. STATED, and it is the only WITNESS-DEPENDENT
invariant here.** An observer that repairs is no longer an observer.

This is the one the brief said was worth real thought, and the thought that
changed my answer was this: *an instrument is not a home, so a property of
`HomeFixpointLaw` has no business in a view of what a healthy home is.* Stated
as "the observation left the home as it found it", it is a property of the home,
and it belongs.

*Why the witness is genuinely necessary, rather than a modelling convenience.*
DEF-067's own text is the argument: the repair destroys exactly the evidence
that would let the verdict be read correctly. By the time the observation is
written the home really is clean, so **every other invariant in this module is
satisfied by the repairing observer** — including the detection invariant it was
meant to be checking. The evidence has to be taken at the instant of the act.
That is the same shape as `External.CommittedWorkIsNeverDestroyed` and
`External.NoHomeIsTornDownWhileItHoldsUniqueWork`, and it earns the same
treatment.

**GUARD: `HomeIntegrityInternal_guard_repairingobserver.cfg` runs the repairing
observer with invariant 12 REMOVED and all twelve others kept, and returns "No
error has been found".** That green is not good news about the instrument. It is
DEF-067 consequence 3 — *"it can green a real defect"* — demonstrated instead of
asserted. If it ever starts failing, some other invariant has been strengthened
into this one and the record of why the witness exists is stale.

**13 — `(hi_observation = "clean") => ~hi_home_damaged`. STATED**, as a second
distinct oracle. Kept separate from 12 precisely because a repairing observer
satisfies it perfectly. The vacuity ledger's composite lesson from rows 12 and
13 is that the count of probes measures nothing and the count of **distinct
oracles** is closer; this is that lesson applied rather than cited. Its own
refutation is `_regression_blindobserver` — an observer that reports clean
without looking — so 13 is not carried by 12's configuration.

---

## 2. Clause 2: all five pre-existing regression cfgs, individually

Re-run after the change, on the extended module, from an unpinned shell with
`tlc2` resolved from this worktree's own home. **A cfg that starts passing is a
defect in the invariant, not a success.**

| cfg | before (76434c7 baseline, re-measured) | after |
| --- | --- | --- |
| `_regression_silentdrift` | FAIL — `RecordDescribesItsStoreOrSaysWhy`, 2 states | **FAIL — `RecordDescribesItsStoreOrSaysWhy`** |
| `_regression_staleack` | FAIL — `AckIsNotStaleOnArrival`, 3 states | **FAIL — `AckIsNotStaleOnArrival`** |
| `_regression_selfrepend` | FAIL — `AckWritesFallInsideItsBaseline`, 3 states | **FAIL — `AckWritesFallInsideItsBaseline`** |
| `_regression_foreignprojection` | FAIL — `ProjectionSourceIsDecidable`, 2 states | **FAIL — `ProjectionSourceIsDecidable`** |
| `_regression_narrowprojection` | FAIL — `ProjectionResolvesInsideThisHome`, 4 states | **FAIL — `ProjectionResolvesInsideThisHome`** |

Five for five, same invariant in every case, and each also passed the
target-removed cross-check. Baseline transcripts are kept beside the new ones as
`tlc/BASELINE.*.out` so the before/after is readable rather than asserted.

**Why they were safe.** Every ARTI-22 action gained an `UNCHANGED his5_vars`
clause and nothing else, with one exception —
`SyncConflictsAndRecordsTheError` now also sets `hi_error_cause_live' = TRUE`,
because a conflict RECORDED is a conflict LIVE at the moment it is recorded.
The four ARTI-22 regression specifications touch none of the new variables, so
they run with all eighteen at their Init values, and every new invariant holds
at Init by construction.

---

## 3. The vacuity work, which is most of what this ticket is

Three separate checks, because the ledger's own composite lesson is that a large
honest probe suite can be entirely blind when every probe is read against one
oracle.

**(a) Can each invariant be violated at all?** Fourteen expected-violation
configurations, fourteen reds. This is the check `regression_config_rule` asks
for, and it is the weakest of the three.

**(b) Does each counterexample violate MY invariant?** All fourteen were re-run
with the target removed and the other thirteen kept. **Fourteen greens.**
`tlc/CROSSCHECK-target-removed.txt`. The review rebuilt this sweep from scratch
rather than trusting the transcript and reproduced 14/14.

*m3, corrected — the fourteen are not one uniform measurement.* A cross-check is
**informative** only where some other invariant reads a variable the regression
spec actually moves; where a group is read by exactly one invariant, removing it
leaves nothing that could fail and the green is true by construction.

| | rows |
| --- | --- |
| **informative (10)** | silentdrift, staleack, selfrepend, recordvetoesidenticaltree, everythingdisposable, gaterefires, collapsedgateadmits, staleerror, repairingobserver, blindobserver |
| **green by construction (3)** | foreignprojection, selfcertifyingdescent, halfboundhome — one invariant each reads `projection`, `descent_vars`, `axis_vars` |
| **degenerate (1)** | narrowprojection — its target is not among the thirteen kept, so with it removed the run *is* `HomeIntegrityInternal.cfg` |

The disjointness that makes three of them uninformative is deliberate and is
what keeps the counterexamples clean. Presenting fourteen equivalent
measurements was not.

*m4, corrected.* v1 said *"every expected-violation cfg lists its neighbouring
invariants"*. **Six of fourteen list only `TypeOK` + target** —
foreignprojection, halfboundhome, narrowprojection, selfcertifyingdescent,
silentdrift, staleack — because their group has no neighbour to list. The other
eight do list one that must not fire.

**(c) Was each invariant ever EVALUATED?** Nine invariants that hold is not the
claim that nine were evaluated — every one is an implication or an equality, and
an implication whose antecedent is unreachable is TRUE and worthless. That is
this view's own recorded near-miss: `ProjectionSourceIsDecidable` shipped, in the
first version of ARTI-22, with `"foreign"` unassigned and TLC unable to
distinguish it from `TRUE`.

So two **reachability probes** were added, following
`External.NoWorktreeEditEverReachesTheRootHome`. Both run the CORRECT
specification and **must FAIL**:

- `NoRefreshedTreeEverHadAForeignRecord` — its counterexample is HIS-1 clause 1
  actually happening: a finished pass over a tree byte-identical to the live
  source, whose record names something else, refreshed rather than held back.
- `NoHomeReachesEveryStateTheseInvariantsAreAbout` — its counterexample is a
  **nine-state trace** in which the antecedent of every remaining new invariant
  is live at once: a divergent tree held back, an open gate surfaced in full, a
  reported error whose cause is on the tree, a sanctioned shim whose parent
  claims this home, a command that wrote both axes, and a damaged home whose
  observation says damaged.

Both fail. If either ever reports "No error has been found", the healthy model
has stopped reaching the states the invariants are about and every green run
above is measuring nothing. The review ran an independent 12-antecedent
reachability sweep and found no vacuity; all 11 reachable antecedents were
confirmed directly.

**(d) Does anything RUN any of this? — DEF-087, fixed here rather than
deferred.** v1 filed it against its own PR and the review folded it back in,
correctly: the goal says *"so the defect cannot come back unnoticed"*, and nine
invariants with zero automatic enforcement do not meet it. It was worse than v1
stated — nothing ran the cfgs **and nothing read the table**:
`grep -rn expected_violations` over `*.py *.sh *.kts *.java *.yml` returned
nothing, and `run_tlc.sh`'s only repository-wide reference is a prose string in
`.github/scripts/render-epic-assignment.py`.

`specs/*/tests/test_tlc_expected_violations.py` now reads all three declared
tables and asserts **the invariant TLC names is the one declared** — not merely
that TLC failed, which would pass in exactly the mechanism-A case this ticket is
about. **25 cases, ~16 s**, in both model directories.

*Proved to discriminate, because a check nobody has watched fail is a check
nobody should believe.* The table was deliberately broken two ways and restored
byte-for-byte afterwards (checksum verified; **no `git restore`**, per DEF-035):

| break | result |
| --- | --- |
| point `gaterefires` at a **neighbour** (`AnUnacknowledgedGateStillRefusesALaunch`) | **RED** — *"failed on `OneGateIsSurfacedInFullAtMostOnce`, but the manifest declares … mechanism A"* |
| declare the **green** guard cfg as an expected violation | **RED** — *"reported NO violation … a regression configuration that starts passing is a defect in the invariant"* |

**Two halves, and the split is the honest part.** The **structural** checks need
no model checker and run everywhere including CI: every declared cfg exists,
every cfg on disk is declared (the ARTI-22 "five undeclared copies" shape),
every named invariant is defined in the module, and the healthy cfg lists every
declared invariant. The **model-checking** checks need `tlc2`.

**The review's "it lands in CI for free" is optimistic and I measured why.** CI
runs `uv run --with pytest pytest specs/program_model/tests specs/current/tests`
— two named directories, **not `specs/`** — so only the mirror in
`specs/current/tests` is collected, and that job installs no `tlc2`. Under an
env-scrubbed, `tlc2`-free invocation shaped like the runner: **34 passed, 18
skipped**, with the skip reason named under `-rs`. So the structural half is in
CI today and the model-checking half is not.

Two things were done about that rather than leaving it implicit.
`specs/current/spec_manifest.yaml` **gained the tables**, because the tree CI
actually collects had no declaration of what its own configurations are for —
my own guard caught that within a minute of the mirror landing. And
`SPEC_REQUIRE_TLC=1` turns the skip into a **failure**, so a skip is a gap that
says so rather than an unbroken row of dots.

**Then the residue was closed rather than deferred — §3(e).**

### (e) DEF-087 closed: the invariants are now enforced in CI, not just the bookkeeping

I first wrote the `tlc2`-on-the-runner half up as residue for whoever owns CI
policy. That was wrong, and the epic owner sent it back with the right
instruction: *decide it on a number, not an assumption.* The number said close
it.

**It is a jar download, not a skill install.** `run_tlc.sh` already carried the
fallback `java -cp "$TLA2TOOLS_JAR"`, so the whole change is three lines of
workflow:

| measured, CI-shaped (`env -i`, no `tlc2` on PATH) | result |
| --- | --- |
| without the jar | **34 passed, 18 skipped** |
| with `TLA2TOOLS_JAR` set | **52 passed, 0 skipped**, ~18 s |

`ci.yml`'s `unit-tests` job now fetches `tla2tools.jar` v1.7.4, **verifies its
SHA-256**, exports `TLA2TOOLS_JAR`, and runs the spec suites with
`SPEC_REQUIRE_TLC=1` so a lost jar **fails** instead of skipping.

**The pinned digest is not decoration.** `936a2620…` is byte-identical to the
jar `spec-double-compiler` installs locally — I downloaded the release and
compared, same 2,274,532 bytes and same digest. So a green CI run and a green
local run are the **same model checker**, not two that happen to agree.

**Ordering.** `Set up JDK 21` was hoisted above the spec suites, because TLC is
a jar. JBang and Maven resolution still run after, so the step's stated intent —
*a broken spec model is reported before the runner spends minutes resolving
Maven artifacts* — is preserved. Its claim that *"both suites finish in well
under a second"* is now false (the TLC half adds ~18 s) and was **corrected in
the comment rather than left standing**; 18 seconds is still far less than Maven
resolution, so the argument survives its number changing.

**Validating the instrument before believing it (HIS-8's lesson).** The
classifier is one `grep -E "^Error: Invariant|No error has been found"`. It was
exercised on inputs whose answer was known **in both directions** before any
result was believed: reds naming 12 distinct invariants, and greens, from the
same command shape over the same corpus. The cross-check sweep in particular
**can** come back red — the identical pipeline produced the reds in §1 — so its
fourteen greens are a measurement and not a pattern that cannot match. A sweep
that cannot fail is the same defect as an assertion that cannot fail.

**And the mechanism kept happening, four times in two days, to four different
readers.** HIS-8's `grep -E` carried a literal `\|` and returned a confident
all-zeros. The reviewer of this PR had its first cross-check show 14 *reds*
because zsh does not word-split an unquoted `$ALL`, so the target was never
removed — it caught that itself and said so. And while finishing these
corrections I wrote a wait-loop for the two long suites whose pattern was
`FAILED`, which matched `[PASS] … AGENT_SYNC_FAILED` and returned "done" while
both suites were still running.

Three of those four were caught only because the result was *implausible* — all
zeros, all reds, done-too-soon. **None was caught by a check.** That is the
argument for §3(d) existing at all: the one countermeasure in this ticket that
does not depend on a human finding the number surprising is the test that
asserts the invariant TLC named.

---

## 4. What I chose NOT to state, and why

**HIS-9 — "a write resolving outside the home's declared roots is refused". NOT
STATED.** Two reasons, and the first is dispositive:

- The at-rest half is already in the accepted model, twice:
  `External.WritesThroughOneHomeReachNoOtherHome` (over the write witness) and
  `External.NoOwnedSurfaceNamesAnotherHome`. Restating it here is exactly what
  `planning_rules.dual_representation_rule` forbids.
- What HIS-9 adds on top — that the write is **REFUSED**, with an exit code,
  naming the path and the home — is a property of the command surface, and this
  module's own header says it models none. It is also where HIS-9's review found
  the real bug: the refusal was swallowed by a `catch (Exception)` so `sync`
  still exited 0. An at-rest invariant over a home cannot see an exit code.

*The honest weakness:* External's invariant is about cross-home REACH. HIS-9's
guard also refuses a write into a path in **no home at all** — the
`/private/tmp` scratchpad case. That sliver is stated nowhere. I judged it not
worth a duplicate representation of the reach rule to reach it, and a reviewer
may disagree.

**HIS-11 — "a failed operation restores the bytes it moved aside". NOT STATED —
omission upheld at review, but MY CITATION WAS WRONG and the review's is better.**

v1 cited `Internal.InFlightMaterializationLeavesTheChildUnitIntact`. That
invariant is guarded on `staging.active`, **so it says nothing at all after an
abort** — which is precisely the moment HIS-11 is about. The invariant that
actually covers it is

```tla
AgentEditsSurviveMaterialization ==
  \A u \in agent_edits:
    /\ child_unit[u].present
    /\ child_unit[u].content = AgentBytes
```

over a **monotone witness set**, whose own comment reads *"no stage, swap,
prune, or abort may overwrite or delete it"*. That is a post-condition that
survives the abort, and restating it is what `dual_representation_rule` forbids.

What remains uncovered is "restored" versus "never touched", which is
byte-identical and harmless. HIS-11's defect was an **EMPTY** pre-state
compensation in an exhaustive switch; what catches *that* is a probe with a
non-degenerate pre-state — a fixture property, already vacuity-ledger row 10 —
not a state relation this module can hold.

**Stated instead:** HIS-10, HIS-13/DEF-067 and HIS-14, argued in §1.

---

## 5. Validation

| check | configuration | result |
| --- | --- | --- |
| `run_tlc.sh`, 18 cfgs, `specs/desired_program_model` | home pinned to this worktree (`tlc2` resolves from `$SKILL_MANAGER_HOME/bin/cli`) | 2 green (healthy + guard), 16 red, every red naming its declared invariant |
| `run_tlc.sh`, 18 cfgs, `specs/current` mirror | same | identical results, byte-identical mirrors |
| cross-check sweep, 14 cfgs, target removed | same | **14 green** |
| `uv run pytest specs/` | five home vars UNSET (DEF-074) | **88 passed** (38 pre-existing + 25 new × 2 model dirs) |
| `pytest … -q` on a CI-shaped runner, locally | `env -i`, no `tlc2` | **34 passed, 18 skipped**, skip reason named under `-rs` |
| **the real CI runner**, PR #249 | GitHub Actions `unit-tests` | **34 passed, 18 skipped in 2.55 s** — the predicted number, confirmed |
| `jbang RunTests.java` | five home vars UNSET (DEF-074) | **ALL PASSED — 146 suites, 1384 cases, 0 failed** (re-run post-review, identical) |
| `run.py home-integrity` | five home vars UNSET (DEF-074) | **BUILD SUCCESSFUL — 18/18 nodes passed**, run `20260824-030800` (re-run post-review) |

| 18-cfg sweep × BOTH model dirs, post-review | home pinned | **32 red / 4 green** — `tlc/POSTREBASE-sweep.txt` |
| cross-check sweep, rebuilt post-review | home pinned | **0 reds of 14** — `tlc/CROSSCHECK-target-removed.txt` |
| `skill-manager home close-out --home <wt> --into <project>` | home pinned, raw build (`./skill-manager`, not the PATH 0.24.0) | **`safe: true`, exit 0, no blockers**, four units all `unchanged` — `close-out.json`. The project home was NOT synced into; `git status` there is clean. |

`run.py --all` was NOT run. It is multi-hour and belongs to HIS-6, which owns
the one terminal sweep — owner's instruction, recorded in the assignment block.

---

## 6. What I am NOT confident about

1. **That nine invariants over a model is worth what it costs.** This ticket
   added 18 variables and 24,000 states to a view nothing executes against the
   product. **The enforcement half is now real and runs in CI** — §3(d) and
   §3(e); DEF-087 is closed, not deferred. What remains true is that these are
   statements about a MODEL: if the model and the product drift, the suite stays
   green. The `home-integrity` graph is the only thing checking these relations
   against real homes, and this ticket did not extend it.
2. **The HIS-11 omission** — upheld at review, with my citation corrected. §4.
3. **Invariant 11's fidelity.** `hi_store_write = hi_agent_write` is a two-state
   model of a contract HIS-14 delivered across **twelve** verbs — v1 said seven,
   which understated delivery; `grep -rn '"--home"' src/main/java/dev/skillmanager/commands/`
   returns 12, and `SkillManagerCli.bindNamedHome` walks the picocli parse chain
   generically. It catches the measured defect. It says nothing about a command
   that binds both axes to the *wrong* home, and I did not model home identity
   beyond named/ambient.
4. **Invariant 12 asserts something the product does not do.** DEF-067 is open.
   If HIS-6 reads `specs/current` as a description of current behaviour rather
   than of intended behaviour, this row is wrong there and right in
   `desired_program_model`. The two directories are byte-identical for this
   module today, so the distinction has nowhere to live.
5. **A duplicate finding was avoided by luck — and my account of it was unfair
   to HIS-8, so here is the corrected version.** I wrote an SF entry against
   `tla-spec-dev close ticket`'s status gate; HIS-8 had already filed it
   upstream with a URL. Mine was dropped at the rebase, seen only because both
   tickets appended to the same file so git forced the two versions side by
   side.

   **The correction: HIS-8 *did* also record it in the deferred backlog, as
   DEF-080.** I branched before that commit landed, so my grep was correct
   against the tree I had and the miss was a concurrency artefact, not a missing
   cross-reference. The ledger split is still real — review measured that
   `skill_feedback.md` appears nowhere in `git-epic-workflow` or
   `git-issue-workflow`, that `references/deferment.md` carries **no pre-filing
   dedup instruction of any kind** (so my grep was personal discipline, not a
   rule), and that exactly one cross-reference exists in either direction. But
   this particular near-miss is not evidence for it. Filed as **DEF-089**; the
   epic agent has taken the upstream fix.
6. **Whether the six property groups should have been six specifications.** The
   repo's own precedent (`two_specs_per_module`) says disjoint variable sets get
   their own specs, and I put them all under one `HomeIntegritySpec` so the
   healthy cfg checks everything at once. That is why the state count is a
   product.

   **The trade-off v1 omitted, and it cuts the other way.** Splitting would turn
   the product into a sum — and it would make
   `HomeIntegrityInternal_probe_reach.cfg` **impossible**, because that probe
   works precisely by putting every group's antecedent live in ONE state, which
   is only true while one specification drives them all. So the un-split model
   is not merely convenient; it buys the strongest vacuity evidence in this
   view. A split needs a different answer to *"was this invariant ever
   evaluated"*, not just six more healthy configurations. Deferral upheld at
   review, which also verified all 11 reachable antecedents directly: **no
   invariant here is checked over a space too small to reach its interesting
   states.**

---

## 7. CI on this PR, read rather than assumed

**Both answered by the epic owner.** `Lint PR title`: *keep* `feat(HIS-5): …` —
nothing downstream depends on the bare `HIS-N:` shape, and the original
instruction described a past failure whose title began with a digit. `DCO`:
leave it red — it is a legal attestation, it fails on every PR in this epic, and
the owner carries it as a known disclosed state on the epic PR.

`Lint PR title` was RED and is now green. The title carried no
conventional-commit type prefix at all, so
`amannn/action-semantic-pull-request`'s `headerPattern` failed before its
`subjectPattern` was ever reached — which is what the assignment's *"the PR
title's subject must start with a letter"* was pointing at, and I mis-read it
as a rule about the first character rather than about the text after a type.
Retitled `feat(HIS-5): …`, which satisfies the linter and keeps the ticket id
where the epic owner looks for it. **Say if the bare `HIS-N: …` shape is
load-bearing for tooling and I will put it back** — HIS-8 merged with that shape
and a red lint.

`DCO` is RED and I did **not** fix it. It is red on HIS-8's merged PR too, and
**no commit on this epic branch carries a `Signed-off-by` trailer** — 0 of the
last 15, against 14 of the last 20 on `main`. So the epic merges through it by
practice. A sign-off is a legal attestation made in the author's name, and a
ticket agent adding one on the operator's behalf is not a formatting fix.
Escalated rather than silently satisfied.

The rest: `Lint release signal for rebase merge` pass, GitGuardian pass, graph
jobs `skipping` (expected — suspended on push and PR, per CLAUDE.md), and
`skill-manager unit tests (RunTests.java + spec models)` **passed, and §3(d)'s
suite genuinely ran inside it** — read for the case count, not the colour.

| runner measurement | result |
| --- | --- |
| **before** the jar step (PR #249, first push) | `34 passed, 18 skipped in 2.55s` — exactly the number the local `env -i` simulation predicted |
| **after** the jar step | see the run linked in the PR — the number, not the colour |

**Checking that the job passed would not have shown either of these.** A suite
that fails to collect at all also reports no failures, so the pass/fail signal
cannot distinguish "18 invariants enforced" from "18 invariants skipped" from
"the file was never imported". That is the same defect this whole ticket is
about, sitting in the harness rather than in the model — which is why the count
is quoted from the log every time.
