# HIS-5 — the counterexample TLC produced for every configuration

Distilled from the full transcripts beside this file. For each state after
the initial predicate, only the variables that CHANGED are shown, and the
action that produced them is named. `Violated:` is the invariant TLC
actually reported — the answer to "does this counterexample violate MY
invariant and not a neighbour's".

## `HomeIntegrityInternal.cfg`

**Violated:** none — `Model checking completed. No error has been found.`  
**Size:** 158801 states generated, 24000 distinct

## `HomeIntegrityInternal_guard_repairingobserver.cfg`

**Violated:** none — `Model checking completed. No error has been found.`  
**Size:** 3 states generated, 3 distinct

## `HomeIntegrityInternal_probe_disposalreach.cfg`

**Violated:** `NoRefreshedTreeEverHadAForeignRecord`

- **State 1** — Initial predicate
- **State 2** — `RefreshTheUnitFromEvidenceOnDisk` → `hi_unit_pass = "done"`

## `HomeIntegrityInternal_probe_reach.cfg`

**Violated:** `NoHomeReachesEveryStateTheseInvariantsAreAbout`

- **State 1** — Initial predicate
- **State 2** — `SyncConflictsAndRecordsTheError` → `hi_store_revision = "B"`, `hi_home_digest = "B"`, `hi_acknowledged = FALSE`, `hi_record_error = TRUE`, `hi_phase = "changed"`, `hi_error_cause_live = TRUE`
- **State 3** — `RefreshTheUnitFromEvidenceOnDisk` → `hi_unit_content = "t_edited"`, `hi_held_back = TRUE`, `hi_unit_pass = "done"`
- **State 4** — `SyncDetectsAChange` → `hi_gate_open = TRUE`, `hi_gate_change = 1`
- **State 5** — `TheGateIsSurfacedInFull` → `hi_full_surfacings = 1`
- **State 6** — `ACloneRecordsADescentTheParentClaims` → `hi_shim_sanctioned = TRUE`, `hi_parent_claims_home = TRUE`, `hi_descent_record = "parent"`
- **State 7** — `ACommandBindsBothAxesAndWrites` → `hi_store_write = "named"`, `hi_agent_write = "named"`
- **State 8** — `TheHomeTakesDamage` → `hi_home_damaged = TRUE`
- **State 9** — `AnObserverReportsWhatItFound` → `hi_observation = "damaged"`

## `HomeIntegrityInternal_regression_blindobserver.cfg`

**Violated:** `ADamagedHomeIsNeverReportedClean`

- **State 1** — Initial predicate
- **State 2** — `TheHomeTakesDamage` → `hi_home_damaged = TRUE`
- **State 3** — `TheObserverReportsCleanWithoutLooking` → `hi_observation = "clean"`

## `HomeIntegrityInternal_regression_collapsedgateadmits.cfg`

**Violated:** `AnUnacknowledgedGateStillRefusesALaunch`

- **State 1** — Initial predicate
- **State 2** — `SyncDetectsAChange` → `hi_gate_open = TRUE`, `hi_gate_change = 1`
- **State 3** — `TheGateIsSurfacedInFull` → `hi_full_surfacings = 1`
- **State 4** — `TheCollapsedGateAdmitsTheLaunch` → `hi_launch_admitted = TRUE`

## `HomeIntegrityInternal_regression_everythingdisposable.cfg`

**Violated:** `ADivergentTreeIsNeverSilentlyReplaced`

- **State 1** — Initial predicate
- **State 2** — `DisposeOfEveryTreeUnconditionally` → `hi_unit_content = "t_edited"`, `hi_unit_pass = "done"`

## `HomeIntegrityInternal_regression_foreignprojection.cfg`

**Violated:** `ProjectionSourceIsDecidable`

- **State 1** — Initial predicate
- **State 2** — `MaterializeIntoUnregisteredHome` → `hi_projection_target = "foreign"`

## `HomeIntegrityInternal_regression_gaterefires.cfg`

**Violated:** `OneGateIsSurfacedInFullAtMostOnce`

- **State 1** — Initial predicate
- **State 2** — `SyncDetectsAChange` → `hi_gate_open = TRUE`, `hi_gate_change = 1`
- **State 3** — `TheGateIsSurfacedInFull` → `hi_full_surfacings = 1`
- **State 4** — `TheGateIsSurfacedInFullAgain` → `hi_full_surfacings = 2`

