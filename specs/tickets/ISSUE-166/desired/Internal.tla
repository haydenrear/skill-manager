------------------------------ MODULE Internal ------------------------------
\* Fine-grained program state of child-home materialization.
\*
\* External.tla models a whole `project resolve` pass as one step. This module
\* models what the pass does per unit: build the tree in a staging area, move it
\* into place, write the per-unit materialization record, or decide the unit
\* carries local edits and must be left alone.
\*
\* The materialization record (`record`) is the baseline. It is what makes
\* "locally modified" decidable: a child unit whose bytes are not the bytes the
\* record says were written has been edited, and a child unit with no usable
\* record has no evidence that it is disposable.
\*
\* Every outcome is a named action rather than a status field, so a TLC
\* counterexample reads as the sequence of decisions the materializer made:
\*   MATERIALIZED           -> StageChildUnitMaterialization + SwapInChildUnit
\*   UNCHANGED              -> AdoptIdenticalChildUnit (or no enabled action)
\*   SKIPPED_LOCAL_CHANGES  -> HoldBackModifiedChildUnit
\*
\* Generates: spec-unit cases (see case_adapters.toml, ticket CHM-8).

EXTENDS Core

CONSTANTS
  \* What a failed `home clone` leaves behind.
  \*   DISCARD_PARTIAL -- today (HomeCloner.discardPartialClone): the
  \*        destination is rolled back to the state it was found in.
  \*   LEAVE_PARTIAL   -- the naive alternative: whatever had been copied stays.
  \*        A partial clone is worse than none, because the next attempt refuses
  \*        it as "destination already exists and is not empty" and the operator
  \*        has to work out by hand which files were ours to delete.
  CloneFailureHandling

DiscardsPartialClone == CloneFailureHandling = "DISCARD_PARTIAL"

\* store_body   parent store: content of each installed unit directory.
\* child_unit   child home: content of each unit directory the child home holds.
\* record       <child home>/.materialization/<kind>/<name>.json, one per unit.
\*              `source` is the digest of the MATERIALIZED VIEW of the parent
\*              tree, which is also exactly what was written, so it doubles as
\*              the content baseline.
\* staging      at most one materialization is in flight at a time; the
\*              materializer walks the resolved units one by one.
\* held_back    units the current pass reported as SKIPPED_LOCAL_CHANGES.
\* agent_edits  witness variable: units an agent has edited. Nothing in
\*              skill-manager may clear it. See External.tla for why it exists.
\*
\* ---------------------------------------------------- the clone in flight
\* clone        at most one `home clone` runs at a time: which destination it is
\*              building, and from where. The analogue of `staging` one level up.
\* dest         per destination: whether the directory holds anything, whether
\*              the clone finished, WHICH SURFACE FIXUPS HAVE RUN, and the
\*              anchors the copy currently carries. `done` is the load-bearing
\*              field -- it is what makes "the copy was handed over before it was
\*              finished" decidable rather than a matter of reading the code.
\* handed_over  destinations SKILL_MANAGER_HOME has been pointed at. A copy
\*              becomes a HOME here, not when the command exits, so this is the
\*              line every fixup has to be on the right side of.
VARIABLES
  store_body,
  child_unit,
  record,
  staging,
  held_back,
  agent_edits,
  clone,
  dest,
  handed_over,
  claimant_selected_revision,
  claimant_parent_revision,
  claimant_trunk_revision,
  claimant_child_revision,
  claimant_fetches,
  claimant_phase

unit_vars == << store_body, child_unit, record, staging, held_back, agent_edits >>
claimant_vars == << claimant_selected_revision, claimant_parent_revision,
                    claimant_trunk_revision, claimant_child_revision,
                    claimant_fetches, claimant_phase >>
clone_vars == << clone, dest, handed_over,
                 claimant_selected_revision, claimant_parent_revision,
                 claimant_trunk_revision, claimant_child_revision,
                 claimant_fetches, claimant_phase >>
\* Written out literally: TLC recognizes the subscript of [][A]_vars
\* syntactically and warns that every variable is missing from it when the tuple
\* is built by concatenation.
vars == << store_body, child_unit, record, staging, held_back, agent_edits,
           clone, dest, handed_over,
           claimant_selected_revision, claimant_parent_revision,
           claimant_trunk_revision, claimant_child_revision,
           claimant_fetches, claimant_phase >>

ClaimantRevisions == {"A", "B"}
ClaimantPhases == {"selected", "complete"}

