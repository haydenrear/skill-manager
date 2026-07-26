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
VARIABLES
  store_body,
  child_unit,
  record,
  staging,
  held_back,
  agent_edits

vars == << store_body, child_unit, record, staging, held_back, agent_edits >>

AbsentUnit == [present |-> FALSE, content |-> 0]
PresentUnit(content) == [present |-> TRUE, content |-> content]

NoRecord == [present |-> FALSE, source |-> 0]
RecordOf(source) == [present |-> TRUE, source |-> source]

NoStaging == [active |-> FALSE, unit |-> ChildUnitA, source |-> 0]

Init ==
  /\ store_body = [u \in Units |-> 0]
  /\ child_unit = [u \in ChildHomeUnits |-> AbsentUnit]
  /\ record = [u \in ChildHomeUnits |-> NoRecord]
  /\ staging = NoStaging
  /\ held_back = {}
  /\ agent_edits = {}

ChildUnitStates == [present: BOOLEAN, content: Contents]
RecordStates == [present: BOOLEAN, source: Contents]
StagingStates == [active: BOOLEAN, unit: ChildHomeUnits, source: Contents]

\* @invariant TypeOK
TypeOK ==
  /\ store_body \in [Units -> StoreBodies]
  /\ child_unit \in [ChildHomeUnits -> ChildUnitStates]
  /\ record \in [ChildHomeUnits -> RecordStates]
  /\ staging \in StagingStates
  /\ held_back \subseteq ChildHomeUnits
  /\ agent_edits \subseteq ChildHomeUnits

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

\* @command AbortChildUnitMaterialization
\* @result MaterializationOutcome
\* @port ChildHomeMaterializer.abort
\* The copy or the move failed. The staging area is swept and the previous
\* child unit must still be there, byte for byte.
AbortChildUnitMaterialization ==
  /\ staging.active
  /\ staging' = NoStaging
  /\ UNCHANGED << store_body, child_unit, record, held_back, agent_edits >>

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

\* @command UpgradeParentStoreUnit
\* @result MaterializationOutcome
\* @port SkillManagerCli.sync_unit
UpgradeParentStoreUnit(u) ==
  /\ store_body[u] \in BodyRevs
  /\ store_body[u] < MaxBodyRev
  /\ store_body' = [store_body EXCEPT ![u] = @ + 1]
  /\ UNCHANGED << child_unit, record, staging, held_back, agent_edits >>

\* @command UpgradeLinkedParentSource
\* @result MaterializationOutcome
\* @port SkillManagerCli.sync_unit
\* The parent-store unit behind a symlink INSIDE a child-home unit changes.
UpgradeLinkedParentSource ==
  /\ store_body[LinkedSourceA] \in BodyRevs
  /\ store_body[LinkedSourceA] < MaxBodyRev
  /\ store_body' = [store_body EXCEPT ![LinkedSourceA] = @ + 1]
  /\ UNCHANGED << child_unit, record, staging, held_back, agent_edits >>

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

Spec == Init /\ [][Next]_vars

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

=============================================================================
