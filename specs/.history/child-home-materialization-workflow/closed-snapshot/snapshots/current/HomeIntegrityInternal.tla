------------------------- MODULE HomeIntegrityInternal -------------------------
\* ARTI-22 (#124) home-integrity policy slice.
\*
\* The accepted whole-program model remains SkillManager.tla, byte-for-byte.
\* This is a bounded, deliberately non-overlapping semantic view of ONE
\* question: what does it mean for a Skill Manager home to be healthy? It
\* models no command surface and replaces no action; it names the relations a
\* home must hold between records it wrote, and the ways a change can break
\* them.
\*
\* Refinement record: this bounded policy view strengthens the record/store and
\* drift/digest relations that the accepted whole-program actions
\* SkillManager.SyncUnit and SkillManager.RecordHomeDrift leave abstract. It
\* does not replace either.
\*
\* WHY THIS MODULE EXISTS AT ALL. The epic surfaced ten defects in the
\* operator's own homes, each found by accident, none asserted anywhere. #124
\* proposed nine candidate invariants and said explicitly that the list was a
\* starting point: argue each, and drop any that turn out to be false, because
\* an invariant a healthy home genuinely violates is a modelling error and not
\* a bug. Three did not survive that argument. Two of the three corrections are
\* recorded here as regression configurations, because a correction that lives
\* only in prose is a correction that gets re-derived wrongly next year.
\*
\* THE REGRESSION CONFIGURATIONS ARE EXPECTED TO FAIL, per this repository's
\* regression_config_rule: every property carries a configuration that must
\* keep violating it, and a regression config that starts PASSING is a defect
\* in the invariant rather than good news.
\*
\*   HomeIntegrityInternal.cfg
\*       the correct behaviours; every invariant holds.
\*   HomeIntegrityInternal_regression_silentdrift.cfg
\*       a sync that advances a store, leaves the record behind, and records no
\*       error. RecordDescribesItsStoreOrSaysWhy must FAIL.
\*   HomeIntegrityInternal_regression_staleack.cfg
\*       an acknowledgement that receipts the digest observed at DETECTION time
\*       while the home has already moved past it. AckIsNotStaleOnArrival must
\*       FAIL.
\*   HomeIntegrityInternal_regression_narrowprojection.cfg
\*       THE CORRECTION, made executable. It runs the CORRECT specification --
\*       a healthy home -- against #124's narrower wording of invariant 9,
\*       ProjectionResolvesInsideThisHome. That invariant must FAIL, and the
\*       fact that it fails on a home doing nothing wrong is the whole finding:
\*       51 of the operator's 106 projections resolve into child homes this
\*       home itself registered, and reading that as "serves bytes from another
\*       home" turns correct child-home materialization into a defect report.

EXTENDS Integers

VARIABLES
  hi_record_revision,      \* the gitHash in installed/<u>.json
  hi_store_revision,       \* the store checkout's HEAD
  hi_record_error,         \* does that record carry an error explaining a gap
  hi_home_digest,          \* home.digest.json's digest
  hi_ack_baseline,         \* the digest home.drift.json says was acknowledged
  hi_acknowledged,         \* home.drift.json's acknowledged flag
  hi_projection_target,    \* where a recorded projection resolves
  hi_child_registered,     \* is that target a child home THIS home registered
  hi_ack_own_writes,       \* did the acking operation itself write after its baseline
  hi_phase,

  \* ------------------------------------------------------------ HIS-5 ---
  \* HIS-1: disposability, decided from bytes on disk.
  hi_unit_pass,            \* has the refresh pass for this unit run yet
  hi_unit_content,         \* the bytes the destination unit is standing on
  hi_live_source,          \* the bytes the live SOURCE is standing on right now
  hi_record_names_source,  \* does the unit's record name THIS store as its source
  hi_held_back,            \* did the pass refuse to refresh the unit

  \* HIS-4: a reported error is re-derived, not remembered.
  hi_error_cause_live,     \* is the condition the recorded error names true of the tree

  \* HIS-3: the drift gate settles without failing open.
  hi_gate_open,            \* is there an unacknowledged drift gate
  hi_gate_change,          \* how many distinct changes the open gate has absorbed
  hi_full_surfacings,      \* home.drift.json's surfacedCount for the CURRENT gate content
  hi_launch_admitted,      \* did the launch gate let the process run

  \* HIS-10: a clone's descent is a pointer, not a grant.
  hi_descent_record,       \* what the clone's provenance record names as its parent
  hi_parent_claims_home,   \* does that parent's OWN store hold a live claim on this home
  hi_shim_sanctioned,      \* does a reader treat the inherited parent-store shim as sanctioned

  \* HIS-14: a home has two axes.
  hi_store_write,          \* which home the STORE half of a command's writes landed in
  hi_agent_write,          \* which home the AGENT-CONFIG half landed in

  \* HIS-13 / DEF-067: an observer that repairs is no longer an observer.
  hi_home_damaged,         \* does the home carry the defect the instrument exists to detect
  hi_observation,          \* what the observation reported
  hi_observer_repaired     \* WITNESS: did the observation itself run a remedy

\* Written out literally. TLC recognizes the subscript of [][A]_vars
\* syntactically and warns that every variable is missing from it when the
\* tuple is built by concatenating the group tuples below.
vars == << hi_record_revision, hi_store_revision, hi_record_error,
           hi_home_digest, hi_ack_baseline, hi_acknowledged,
           hi_projection_target, hi_child_registered, hi_ack_own_writes,
           hi_phase,
           hi_unit_pass, hi_unit_content, hi_live_source,
           hi_record_names_source, hi_held_back,
           hi_error_cause_live,
           hi_gate_open, hi_gate_change, hi_full_surfacings,
           hi_launch_admitted,
           hi_descent_record, hi_parent_claims_home, hi_shim_sanctioned,
           hi_store_write, hi_agent_write,
           hi_home_damaged, hi_observation, hi_observer_repaired >>

\* Group tuples, used only in UNCHANGED clauses. Each HIS-5 property group is
\* a DISJOINT set of variables and each new invariant reads only its own
\* group, so a counterexample to one cannot be a counterexample to another.
\* That separation is not cosmetic: it is what lets every expected-violation
\* configuration below carry its NEIGHBOURS as well as its target and still
\* name the target in TLC's report. See the vacuity ledger, mechanism A -- a
\* configuration that fails for a reason other than the invariant under test
\* is a probe pointed at the wrong branch.
arti22_vars   == << hi_record_revision, hi_store_revision, hi_record_error,
                    hi_home_digest, hi_ack_baseline, hi_acknowledged,
                    hi_projection_target, hi_child_registered,
                    hi_ack_own_writes, hi_phase >>
disposal_vars == << hi_unit_pass, hi_unit_content, hi_live_source,
                    hi_record_names_source, hi_held_back >>
gate_vars     == << hi_gate_open, hi_gate_change, hi_full_surfacings,
                    hi_launch_admitted >>
descent_vars  == << hi_descent_record, hi_parent_claims_home,
                    hi_shim_sanctioned >>
axis_vars     == << hi_store_write, hi_agent_write >>
observer_vars == << hi_home_damaged, hi_observation, hi_observer_repaired >>

\* Everything HIS-5 added, for the ARTI-22 actions that touch none of it.
his5_vars == << disposal_vars, hi_error_cause_live, gate_vars, descent_vars,
                axis_vars, observer_vars >>

HiRevisions == {"A", "B"}
HiTargets == {"self", "child", "foreign"}
HiPhases == {"provisioned", "changed", "acknowledged"}

\* HIS-5 domains.
HiTrees        == {"t_src", "t_edited"}   \* two trees that are not the same bytes
HiUnitPasses   == {"pending", "done"}
HiDescents     == {"none", "parent", "nowhere"}
HiWriteTargets == {"none", "named", "ambient"}
HiObservations == {"none", "clean", "damaged"}

Init ==
  /\ hi_record_revision = "A"
  /\ hi_store_revision = "A"
  /\ hi_record_error = FALSE
  /\ hi_home_digest = "A"
  /\ hi_ack_baseline = "A"
  /\ hi_acknowledged = TRUE
  /\ hi_projection_target = "self"
  /\ hi_child_registered = FALSE
  /\ hi_ack_own_writes = FALSE
  /\ hi_phase = "provisioned"
  \* HIS-5. A home that has done nothing yet: the unit is standing on the same
  \* bytes as its source, nothing is held back, no gate is open, no error is
  \* reported and none is live, no descent is recorded and nothing is
  \* sanctioned, no command has written on either axis, the home is undamaged
  \* and unobserved. Every HIS-5 invariant holds here, which is what lets the
  \* five ARTI-22 regression specs -- which touch none of these variables --
  \* keep failing on their own invariant and nothing else.
  /\ hi_unit_pass = "pending"
  /\ hi_unit_content = "t_src"
  /\ hi_live_source = "t_src"
  /\ hi_record_names_source = FALSE
  /\ hi_held_back = FALSE
  /\ hi_error_cause_live = FALSE
  /\ hi_gate_open = FALSE
  /\ hi_gate_change = 0
  /\ hi_full_surfacings = 0
  /\ hi_launch_admitted = FALSE
  /\ hi_descent_record = "none"
  /\ hi_parent_claims_home = FALSE
  /\ hi_shim_sanctioned = FALSE
  /\ hi_store_write = "none"
  /\ hi_agent_write = "none"
  /\ hi_home_damaged = FALSE
  /\ hi_observation = "none"
  /\ hi_observer_repaired = FALSE

\* @invariant TypeOK
TypeOK ==
  /\ hi_record_revision \in HiRevisions
  /\ hi_store_revision \in HiRevisions
  /\ hi_record_error \in BOOLEAN
  /\ hi_home_digest \in HiRevisions
  /\ hi_ack_baseline \in HiRevisions
  /\ hi_acknowledged \in BOOLEAN
  /\ hi_projection_target \in HiTargets
  /\ hi_child_registered \in BOOLEAN
  /\ hi_ack_own_writes \in BOOLEAN
  /\ hi_phase \in HiPhases
  /\ hi_unit_pass \in HiUnitPasses
  /\ hi_unit_content \in HiTrees
  /\ hi_live_source \in HiTrees
  /\ hi_record_names_source \in BOOLEAN
  /\ hi_held_back \in BOOLEAN
  /\ hi_error_cause_live \in BOOLEAN
  /\ hi_gate_open \in BOOLEAN
  /\ hi_gate_change \in 0..2
  /\ hi_full_surfacings \in 0..2
  /\ hi_launch_admitted \in BOOLEAN
  /\ hi_descent_record \in HiDescents
  /\ hi_parent_claims_home \in BOOLEAN
  /\ hi_shim_sanctioned \in BOOLEAN
  /\ hi_store_write \in HiWriteTargets
  /\ hi_agent_write \in HiWriteTargets
  /\ hi_home_damaged \in BOOLEAN
  /\ hi_observation \in HiObservations
  /\ hi_observer_repaired \in BOOLEAN

------------------------------------------------------------------------------
\* The correct behaviours.
------------------------------------------------------------------------------

\* A sync that advances the store and updates the record with it. The ordinary
\* case, and the one every other case is measured against.
\* @command SyncUnitAdvancingBothStoreAndRecord
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_sync
SyncAdvancesStoreAndRecord ==
  /\ hi_phase = "provisioned"
  /\ hi_store_revision' = "B"
  /\ hi_record_revision' = "B"
  /\ hi_record_error' = FALSE
  /\ hi_home_digest' = "B"
  /\ hi_acknowledged' = FALSE
  /\ hi_phase' = "changed"
  /\ UNCHANGED << hi_ack_baseline, hi_projection_target, hi_child_registered,
                  hi_ack_own_writes >>
  /\ UNCHANGED his5_vars

\* A sync whose merge conflicted: the store moved, the record could not follow,
\* and the record SAYS SO. This is the state three units are in on the
\* operator's project home right now -- deploy-helm, hyper-experiments-finance
\* and spec-double-compiler, each carrying errors[0].kind = "MERGE_CONFLICT".
\* It is not a defect and the invariant admits it, which is exactly why the
\* invariant has a disjunct.
\* @command SyncUnitConflictingAndRecordingTheError
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_sync
SyncConflictsAndRecordsTheError ==
  /\ hi_phase = "provisioned"
  /\ hi_store_revision' = "B"
  /\ hi_record_revision' = "A"
  /\ hi_record_error' = TRUE
  /\ hi_home_digest' = "B"
  /\ hi_acknowledged' = FALSE
  /\ hi_phase' = "changed"
  /\ UNCHANGED << hi_ack_baseline, hi_projection_target, hi_child_registered,
                  hi_ack_own_writes >>
  \* HIS-4. The error is recorded because the condition is TRUE of the tree
  \* right now. That is the whole content of ARecordedErrorIsAFunctionOf-
  \* TheLiveTree: the record is the report, not the cause.
  /\ hi_error_cause_live' = TRUE
  /\ UNCHANGED << disposal_vars, gate_vars, descent_vars, axis_vars,
                  observer_vars >>

\* Materialize a unit into a child home this home registered. The projection
\* leaves this home and is entirely accountable, because child-homes/ records
\* the target and the units it carries.
\* @command MaterializeUnitIntoRegisteredChildHome
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.project_resolve
MaterializeIntoRegisteredChild ==
  /\ hi_phase = "provisioned"
  /\ hi_projection_target' = "child"
  /\ hi_child_registered' = TRUE
  /\ UNCHANGED << hi_record_revision, hi_store_revision, hi_record_error,
                  hi_home_digest, hi_ack_baseline, hi_acknowledged,
                  hi_ack_own_writes, hi_phase >>
  /\ UNCHANGED his5_vars

\* Acknowledge the pending change, receipting the digest the home is on NOW.
\* DriftGate.recordSince writes the pending gate and then refreshes the
\* baseline, so report.to already equals home.digest.json's digest by the time
\* acknowledge() carries it through. Verified on the operator's root home:
\* both df22c759..., acknowledged.
\* @command AcknowledgeDriftAgainstTheCurrentDigest
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift_ack
AcknowledgeCurrentDigest ==
  /\ hi_phase = "changed"
  /\ hi_acknowledged' = TRUE
  /\ hi_ack_baseline' = hi_home_digest
  /\ hi_phase' = "acknowledged"
  /\ UNCHANGED << hi_record_revision, hi_store_revision, hi_record_error,
                  hi_home_digest, hi_projection_target, hi_child_registered >>
  /\ hi_ack_own_writes' = FALSE
  /\ UNCHANGED his5_vars

------------------------------------------------------------------------------
\* The regression behaviours. Each is a thing the product must NOT do, kept
\* runnable so the invariant that forbids it cannot quietly stop forbidding it.
------------------------------------------------------------------------------

\* A sync that advances the store, leaves the record naming the old revision,
\* and records NOTHING about the gap. This is what "3 of 20 records disagree
\* with their own store" would have meant if those three had not carried an
\* error -- and it is the state a future change could produce silently.
\* @command SyncUnitLeavingTheRecordBehindSilently
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_sync_regression
SyncLeavesTheRecordBehindSilently ==
  /\ hi_phase = "provisioned"
  /\ hi_store_revision' = "B"
  /\ hi_record_revision' = "A"
  /\ hi_record_error' = FALSE
  /\ hi_home_digest' = "B"
  /\ hi_acknowledged' = FALSE
  /\ hi_phase' = "changed"
  /\ UNCHANGED << hi_ack_baseline, hi_projection_target, hi_child_registered,
                  hi_ack_own_writes >>
  /\ UNCHANGED his5_vars

\* Materialize a unit into a home this home never registered as a child. The
\* projection leaves this home and NOTHING here can say whose bytes it serves:
\* no child-homes/ record names the target, so the home cannot distinguish it
\* from a stale link into a directory that used to be something. This is the
\* state #124's invariant 9 was reaching for, and it is genuinely undecidable.
\* @command MaterializeUnitIntoUnregisteredHome
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.project_resolve_regression
MaterializeIntoUnregisteredHome ==
  /\ hi_phase = "provisioned"
  /\ hi_projection_target' = "foreign"
  /\ hi_child_registered' = FALSE
  /\ UNCHANGED << hi_record_revision, hi_store_revision, hi_record_error,
                  hi_home_digest, hi_ack_baseline, hi_acknowledged,
                  hi_ack_own_writes, hi_phase >>
  /\ UNCHANGED his5_vars

\* An acknowledgement that receipts the digest observed when the change was
\* DETECTED rather than the digest the home is on. The ack is then a receipt
\* for a state nobody is in, and the very next drift check re-pends with no
\* cause the operator can see. #124's defect 4 was diagnosed as this shape
\* before the sequencing turned out to explain it; the shape remains possible,
\* so it stays modelled.
\* @command AcknowledgeDriftAgainstTheDetectionDigest
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift_ack_regression
AcknowledgeDetectionDigest ==
  /\ hi_phase = "changed"
  /\ hi_acknowledged' = TRUE
  /\ hi_ack_baseline' = "A"
  /\ hi_ack_own_writes' = FALSE
  /\ hi_phase' = "acknowledged"
  /\ UNCHANGED << hi_record_revision, hi_store_revision, hi_record_error,
                  hi_home_digest, hi_projection_target, hi_child_registered >>
  /\ hi_ack_own_writes' = FALSE
  /\ UNCHANGED his5_vars

\* An acknowledging operation that performs its OWN content writes after
\* computing the baseline it acknowledges. This is #124's defect 4, in the only
\* form that is assertable without demanding the gate fail open: ARTI-00 acked
\* project-tier drift and the SAME operation's syncs then changed three units,
\* leaving the home DRIFT PENDING again with nothing the operator did in
\* between. Re-pending after a change is correct; a command that re-pends
\* itself is a transaction-boundary defect in that command.
\* @command AcknowledgeDriftThenWriteWithinTheSameOperation
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift_ack_regression
AcknowledgeThenWriteWithinTheSameOperation ==
  /\ hi_phase = "changed"
  /\ hi_acknowledged' = TRUE
  /\ hi_ack_baseline' = hi_home_digest
  /\ hi_ack_own_writes' = TRUE
  /\ hi_phase' = "acknowledged"
  /\ UNCHANGED << hi_record_revision, hi_store_revision, hi_record_error,
                  hi_home_digest, hi_projection_target, hi_child_registered >>
  /\ UNCHANGED his5_vars

------------------------------------------------------------------------------
\* HIS-5 (#217). The behaviours this epic FIXED, and the broken ones they
\* replaced, kept runnable so the fixes cannot come back undone unnoticed.
\*
\* WHAT IS DELIBERATELY NOT STATED HERE, and why -- because an omission that is
\* a decision is fine and an omission nobody noticed is what this epic keeps
\* catching:
\*
\*   HIS-9, "a write resolving outside the home's declared roots is refused".
\*     The AT-REST half is already stated, twice, in the accepted model:
\*     External.WritesThroughOneHomeReachNoOtherHome (over the write witness)
\*     and External.NoOwnedSurfaceNamesAnotherHome. Restating it here is exactly
\*     what planning_rules.dual_representation_rule forbids. What HIS-9 adds on
\*     top -- that the offending write is REFUSED, with an exit code, naming the
\*     path and the home -- is a property of the COMMAND SURFACE, and this
\*     module's header says it models none.
\*
\*   HIS-11, "a failed operation restores the bytes it moved aside".
\*     THE CITATION HERE WAS CORRECTED AT REVIEW, and the correction matters
\*     because the first one did not actually cover the case.
\*     Internal.InFlightMaterializationLeavesTheChildUnitIntact is guarded on
\*     `staging.active`, so it says nothing at all AFTER an abort -- which is
\*     precisely the moment HIS-11 is about. The invariant that does cover it is
\*
\*         Internal.AgentEditsSurviveMaterialization ==
\*           \A u \in agent_edits:
\*             /\ child_unit[u].present
\*             /\ child_unit[u].content = AgentBytes
\*
\*     over a MONOTONE witness set, with the comment "no stage, swap, prune, or
\*     abort may overwrite or delete it". That is a post-condition that survives
\*     the abort, and it is the rule dual_representation_rule forbids restating.
\*     What is left uncovered is "restored" versus "never touched", which is
\*     byte-identical and harmless. HIS-11's defect was an EMPTY compensation
\*     list in an exhaustive switch; what catches that is a probe with a
\*     non-degenerate pre-state, a fixture property recorded as vacuity-ledger
\*     row 10, not a state relation this module can hold.
\*
\* HIS-14 IS stated, and the reason it is not in the paragraph above is worth
\* recording: the agent axis is not the store axis, and NO module in this
\* directory models it. External's homes have one axis. DEF-076 measured the
\* consequence -- the agent axis is outside `home verify`'s walk, so nothing saw
\* the dangling projections until `home repair` existed.
------------------------------------------------------------------------------

\* ---------------------------------------------------------------- HIS-1 ---
\* Disposability is decided from bytes on disk. `hi_live_source` is what the
\* source is standing on RIGHT NOW; `hi_record_names_source` is whether the
\* destination's record happens to name this store. Sixteen of eighteen of the
\* operator's records named something else -- two deleted worktrees and a
\* /private/tmp scratchpad among them -- over trees nobody had touched.
\*
\* Both halves of the pass are ONE action on purpose. Splitting "present the
\* unit" from "decide it" leaves an intermediate state in which a divergent
\* tree is not yet held back, and ADivergentTreeIsNeverSilentlyReplaced would
\* have had to be weakened to "...once the pass is done" -- a guard a broken
\* model satisfies by never finishing a pass.
\*
\* ============================ THE FRAME CONDITION ==========================
\* READ THIS BEFORE ADDING AN ACTION TO THIS GROUP. It is load-bearing, it was
\* undocumented in the first version of this ticket, and a reviewer found it.
\*
\* TWO THINGS DO NOT HAPPEN inside the window this group models:
\*   (1) THE SOURCE DOES NOT MOVE. `hi_live_source` is declared a variable and
\*       is assigned ONLY in Init -- constant across all reachable states. It
\*       is a variable rather than a constant so that a later ticket can make
\*       it move deliberately, not because anything moves it today.
\*   (2) NOTHING EDITS A UNIT OUTSIDE A PASS. `hi_unit_content` changes only in
\*       the same step that judges it.
\*
\* Together those make "the tree diverges from the live source" and "the pass
\* judged this tree divergent" the SAME PROPOSITION, which is what lets clause
\* 2 be stated over the divergence rather than over a witness of what the pass
\* saw.
\*
\* WHAT BREAKS IF YOU REMOVE EITHER, and it is the CORRECT configuration that
\* breaks, not a regression one. MEASURED, by adding
\* `TheSourcePublishesANewRevision` -- the most ordinary event in the system --
\* to HomeIntegrityNext and running HomeIntegrityInternal.cfg:
\*
\*     clause 2 UNGUARDED   red at DEPTH 2   the source moves; no pass has run
\*     clause 2 GUARDED     red at DEPTH 3   a pass refreshes, then the source
\*                                           moves past the refreshed copy
\*
\* Production really does leave a unit standing on bytes that differ from the
\* live source without holding it back: an ordinary clean out-of-date copy
\* satisfies `destUntouched && recordIsAboutThisSource && !mergeResult`, is
\* disposed of, and is refreshed. THAT IS WHAT `sync` IS FOR.
\*
\* READ THE TWO NUMBERS TOGETHER, because they say something the guard alone
\* does not. THE GUARD DOES NOT MAKE CLAUSE 2 TRUE OF A MOVING SOURCE -- it buys
\* one step. What makes it true is the frame, and the guard's job is to stop the
\* invariant claiming anything about states no pass has judged. Both are needed
\* and neither substitutes for the other.
\*
\* THE REPAIR IS NOT TO WEAKEN CLAUSE 2. That is the move this module's own
\* header says it refused, and it would give back HIS-1's second acceptance
\* assertion. The repair is to capture WHAT THE PASS JUDGED -- the divergence
\* observed at the moment of the decision -- and state clause 2 over that. It
\* costs a witness and therefore a guard configuration, which is why it was not
\* done here for a model in which the source cannot move.
\* ===========================================================================

\* @command RefreshAUnitDecidingDisposabilityFromEvidenceOnDisk
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.project_resolve
RefreshTheUnitFromEvidenceOnDisk ==
  /\ hi_unit_pass = "pending"
  /\ hi_unit_pass' = "done"
  /\ \E c \in HiTrees, r \in BOOLEAN:
       /\ hi_unit_content' = c
       /\ hi_record_names_source' = r
       \* The decision. It reads the two trees and NOTHING ELSE. `r` ranges
       \* over BOTH values so TLC must show the invariant holding whatever the
       \* record names -- if this were pinned to TRUE the property would be
       \* satisfied by a model that consults the record, which is the defect.
       /\ hi_held_back' = (c # hi_live_source)
  /\ UNCHANGED hi_live_source
  /\ UNCHANGED << arti22_vars, hi_error_cause_live, gate_vars, descent_vars,
                  axis_vars, observer_vars >>

\* THE DEFECT -- AND THIS ACTION IS HARSHER THAN THE PRODUCT EVER WAS. Corrected
\* after review; the first version of this comment claimed `disposable()` was
\* the conjunction `sourceHeldTheseBytes && recordIsAboutThisSource`. IT IS NOT,
\* AND NEVER WAS. ChildHomeMaterializer.Disposal.disposable() is, and was before
\* HIS-1:
\*
\*     (destUntouched && recordIsAboutThisSource && !mergeResult)
\*         || sourceHeldTheseBytes
\*
\* a DISJUNCTION with sourceHeldTheseBytes as an escape arm. `git log -S` over
\* that expression returns no commit; HIS-1 (8346fef, "copyUnit asks the disk
\* before it asks the record") did not touch it. What HIS-1 actually did was
\* extract `settledWithoutARecord(...)` and call it from `copyUnit` BEFORE
\* `disposal(...)` -- `reconcile` had had those three answers all along, and the
\* defect was that ONE of the two paths asked them.
\*
\* SO WHAT DOES THIS ACTION MODEL? A machine strictly harsher than the broken
\* one: `held_back = divergent \/ ~record_names_source` holds back on the
\* record's say-so alone. The real broken copyUnit satisfies clause 1 in the
\* COMMON case, because describesSource's third arm is
\*
\*     record.entryDigests().equals(src.entries())
\*
\* -- the destination's recorded entry digests against the LIVE SOURCE's
\* fingerprint -- so a record naming a worktree deleted in July still answers
\* "yes, evidence about this source" whenever the bytes line up, and the unit is
\* rescued regardless of what `record.source()` names.
\*
\* THAT IS VACUITY-LEDGER ROW 1, ONE LEVEL UP. Row 1 records HIS-1's first
\* regression test passing WITHOUT the fix, "describesSource rescued it because
\* both trees were left pristine". The single boolean `hi_record_names_source`
\* cannot express the distinction that actually decided that case -- a STALE
\* record (names elsewhere, digests still match the live source: rescued) versus
\* a record that is genuinely not about this source (digests differ: held back).
\* A faithful model needs two bits there, not one.
\*
\* THE INVARIANT IS STILL A CORRECT PIN ON DELIVERED BEHAVIOUR -- production
\* disposes of every identical tree and this forbids holding one back. What was
\* wrong was the story about which implementation it refutes, and the story is
\* the part the PR asked a reviewer to weigh.
\* @command RefreshAUnitAskingTheRecordAsWellAsTheSource
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.project_resolve_regression
HoldBackAnIdenticalTreeBecauseOfItsRecord ==
  /\ hi_unit_pass = "pending"
  /\ hi_unit_pass' = "done"
  /\ \E c \in HiTrees, r \in BOOLEAN:
       /\ hi_unit_content' = c
       /\ hi_record_names_source' = r
       /\ hi_held_back' = (c # hi_live_source \/ ~r)
  /\ UNCHANGED hi_live_source
  /\ UNCHANGED << arti22_vars, hi_error_cause_live, gate_vars, descent_vars,
                  axis_vars, observer_vars >>

\* THE FIX GONE TOO FAR, and the reason clause 1 is not the whole property. A
\* model in which everything is disposable satisfies
\* AnIdenticalTreeIsDisposableWhateverItsRecordNames completely and destroys
\* every edit an agent made.
\* @command RefreshEveryUnitWithoutComparingAnything
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.project_resolve_regression
DisposeOfEveryTreeUnconditionally ==
  /\ hi_unit_pass = "pending"
  /\ hi_unit_pass' = "done"
  /\ \E c \in HiTrees, r \in BOOLEAN:
       /\ hi_unit_content' = c
       /\ hi_record_names_source' = r
       /\ hi_held_back' = FALSE
  /\ UNCHANGED hi_live_source
  /\ UNCHANGED << arti22_vars, hi_error_cause_live, gate_vars, descent_vars,
                  axis_vars, observer_vars >>

\* ---------------------------------------------------------------- HIS-4 ---
\* The condition clears on the tree and the report follows, with nobody editing
\* the record. ReconcileUseCase -> ValidateAndClearError re-derives the
\* installed-record error on every command; `resolvedAt` would have been a
\* second answer to a question the code already answers.

\* @command ResolveTheConflictAndRederiveTheRecordOnTheNextRead
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_sync
ResolveTheConflictAndRederiveTheRecord ==
  /\ hi_record_error
  /\ hi_error_cause_live
  /\ hi_error_cause_live' = FALSE
  /\ hi_record_error' = FALSE
  /\ hi_record_revision' = hi_store_revision
  /\ UNCHANGED << hi_store_revision, hi_home_digest, hi_ack_baseline,
                  hi_acknowledged, hi_projection_target, hi_child_registered,
                  hi_ack_own_writes, hi_phase >>
  /\ UNCHANGED << disposal_vars, gate_vars, descent_vars, axis_vars,
                  observer_vars >>

\* THE DEFECT. `git reset` cleared the index and the merge was clean; `skt
\* check` kept reporting MERGE_CONFLICT because errors[] carries no resolvedAt
\* and nothing re-derived it. The record had become a diary.
\* @command ResolveTheConflictAndLeaveTheRecordReportingIt
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_sync_regression
TheConditionClearsButTheRecordKeepsReportingIt ==
  /\ hi_record_error
  /\ hi_error_cause_live
  /\ hi_error_cause_live' = FALSE
  \* The record is NOT edited -- that is the point. It is also advanced, so
  \* RecordDescribesItsStoreOrSaysWhy holds through this step by its FIRST
  \* disjunct and the counterexample can only be to the HIS-4 invariant.
  /\ hi_record_revision' = hi_store_revision
  /\ UNCHANGED hi_record_error
  /\ UNCHANGED << hi_store_revision, hi_home_digest, hi_ack_baseline,
                  hi_acknowledged, hi_projection_target, hi_child_registered,
                  hi_ack_own_writes, hi_phase >>
  /\ UNCHANGED << disposal_vars, gate_vars, descent_vars, axis_vars,
                  observer_vars >>

\* ---------------------------------------------------------------- HIS-3 ---
\* The gate settles WITHOUT failing open. DriftGate.java:39-49 records the spec
\* run that proved a gate clearing itself on refresh lets the second recompute
\* report "nothing to acknowledge" while the first sync's change is still
\* unread, so the two halves are stated separately: collapsing the SURFACE is
\* the fix, and the gate still REFUSING is the thing the fix must not buy it
\* with.
\*
\* hi_full_surfacings is home.drift.json's persisted surfacedCount -- HIS-3
\* took route (a), a schema change, not route (b)'s per-process call-site
\* distinction. It is therefore state the product writes and re-reads, not a
\* ghost this module keeps so an invariant can be written, which is why
\* OneGateIsSurfacedInFullAtMostOnce is NOT witness-dependent and carries no
\* guard configuration. Under route (b) it would have been.

\* @command SyncDetectsAChangeAndOpensOrExtendsTheGate
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift
SyncDetectsAChange ==
  /\ hi_gate_change < 2
  /\ hi_gate_change' = hi_gate_change + 1
  /\ hi_gate_open' = TRUE
  \* A genuinely NEW change re-opens the full report. record() merges into the
  \* pending gate, so "new" is defined against the union, not against whether a
  \* record exists.
  /\ hi_full_surfacings' = 0
  /\ hi_launch_admitted' = FALSE
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live,
                  descent_vars, axis_vars, observer_vars >>

\* @command SurfaceAnUnacknowledgedGateInFullForTheFirstTime
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift
TheGateIsSurfacedInFull ==
  /\ hi_gate_open
  /\ hi_full_surfacings = 0
  /\ hi_full_surfacings' = 1
  /\ hi_launch_admitted' = FALSE
  /\ UNCHANGED << hi_gate_open, hi_gate_change >>
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live,
                  descent_vars, axis_vars, observer_vars >>

\* @command SurfaceTheSameGateAgainCollapsedToOneLine
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift
TheGateIsSurfacedCollapsed ==
  /\ hi_gate_open
  /\ hi_full_surfacings >= 1
  /\ hi_launch_admitted' = FALSE
  /\ UNCHANGED << hi_gate_open, hi_gate_change, hi_full_surfacings >>
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live,
                  descent_vars, axis_vars, observer_vars >>

\* @command AcknowledgeThePendingGate
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift_ack
TheGateIsAcknowledged ==
  /\ hi_gate_open
  /\ hi_gate_open' = FALSE
  \* surfacedCount does not survive an ack. A marker that did would let a
  \* re-pended gate open collapsed -- the interaction HIS-3's own design note
  \* flagged against AckIsNotStaleOnArrival.
  /\ hi_full_surfacings' = 0
  /\ hi_launch_admitted' = FALSE
  /\ UNCHANGED hi_gate_change
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live,
                  descent_vars, axis_vars, observer_vars >>

\* @command LaunchThroughAGateWithNothingUnread
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.exec
ALaunchProceedsThroughAClearGate ==
  /\ ~hi_gate_open
  /\ hi_launch_admitted' = TRUE
  /\ UNCHANGED << hi_gate_open, hi_gate_change, hi_full_surfacings >>
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live,
                  descent_vars, axis_vars, observer_vars >>

\* THE DRAG. DriftReport.render() re-emitted 889 lines on every `project sync`,
\* every `exec` launch gate and every `home drift`, for one unacknowledged gate
\* with nothing new in it.
\* @command SurfaceTheSameGateInFullAgain
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_drift_regression
TheGateIsSurfacedInFullAgain ==
  /\ hi_gate_open
  /\ hi_full_surfacings >= 1
  /\ hi_full_surfacings < 2
  /\ hi_full_surfacings' = hi_full_surfacings + 1
  /\ hi_launch_admitted' = FALSE
  /\ UNCHANGED << hi_gate_open, hi_gate_change >>
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live,
                  descent_vars, axis_vars, observer_vars >>

\* THE FIX BUYING ITSELF WITH THE SAFETY -- what "make the gate stop nagging"
\* becomes if the collapse is implemented by retiring the gate. The surfacing
\* is collapsed, OneGateIsSurfacedInFullAtMostOnce is perfectly satisfied, and
\* the launch that should have exited 8 runs.
\* @command LaunchThroughACollapsedButUnacknowledgedGate
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.exec_regression
TheCollapsedGateAdmitsTheLaunch ==
  /\ hi_gate_open
  /\ hi_full_surfacings >= 1
  /\ hi_launch_admitted' = TRUE
  /\ UNCHANGED << hi_gate_open, hi_gate_change, hi_full_surfacings >>
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live,
                  descent_vars, axis_vars, observer_vars >>

\* --------------------------------------------------------------- HIS-10 ---
\* THE PROPERTY THE WHOLE EPIC CONVERGED ON. The record is a POINTER TO
\* EVIDENCE, not the evidence. HomeProvenance.sanctions walks clonedFrom hop by
\* hop and asks ChildHomeLink.isChildOf LIVE at each hop; parentStores survives
\* only as a reporting snapshot that nothing consults.

\* @command CloneRecordingADescentTheParentItselfClaims
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_clone
ACloneRecordsADescentTheParentClaims ==
  /\ hi_descent_record = "none"
  /\ hi_descent_record' = "parent"
  /\ hi_parent_claims_home' = TRUE
  /\ hi_shim_sanctioned' = TRUE
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  axis_vars, observer_vars >>

\* FAIL CLOSED. A record naming a home that does not claim this one -- the
\* review's `clonedFrom: /nowhere` -- sanctions nothing. Re-derivation gives up
\* the property the old javadoc chose the snapshot for ("needs no second home
\* to be present, readable, or even to still exist"), and this is where that
\* cost is paid.
\* @command CloneRecordingADescentNoParentClaims
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_clone
ACloneRecordsADescentNoParentClaims ==
  /\ hi_descent_record = "none"
  /\ hi_descent_record' = "nowhere"
  /\ hi_parent_claims_home' = FALSE
  /\ hi_shim_sanctioned' = FALSE
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  axis_vars, observer_vars >>

\* THE LIVE RE-DERIVATION, in the one case a snapshot cannot survive: the
\* parent revokes and every reader answers differently on the next command,
\* with nothing rewritten in the clone.
\* @command ParentRevokesItsClaimAndEveryReaderFollows
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_verify
TheParentRevokesItsClaim ==
  /\ hi_parent_claims_home
  /\ hi_parent_claims_home' = FALSE
  /\ hi_shim_sanctioned' = FALSE
  /\ UNCHANGED hi_descent_record
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  axis_vars, observer_vars >>

\* THE FIRST VERSION OF HIS-10, WHICH REVIEW REFUSED. A hand-written
\* home.provenance.json flipped `home verify` from exit 1 to exit 0, and
\* clonedFrom: /nowhere -- a path that does not exist -- was printed as
\* authoritative descent. Anything that could write one file into a home
\* switched that home's isolation gate off permanently.
\* @command DescentRecordGrantsItsOwnSanction
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_verify_regression
ADescentRecordSanctionsItself ==
  /\ hi_descent_record = "none"
  /\ hi_descent_record' = "nowhere"
  /\ hi_parent_claims_home' = FALSE
  /\ hi_shim_sanctioned' = TRUE
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  axis_vars, observer_vars >>

\* THE SECOND FACE, which needed no hand-editing at all: the sanction is
\* carried forward from the snapshot the record took at clone time, so revoking
\* the parent's claim leaves `verify proj` = 1 and `verify wt1` = 0 on the same
\* shim and the same parent.
\* @command SanctionCarriedForwardFromTheCloneTimeSnapshot
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_verify_regression
TheSnapshotKeepsSanctioningAfterTheParentRevokes ==
  /\ hi_parent_claims_home
  /\ hi_shim_sanctioned
  /\ hi_parent_claims_home' = FALSE
  /\ UNCHANGED << hi_descent_record, hi_shim_sanctioned >>
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  axis_vars, observer_vars >>

\* --------------------------------------------------------------- HIS-14 ---
\* A HOME HAS TWO AXES. SKILL_MANAGER_HOME says where the UNITS live;
\* CLAUDE_CONFIG_DIR / CODEX_HOME / GEMINI_HOME say where the AGENT CONFIGS
\* live. A command that binds one and lets the other resolve against the
\* ambient shell writes half of itself into a home nobody named -- measured,
\* into the operator's real ~/.claude, ~/.codex and ~/.gemini, twice.
\*
\* A REFUSAL is the state in which neither axis was written, which Init already
\* occupies and the invariant admits. That is deliberate: "binds BOTH axes, or
\* refuses" is one property, not two, once you state it over where the bytes
\* landed rather than over what the flag parser did.

\* @command CommandNamingAHomeBindsBothOfItsAxes
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.sync
ACommandBindsBothAxesAndWrites ==
  /\ hi_store_write = "none"
  /\ hi_store_write' = "named"
  /\ hi_agent_write' = "named"
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, observer_vars >>

\* No --home given: BOTH halves resolve against the ambient environment, which
\* is one home and is correct. The invariant is not "never ambient" -- it is
\* that the two agree, and this action is what stops it being satisfied by a
\* model that hard-codes "named".
\* @command CommandNamingNoHomeUsesTheAmbientHomeOnBothAxes
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.sync
ACommandWithNoHomeNamedUsesTheAmbientHome ==
  /\ hi_store_write = "none"
  /\ hi_store_write' = "ambient"
  /\ hi_agent_write' = "ambient"
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, observer_vars >>

\* DEF-029, AS MEASURED. `sync probe-unit --merge --home <scratch>` wrote
\* units.lock.toml into the scratch home and linked the agent half into the
\* operator's real homes, which is how probe-unit turned up as a loadable skill
\* in an unrelated agent session days later.
\* @command CommandBindsTheStoreAxisAndLetsTheAgentAxisResolveAgainstTheShell
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.sync_regression
ACommandBindsTheStoreAxisOnly ==
  /\ hi_store_write = "none"
  /\ hi_store_write' = "named"
  /\ hi_agent_write' = "ambient"
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, observer_vars >>

\* --------------------------------------------------- HIS-13 / DEF-067 ---
\* AN OBSERVER THAT REPAIRS IS NO LONGER AN OBSERVER. HomeFixpointLaw parses
\* the remedy out of a refusal and RUNS it, in 24 of 29 graphs. This is the one
\* group here whose defect is LIVE: DEF-067 is open, and the invariant is
\* stated against the desired instrument rather than the shipped one.
\*
\* Repair itself is not the hazard -- HIS-13 shipped a repairer deliberately,
\* and ARepairRunsAsItsOwnCommand is a healthy action. The hazard is repair
\* INSIDE the observation, because the evidence for telling "this home was
\* stale" from "the product is broken in the way this law exists to detect" is
\* exactly what the repair destroys.

\* @command HomeTakesDamageBeforeAnythingLooksAtIt
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_verify
TheHomeTakesDamage ==
  /\ ~hi_home_damaged
  /\ hi_observation = "none"
  /\ hi_home_damaged' = TRUE
  /\ UNCHANGED << hi_observation, hi_observer_repaired >>
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, axis_vars >>

\* @command ObserveTheHomeAndReportWhatIsThere
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_verify
AnObserverReportsWhatItFound ==
  /\ hi_observation = "none"
  /\ hi_observation' = IF hi_home_damaged THEN "damaged" ELSE "clean"
  /\ hi_observer_repaired' = FALSE
  /\ UNCHANGED hi_home_damaged
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, axis_vars >>

\* Repair is a SEPARATE command, downstream of a verdict that already exists.
\* HIS-13's graph node records the same separation as a metric:
\* detectRunsBeforeRepair 2, findingsAtFirstDetect 2, findingsAfterRepair 0.
\* @command RepairTheHomeAsItsOwnCommandAfterDetectionReported
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_repair
ARepairRunsAsItsOwnCommand ==
  /\ hi_observation = "damaged"
  /\ hi_home_damaged' = FALSE
  /\ hi_observation' = "none"
  /\ UNCHANGED hi_observer_repaired
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, axis_vars >>

\* DEF-067, MADE RUNNABLE. Bare `home verify` refuses the damage and prints
\* `complete it with: ... sync --force-scripts`; the law parses that, runs it,
\* re-verifies, exits 0, records homesRepaired and PASSES. Every reading is
\* true and the defect is gone from the record.
\* @command ObserveTheHomeAndRunTheRemedyItPrinted
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_verify_regression
TheObserverParsesTheRemedyAndRunsIt ==
  /\ hi_observation = "none"
  /\ hi_home_damaged
  /\ hi_observer_repaired' = TRUE
  /\ hi_home_damaged' = FALSE
  \* By the time the verdict is computed the home really is clean, so
  \* ADamagedHomeIsNeverReportedClean is SATISFIED. That is the finding, and
  \* the guard configuration is how it is proved rather than asserted.
  /\ hi_observation' = "clean"
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, axis_vars >>

\* The plain detection failure, kept so the detection invariant has its own
\* refutation and is not carried by the repair one.
\* @command ReportTheHomeCleanWithoutLooking
\* @result HomeIntegrityOutcome
\* @port SkillManagerCli.home_verify_regression
TheObserverReportsCleanWithoutLooking ==
  /\ hi_observation = "none"
  /\ hi_observation' = "clean"
  /\ hi_observer_repaired' = FALSE
  /\ UNCHANGED hi_home_damaged
  /\ UNCHANGED << arti22_vars, disposal_vars, hi_error_cause_live, gate_vars,
                  descent_vars, axis_vars >>

------------------------------------------------------------------------------

HomeIntegrityNext ==
  \/ SyncAdvancesStoreAndRecord
  \/ SyncConflictsAndRecordsTheError
  \/ MaterializeIntoRegisteredChild
  \/ AcknowledgeCurrentDigest
  \* HIS-5. Every healthy behaviour the epic's fixes produce, so that
  \* HomeIntegrityInternal.cfg checks the new invariants against a home that
  \* actually exercises them rather than against one sitting at Init.
  \/ RefreshTheUnitFromEvidenceOnDisk
  \/ ResolveTheConflictAndRederiveTheRecord
  \/ SyncDetectsAChange
  \/ TheGateIsSurfacedInFull
  \/ TheGateIsSurfacedCollapsed
  \/ TheGateIsAcknowledged
  \/ ALaunchProceedsThroughAClearGate
  \/ ACloneRecordsADescentTheParentClaims
  \/ ACloneRecordsADescentNoParentClaims
  \/ TheParentRevokesItsClaim
  \/ ACommandBindsBothAxesAndWrites
  \/ ACommandWithNoHomeNamedUsesTheAmbientHome
  \/ TheHomeTakesDamage
  \/ AnObserverReportsWhatItFound
  \/ ARepairRunsAsItsOwnCommand

SilentDriftNext ==
  \/ SyncLeavesTheRecordBehindSilently
  \/ AcknowledgeCurrentDigest

StaleAckNext ==
  \/ SyncAdvancesStoreAndRecord
  \/ AcknowledgeDetectionDigest

SelfRePendingAckNext ==
  \/ SyncAdvancesStoreAndRecord
  \/ AcknowledgeThenWriteWithinTheSameOperation

ForeignProjectionNext ==
  \/ MaterializeIntoUnregisteredHome
  \/ MaterializeIntoRegisteredChild

HomeIntegritySpec == Init /\ [][HomeIntegrityNext]_vars
SilentDriftSpec   == Init /\ [][SilentDriftNext]_vars
StaleAckSpec      == Init /\ [][StaleAckNext]_vars
ForeignProjectionSpec == Init /\ [][ForeignProjectionNext]_vars
SelfRePendingAckSpec  == Init /\ [][SelfRePendingAckNext]_vars

\* ------------------------------------------------------- HIS-5 (#217) ---
\* One regression specification per new invariant. Each contains ONLY the
\* actions of its own property group, so the counterexample TLC prints is
\* short and cannot wander into a neighbour's variables. Every expected-
\* violation configuration below then carries the neighbouring invariants as
\* well as its target: if TLC names anything but the target, the probe is
\* pointed at the wrong branch and the row is a vacuity finding, not a pass.

RecordVetoesIdenticalTreeNext ==
  \/ HoldBackAnIdenticalTreeBecauseOfItsRecord

EverythingDisposableNext ==
  \/ DisposeOfEveryTreeUnconditionally

StaleErrorNext ==
  \/ SyncConflictsAndRecordsTheError
  \/ TheConditionClearsButTheRecordKeepsReportingIt

GateRefiresNext ==
  \/ SyncDetectsAChange
  \/ TheGateIsSurfacedInFull
  \/ TheGateIsSurfacedInFullAgain

CollapsedGateAdmitsNext ==
  \/ SyncDetectsAChange
  \/ TheGateIsSurfacedInFull
  \/ TheCollapsedGateAdmitsTheLaunch

SelfCertifyingDescentNext ==
  \/ ACloneRecordsADescentTheParentClaims
  \/ ADescentRecordSanctionsItself
  \/ TheSnapshotKeepsSanctioningAfterTheParentRevokes

HalfBoundHomeNext ==
  \/ ACommandBindsTheStoreAxisOnly

RepairingObserverNext ==
  \/ TheHomeTakesDamage
  \/ TheObserverParsesTheRemedyAndRunsIt

BlindObserverNext ==
  \/ TheHomeTakesDamage
  \/ TheObserverReportsCleanWithoutLooking

RecordVetoesIdenticalTreeSpec == Init /\ [][RecordVetoesIdenticalTreeNext]_vars
EverythingDisposableSpec      == Init /\ [][EverythingDisposableNext]_vars
StaleErrorSpec                == Init /\ [][StaleErrorNext]_vars
GateRefiresSpec               == Init /\ [][GateRefiresNext]_vars
CollapsedGateAdmitsSpec       == Init /\ [][CollapsedGateAdmitsNext]_vars
SelfCertifyingDescentSpec     == Init /\ [][SelfCertifyingDescentNext]_vars
HalfBoundHomeSpec             == Init /\ [][HalfBoundHomeNext]_vars
RepairingObserverSpec         == Init /\ [][RepairingObserverNext]_vars
BlindObserverSpec             == Init /\ [][BlindObserverNext]_vars

------------------------------------------------------------------------------
\* The invariants.
------------------------------------------------------------------------------

\* #124's invariant 1, kept as written -- DISJUNCT INCLUDED, because the
\* disjunct is the whole invariant. A record may disagree with its store only
\* while it says why.
\* @invariant RecordDescribesItsStoreOrSaysWhy
RecordDescribesItsStoreOrSaysWhy ==
  (hi_record_revision = hi_store_revision) \/ hi_record_error

\* #124's invariant 3, with its second clause dropped. "An operation that
\* changes content and then acks must not leave a fresh unacked record" is a
\* demand that the gate fail open, and DriftGate's own class comment records
\* the spec run that showed why it must not: with the clearing policy, the
\* reporting invariant went vacuously true and TLC answered "No error has been
\* found" over a gate that had stopped gating. What remains is the part with
\* content: an acknowledgement receipts the digest the home is actually on.
\* @invariant AckIsNotStaleOnArrival
AckIsNotStaleOnArrival ==
  hi_acknowledged => (hi_ack_baseline = hi_home_digest)

\* #124's defect 4, in the form that survives. Dropping AckIsStable's second
\* clause was right -- "an operation that changes content and then acks must not
\* leave a fresh unacked record" demands the gate fail open -- but it was the
\* ONLY statement forbidding a self-inflicted re-pend, and dropping it left
\* defect 4 asserted by nothing at all. A review pointed that out and proposed
\* this, which is a property of the acknowledging COMMAND's transaction
\* boundary rather than of the gate: whatever an ack writes must fall inside
\* the baseline it acknowledges. The gate stays fail-closed; the command stops
\* invalidating its own receipt.
\* @invariant AckWritesFallInsideItsBaseline
AckWritesFallInsideItsBaseline == ~hi_ack_own_writes

\* #124's invariant 9, RESTATED with the clause that makes it true: a
\* projection is decidable when it resolves inside this home, or inside a child
\* home this home registered.
\* Reachability note, because this invariant nearly shipped unfalsifiable.
\* With only MaterializeIntoRegisteredChild in the model, "foreign" was never
\* assigned and "child" was only ever set together with hi_child_registered =
\* TRUE -- so TLC could not distinguish ProjectionSourceIsDecidable from TRUE,
\* and it had no expected-violation configuration, against this repository's
\* regression_config_rule. The correction this ticket is proudest of was the
\* one invariant here with no executable refutation. A review caught it;
\* MaterializeIntoUnregisteredHome and the ...regression_foreignprojection.cfg
\* configuration are the repair.
\* @invariant ProjectionSourceIsDecidable
ProjectionSourceIsDecidable ==
  \/ hi_projection_target = "self"
  \/ (hi_projection_target = "child" /\ hi_child_registered)

\* #124's invariant 9 AS WRITTEN, kept only so a configuration can demonstrate
\* that it is FALSE of a healthy home. Never add this to
\* HomeIntegrityInternal.cfg: it is here to fail, under
\* HomeIntegrityInternal_regression_narrowprojection.cfg, against the CORRECT
\* specification. Reading its violation as a defect is what produced "51 of 106
\* projections serve bytes from another home".
\* @invariant ProjectionResolvesInsideThisHome
ProjectionResolvesInsideThisHome ==
  hi_projection_target = "self"

------------------------------------------------------------------------------
\* HIS-5 (#217). The epic's behavioural fixes, stated so a deliberately broken
\* model violates them.
\*
\* WITNESS-DEPENDENCE is declared for every one of them, per this workflow's
\* guard_config_rule. The test used is: does the invariant read a variable that
\* exists ONLY so the property can be written -- a shadow of an act that erases
\* its own evidence -- or does it read something the product persists, prints,
\* or leaves on disk? Exactly one of the nine is the former.
------------------------------------------------------------------------------

\* HIS-1, CLAUSE 1. A tree byte-identical to what the live source is standing
\* on right now is disposable, whatever its record names. Refreshing it is a
\* byte-identical no-op, so nothing can be destroyed -- and that argument needs
\* no record at all, which is the point: sixteen of the operator's eighteen
\* per-unit records named a source that was not the root store, six of them a
\* path that no longer existed, and all eighteen were pristine.
\*
\* THIS INVARIANT DOES NOT MENTION hi_record_names_source, AND MUST NOT. An
\* invariant that granted the record a vote could not forbid holding back a
\* byte-identical tree on the record's say-so, which is the shape HIS-1's
\* objective describes: sixteen of eighteen pristine trees held back on every
\* sync. HoldBackAnIdenticalTreeBecauseOfItsRecord lets the record range over
\* BOTH values rather than pinning it, so TLC must show the property holding
\* whichever way the record points.
\*
\* IT DOES NOT REFUTE THE IMPLEMENTATION HIS-1 REPLACED, and the first version
\* of this ticket said it did. See that action's comment: `disposable()` is a
\* DISJUNCTION and the real defect was that only ONE of the two paths asked the
\* three disk questions at all. What this pins is the DELIVERED behaviour --
\* every identical tree is disposable -- against a future change that makes the
\* record decisive again.
\*
\* NOT WITNESS-DEPENDENT: hi_held_back is the refusal the operator reads and
\* the refresh that did not happen, not a shadow kept for the invariant.
\* @invariant AnIdenticalTreeIsDisposableWhateverItsRecordNames
AnIdenticalTreeIsDisposableWhateverItsRecordNames ==
  (hi_unit_content = hi_live_source) => ~hi_held_back

\* HIS-1, CLAUSE 2. THE GUARD ON CLAUSE 1, and the reason clause 1 is not the
\* whole property: "everything is disposable" satisfies clause 1 completely and
\* destroys every edit an agent made. A tree that a PASS found differing from
\* the live source is held back by that pass.
\*
\* THE ANTECEDENT IS GUARDED ON hi_unit_pass = "done" AND THAT GUARD IS NOT
\* DECORATION. Without it the invariant reads every state, including states in
\* which no pass has judged anything, and it then says something FALSE of a
\* healthy home the moment this group models an upstream revision or an
\* out-of-band edit: an ordinary clean out-of-date copy diverges from the live
\* source and is correctly NOT held back, because refreshing it is what `sync`
\* is for. See THE FRAME CONDITION beside RefreshTheUnitFromEvidenceOnDisk for
\* what the group does and does not let happen, and for why the repair to a
\* moving source is to capture what the pass JUDGED rather than to weaken this.
\*
\* The guard costs nothing today -- `hi_unit_pass = "done"` holds in every state
\* where the antecedent could otherwise fire -- and it makes the invariant say
\* what it means rather than rely on a frame nobody wrote down.
\*
\* NOT WITNESS-DEPENDENT, same reason as clause 1: hi_unit_pass is whether the
\* pass ran, and hi_held_back is the refusal it printed.
\* @invariant ADivergentTreeIsNeverSilentlyReplaced
ADivergentTreeIsNeverSilentlyReplaced ==
  (hi_unit_pass = "done" /\ hi_unit_content # hi_live_source) => hi_held_back

\* HIS-3, CLAUSE 1. One unacknowledged gate is surfaced in FULL at most once.
\* Second and later passes over the same gate content collapse; a genuinely new
\* change resets the count and re-opens the full report, which is why
\* SyncDetectsAChange zeroes hi_full_surfacings rather than leaving it.
\*
\* NOT WITNESS-DEPENDENT -- and the reason first given for that was wrong, so
\* here is the measured one. hi_full_surfacings is NOT home.drift.json's
\* surfacedCount. DriftGate.markSurfaced increments on EVERY surfacing, full or
\* collapsed (ExecCommand:172 and HomeCommand:1054 both call it after the
\* firstSurfacing() branch), so the persisted field legitimately reaches 3, 4,
\* 5 and `hi_full_surfacings <= 1` is false of it.
\*
\* What this variable models is `min(surfacedCount, 1)` -- equivalently
\* `~firstSurfacing()`, since DriftGate.firstSurfacing() is `surfacedCount <= 0`
\* and is the ONLY reader of the field anywhere in the product. That predicate
\* is persisted, is re-read across processes, and is what decides whether the
\* full report is emitted, so the conclusion stands: this reads product state,
\* not a ghost, and needs no guard configuration.
\*
\* HIS-3 took route (a), the schema change; under route (b) -- the per-process
\* call-site distinction -- there would have been nothing on disk to read and
\* this WOULD have needed a guard. A future change of route silently turns this
\* invariant into a witness-reading one.
\* @invariant OneGateIsSurfacedInFullAtMostOnce
OneGateIsSurfacedInFullAtMostOnce == hi_full_surfacings <= 1

\* HIS-3, CLAUSE 2. THE HALF THAT STOPS CLAUSE 1 BEING DECORATION. A gate that
\* has been surfaced is still a gate: while anything is unacknowledged, a launch
\* does not proceed. Collapsing the SURFACE must not be bought by retiring the
\* GATE -- DriftGate.java:39-49 records the spec run where exactly that made the
\* reporting invariant vacuously true and TLC answered "No error has been found"
\* over a gate that had stopped gating.
\*
\* NOT WITNESS-DEPENDENT: hi_launch_admitted is whether the process ran, i.e.
\* the difference between exit 8 and exit 0.
\* @invariant AnUnacknowledgedGateStillRefusesALaunch
AnUnacknowledgedGateStillRefusesALaunch ==
  hi_gate_open => ~hi_launch_admitted

\* HIS-4, CLAUSE 3. A reported error is a function of the LIVE TREE, not of
\* history. ReconcileUseCase -> ValidateAndClearError re-derives the
\* installed-record error on every command, so a condition that has cleared
\* stops being reported with nobody editing the record -- and a condition that
\* is live is reported even though nothing wrote a new record. Stated as an
\* equality for that reason: the one-directional form permits a record that
\* forgets a live conflict, which is the same defect facing the other way.
\*
\* NOTE the interaction that keeps this independent of
\* RecordDescribesItsStoreOrSaysWhy. That invariant is satisfied by a stale
\* error -- the error is its escape disjunct -- so a record whose error has
\* outlived its cause looks HEALTHY to it. The two are not redundant; the older
\* one is propped up by exactly the state the newer one forbids.
\*
\* NOT WITNESS-DEPENDENT: hi_error_cause_live is the tree, and hi_record_error
\* is what the command printed.
\* @invariant ARecordedErrorIsAFunctionOfTheLiveTree
ARecordedErrorIsAFunctionOfTheLiveTree ==
  hi_record_error = hi_error_cause_live

\* HIS-10. A CLONE'S DESCENT IS A POINTER, NOT A GRANT. Nothing is sanctioned
\* unless the home the record names holds a claim on this one, LIVE, at the
\* moment the question is asked. The record says where to look; the parent's own
\* store answers.
\*
\* THIS INVARIANT DOES NOT MENTION hi_descent_record, AND MUST NOT. Both of the
\* rejected first version's failures are failures of a model that reads the
\* record instead of re-deriving: a hand-written home.provenance.json naming
\* /nowhere, and a clone-time snapshot that keeps sanctioning after the parent
\* revokes. An invariant phrased over the record would have accepted both.
\*
\* IT FAILS CLOSED, and that is stated rather than assumed: evidence that cannot
\* be re-derived does not sanction, so a worktree whose project home was deleted
\* reverts to pre-HIS-10 behaviour.
\*
\* WHAT `hi_parent_claims_home` REALLY STANDS FOR, corrected after review. The
\* first version of this comment implied the parent's registry is the only
\* witness. ChildHomeLink.isChildOf is
\*
\*     parentClaims(p, c) || childWasMaterializedFrom(c, p)
\*
\* and the second disjunct walks `<child>/.materialization/**/*.json` and trusts
\* its `source` field -- A FILE INSIDE THE HOME BEING JUDGED. So the live
\* re-derivation this invariant pins is broader than "the parent's own registry
\* says so", and one of its two arms is in-home evidence.
\*
\* NOT CLAIMED: forgery-proof, and this is where that caveat earns its keep. No
\* forgery was demonstrated, and HIS-10 named the same pre-existing trust rather
\* than widening it. What HIS-10 removed is the ARBITRARY grant -- a record that
\* sanctioned itself with no live question asked at all. Modelling the two arms
\* separately would be a faithful extension and is not done here.
\*
\* NOT WITNESS-DEPENDENT: hi_shim_sanctioned is `home verify`'s exit code and
\* whether the shim survives a sync; hi_parent_claims_home is a file in the
\* parent's store.
\* @invariant SanctionIsRederivedFromTheParentsOwnStore
SanctionIsRederivedFromTheParentsOwnStore ==
  hi_shim_sanctioned => hi_parent_claims_home

\* HIS-14. A HOME HAS TWO AXES AND A COMMAND BINDS BOTH OR NEITHER. The store
\* axis is SKILL_MANAGER_HOME; the agent axis is CLAUDE_CONFIG_DIR / CODEX_HOME
\* / GEMINI_HOME. `--home` bound the first only, so the agent half resolved
\* against whatever the shell happened to export -- measured, into the
\* operator's real agent directories, twice, and found by an unrelated agent
\* session rather than by any check.
\*
\* The refusal branch is the state where neither axis was written, which Init
\* occupies and this admits. "Binds both, or refuses" is one property once it is
\* stated over where the bytes landed instead of over what the flag parser did.
\*
\* NOT stated by External.WritesThroughOneHomeReachNoOtherHome, and that is
\* worth being precise about rather than assuming: External's homes have ONE
\* axis. No module in this directory models the agent axis at all, which is
\* also why DEF-076 could measure a dangling projection in every ticket
\* worktree's .codex and .gemini with nothing to notice it.
\*
\* NOT WITNESS-DEPENDENT: both variables are where files were written.
\* @invariant AHomesTwoAxesNameOneHome
AHomesTwoAxesNameOneHome == hi_store_write = hi_agent_write

\* HIS-13 / DEF-067. AN OBSERVER THAT REPAIRS IS NO LONGER AN OBSERVER.
\* HomeFixpointLaw parses the remedy out of a refusal and runs it, in 24 of 29
\* graphs, and cannot then distinguish "this home was stale" from "another node
\* left that there" from "the product is broken in the way this law exists to
\* detect". It converts all three into PASS plus a homesRepaired count.
\*
\* This is not a prohibition on repair. HIS-13 shipped a repairer deliberately
\* and ARepairRunsAsItsOwnCommand is a healthy action here. It is a prohibition
\* on repairing INSIDE the observation.
\*
\* WITNESS-DEPENDENT -- the only one of the nine, and DEF-067's own text is the
\* argument for why. The repair destroys exactly the evidence that would let the
\* verdict be read correctly, so by the time the observation is written the home
\* really is clean and every other invariant here is satisfied. The evidence has
\* to be taken at the instant of the act, which is what hi_observer_repaired is.
\* GUARD: HomeIntegrityInternal_guard_repairingobserver.cfg runs the same
\* repairing observer with THIS invariant removed and must keep returning "No
\* error has been found". If it ever starts failing, some other invariant has
\* been strengthened into this one and the record of why the witness exists is
\* stale. Its passing is DEF-067's third consequence -- "it can green a real
\* defect" -- demonstrated rather than asserted.
\*
\* THIS INVARIANT IS TRUE OF THE DESIRED INSTRUMENT AND FALSE OF THE SHIPPED
\* ONE. DEF-067 is open. That is deliberate and is the same shape as
\* ProjectionResolvesInsideThisHome above, inverted: there, a configuration
\* preserves a correction; here, a configuration preserves a finding that has
\* not been fixed yet.
\* @invariant AnObservationNeverRepairsWhatItObserved
AnObservationNeverRepairsWhatItObserved == ~hi_observer_repaired

\* HIS-13. THE DETECTION HALF, and the second oracle. A home carrying the
\* damage is never reported clean. Kept separate from the invariant above
\* because a repairing observer satisfies THIS one perfectly -- which is the
\* whole of DEF-067 -- and because the vacuity ledger's composite lesson is that
\* the count of probes measures nothing and the count of distinct ORACLES is
\* closer.
\*
\* NOT WITNESS-DEPENDENT: hi_observation is what the command printed and
\* hi_home_damaged is the state of the home's files.
\* @invariant ADamagedHomeIsNeverReportedClean
ADamagedHomeIsNeverReportedClean ==
  (hi_observation = "clean") => ~hi_home_damaged

------------------------------------------------------------------------------
\* HIS-5 REACHABILITY PROBES -- THESE TWO MUST FAIL.
\*
\* Nine invariants that hold is not the same claim as nine invariants that were
\* EVALUATED. Every one of them above is an implication or an equality, and an
\* implication whose antecedent the model never reaches is TRUE and worthless --
\* which is this epic's signature defect, and which
\* ProjectionSourceIsDecidable's own reachability note records nearly shipping.
\*
\* So the antecedents are stated as their own negations and checked against the
\* HEALTHY specification, following External.NoWorktreeEditEverReachesTheRoot-
\* Home. If TLC reports "No error has been found" for either of these, the
\* healthy model has stopped reaching the states the invariants are about and
\* every green run above is measuring nothing. Their COUNTEREXAMPLES are the
\* evidence, and they are kept with the ticket's TLC transcripts.
\*
\* Two rather than one, because the HIS-1 clauses have contradictory
\* antecedents -- a tree cannot be identical to the live source and divergent
\* from it in the same state -- so no single state witnesses both.
------------------------------------------------------------------------------

\* MUST FAIL. Its counterexample is HIS-1 clause 1 actually happening: a pass
\* that finished, over a tree byte-identical to the live source, whose record
\* names something else, refreshed rather than held back. Without this the
\* clause-1 invariant would be satisfied by a model in which the record always
\* names this source -- the exact vacuity the regression configuration was
\* written to avoid, one level up.
\* @invariant NoRefreshedTreeEverHadAForeignRecord
NoRefreshedTreeEverHadAForeignRecord ==
  ~ /\ hi_unit_pass = "done"
    /\ hi_unit_content = hi_live_source
    /\ ~hi_record_names_source
    /\ ~hi_held_back

\* MUST FAIL. Its counterexample is ONE state in which every other new
\* invariant's antecedent is live at once: a divergent tree that was held back,
\* an open gate that has been surfaced in full, a reported error whose cause is
\* on the tree, a sanctioned shim whose parent claims this home, a command that
\* wrote both axes, and a damaged home whose observation says damaged.
\* @invariant NoHomeReachesEveryStateTheseInvariantsAreAbout
NoHomeReachesEveryStateTheseInvariantsAreAbout ==
  ~ /\ hi_unit_pass = "done"
    /\ hi_unit_content # hi_live_source
    /\ hi_held_back
    /\ hi_gate_open
    /\ hi_full_surfacings = 1
    /\ hi_record_error
    /\ hi_error_cause_live
    /\ hi_shim_sanctioned
    /\ hi_parent_claims_home
    /\ hi_store_write = "named"
    /\ hi_agent_write = "named"
    /\ hi_home_damaged
    /\ hi_observation = "damaged"

===============================================================================