AbsentUnit == [present |-> FALSE, content |-> 0]
PresentUnit(content) == [present |-> TRUE, content |-> content]

NoRecord == [present |-> FALSE, source |-> 0]
RecordOf(source) == [present |-> TRUE, source |-> source]

NoStaging == [active |-> FALSE, unit |-> ChildUnitA, source |-> 0]

NoClone == [active |-> FALSE, target |-> CloneP, from |-> SourceHome]

EmptyDestination ==
  [populated |-> FALSE, complete |-> FALSE, done |-> {},
   anchor |-> [s \in Surfaces |-> NoAnchor]]

Init ==
  /\ store_body = [u \in Units |-> 0]
  /\ child_unit = [u \in ChildHomeUnits |-> AbsentUnit]
  /\ record = [u \in ChildHomeUnits |-> NoRecord]
  /\ staging = NoStaging
  /\ held_back = {}
  /\ agent_edits = {}
  /\ clone = NoClone
  /\ dest = [d \in Clones |-> EmptyDestination]
  /\ handed_over = {}
  \* The public named sync has already selected immutable revision A in the
  \* parent home. The registered project pins A while the upstream trunk has
  \* advanced to B. Automatic claimant refresh starts only after selection.
  /\ claimant_selected_revision = "A"
  /\ claimant_parent_revision = "A"
  /\ claimant_trunk_revision = "B"
  /\ claimant_child_revision = "A"
  /\ claimant_fetches = 0
  /\ claimant_phase = "selected"

ChildUnitStates == [present: BOOLEAN, content: Contents]
RecordStates == [present: BOOLEAN, source: Contents]
StagingStates == [active: BOOLEAN, unit: ChildHomeUnits, source: Contents]
CloneStates == [active: BOOLEAN, target: Clones, from: Homes]
DestinationStates == [populated: BOOLEAN, complete: BOOLEAN,
                      done: SUBSET OwnedSurfaces,
                      anchor: [Surfaces -> Anchors]]

\* @invariant TypeOK
TypeOK ==
  /\ store_body \in [Units -> StoreBodies]
  /\ child_unit \in [ChildHomeUnits -> ChildUnitStates]
  /\ record \in [ChildHomeUnits -> RecordStates]
  /\ staging \in StagingStates
  /\ held_back \subseteq ChildHomeUnits
  /\ agent_edits \subseteq ChildHomeUnits
  /\ clone \in CloneStates
  /\ dest \in [Clones -> DestinationStates]
  /\ handed_over \subseteq Clones
  /\ claimant_selected_revision \in ClaimantRevisions
  /\ claimant_parent_revision \in ClaimantRevisions
  /\ claimant_trunk_revision \in ClaimantRevisions
  /\ claimant_child_revision \in ClaimantRevisions
  /\ claimant_fetches \in 0..1
  /\ claimant_phase \in ClaimantPhases

\* A child unit whose tree no longer matches its materialization record. A unit
\* with no usable record counts as modified: without provenance there is no
\* evidence that anything in it is disposable.
LocallyModified(u) ==
  /\ child_unit[u].present
  /\ \/ ~record[u].present
     \/ child_unit[u].content # record[u].source

\* What a fresh materialization of u would write right now.
DesiredContent(u) == MaterializedContent(store_body, u)

\* The unit is out of date with respect to its parent source.
NeedsRefresh(u) ==
  \/ ~child_unit[u].present
  \/ ~record[u].present
  \/ record[u].source # DesiredContent(u)

\* Units `project resolve` prunes: everything the project no longer claims.
UnclaimedByProject == ChildHomeUnits \ ProjectChildHomePayload

-----------------------------------------------------------------------------
\* Actions

