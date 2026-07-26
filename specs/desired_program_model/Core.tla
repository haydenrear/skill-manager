-------------------------------- MODULE Core --------------------------------
\* Shared constants and operators for the child-home materialization slice of
\* the Skill Manager program model.
\*
\* This module exists so Internal.tla (fine-grained program state, spec-unit
\* case source) and External.tla (publicly observable behavior, Test Graph case
\* source) are two views of ONE model rather than two sources of truth.
\*
\* Scope note: this is the first slice of the Core/Internal/External baseline.
\* The rest of the accepted program model still lives in the monolithic
\* SkillManager.tla carried forward beside these modules. See README.md and
\* ticket_plan.yaml phase MIG for the plan that folds the remainder in.

EXTENDS Naturals, FiniteSets, Sequences, TLC

CONSTANTS
  \* Parent-store units.
  ChildUnitA,
  ChildUnitB,
  LinkedSourceA,

  \* Homes and the project whose child home this slice is about.
  ProjectA,
  ParentHomeA,
  ChildHomeA,

  \* A content token standing for "bytes an agent wrote", distinct from every
  \* revision the parent store can produce. It is what makes "the child's copy"
  \* and "the parent's unit" different objects instead of the same bytes.
  AgentBytes,

  NoReason,

  \* ---------------------------------------------------------------- modes
  \* These are real materialization modes, not test switches. Each names a
  \* behavior skill-manager either has today or had before this epic, and each
  \* one turns a desired-state invariant from a comment into a TLC
  \* counterexample. The desired configuration is the first value of each.

  \* COPY: a child-home unit is an independent tree (MaterializationMode.COPY).
  \* LINK: the pre-epic child home, where a unit was a symlink at the parent
  \*       store entry, so parent and child were literally the same bytes.
  ChildMaterialization,

  \* HOLD_BACK_LOCAL_CHANGES: an agent-modified child unit is never rewritten
  \*       or deleted; it is left alone and reported.
  \* OVERWRITE_LOCAL_CHANGES: the first implementation of this epic, which
  \*       silently destroyed the agent's edits on the next `project resolve`.
  ChildRefreshPolicy,

  \* DEREFERENCE: symlinks inside a unit that point into the parent store are
  \*       replaced by their content, so isolation actually holds.
  \* PRESERVE_LINK: the link is recreated in the child home and still points
  \*       into the parent store.
  ChildLinkHandling,

  \* ATOMIC_MOVE: the new tree is staged and moved into place.
  \* DELETE_THEN_WRITE: the live child unit is removed before the new tree is
  \*       written, so an interrupted run leaves nothing behind.
  ChildSwapStrategy

\* Parent-store units reachable from this slice.
Units == {ChildUnitA, ChildUnitB, LinkedSourceA}

\* Units a child home can hold. LinkedSourceA is only ever reached through a
\* symlink inside ChildUnitA; it is never materialized as a unit of its own.
ChildHomeUnits == {ChildUnitA, ChildUnitB}

\* Payload of `skill-manager project resolve` for ProjectA.
ProjectChildHomePayload == {ChildUnitA}

\* Payload of `skill-manager harness instantiate --child-home-dir`, which goes
\* through the same materializer.
HarnessChildHomePayload == {ChildUnitB}

\* Any unit some pass may materialize.
MaterializablePayload == ProjectChildHomePayload \cup HarnessChildHomePayload

\* A symlink inside ChildUnitA pointing at LinkedSourceA in the parent store.
StoreLinkEdges == {<<ChildUnitA, LinkedSourceA>>}

LinkIntoParentStore(u) == <<u, LinkedSourceA>> \in StoreLinkEdges

\* One revision step per unit is enough: the behavior only distinguishes
\* "the source the child was materialized from" and "some later source".
MaxBodyRev == 1

\* A unit's own bytes plus the bytes behind its store link.
MaxSourceView == 2 * MaxBodyRev

\* Written with literal endpoints so `analyze complexity` can size them; keep
\* them in step with MaxBodyRev / MaxSourceView above.
BodyRevs == 0..1
SourceViews == 0..2

\* What a child-home unit directory can contain.
Contents == SourceViews \cup {AgentBytes}

\* What a parent-store unit directory can contain. AgentBytes is reachable here
\* ONLY through a write-through, which is exactly the thing
\* ChildHomeWritesNeverReachTheParentStore forbids.
StoreBodies == BodyRevs \cup {AgentBytes}

CopiesUnits == ChildMaterialization = "COPY"
HoldsBackLocalChanges == ChildRefreshPolicy = "HOLD_BACK_LOCAL_CHANGES"
DereferencesStoreLinks == ChildLinkHandling = "DEREFERENCE"
SwapsAtomically == ChildSwapStrategy = "ATOMIC_MOVE"

\* Bytes behind the store link, or 0 when the unit has no store link.
LinkedBodyRev(body, u) ==
  IF LinkIntoParentStore(u) THEN body[LinkedSourceA] ELSE 0

\* Digest of a parent-store unit as it SHOULD appear once materialized into a
\* child home: the unit's own bytes plus the bytes behind every symlink that
\* points into the parent store.
\*
\* Digesting the raw tree instead -- hashing a store link as its target string
\* -- is the freshness bug this operator exists to make visible: an upgrade of
\* LinkedSourceA would not change the digest, and the child copy would keep
\* stale dereferenced content forever.
SourceView(body, u) ==
  IF \/ body[u] = AgentBytes
     \/ (LinkIntoParentStore(u) /\ body[LinkedSourceA] = AgentBytes)
  THEN AgentBytes
  ELSE body[u] + LinkedBodyRev(body, u)

\* What a materialization actually writes under the configured link handling.
\* Under PRESERVE_LINK only the unit's own bytes land in the child home; the
\* linked bytes stay behind a link into the parent store.
MaterializedContent(body, u) ==
  IF DereferencesStoreLinks THEN SourceView(body, u) ELSE body[u]

\* Under PRESERVE_LINK an in-unit link still points into the parent store, so
\* an edit below it lands in the parent store. Under LINK the whole unit is the
\* parent store entry.
WriteThroughUnits(u) ==
  LET whole == IF CopiesUnits THEN {} ELSE {u}
      linked == IF DereferencesStoreLinks \/ ~LinkIntoParentStore(u)
                THEN {}
                ELSE {LinkedSourceA}
  IN whole \cup linked

=============================================================================
