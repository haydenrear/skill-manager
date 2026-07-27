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
\*
\* ------------------------------------------------------ the home level
\* home         per-home record: whether it exists, whether its root has been
\*              renamed, the state of its toolchain roots, whether it carries
\*              the package managers, and what its clone reported.
\* home_anchor  per home, per surface: the path that home records there. This
\*              is the whole home-level model in one variable -- "a home is a
\*              pure function of its root" is a statement about nothing else.
\* home_body    per home, per unit: what that home's store holds. Three homes
\*              instead of one parent store is what makes "the write reached
\*              another home" statable at all.
\* home_writes  WITNESS. <<through, unit, landed_in>> triples: one per home a
\*              write actually LANDED in, recorded as it happened. A triple
\*              whose third component differs from its first is a write that
\*              escaped its home, and nothing -- no later copy, no overwrite --
\*              can erase it. Recorded per landing rather than per write for a
\*              reason TLC found: comparing final bytes instead flags a
\*              legitimate clone-of-an-edited-home as a leak, because a copy
\*              carrying an agent's edit is correct behavior and the bytes are
\*              indistinguishable afterwards. Reach is only observable at the
\*              moment of the write, so that is what the witness records.
\* home_authored  WITNESS. Homes that hold author-committed bytes on their
\*              content surface. A clone JOINS this set because the copy carried
\*              those bytes; if the clone then rewrites them, this remembers
\*              they were there.
\* source_snapshot  WITNESS. What the source home held when it was first cloned.
\*              Captured once and never rewritten -- capturing it a second time
\*              would let a corrupted source be re-baselined as correct.
VARIABLES
  store_body,
  child_home,
  agent_edits,
  pass,
  home,
  home_anchor,
  home_body,
  home_writes,
  home_authored,
  source_snapshot

unit_vars == << store_body, child_home, agent_edits, pass >>
home_vars == << home, home_anchor, home_body, home_writes, home_authored,
                source_snapshot >>
\* Written out literally rather than as unit_vars \o home_vars: TLC recognizes
\* the subscript of [][A]_vars syntactically and warns that every variable is
\* missing from it when the tuple is built by concatenation.
vars == << store_body, child_home, agent_edits, pass,
           home, home_anchor, home_body, home_writes, home_authored,
           source_snapshot >>

PassKinds == {"none", "resolve", "sync", "harness"}

NoPass == [kind |-> "none", accepted |-> TRUE, held_back |-> {}]

AbsentUnit == [present |-> FALSE, content |-> 0]

PresentUnit(content) == [present |-> TRUE, content |-> content]

\* A home nobody has cloned yet: the destination directory does not exist.
AbsentHome ==
  [present |-> FALSE, moved |-> FALSE, toolchain |-> "absent", pm |-> FALSE,
   reported_shims |-> FALSE, reported_content |-> FALSE]

\* The source home as it stands before anything is cloned from it: its
\* toolchains are its own and provisioned, and it has nothing to report because
\* it was never a copy of anything.
SourceHomeState ==
  [present |-> TRUE, moved |-> FALSE, toolchain |-> "own", pm |-> TRUE,
   reported_shims |-> TRUE, reported_content |-> TRUE]

NoSnapshot == [captured |-> FALSE, body |-> [u \in HomeUnits |-> InstalledBytes]]

Init ==
  /\ store_body = [u \in Units |-> 0]
  /\ child_home = [u \in ChildHomeUnits |-> AbsentUnit]
  /\ agent_edits = {}
  /\ pass = NoPass
  /\ home = [h \in Homes |-> IF h = SourceHome THEN SourceHomeState ELSE AbsentHome]
  /\ home_anchor = [h \in Homes |->
        [s \in Surfaces |->
           IF h = SourceHome THEN FreshlyWrittenAnchor(h, s) ELSE NoAnchor]]
  /\ home_body = [h \in Homes |->
        [u \in HomeUnits |-> IF h = SourceHome THEN InstalledBytes ELSE MissingUnit]]
  /\ home_writes = {}
  /\ home_authored = {SourceHome}
  /\ source_snapshot = NoSnapshot

ChildUnitStates == [present: BOOLEAN, content: Contents]

PassRecords == [kind: PassKinds, accepted: BOOLEAN,
                held_back: SUBSET ChildHomeUnits]