\* @command StageChildUnitMaterialization
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.stage
\* Builds the desired tree under <child home>/.materialization/tmp. The live
\* child unit is not touched, so a failure part way through cannot truncate it.
StageChildUnitMaterialization(u) ==
  /\ ~staging.active
  /\ u \in MaterializablePayload
  /\ ~LocallyModified(u)
  /\ NeedsRefresh(u)
  /\ staging' = [active |-> TRUE, unit |-> u, source |-> DesiredContent(u)]
  \* ATOMIC_MOVE stages beside the destination and moves it in. The naive
  \* alternative removes the live unit first and writes in place.
  /\ child_unit' = IF SwapsAtomically
                   THEN child_unit
                   ELSE [child_unit EXCEPT ![u] = AbsentUnit]
  /\ UNCHANGED << store_body, record, held_back, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command SwapInChildUnit
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.swap_in
\* ATOMIC_MOVE of the staged tree over the live child unit, then the record.
SwapInChildUnit ==
  /\ staging.active
  /\ child_unit' = [child_unit EXCEPT ![staging.unit] = PresentUnit(staging.source)]
  /\ record' = [record EXCEPT ![staging.unit] = RecordOf(staging.source)]
  /\ staging' = NoStaging
  /\ held_back' = held_back \ {staging.unit}
  /\ UNCHANGED << store_body, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command AbortChildUnitMaterialization
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.abort
\* The copy or the move failed. The staging area is swept and the previous
\* child unit must still be there, byte for byte.
AbortChildUnitMaterialization ==
  /\ staging.active
  /\ staging' = NoStaging
  /\ UNCHANGED << store_body, child_unit, record, held_back, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command HoldBackModifiedChildUnit
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.hold_back
\* The unit carries local edits. It is left completely alone and reported --
\* whether the project still claims it or not. "No longer a dependency" is not
\* a licence to delete someone's work.
HoldBackModifiedChildUnit(u) ==
  /\ ~staging.active
  /\ HoldsBackLocalChanges
  /\ LocallyModified(u)
  /\ u \notin held_back
  /\ held_back' = held_back \cup {u}
  /\ UNCHANGED << store_body, child_unit, record, staging, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command AdoptIdenticalChildUnit
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.adopt
\* A child unit with no trustworthy record that happens to be exactly what we
\* would have written. Adopt it by writing the record; anything else is refused,
\* because an agent's edits cannot be told from a stale copy.
AdoptIdenticalChildUnit(u) ==
  /\ ~staging.active
  /\ child_unit[u].present
  /\ ~record[u].present
  /\ child_unit[u].content = DesiredContent(u)
  /\ record' = [record EXCEPT ![u] = RecordOf(child_unit[u].content)]
  /\ held_back' = held_back \ {u}
  /\ UNCHANGED << store_body, child_unit, staging, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command PruneUnclaimedChildUnit
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.prune
\* Drops a child unit the project no longer depends on, and forgets its record.
PruneUnclaimedChildUnit(u) ==
  /\ ~staging.active
  /\ u \in UnclaimedByProject
  /\ child_unit[u].present
  /\ ~LocallyModified(u)
  /\ child_unit' = [child_unit EXCEPT ![u] = AbsentUnit]
  /\ record' = [record EXCEPT ![u] = NoRecord]
  /\ held_back' = held_back \ {u}
  /\ UNCHANGED << store_body, staging, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command PlaceUntrackedChildUnit
\* @result MaterializationOutcome
\* @port AgentWorkspace.place_untracked_child_unit
\* A directory that appeared in the child home without a materialization record
\* -- someone copied a skill in by hand. Either it is byte-identical to what we
\* would write (adoptable) or it is not (held back forever, never deleted).
PlaceUntrackedChildUnit(u, content) ==
  /\ ~staging.active
  /\ ~child_unit[u].present
  /\ ~record[u].present
  /\ content \in {DesiredContent(u), AgentBytes}
  /\ child_unit' = [child_unit EXCEPT ![u] = PresentUnit(content)]
  /\ UNCHANGED << store_body, record, staging, held_back, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command AgentEditChildUnit
\* @result MaterializationOutcome
\* @port AgentWorkspace.edit_child_home_unit
AgentEditChildUnit(u) ==
  /\ ~staging.active
  /\ child_unit[u].present
  /\ child_unit[u].content # AgentBytes
  /\ child_unit' = [child_unit EXCEPT ![u] = PresentUnit(AgentBytes)]
  /\ agent_edits' = agent_edits \cup {u}
  /\ store_body' =
       [w \in Units |->
          IF w \in WriteThroughUnits(u) THEN AgentBytes ELSE store_body[w]]
  /\ UNCHANGED << record, staging, held_back >>
  /\ UNCHANGED clone_vars

\* @command UpgradeParentStoreUnit
\* @result MaterializationOutcome
\* @port SkillManagerCli.sync_unit
UpgradeParentStoreUnit(u) ==
  /\ store_body[u] \in BodyRevs
  /\ store_body[u] < MaxBodyRev
  /\ store_body' = [store_body EXCEPT ![u] = @ + 1]
  /\ UNCHANGED << child_unit, record, staging, held_back, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command UpgradeLinkedParentSource
