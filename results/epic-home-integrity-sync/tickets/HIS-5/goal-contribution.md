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

## 0. What this ticket does NOT deliver, up front

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
- **This ticket adds no executable coverage.** Nine invariants over a bounded
  model are nine statements about a MODEL. The `home-integrity` graph is what
  checks the same relations against real homes, and this ticket did not extend
  it. If the model and the product drift, nothing here notices.
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

*The invariant does not mention `hi_record_names_source`, and must not.* That is
the whole content: an invariant that granted the record a vote would be
satisfied by exactly the implementation the defect was —
`sourceHeldTheseBytes && recordIsAboutThisSource`. `RefreshTheUnitFromEvidence-
OnDisk` lets the record range over BOTH values rather than pinning it, so TLC
must show the property holding whichever way the record points.

*Counterexample:* one step. `HoldBackAnIdenticalTreeBecauseOfItsRecord` with
`hi_unit_content = hi_live_source = "t_src"` carried from Init and
`hi_record_names_source = FALSE` — sixteen of the operator's eighteen records —
sets `hi_held_back = TRUE`. Nothing else in the state moves. **Violates 5.
`ADivergentTreeIsNeverSilentlyReplaced` is listed in that cfg and holds**, and
that is not an accident of ordering: the broken rule is strictly MORE
conservative than the fixed one, so clause 2 cannot fire on it.

**6 — `(hi_unit_content # hi_live_source) => hi_held_back`.** The guard on 5, and
the reason 5 is not the whole property: "everything is disposable" satisfies 5
completely and destroys every agent edit in the home. `DisposeOfEveryTree-
Unconditionally` refutes it; **5 holds on that trace**, cross-checked.

**7 — `hi_full_surfacings <= 1`.** One unacknowledged gate is surfaced in full at
most once; a genuinely new change zeroes the counter and re-opens the full
report, which is why `SyncDetectsAChange` resets rather than leaves it.

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

**(b) Does each counterexample violate MY invariant?** Every regression cfg
lists its neighbours, and all fourteen were re-run with the target removed and
the other thirteen kept. **Fourteen greens.** Each counterexample violates
exactly one invariant, and it is the declared one. `tlc/CROSSCHECK-target-removed.txt`.

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
above is measuring nothing.

**Validating the instrument before believing it (HIS-8's lesson).** The
classifier is one `grep -E "^Error: Invariant|No error has been found"`. It was
exercised on inputs whose answer was known **in both directions** before any
result was believed: 16 reds naming 12 distinct invariants, and 4 greens, from
the same command shape over the same corpus. The cross-check sweep in particular
**can** come back red — the identical pipeline produced the reds in §1 — so its
fourteen greens are a measurement and not a pattern that cannot match. A sweep
that cannot fail is the same defect as an assertion that cannot fail.

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

**HIS-11 — "a failed operation restores the bytes it moved aside". NOT STATED.**
The rule is `Internal.InFlightMaterializationLeavesTheChildUnitIntact` and
`External.AConflictedUnitIsNeverPartiallyWritten`: a destination is never left as
half of two things. HIS-11's defect was not a different rule — it was an
**EMPTY** pre-state compensation in an exhaustive switch, and an at-rest
invariant over this view's variables cannot tell "restored" from "never
touched". What catches that is a probe with a non-degenerate pre-state, which is
a fixture property and is already recorded as vacuity-ledger row 10.

*This is the weakest of my omissions.* A write witness would make it statable,
the way `sync_history` was added to External for exactly this reason. I did not
add one.

**Stated instead:** HIS-10, HIS-13/DEF-067 and HIS-14, argued in §1.

---

## 5. Validation

| check | configuration | result |
| --- | --- | --- |
| `run_tlc.sh`, 18 cfgs, `specs/desired_program_model` | home pinned to this worktree (`tlc2` resolves from `$SKILL_MANAGER_HOME/bin/cli`) | 2 green (healthy + guard), 16 red, every red naming its declared invariant |
| `run_tlc.sh`, 18 cfgs, `specs/current` mirror | same | identical results, byte-identical mirrors |
| cross-check sweep, 14 cfgs, target removed | same | **14 green** |
| `uv run pytest specs/` | five home vars UNSET (DEF-074) | **38 passed** |
| `jbang RunTests.java` | five home vars UNSET (DEF-074) | **ALL PASSED — 146 suites, 1384 cases, 0 failed** |
| `run.py home-integrity` | five home vars UNSET (DEF-074) | **BUILD SUCCESSFUL — 18/18 nodes passed**, run `20260824-021701` |

| 18-cfg sweep re-run AFTER rebase onto `e644bd2` | home pinned | identical — `tlc/POSTREBASE-sweep.txt` |
| `skill-manager home close-out --home <wt> --into <project>` | home pinned, raw build (`./skill-manager`, not the PATH 0.24.0) | **`safe: true`, exit 0, no blockers**, four units all `unchanged` — `close-out.json`. The project home was NOT synced into; `git status` there is clean. |

`run.py --all` was NOT run. It is multi-hour and belongs to HIS-6, which owns
the one terminal sweep — owner's instruction, recorded in the assignment block.

---

## 6. What I am NOT confident about

1. **That nine invariants over a model is worth what it costs.** This ticket
   added 18 variables and 24,000 states to a view nothing executes. Its whole
   value is that a future change which reintroduces one of these defects makes a
   cfg go green, and **nothing runs these cfgs automatically** — the graphs are
   suspended on push and PR, and `run_tlc.sh` is invoked by hand. An assertion
   nobody runs is the same defect as an assertion that cannot fail, one level
   up. HIS-6 should decide whether this view has a runner.
2. **The HIS-11 omission**, per §4.
3. **Invariant 11's fidelity.** `hi_store_write = hi_agent_write` is a two-state
   model of a contract HIS-14 delivered across seven verbs. It catches the
   measured defect. It says nothing about a command that binds both axes to the
   *wrong* home, and I did not model home identity beyond named/ambient.
4. **Invariant 12 asserts something the product does not do.** DEF-067 is open.
   If HIS-6 reads `specs/current` as a description of current behaviour rather
   than of intended behaviour, this row is wrong there and right in
   `desired_program_model`. The two directories are byte-identical for this
   module today, so the distinction has nowhere to live.
5. **A duplicate finding was avoided by luck, not by process, and it is worth
   more than any of the above.** I wrote an SF-002 against
   `tla-spec-dev close ticket`'s status gate before rebasing. **HIS-8 had
   already filed it, upstream, with a URL** — its own SF-002, one commit ahead
   of me on the epic branch. Mine was dropped at the rebase, and the ONLY
   reason it was seen is that both tickets happened to append to the same file,
   so git forced the two versions to be read side by side. Had HIS-8 filed it
   anywhere else, I would have re-filed a known defect as new — which is this
   epic's named recurring failure. I grepped the DEFERRED backlog before filing
   (as instructed) and it was not there; `specs/results/skill_feedback.md` is a
   second ledger, and nothing told me to grep it. **There are at least two
   append-only ledgers in this repository and the pre-filing check covers one.**
6. **Whether the six property groups should have been six specifications.** The
   repo's own precedent (`two_specs_per_module`) says disjoint variable sets get
   their own specs, and I put them all under one `HomeIntegritySpec` so the
   healthy cfg checks everything at once. That is why the state count is a
   product. It was a deliberate trade and it is the decision most likely to look
   wrong at the next extension.