HomeStates == [present: BOOLEAN, moved: BOOLEAN,
               toolchain: {"absent", "own", "shared"}, pm: BOOLEAN,
               reported_shims: BOOLEAN, reported_content: BOOLEAN]

SnapshotStates == [captured: BOOLEAN, body: [HomeUnits -> HomeContents]]

\* @invariant TypeOK
TypeOK ==
  /\ store_body \in [Units -> StoreBodies]
  /\ child_home \in [ChildHomeUnits -> ChildUnitStates]
  /\ agent_edits \subseteq ChildHomeUnits
  /\ pass \in PassRecords
  /\ home \in [Homes -> HomeStates]
  /\ home_anchor \in [Homes -> [Surfaces -> Anchors]]
  /\ home_body \in [Homes -> [HomeUnits -> HomeContents]]
  /\ home_writes \subseteq (Homes \X HomeUnits \X Homes)
  /\ home_authored \subseteq Homes
  /\ source_snapshot \in SnapshotStates

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

\* ------------------------------------------------------- home predicates

\* Surfaces of h that skill-manager wrote and that name a different home. This
\* is `home verify`'s leak list, and it is deliberately NOT the same thing as
\* ToleratedContentReference below: one fails the clone, the other is counted.
HomeLeaks(h) ==
  {s \in OwnedSurfaces : NamesAForeignHome(h, home_anchor[h][s])}

\* Whether h's authored content names another home. HomeCloner reports this
\* separately from leaks() so that a caller cannot read "0 leaks" as "nothing
\* survives" -- 175 files in the real home are in exactly this state.
ToleratedContentReference(h) == NamesAForeignHome(h, home_anchor[h][ContentSurface])

\* The state of a home freshly cloned from `from` into `dest`.
ClonedHomeState(from, dest) ==
  [present          |-> TRUE,
   moved            |-> FALSE,
   \* venvs/, tools/ and npm/ are not copied. "shared" is the rejected option
   \* where the copy points at the source's roots instead.
   toolchain        |-> IF SharesToolchainsWithSource THEN "shared" ELSE "absent",
   pm               |-> CarriesPackageManagers,
   \* What the clone actually printed, which is not the same as what was true.
   reported_shims   |-> ReportsMissingToolchains,
   reported_content |-> ReportsToleratedContent]

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
  /\ UNCHANGED home_vars

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
  /\ UNCHANGED home_vars

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
  /\ UNCHANGED home_vars

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
  /\ UNCHANGED home_vars

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
  /\ UNCHANGED home_vars

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
  /\ UNCHANGED home_vars

Next ==
  \/ ResolveProjectChildHome
  \/ SyncProjectChildHome
  \/ InstantiateHarnessChildHome
  \/ \E u \in ChildHomeUnits: EditChildHomeUnit(u)
  \/ \E u \in ChildHomeUnits: UpgradeParentStoreUnit(u)
  \/ UpgradeLinkedParentSource

-----------------------------------------------------------------------------
\* Home-level actions

\* @command CloneHomeIntoProject
\* @result HomeCloneResult
\* @port SkillManagerCli.clone_home
\* `skill-manager home clone --to <project>/.skill-manager`, then point
\* SKILL_MANAGER_HOME at the result. `from` ranges over every present home, not
\* just the source: cloning a project home into a second project is a real
\* operation, and it is the only way "a write through one home reaches no OTHER
\* home" becomes reachable rather than merely stated.
CloneHomeIntoProject(from, dest) ==
  /\ dest \in Clones
  /\ from # dest
  /\ home[from].present
  /\ ~home[dest].present
  /\ home' = [home EXCEPT ![dest] = ClonedHomeState(from, dest)]
  /\ home_anchor' = [home_anchor EXCEPT ![dest] =
        [s \in Surfaces |-> ClonedAnchor(from, dest, s, home_anchor[from][s])]]
  /\ home_body' = [home_body EXCEPT ![dest] = home_body[from]]
  \* The copy carried the author's bytes. It joins the witness set no matter
  \* what the content policy then does to them; that is what makes a later
  \* rewrite detectable instead of merely unreported.
  /\ home_authored' = home_authored \cup {dest}
  /\ source_snapshot' = IF source_snapshot.captured
                        THEN source_snapshot
                        ELSE [captured |-> TRUE, body |-> home_body[SourceHome]]
  /\ UNCHANGED home_writes
  /\ UNCHANGED unit_vars