\* @result MaterializationOutcome
\* @port SkillManagerCli.sync_unit
\* The parent-store unit behind a symlink INSIDE a child-home unit changes.
UpgradeLinkedParentSource ==
  /\ store_body[LinkedSourceA] \in BodyRevs
  /\ store_body[LinkedSourceA] < MaxBodyRev
  /\ store_body' = [store_body EXCEPT ![LinkedSourceA] = @ + 1]
  /\ UNCHANGED << child_unit, record, staging, held_back, agent_edits >>
  /\ UNCHANGED clone_vars

\* --------------------------------------------------------------------------
\* Pre-epic paths. Reachable only under ChildRefreshPolicy =
\* "OVERWRITE_LOCAL_CHANGES"; they exist so the regression configs produce a
\* real counterexample instead of a comment.

\* @command OverwriteModifiedChildUnit
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.overwrite_modified
OverwriteModifiedChildUnit(u) ==
  /\ ~staging.active
  /\ ~HoldsBackLocalChanges
  /\ u \in MaterializablePayload
  /\ LocallyModified(u)
  /\ child_unit' = [child_unit EXCEPT ![u] = PresentUnit(DesiredContent(u))]
  /\ record' = [record EXCEPT ![u] = RecordOf(DesiredContent(u))]
  /\ UNCHANGED << store_body, staging, held_back, agent_edits >>
  /\ UNCHANGED clone_vars

\* @command DeleteUnclaimedModifiedChildUnit
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.prune_modified
DeleteUnclaimedModifiedChildUnit(u) ==
  /\ ~staging.active
  /\ ~HoldsBackLocalChanges
  /\ u \in UnclaimedByProject
  /\ LocallyModified(u)
  /\ child_unit' = [child_unit EXCEPT ![u] = AbsentUnit]
  /\ record' = [record EXCEPT ![u] = NoRecord]
  /\ UNCHANGED << store_body, staging, held_back, agent_edits >>
  /\ UNCHANGED clone_vars

Next ==
  \/ \E u \in ChildHomeUnits: StageChildUnitMaterialization(u)
  \/ SwapInChildUnit
  \/ AbortChildUnitMaterialization
  \/ \E u \in ChildHomeUnits: HoldBackModifiedChildUnit(u)
  \/ \E u \in ChildHomeUnits: AdoptIdenticalChildUnit(u)
  \/ \E u \in ChildHomeUnits: PruneUnclaimedChildUnit(u)
  \/ \E u \in ChildHomeUnits, c \in Contents: PlaceUntrackedChildUnit(u, c)
  \/ \E u \in ChildHomeUnits: AgentEditChildUnit(u)
  \/ \E u \in ChildHomeUnits: UpgradeParentStoreUnit(u)
  \/ UpgradeLinkedParentSource
  \/ \E u \in ChildHomeUnits: OverwriteModifiedChildUnit(u)
  \/ \E u \in ChildHomeUnits: DeleteUnclaimedModifiedChildUnit(u)

-----------------------------------------------------------------------------
\* Automatic claiming-project refresh after a public named sync.
\*
\* Source selection has already happened before this slice begins. The parent
\* checkout and installed provenance stand on the selected immutable revision;
\* the claimant refresh owns only reconciliation from those bytes. An explicit
\* user-invoked `project sync` is a different action and retains trunk pulling.

\* @command ReconcileClaimingProjectFromSelectedParent
\* @result ClaimantRefreshOutcome
\* @port SkillManagerCli.sync_claiming_projects
ReconcileClaimingProjectFromSelectedParent ==
  /\ claimant_phase = "selected"
  /\ claimant_child_revision' = claimant_parent_revision
  /\ claimant_phase' = "complete"
  /\ UNCHANGED << claimant_selected_revision, claimant_parent_revision,
                  claimant_trunk_revision, claimant_fetches >>
  /\ UNCHANGED << store_body, child_unit, record, staging, held_back,
                  agent_edits, clone, dest, handed_over >>

