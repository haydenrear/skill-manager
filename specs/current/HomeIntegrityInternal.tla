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
  hi_phase

vars == << hi_record_revision, hi_store_revision, hi_record_error,
           hi_home_digest, hi_ack_baseline, hi_acknowledged,
           hi_projection_target, hi_child_registered, hi_phase >>

HiRevisions == {"A", "B"}
HiTargets == {"self", "child", "foreign"}
HiPhases == {"provisioned", "changed", "acknowledged"}

Init ==
  /\ hi_record_revision = "A"
  /\ hi_store_revision = "A"
  /\ hi_record_error = FALSE
  /\ hi_home_digest = "A"
  /\ hi_ack_baseline = "A"
  /\ hi_acknowledged = TRUE
  /\ hi_projection_target = "self"
  /\ hi_child_registered = FALSE
  /\ hi_phase = "provisioned"

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
  /\ hi_phase \in HiPhases

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
  /\ UNCHANGED << hi_ack_baseline, hi_projection_target, hi_child_registered >>

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
  /\ UNCHANGED << hi_ack_baseline, hi_projection_target, hi_child_registered >>

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
                  hi_home_digest, hi_ack_baseline, hi_acknowledged, hi_phase >>

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
  /\ UNCHANGED << hi_ack_baseline, hi_projection_target, hi_child_registered >>

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
  /\ hi_phase' = "acknowledged"
  /\ UNCHANGED << hi_record_revision, hi_store_revision, hi_record_error,
                  hi_home_digest, hi_projection_target, hi_child_registered >>

------------------------------------------------------------------------------

HomeIntegrityNext ==
  \/ SyncAdvancesStoreAndRecord
  \/ SyncConflictsAndRecordsTheError
  \/ MaterializeIntoRegisteredChild
  \/ AcknowledgeCurrentDigest

SilentDriftNext ==
  \/ SyncLeavesTheRecordBehindSilently
  \/ AcknowledgeCurrentDigest

StaleAckNext ==
  \/ SyncAdvancesStoreAndRecord
  \/ AcknowledgeDetectionDigest

HomeIntegritySpec == Init /\ [][HomeIntegrityNext]_vars
SilentDriftSpec   == Init /\ [][SilentDriftNext]_vars
StaleAckSpec      == Init /\ [][StaleAckNext]_vars

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

\* #124's invariant 9, RESTATED with the clause that makes it true: a
\* projection is decidable when it resolves inside this home, or inside a child
\* home this home registered.
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

===============================================================================