\* @command EditUnitThroughHome
\* @result HomeCloneResult
\* @port AgentWorkspace.edit_unit_through_home
\* An agent edits a skill in the home its SKILL_MANAGER_HOME names. No
\* skill-manager code runs; the write goes wherever the filesystem sends it,
\* which under a preserved absolute in-home link is another home's store.
EditUnitThroughHome(h, u) ==
  \* The root home is never written through. That is the standing constraint on
  \* issue #1, and modeling it as unreachable is what makes
  \* SourceHomeIsByteIdenticalToItsCloneTimeSelf a claim about leaks rather
  \* than a claim about discipline.
  /\ h \in Clones
  /\ home[h].present
  /\ home_body[h][u] # MissingUnit
  /\ home_body[h][u] # h
  /\ home_body' = [g \in Homes |->
        IF g \in WriteReach(h, home_anchor[h][SymlinkSurface])
        THEN [home_body[g] EXCEPT ![u] = h]
        ELSE home_body[g]]
  /\ home_writes' = home_writes \cup
        {<<h, u, g>> : g \in WriteReach(h, home_anchor[h][SymlinkSurface])}
  /\ UNCHANGED << home, home_anchor, home_authored, source_snapshot >>
  /\ UNCHANGED unit_vars

\* @command UnbindUnitThroughHome
\* @result HomeCloneResult
\* @port SkillManagerCli.unbind
\* `unbind` deletes the tree its projection ledger recorded as `destPath`. Which
\* home that path names is decided entirely by the ledger's anchor, so a ledger
\* a plain copy carried verbatim makes an unbind in the COPY delete the
\* ORIGINAL's installed unit. Reproduced end to end during review of #20.
UnbindUnitThroughHome(h, u) ==
  /\ h \in Clones
  /\ home[h].present
  /\ home_body[UnbindTarget(h, home_anchor[h][StateSurface])][u] # MissingUnit
  /\ home_body' = [home_body EXCEPT
        ![UnbindTarget(h, home_anchor[h][StateSurface])][u] = MissingUnit]
  /\ UNCHANGED << home, home_anchor, home_writes, home_authored, source_snapshot >>
  /\ UNCHANGED unit_vars

\* @command RelocateHome
\* @result HomeCloneResult
\* @port Operator.move_home_root
\* Rename a home's root directory. The pure-function property IS this action:
\* if a home is a function of SKILL_MANAGER_HOME then this changes nothing, and
\* if it is not then this is where that shows.
RelocateHome(h) ==
  /\ home[h].present
  /\ ~home[h].moved
  /\ home' = [home EXCEPT ![h].moved = TRUE]
  /\ UNCHANGED << home_anchor, home_body, home_writes, home_authored,
                  source_snapshot >>
  /\ UNCHANGED unit_vars

\* @command ReprovisionToolchains
\* @result HomeCloneResult
\* @port SkillManagerCli.provision_cli_deps
\* `skill-manager cli` rebuilds venvs/, tools/ and npm/ from cli-lock.toml. It
\* needs pm/ -- the bundled node and uv -- which is exactly why the clone
\* carries pm/ while skipping the three roots it rebuilds.
ReprovisionToolchains(h) ==
  /\ home[h].present
  /\ home[h].toolchain = "absent"
  /\ home[h].pm
  /\ home' = [home EXCEPT ![h].toolchain = "own"]
  /\ home_anchor' = [home_anchor EXCEPT ![h][ProvisionedSurface] = h]
  /\ UNCHANGED << home_body, home_writes, home_authored, source_snapshot >>
  /\ UNCHANGED unit_vars

HomeNext ==
  \/ \E from \in Homes, dest \in Clones: CloneHomeIntoProject(from, dest)
  \/ \E h \in Homes, u \in HomeUnits: EditUnitThroughHome(h, u)
  \/ \E h \in Homes, u \in HomeUnits: UnbindUnitThroughHome(h, u)
  \/ \E h \in Homes: RelocateHome(h)
  \/ \E h \in Homes: ReprovisionToolchains(h)