## `HomeIntegrityInternal_regression_halfboundhome.cfg`

**Violated:** `AHomesTwoAxesNameOneHome`

- **State 1** — Initial predicate
- **State 2** — `ACommandBindsTheStoreAxisOnly` → `hi_store_write = "named"`, `hi_agent_write = "ambient"`

## `HomeIntegrityInternal_regression_narrowprojection.cfg`

**Violated:** `ProjectionResolvesInsideThisHome`

- **State 1** — Initial predicate
- **State 2** — `MaterializeIntoRegisteredChild` → `hi_projection_target = "child"`, `hi_child_registered = TRUE`

## `HomeIntegrityInternal_regression_recordvetoesidenticaltree.cfg`

**Violated:** `AnIdenticalTreeIsDisposableWhateverItsRecordNames`

- **State 1** — Initial predicate
- **State 2** — `HoldBackAnIdenticalTreeBecauseOfItsRecord` → `hi_held_back = TRUE`, `hi_unit_pass = "done"`

## `HomeIntegrityInternal_regression_repairingobserver.cfg`

**Violated:** `AnObservationNeverRepairsWhatItObserved`

- **State 1** — Initial predicate
- **State 2** — `TheHomeTakesDamage` → `hi_home_damaged = TRUE`
- **State 3** — `TheObserverParsesTheRemedyAndRunsIt` → `hi_observer_repaired = TRUE`, `hi_observation = "clean"`, `hi_home_damaged = FALSE`

## `HomeIntegrityInternal_regression_selfcertifyingdescent.cfg`

**Violated:** `SanctionIsRederivedFromTheParentsOwnStore`

- **State 1** — Initial predicate
- **State 2** — `ADescentRecordSanctionsItself` → `hi_shim_sanctioned = TRUE`, `hi_descent_record = "nowhere"`

## `HomeIntegrityInternal_regression_selfrepend.cfg`

**Violated:** `AckWritesFallInsideItsBaseline`

- **State 1** — Initial predicate
- **State 2** — `SyncAdvancesStoreAndRecord` → `hi_store_revision = "B"`, `hi_home_digest = "B"`, `hi_acknowledged = FALSE`, `hi_phase = "changed"`, `hi_record_revision = "B"`
- **State 3** — `AcknowledgeThenWriteWithinTheSameOperation` → `hi_acknowledged = TRUE`, `hi_phase = "acknowledged"`, `hi_ack_own_writes = TRUE`, `hi_ack_baseline = "B"`

## `HomeIntegrityInternal_regression_silentdrift.cfg`

**Violated:** `RecordDescribesItsStoreOrSaysWhy`

- **State 1** — Initial predicate
- **State 2** — `SyncLeavesTheRecordBehindSilently` → `hi_store_revision = "B"`, `hi_home_digest = "B"`, `hi_acknowledged = FALSE`, `hi_phase = "changed"`

## `HomeIntegrityInternal_regression_staleack.cfg`

**Violated:** `AckIsNotStaleOnArrival`

- **State 1** — Initial predicate
- **State 2** — `SyncAdvancesStoreAndRecord` → `hi_store_revision = "B"`, `hi_home_digest = "B"`, `hi_acknowledged = FALSE`, `hi_phase = "changed"`, `hi_record_revision = "B"`
- **State 3** — `AcknowledgeDetectionDigest` → `hi_acknowledged = TRUE`, `hi_phase = "acknowledged"`

## `HomeIntegrityInternal_regression_staleerror.cfg`

**Violated:** `ARecordedErrorIsAFunctionOfTheLiveTree`

- **State 1** — Initial predicate
- **State 2** — `SyncConflictsAndRecordsTheError` → `hi_store_revision = "B"`, `hi_home_digest = "B"`, `hi_acknowledged = FALSE`, `hi_record_error = TRUE`, `hi_phase = "changed"`, `hi_error_cause_live = TRUE`
- **State 3** — `TheConditionClearsButTheRecordKeepsReportingIt` → `hi_record_revision = "B"`, `hi_error_cause_live = FALSE`