\* Regression-only action: the pre-#166 follow-on called default project sync,
\* fetched the trunk independently, and reconciled B after named sync selected A.
\* @command PullTrunkThenReconcileClaimingProject
\* @result ClaimantRefreshOutcome
\* @port SkillManagerCli.sync_claiming_projects_regression
PullTrunkThenReconcileClaimingProject ==
  /\ claimant_phase = "selected"
  /\ claimant_parent_revision' = claimant_trunk_revision
  /\ claimant_child_revision' = claimant_trunk_revision
  /\ claimant_fetches' = 1
  /\ claimant_phase' = "complete"
  /\ UNCHANGED << claimant_selected_revision, claimant_trunk_revision >>
  /\ UNCHANGED << store_body, child_unit, record, staging, held_back,
                  agent_edits, clone, dest, handed_over >>

ClaimantRefreshNext == ReconcileClaimingProjectFromSelectedParent

ClaimantRefreshRegressionNext == PullTrunkThenReconcileClaimingProject

-----------------------------------------------------------------------------
\* `home clone`, phase by phase
\*
\* External.tla models a whole clone as one step. This section models what the
\* clone DOES: copy the tree, then run one fixup pass per surface, then either
\* complete or roll the destination back. It is the same relationship staging
\* and SwapInChildUnit have to ResolveProjectChildHome one level up, and it
\* exists for the same reason: the interesting failures are between the phases.
\*
\* Each fixup pass is enabled by the policy that says skill-manager HAS that
\* pass. A pre-epic `cp -R` has none of them, and the point is that it still
\* completes and still reports success -- which is why the gate below is on
\* handed_over rather than on the exit code.

\* @command BeginCloneCopyTree
\* @result CloneOutcome
\* @port HomeCloner.copy_tree
\* Walk the source home, skipping SKIPPED_DIRS and SKIPPED_SEGMENTS. The
\* destination is populated from here on, and it is not yet a home.
BeginCloneCopyTree(from, target) ==
  /\ ~clone.active
  /\ target \in Clones
  /\ from # target
  /\ ~dest[target].populated
  /\ clone' = [active |-> TRUE, target |-> target, from |-> from]
  /\ dest' = [dest EXCEPT ![target] =
        [populated |-> TRUE, complete |-> FALSE, done |-> {},
         anchor |-> [s \in Surfaces |-> FreshlyWrittenAnchor(from, s)]]]
  /\ UNCHANGED handed_over
  /\ UNCHANGED unit_vars
  /\ UNCHANGED claimant_vars

\* @command RelativizeInHomeLinks
\* @result CloneOutcome
\* @port HomeCloner.copy_link
\* An absolute link into the source home is rewritten RELATIVE -- not
\* re-anchored. A link target holds no environment variable, so relative is the
\* only permanently root-independent form.
RelativizeInHomeLinks ==
  /\ clone.active
  /\ ~CarriesLinksVerbatim
  /\ SymlinkSurface \notin dest[clone.target].done
  /\ dest' = [dest EXCEPT ![clone.target].anchor[SymlinkSurface] = RootRelative,
                          ![clone.target].done = @ \cup {SymlinkSurface}]
  /\ UNCHANGED << clone, handed_over >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED claimant_vars

\* @command ReanchorStateRecords
\* @result CloneOutcome
\* @port HomeCloner.reanchor_state
\* Re-read skill-manager's own records through the production serde and rewrite
\* them for the copy. A record it cannot parse is left alone rather than
\* half-rewritten, which is why this runs through the serde and not over bytes.
ReanchorStateRecords ==
  /\ clone.active
  /\ ~CarriesStatePathsVerbatim
  /\ StateSurface \notin dest[clone.target].done
  /\ dest' = [dest EXCEPT
        ![clone.target].anchor[StateSurface] =
            IF dest[clone.target].anchor[StateSurface] = RootRelative
            THEN RootRelative
            ELSE clone.target,
        ![clone.target].done = @ \cup {StateSurface}]
  /\ UNCHANGED << clone, handed_over >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED claimant_vars

\* @command ReanchorProvisionedFiles
\* @result CloneOutcome
\* @port HomeCloner.reanchor_provisioned
\* Byte substitution over generated artifacts -- venv console scripts and
\* generated shims. The one surface where substitution is the only option,
\* because a shebang is resolved literally.
ReanchorProvisionedFiles ==
  /\ clone.active
  /\ ~SharesToolchainsWithSource
  /\ ProvisionedSurface \notin dest[clone.target].done
  /\ dest' = [dest EXCEPT ![clone.target].anchor[ProvisionedSurface] = clone.target,
                          ![clone.target].done = @ \cup {ProvisionedSurface}]
  /\ UNCHANGED << clone, handed_over >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED claimant_vars