-----------------------------------------------------------------------------
\* Specifications
\*
\* The two slices are checked SEPARATELY and that is a measured decision, not
\* an oversight. Their actions touch disjoint variables, so a combined Next
\* explores the product of the two reachable state spaces -- 250 unit states
\* times the home slice -- for no additional proposition: no invariant in
\* either slice mentions a variable from the other. The product would exceed
\* budgets.max_distinct_states 50000 while checking nothing new. Each spec
\* therefore pins the other slice at its initial value, which is why the
\* existing seven .cfg files keep their exact state counts.
\*
\* The cost of the split is real and worth naming: a rule that spans the two
\* levels -- for example "materializing a child unit inside a clone must not
\* write through the clone's in-home links" -- cannot be stated in either. Both
\* halves of that are covered (unit-level PRESERVE_LINK, home-level
\* ABSOLUTE_CARRIED_VERBATIM), but their composition is not. Folding the levels
\* into one view belongs with phase MIG, where a shared `store_body` indexed by
\* home would remove the duplication rather than multiply it.

\* Each action carries the OTHER slice's UNCHANGED itself rather than having it
\* conjoined onto Next here. That is not cosmetic: TLC names the action in a
\* counterexample only when the box subscript encloses a bare disjunction of
\* actions, so `[][Next /\ UNCHANGED home_vars]_vars` costs every trace in this
\* module its action names.
Spec == Init /\ [][Next]_vars

HomeSpec == Init /\ [][HomeNext]_vars

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

-----------------------------------------------------------------------------
\* Home-level invariants
\*
\* Read the four SUBSTANTIVE ones first -- AHomeIsAPureFunctionOfItsRoot,
\* NoOwnedSurfaceNamesAnotherHome, WritesThroughOneHomeReachNoOtherHome,
\* SourceHomeIsByteIdenticalToItsCloneTimeSelf, AuthoredContentIsNeverRewritten.
\* Each of them is a statement about the FILESYSTEM. The two reporting
\* invariants at the end are statements about what a command printed, and they
\* are last on purpose: an earlier ticket on this epic established that a
\* reporting invariant alone cannot catch silent destruction, because a
\* destructive implementation also destroys the thing the report is about, and
\* then both sides of the equivalence go false together and TLC finds nothing.
\* See the demonstration recorded against External_regression_rewritecontent.cfg
\* in spec_manifest.yaml.

\* @invariant AHomeIsAPureFunctionOfItsRoot
\* RELOCATABILITY. Renaming a home's root changes no behavior: every path the
\* home records on a surface that write-time encoding can make root-independent
\* still resolves afterwards. This is the property `home clone` depends on --
\* copy the tree, point SKILL_MANAGER_HOME at the copy, forget the original --
\* and it is a property of every home, not only of copies.
\*
\* Scoped to RelocatableSurfaces, and see Core for why ProvisionedSurface is
\* not one: a shebang is resolved literally by the kernel, so a venv console
\* script can only be re-anchored per clone. Claiming otherwise here would make
\* the model assert a guarantee the code does not have.
AHomeIsAPureFunctionOfItsRoot ==
  \A h \in Homes:
    home[h].present =>
      \A s \in RelocatableSurfaces:
        AnchorResolves(h, home_anchor[h][s], home[h].moved)

\* @invariant NoOwnedSurfaceNamesAnotherHome
\* ISOLATION, statically. No path skill-manager wrote in a home names any other
\* home. This is `home verify`'s acceptance criterion, and it is the invariant
\* that separates a leak from the tolerated content reference below: the content
\* surface is excluded here and covered by its own two invariants, so the
\* exception is declared rather than assumed.
NoOwnedSurfaceNamesAnotherHome ==
  \A h \in Homes: home[h].present => HomeLeaks(h) = {}

\* @invariant WritesThroughOneHomeReachNoOtherHome
\* CROSS-HOME INDEPENDENCE. A write made through one home lands in that home and
\* in no other -- not in a sibling project's home, which is the case no
\* single-clone model can express, and not in the home it was copied from.
\*
\* Stated over the witness rather than over final bytes, and TLC is the reason.
\* The obvious form -- "no other home's copy holds bytes written through h" --
\* produced a counterexample at depth 4 that is not a defect: edit through
\* clone P, then clone P into clone Q. Q's copy legitimately carries the edit,
\* because copying a home copies its content, and afterwards the leaked bytes
\* and the copied bytes are the same bytes. Reach is only observable at the
\* instant of the write, so the witness records one triple per home the write
\* landed in and the invariant reads them back.
WritesThroughOneHomeReachNoOtherHome ==
  \A w \in home_writes: w[3] = w[1]

