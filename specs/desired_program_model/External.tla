------------------------------ MODULE External ------------------------------
\* Publicly observable behavior of a project child home.
\*
\* The public surface of `skill-manager project resolve` is filesystem
\* behavior, so this module IS the library from a caller's point of view: what
\* the child home holds afterwards, what the parent store still holds, and
\* which units the command reported as held back.
\*
\* The one thing the pre-epic model could not say is said here: the parent
\* store unit (store_body) and the child-home unit (child_home) are DIFFERENT
\* objects. Under the old symlink child home they were the same bytes, so
\* "the agent's edit survived" and "the parent store was not touched" could not
\* be told apart, and the silent-deletion bug was not expressible.
\*
\* Generates: Test Graph cases (see testgraph_bindings.yml, ticket CHM-8).

EXTENDS Core

\* store_body   parent store: content of each installed unit directory.
\* child_home   child home: content of each unit directory the child home holds.
\* agent_edits  witness variable -- the units an agent has edited inside the
\*              child home. Nothing in skill-manager may clear it; it exists so
\*              that an implementation which destroys an edit cannot also
\*              destroy the evidence that the edit happened.
\* pass         outcome of the most recent child-home pass, as the CLI reports
\*              it: which command ran, whether it succeeded, and which units it
\*              held back.
VARIABLES
  store_body,
  child_home,
  agent_edits,
  pass

vars == << store_body, child_home, agent_edits, pass >>

PassKinds == {"none", "resolve", "sync", "harness"}

NoPass == [kind |-> "none", accepted |-> TRUE, held_back |-> {}]

AbsentUnit == [present |-> FALSE, content |-> 0]

PresentUnit(content) == [present |-> TRUE, content |-> content]

Init ==
  /\ store_body = [u \in Units |-> 0]
  /\ child_home = [u \in ChildHomeUnits |-> AbsentUnit]
  /\ agent_edits = {}
  /\ pass = NoPass

ChildUnitStates == [present: BOOLEAN, content: Contents]

PassRecords == [kind: PassKinds, accepted: BOOLEAN,
                held_back: SUBSET ChildHomeUnits]

\* @invariant TypeOK
TypeOK ==
  /\ store_body \in [Units -> StoreBodies]
  /\ child_home \in [ChildHomeUnits -> ChildUnitStates]
  /\ agent_edits \subseteq ChildHomeUnits
  /\ pass \in PassRecords

\* A child unit whose bytes are no longer the bytes skill-manager wrote.
LocallyModified(u) ==
  /\ child_home[u].present
  /\ child_home[u].content = AgentBytes

\* Units a pass refreshes.
PassPayload(kind) ==
  CASE kind = "resolve" -> ProjectChildHomePayload
    [] kind = "sync"    -> ProjectChildHomePayload
    [] kind = "harness" -> HarnessChildHomePayload
    [] OTHER            -> {}

\* Units a pass examines. `resolve` also scans units the project no longer
\* claims, because it prunes them.
PassScope(kind) ==
  IF kind = "resolve" THEN ChildHomeUnits ELSE PassPayload(kind)

RefreshedUnit(u) == PresentUnit(MaterializedContent(store_body, u))

\* A pass may rewrite or drop a unit only when it is not locally modified --
\* unless the refresh policy is the pre-epic OVERWRITE_LOCAL_CHANGES.
Protected(u) == HoldsBackLocalChanges /\ LocallyModified(u)

HeldBackIn(scope) ==
  IF HoldsBackLocalChanges
  THEN {u \in scope : LocallyModified(u)}
  ELSE {}

-----------------------------------------------------------------------------
\* Actions

\* @command ResolveProjectChildHome
\* @result ChildHomeResult
\* @port SkillManagerCli.resolve_project_child_home
\* Refreshes every unit the project claims and prunes every unit it no longer
\* claims. Locally modified units are held back on both paths: "no longer a
\* dependency" is not a licence to delete an agent's work.
ResolveProjectChildHome ==
  /\ child_home' =
       [u \in ChildHomeUnits |->
          IF Protected(u)
          THEN child_home[u]
          ELSE IF u \in ProjectChildHomePayload
               THEN RefreshedUnit(u)
               ELSE AbsentUnit]
  /\ pass' = [kind |-> "resolve",
              accepted |-> TRUE,
              held_back |-> HeldBackIn(ChildHomeUnits)]
  /\ UNCHANGED << store_body, agent_edits >>

\* @command SyncProjectChildHome
\* @result ChildHomeResult
\* @port SkillManagerCli.sync_claiming_project_child_homes
\* Parent-side sync refresh of the claiming child homes. Non-destructive: it
\* refreshes the payload and never prunes.
SyncProjectChildHome ==
  /\ child_home' =
       [u \in ChildHomeUnits |->
          IF u \in ProjectChildHomePayload /\ ~Protected(u)
          THEN RefreshedUnit(u)
          ELSE child_home[u]]
  /\ pass' = [kind |-> "sync",
              accepted |-> TRUE,
              held_back |-> HeldBackIn(ProjectChildHomePayload)]
  /\ UNCHANGED << store_body, agent_edits >>