\* The fixup passes THIS IMPLEMENTATION HAS. HomeCloner.build runs every one of
\* them before it returns, so completion is guarded on this set -- and the gate
\* invariant then checks that this set is all of OwnedSurfaces. Guarding
\* completion on OwnedSurfaces directly would be the mistake: it would make the
\* model assume the passes exist, so the one behavior worth catching (a plain
\* `cp -R`, which has none of them and still exits 0) would deadlock instead of
\* producing a counterexample.
AvailableFixups ==
  {s \in OwnedSurfaces :
     CASE s = SymlinkSurface -> ~CarriesLinksVerbatim
       [] s = StateSurface   -> ~CarriesStatePathsVerbatim
       [] OTHER              -> ~SharesToolchainsWithSource}

\* @command CompleteClone
\* @result CloneOutcome
\* @port HomeCloner.build
\* The clone exits, having run every pass it has.
CompleteClone ==
  /\ clone.active
  /\ dest[clone.target].done = AvailableFixups
  /\ dest' = [dest EXCEPT ![clone.target].complete = TRUE]
  /\ clone' = NoClone
  /\ UNCHANGED handed_over
  /\ UNCHANGED unit_vars
  /\ UNCHANGED claimant_vars

\* @command AbortCloneAndDiscardPartial
\* @result CloneOutcome
\* @port HomeCloner.discard_partial_clone
\* The copy failed part way through. Under DISCARD_PARTIAL the destination goes
\* back to the state it was found in; the naive alternative leaves the operator
\* a half-populated directory that the next attempt will refuse.
AbortCloneAndDiscardPartial ==
  /\ clone.active
  /\ dest' = [dest EXCEPT ![clone.target] =
        IF DiscardsPartialClone
        THEN EmptyDestination
        ELSE [dest[clone.target] EXCEPT !.complete = FALSE]]
  /\ clone' = NoClone
  /\ UNCHANGED handed_over
  /\ UNCHANGED unit_vars
  /\ UNCHANGED claimant_vars

\* @command PointEnvAtClone
\* @result CloneOutcome
\* @port Operator.export_skill_manager_home
\* The copy becomes a home. Nothing in skill-manager runs here -- which is
\* exactly why every fixup has to already have happened.
PointEnvAtClone(d) ==
  /\ d \in Clones
  /\ dest[d].complete
  /\ d \notin handed_over
  /\ handed_over' = handed_over \cup {d}
  /\ UNCHANGED << clone, dest >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED claimant_vars

CloneNext ==
  \/ \E from \in Homes, target \in Clones: BeginCloneCopyTree(from, target)
  \/ RelativizeInHomeLinks
  \/ ReanchorStateRecords
  \/ ReanchorProvisionedFiles
  \/ CompleteClone
  \/ AbortCloneAndDiscardPartial
  \/ \E d \in Clones: PointEnvAtClone(d)

-----------------------------------------------------------------------------
\* Specifications
\*
\* Two specs over disjoint variable sets, for the reason recorded in the
\* matching section of External.tla: the slices share no invariant, so checking
\* them together explores the product of two reachable state spaces and finds
\* nothing new. Each spec pins the other slice at its initial value, so
\* Internal.cfg and the two existing Internal regression configs keep their
\* exact state counts.

Spec == Init /\ [][Next]_vars

CloneSpec == Init /\ [][CloneNext]_vars

ClaimantRefreshSpec == Init /\ [][ClaimantRefreshNext]_vars

ClaimantRefreshRegressionSpec == Init /\ [][ClaimantRefreshRegressionNext]_vars

-----------------------------------------------------------------------------
\* Invariants

\* @invariant InFlightMaterializationLeavesTheChildUnitIntact
\* ATOMICITY. While a materialization is in flight, the live child unit is
\* still exactly the tree its materialization record describes. A partially
\* written or already-deleted unit would read as locally modified, and every
\* later pass would hold it back forever instead of repairing it.
InFlightMaterializationLeavesTheChildUnitIntact ==
  staging.active =>
    /\ child_unit[staging.unit].present = record[staging.unit].present
    /\ record[staging.unit].present =>
         child_unit[staging.unit].content = record[staging.unit].source

\* @invariant AgentEditsSurviveMaterialization
\* NO SILENT DESTRUCTION, per unit. Once an agent has edited a child unit, no
\* stage, swap, prune, or abort may overwrite or delete it.
AgentEditsSurviveMaterialization ==
  \A u \in agent_edits:
    /\ child_unit[u].present
    /\ child_unit[u].content = AgentBytes