\* @invariant SourceHomeIsByteIdenticalToItsCloneTimeSelf
\* THE SOURCE HOME IS NEVER TOUCHED. Nothing in this slice writes through the
\* source home: it is read once, at clone time. So it must still hold exactly
\* the bytes it held then.
\*
\* source_snapshot is a witness captured on the FIRST clone and never
\* recaptured. Both halves of that matter. Comparing against a live re-read
\* would compare the source to itself and pass unconditionally; recapturing on
\* every clone would let a source corrupted by clone 1 be re-baselined as
\* correct by clone 2. And no report can substitute: a `home clone` that
\* silently repointed a ledger at the source still prints "clone verified,
\* 0 leaks" truthfully, because the leak is in what the ledger MEANS, not in a
\* path the scan can see.
SourceHomeIsByteIdenticalToItsCloneTimeSelf ==
  source_snapshot.captured =>
    \A u \in HomeUnits: home_body[SourceHome][u] = source_snapshot.body[u]

\* @invariant AuthoredContentIsNeverRewritten
\* THE DECLARED EXCEPTION. Files under skills/, plugins/, docs/ and harnesses/
\* may legitimately record the absolute path a past run used -- append-only spec
\* history, effect-provider evidence. 175 of them do in the real home.
\* Re-anchoring them like any other path corrupts the record.
\*
\* home_authored is a witness: a clone joins it because the copy CARRIED those
\* bytes. That is what makes a rewrite detectable. It is also exactly the case
\* where a reporting invariant is worthless: after a rewrite there is no
\* surviving reference, so EveryToleratedContentReferenceIsReported is satisfied
\* on both sides while the record is gone.
AuthoredContentIsNeverRewritten ==
  \A h \in home_authored:
    home[h].present => home_anchor[h][ContentSurface] = AuthoredPath

\* @invariant ToolchainRootsAreNeverShared
\* ABSENT, NOT STALE. A clone carries no venvs/, tools/ or npm/. Pointing it at
\* the source's would give it a hidden absolute dependency on the source AND
\* make one project's `skill-manager cli` mutate a toolchain another project is
\* using -- installers write into these roots and a skill-script installer is
\* unbounded user code, so the write set has no bound to reason about.
ToolchainRootsAreNeverShared ==
  \A h \in Homes: home[h].toolchain # "shared"

\* @invariant AHomeMissingItsToolchainsStillHasItsPackageManagers
\* pm/ is deliberately NOT in HomeCloner.SKIPPED_DIRS: it holds the bundled node
\* and uv that re-provisioning itself needs. A clone that skipped it would be
\* permanently unable to rebuild the toolchains it is permanently missing, which
\* is a worse outcome than either copying or sharing them.
AHomeMissingItsToolchainsStillHasItsPackageManagers ==
  \A h \in Homes:
    (home[h].present /\ home[h].toolchain = "absent") => home[h].pm

\* @invariant EveryHomeMissingItsToolchainsSaysSo
\* REPORTING, toolchain surface. A shim whose target is under a skipped root is
\* reported, never silently broken. `bin/cli/computeq` execs into
\* `<home>/cache/skill-script-.../venv/bin/computeq`; the clone skips that, so
\* the re-anchored path is CORRECT and points at nothing. The dangling-symlink
\* scan cannot see it, and without the report the clone hands over three broken
\* CLI tools while exiting 0.
EveryHomeMissingItsToolchainsSaysSo ==
  \A h \in Homes:
    (home[h].present /\ home[h].toolchain = "absent") => home[h].reported_shims

\* @invariant EveryToleratedContentReferenceIsReported
\* REPORTING, content surface. A tolerated reference is still a reference, so a
\* clone that leaves one must count it. HomeCloner keeps contentReferences out
\* of leaks() for precisely this reason: an empty leak list must not be readable
\* as "nothing survives".
EveryToleratedContentReferenceIsReported ==
  \A h \in Homes:
    (home[h].present /\ ToleratedContentReference(h)) => home[h].reported_content

=============================================================================
