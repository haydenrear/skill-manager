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
  \* ---------------------------------------------------------------- units
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
  ChildSwapStrategy,

  \* ------------------------------------------------- home-level policies
  \* The four dimensions of HOME-level isolation. Same discipline as the four
  \* above: every value names a behavior skill-manager has today or had before
  \* this epic, and the first value of each is the desired configuration.

  \* HomePaths at write time -- how a home records a path INTO ITSELF.
  \*   TOKENIZED                    -- today: `$SKILL_MANAGER_HOME/<rel>`,
  \*        expanded on read. The pure-function property stated directly.
  \*   ABSOLUTE_REANCHORED_ON_CLONE -- pre-epic writes plus HomeCloner's
  \*        substitution pass. Correct immediately after a clone, but the home
  \*        is no longer a function of its root: `mv` breaks it and only
  \*        another clone repairs it.
  \*   ABSOLUTE_CARRIED_VERBATIM    -- pre-epic writes and a plain `cp -R`.
  \*        Issue #20's headline finding: 54 metadata files in the copy still
  \*        name the ORIGINAL home. `unbind` then deletes there.
  HomeSelfReferenceEncoding,

  \* HomeLinks at write time -- the same three shapes for a symlink inside the
  \* home whose target is also inside the home. A link target cannot hold an
  \* environment variable (the kernel resolves the stored bytes literally), so
  \* "root-independent" here means RELATIVE rather than tokenized. Same
  \* principle, different encoding.
  \*   RELATIVE                  -- today.
  \*   ABSOLUTE_RELATIVIZED_ON_CLONE -- pre-epic writes, repaired by
  \*        HomeCloner.copyLink. Robust after a clone; not relocatable in place.
  \*   ABSOLUTE_CARRIED_VERBATIM -- pre-epic writes and a plain `cp -R`: the
  \*        copy holds a live symlink into the home it was copied from, so an
  \*        ordinary edit below it writes there.
  HomeLinkEncoding,

  \*   SKIP_AND_REPROVISION -- today: venvs/, tools/ and npm/ are not copied,
  \*        pm/ IS (it holds the node and uv that re-provisioning needs), and
  \*        the shims left without a target are reported.
  \*   SKIP_INCLUDING_PM    -- also skips pm/, leaving a clone that cannot
  \*        rebuild the toolchains it is missing.
  \*   SKIP_SILENTLY        -- skips them and reports success: three CLI tools
  \*        handed over broken with no diagnostic.
  \*   SHARE_WITH_SOURCE    -- the option considered and rejected on #20: point
  \*        the clone at the source home's toolchain roots. Installers write
  \*        into them (PipBackend UV_TOOL_DIR, SkillScriptBackend
  \*        SKILL_MANAGER_CACHE_DIR, and `sync --force-scripts` reruns both), so
  \*        this reintroduces cross-project mutation, silently, through
  \*        unbounded user code.
  CloneToolchainPolicy,

  \*   TOLERATE_AND_REPORT -- today: a file under skills/ / plugins/ / docs/ /
  \*        harnesses/ that records an absolute home path is authored content --
  \*        append-only spec history, effect-provider evidence -- and is left
  \*        exactly as written and counted in the report.
  \*   REWRITE             -- re-anchor it like any other path. Corrupts the
  \*        record, and afterwards there is nothing left to report.
  \*   TOLERATE_SILENTLY   -- leave it and do not mention it, so "0 leaks"
  \*        reads as "nothing survives".
  CloneContentPolicy

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

-----------------------------------------------------------------------------
\*                        HOME-LEVEL ISOLATION
\*
\* Everything above this line is about the units inside ONE home. Everything
\* below is about whole homes: `$SKILL_MANAGER_HOME` is copied into a project,
\* the env var is pointed at the copy, and the copy must touch neither the home
\* it came from nor any other copy.
\*
\* The unit-level slice above could not state that. `store_body` there is "the
\* parent store", singular; there is no second home for a write to leak into
\* and no root for anything to be anchored to, so "relocating this home changes
\* no behavior" and "a write through this home reached another home" were both
\* unsayable. That is the gap this section closes, and it is a DIFFERENT gap
\* from the one the unit-level slice closed -- child-home units being
\* independent copies says nothing about whether the home around them is a
\* function of its root.
\*
\* Style note: the domain values below are strings, not declared model values
\* like AgentBytes. Three homes and six content tokens would be nine more
\* CONSTANTS that all seven existing .cfg files must assign and none of them
\* varies, and the policy constants are already strings, so the section reads
\* uniformly. Only the four policies above are CONSTANTS, because those are the
\* only things a regression config changes.