\* @invariant ChildHomeWritesNeverReachTheParentStore
\* INDEPENDENCE, per unit. AgentBytes can only enter the parent store through
\* a write-through from the child home.
ChildHomeWritesNeverReachTheParentStore ==
  \A u \in Units: store_body[u] # AgentBytes

\* @invariant MaterializationRecordsDescribePresentChildUnits
\* A record claiming a unit that is not there makes every later freshness and
\* local-modification decision about that unit wrong.
MaterializationRecordsDescribePresentChildUnits ==
  \A u \in ChildHomeUnits:
    record[u].present => child_unit[u].present

\* @invariant HeldBackUnitsAreLocallyModified
\* The report never names a unit that was not actually held back.
HeldBackUnitsAreLocallyModified ==
  \A u \in held_back: LocallyModified(u)

\* @invariant ChildUnitContentMatchesItsRecord
\* The record is a true baseline: apart from units the agent edited, a unit
\* with a record is present and holds exactly the bytes the record describes.
\* This is what makes LocallyModified decidable at all, so it must hold at
\* every point in a pass, not only between passes.
ChildUnitContentMatchesItsRecord ==
  \A u \in ChildHomeUnits:
    (record[u].present /\ u \notin agent_edits)
      => /\ child_unit[u].present
         /\ child_unit[u].content = record[u].source

\* @invariant AutomaticClaimantRefreshUsesSelectedParentRevision
\* The automatic follow-on is a reconciliation of the revision the public sync
\* already selected. It cannot make a second source-selection decision.
AutomaticClaimantRefreshUsesSelectedParentRevision ==
  claimant_phase = "complete" =>
    /\ claimant_parent_revision = claimant_selected_revision
    /\ claimant_child_revision = claimant_selected_revision

\* @invariant AutomaticClaimantRefreshDoesNotPullTrunk
\* Fetch/merge belongs to an explicit project sync, never to automatic refresh.
AutomaticClaimantRefreshDoesNotPullTrunk ==
  claimant_fetches = 0

-----------------------------------------------------------------------------
\* Clone invariants

\* @invariant EveryOwnedSurfaceIsReanchoredBeforeAHomeIsHandedOver
\* THE GATE. A copy becomes a home the instant SKILL_MANAGER_HOME points at it,
\* not when `home clone` exits, so every surface fixup must be on the right side
\* of THAT line. Stated over `done` -- which passes actually ran -- rather than
\* over the resulting anchors, because that is the difference between "this copy
\* happens to be clean" and "this copy was checked".
\*
\* Deliberately not derivable from the exit code: a plain `cp -R` completes and
\* reports success, so an invariant phrased against completion would be
\* satisfied by exactly the behavior this one rejects.
EveryOwnedSurfaceIsReanchoredBeforeAHomeIsHandedOver ==
  \A d \in handed_over:
    /\ dest[d].complete
    /\ dest[d].done = OwnedSurfaces

\* @invariant NoUnfinishedDestinationSurvivesAFailedClone
\* CLONE ATOMICITY, and the analogue of
\* InFlightMaterializationLeavesTheChildUnitIntact one level up. When no clone is
\* in flight, a populated destination is a finished one. A partial clone is worse
\* than none: the next attempt refuses it as "destination already exists and is
\* not empty", and the operator has to work out by hand which files were ours.
NoUnfinishedDestinationSurvivesAFailedClone ==
  \A d \in Clones:
    (~clone.active /\ dest[d].populated) => dest[d].complete

\* @invariant AnInFlightCloneIsNeverHandedOver
\* While a clone is in flight its destination is a staging area, not a home.
AnInFlightCloneIsNeverHandedOver ==
  clone.active => clone.target \notin handed_over

\* @invariant NoHandedOverHomeNamesAnotherHome
\* The leak check at clone granularity: the same property External states over a
\* finished home, here stated over the anchors the phases actually produced. If
\* this holds and EveryOwnedSurfaceIsReanchoredBeforeAHomeIsHandedOver does not,
\* the copy is clean by luck rather than by construction -- which is why both are
\* checked.
NoHandedOverHomeNamesAnotherHome ==
  \A d \in handed_over:
    \A s \in OwnedSurfaces: ~NamesAForeignHome(d, dest[d].anchor[s])

=============================================================================