\* @command InstantiateHarnessChildHome
\* @result ChildHomeResult
\* @port SkillManagerCli.instantiate_child_home_from_harness
\* `harness instantiate --child-home-dir` goes through the same materializer,
\* so it obeys the same hold-back rule.
InstantiateHarnessChildHome ==
  /\ child_home' =
       [u \in ChildHomeUnits |->
          IF u \in HarnessChildHomePayload /\ ~Protected(u)
          THEN RefreshedUnit(u)
          ELSE child_home[u]]
  /\ pass' = [kind |-> "harness",
              accepted |-> TRUE,
              held_back |-> HeldBackIn(HarnessChildHomePayload)]
  /\ UNCHANGED << store_body, agent_edits >>

\* @command EditChildHomeUnit
\* @result ChildHomeResult
\* @port AgentWorkspace.edit_child_home_unit
\* An agent editing a skill inside its own project home. Under COPY the write
\* stays in the child home. Under LINK -- and under a preserved store link --
\* the same write lands in the parent store.
EditChildHomeUnit(u) ==
  /\ child_home[u].present
  /\ child_home[u].content # AgentBytes
  /\ child_home' = [child_home EXCEPT ![u] = PresentUnit(AgentBytes)]
  /\ agent_edits' = agent_edits \cup {u}
  /\ store_body' =
       [w \in Units |->
          IF w \in WriteThroughUnits(u) THEN AgentBytes ELSE store_body[w]]
  \* Any report the last pass printed is now out of date.
  /\ pass' = NoPass

\* @command UpgradeParentStoreUnit
\* @result ChildHomeResult
\* @port SkillManagerCli.sync_unit
\* The parent store's copy of a child-home unit changes (install/sync/upgrade).
UpgradeParentStoreUnit(u) ==
  /\ store_body[u] \in BodyRevs
  /\ store_body[u] < MaxBodyRev
  /\ store_body' = [store_body EXCEPT ![u] = @ + 1]
  /\ pass' = NoPass
  /\ UNCHANGED << child_home, agent_edits >>

\* @command UpgradeLinkedParentSource
\* @result ChildHomeResult
\* @port SkillManagerCli.sync_unit
\* The parent-store unit that a symlink INSIDE a child-home unit points at
\* changes. A child home that dereferenced that link must still converge.
UpgradeLinkedParentSource ==
  /\ store_body[LinkedSourceA] \in BodyRevs
  /\ store_body[LinkedSourceA] < MaxBodyRev
  /\ store_body' = [store_body EXCEPT ![LinkedSourceA] = @ + 1]
  /\ pass' = NoPass
  /\ UNCHANGED << child_home, agent_edits >>

Next ==
  \/ ResolveProjectChildHome
  \/ SyncProjectChildHome
  \/ InstantiateHarnessChildHome
  \/ \E u \in ChildHomeUnits: EditChildHomeUnit(u)
  \/ \E u \in ChildHomeUnits: UpgradeParentStoreUnit(u)
  \/ UpgradeLinkedParentSource

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
\* Invariants

\* @invariant ChildHomeWritesNeverReachTheParentStore
\* INDEPENDENCE. A write to a child-home unit does not change the parent-store
\* unit it was materialized from. AgentBytes can only enter store_body through
\* a write-through, so this is exactly the isolation guarantee.
ChildHomeWritesNeverReachTheParentStore ==
  \A u \in Units: store_body[u] # AgentBytes

\* @invariant AgentEditedChildUnitsAreNeverDestroyed
\* NO SILENT DESTRUCTION. Once an agent has edited a child-home unit, no
\* resolve, sync, harness instantiation, or prune may overwrite or delete it.
\* agent_edits remembers the edit even if the unit's bytes are gone, so an
\* implementation cannot satisfy this by erasing the evidence with the work.
AgentEditedChildUnitsAreNeverDestroyed ==
  \A u \in agent_edits:
    /\ child_home[u].present
    /\ child_home[u].content = AgentBytes

\* @invariant EveryPassReportsExactlyTheHeldBackUnits
\* REPORTING. A pass that leaves a modified unit alone must say so, and must
\* not name a unit it did not hold back. Held-back units are reported, not
\* silently skipped.
EveryPassReportsExactlyTheHeldBackUnits ==
  pass.kind # "none" =>
    \A u \in PassScope(pass.kind):
      LocallyModified(u) <=> u \in pass.held_back

\* @invariant UnmodifiedChildUnitsConvergeOnTheirSource
\* CONVERGENCE. After a pass, every unmodified child unit in the payload holds
\* the current materialized view of its parent source -- including the bytes
\* behind a dereferenced symlink. Stated against SourceView, not against the
\* unit's own bytes, so a child home that only tracked the raw tree fails here.
UnmodifiedChildUnitsConvergeOnTheirSource ==
  pass.kind # "none" =>
    \A u \in PassPayload(pass.kind):
      (child_home[u].present /\ child_home[u].content # AgentBytes)
        => child_home[u].content = SourceView(store_body, u)

\* @invariant ResolveLeavesOnlyClaimedOrHeldBackUnits
\* After `project resolve` the child home holds the project payload plus the
\* units resolve reported as held back, and nothing else.
ResolveLeavesOnlyClaimedOrHeldBackUnits ==
  pass.kind = "resolve" =>
    \A u \in ChildHomeUnits:
      child_home[u].present =>
        \/ u \in ProjectChildHomePayload
        \/ u \in pass.held_back

=============================================================================