\* The home a clone is taken from -- in production `~/.skill-manager`. Nothing
\* in this slice ever writes THROUGH it: it is read once, at clone time. That
\* is not a modeling convenience, it is the standing constraint on issue #1.
SourceHome == "source"

\* Two project-local clones. Two, not one: "a write through one home reaches no
\* other home" is a different proposition from "... does not reach the source",
\* and with a single clone the first one is unsayable.
CloneP == "cloneP"
CloneQ == "cloneQ"

Clones == {CloneP, CloneQ}
Homes  == {SourceHome} \cup Clones

\* Units in a home's store. ONE unit is deliberate: this slice distinguishes
\* homes, not units, and the unit dimension is already exhausted above. Adding
\* a second unit here would multiply the state space by ~4 and would not make
\* one additional home-level proposition statable.
HomeUnits == {ChildUnitA}

\* No unit at that path -- what a deletion leaves behind.
MissingUnit == "missing"

\* The bytes a unit holds as skill-manager installed it. One value, not a
\* revision counter: nothing in the home slice upgrades a unit, because "the
\* copy refreshes when its source changes" is the unit-level slice's property
\* (UnmodifiedChildUnitsConvergeOnTheirSource) and restating it here would put
\* one rule in two places.
InstalledBytes == "installed"

\* A home's copy of a unit holds either the installed bytes, or the bytes an
\* agent wrote THROUGH a particular home (the home's own name is the token, so
\* `home_body[g][u] = h` reads "home g's copy of u holds bytes written through
\* home h" -- and for g # h that is a leak), or nothing.
\*
\* All strings, deliberately: TLC evaluates membership in `0..1 \cup <strings>`
\* by testing the interval first and raises a type error rather than returning
\* FALSE, so a domain that mixes BodyRevs with names cannot be used in a TypeOK.
HomeContents == {InstalledBytes, MissingUnit} \cup Homes

\* --------------------------------------------------------------- surfaces
\* The four classes of recorded path. Exactly HomeCloner.Surface, plus the
\* symlink encoding as its own class: a link target cannot hold an environment
\* variable, so it is fixed by a different mechanism (relative) even though the
\* property is the same one.
StateSurface       == "state"
SymlinkSurface     == "symlink"
ProvisionedSurface == "provisioned"
ContentSurface     == "content"

\* Surfaces skill-manager itself writes, and is therefore accountable for.
\* HomeCloner.classify is default-deny for exactly this reason: an unrecognized
\* directory is STATE, so a future feature's records are held to this standard
\* rather than silently exempted.
OwnedSurfaces == {StateSurface, SymlinkSurface, ProvisionedSurface}
Surfaces == OwnedSurfaces \cup {ContentSurface}

\* Surfaces that WRITE-TIME encoding can make root-independent: a stored path
\* tokenizes, an in-home symlink is written relative.
\*
\* ProvisionedSurface is deliberately absent, and the omission is the point.
\* A venv console script begins `#!/abs/path/to/venv/bin/python` and the kernel
\* reads that line literally -- verified on #20: `#!$SM_HOME/...` yields
\* `bad interpreter: $SM_HOME/...`. So it cannot be tokenized and cannot be
\* made relative; the only available fix is re-anchoring per clone. That
\* asymmetry is precisely why `home clone` exists rather than `mv`, and
\* pretending otherwise would make the model claim a guarantee the code does
\* not have.
RelocatableSurfaces == {StateSurface, SymlinkSurface}

\* ---------------------------------------------------------------- anchors
\* RootRelative -- root-independent: `$SKILL_MANAGER_HOME/<rel>` or a relative
\*                 link. Resolves under any root.
\* a home       -- an absolute path anchored at that home's root.
\* AuthoredPath -- the exact bytes an author committed: the absolute path a past
\*                 run really used, inside an append-only record. It names the
\*                 source home, but as DATA -- nothing resolves it.
RootRelative == "rel"
AuthoredPath == "authored"
NoAnchor     == "none"

Anchors == {RootRelative, AuthoredPath, NoAnchor} \cup Homes

\* --------------------------------------------------------------- policies
TokenizesSelfReferences   == HomeSelfReferenceEncoding = "TOKENIZED"
CarriesStatePathsVerbatim == HomeSelfReferenceEncoding = "ABSOLUTE_CARRIED_VERBATIM"

WritesRelativeInHomeLinks == HomeLinkEncoding = "RELATIVE"
CarriesLinksVerbatim      == HomeLinkEncoding = "ABSOLUTE_CARRIED_VERBATIM"

SharesToolchainsWithSource == CloneToolchainPolicy = "SHARE_WITH_SOURCE"
CarriesPackageManagers     == CloneToolchainPolicy # "SKIP_INCLUDING_PM"
ReportsMissingToolchains   == CloneToolchainPolicy # "SKIP_SILENTLY"

ToleratesAuthoredContent  == CloneContentPolicy # "REWRITE"
ReportsToleratedContent   == CloneContentPolicy = "TOLERATE_AND_REPORT"

\* ------------------------------------------------------ anchor semantics
\* Whether the path recorded at <<h, surface>> names a home OTHER than h. This
\* is what a leak check looks for, and it is the one question `home verify`
\* answers. AuthoredPath counts as foreign in every home but the source,
\* because that is literally what it is: the source's absolute path, sitting in
\* a copy.
NamesAForeignHome(h, anchor) ==
  CASE anchor = RootRelative -> FALSE
    [] anchor = NoAnchor     -> FALSE
    [] anchor = AuthoredPath -> h # SourceHome
    [] OTHER                 -> anchor # h

\* Whether the path recorded at <<h, surface>> still finds what it named after
\* `moved` says h's root was renamed. An absolute path anchored at h's own root
\* stops resolving the instant h moves; a root-relative one never does.
\*
\* The last branch is deliberately TRUE: a path naming ANOTHER home resolves
\* perfectly well. It is a leak, not a dangling reference, and conflating the
\* two would let a leak masquerade as a repairable breakage.
AnchorResolves(h, anchor, moved) ==
  CASE anchor = RootRelative -> TRUE
    [] anchor = NoAnchor     -> TRUE
    [] anchor = AuthoredPath -> TRUE
    [] anchor = h            -> ~moved
    [] OTHER                 -> TRUE

\* The anchor a home written by current code holds on `surface`. Provisioned is
\* always the home's own absolute root: there is no encoding that avoids it.
FreshlyWrittenAnchor(h, surface) ==
  CASE surface = StateSurface       -> IF TokenizesSelfReferences THEN RootRelative ELSE h
    [] surface = SymlinkSurface     -> IF WritesRelativeInHomeLinks THEN RootRelative ELSE h
    [] surface = ProvisionedSurface -> h
    [] OTHER                        -> AuthoredPath

\* The anchor a clone of `from` at `dest` lands with on `surface`, given what
\* `from` held. This function IS the clone's fixup policy, so a regression
\* config changes one constant and every consequence follows.
ClonedAnchor(from, dest, surface, inherited) ==
  CASE surface = StateSurface ->
         \* HomeCloner.reanchorState re-reads skill-manager's own records
         \* through the production serde and rewrites them for the copy. A
         \* plain `cp -R` does not.
         CASE inherited = RootRelative    -> RootRelative
           [] CarriesStatePathsVerbatim   -> inherited
           [] OTHER                       -> dest
    [] surface = SymlinkSurface ->
         \* HomeCloner.copyLink rewrites an absolute in-home link RELATIVE, so
         \* the repaired form is root-independent rather than re-anchored --
         \* which is why a clone survives a later relocation but the source it
         \* was copied from does not.
         CASE inherited = RootRelative -> RootRelative
           [] CarriesLinksVerbatim     -> inherited
           [] OTHER                    -> RootRelative
    [] surface = ProvisionedSurface ->
         IF SharesToolchainsWithSource THEN from ELSE dest
    [] OTHER ->
         \* The one surface the clone must NOT touch.
         IF ToleratesAuthoredContent THEN inherited ELSE dest

\* Homes a write through h lands in. Under a preserved absolute in-home link
\* the copy contains a live symlink into the home the link names, so an
\* ordinary edit below it writes there too -- no skill-manager code involved.
WriteReach(h, anchor) ==
  {h} \cup (IF anchor \in Homes /\ anchor # h THEN {anchor} ELSE {})

\* The home whose tree an `unbind` through h actually deletes. `unbind` removes
\* the tree its projection ledger recorded as `destPath`, so which home that is
\* depends entirely on the ledger's anchor. This is the reproduced defect on
\* #20: a destPath rewritten to name a foreign home made `unbind` delete that
\* home's installed unit and leave the agent symlink dangling.
UnbindTarget(h, anchor) == IF anchor \in Homes THEN anchor ELSE h

=============================================================================
