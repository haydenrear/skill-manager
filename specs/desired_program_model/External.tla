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

CONSTANTS
  \* ---------------------------------------------- git-backed unit content
  \* Whether the unit being reconciled is one that was installed from a GIT
  \* source, so the home's copy carries a .git of its own. FALSE pins every
  \* variable this dimension adds at its initial value, so a config that does
  \* not set it explores exactly the state space it explored before the
  \* dimension existed -- which is why the twenty-odd configs that predate this
  \* keep their state counts.
  GitBackedUnits,

  \* How a reconciliation decides whether a git-backed unit still holds what was
  \* materialized into it. Issue #29, and the reason it is a three-valued policy
  \* rather than a boolean is that BOTH of the other two values are defects and
  \* they are defects in opposite directions.
  \*
  \* SPLIT_AT_GIT       -- WHAT THE CODE DOES since #29. The tree is split at
  \*      .git and each half is judged by the authority that can answer it: the
  \*      digest for every byte outside .git, and git's own HEAD for the history.
  \*      Bookkeeping (an index rewrite, a reflog append, a gc) moves neither, so
  \*      it is invisible; a commit moves the head, so it is not.
  \* WHOLE_TREE_DIGEST  -- BEFORE #29. One digest over the whole tree, .git
  \*      included. A copy of .git rewrites itself on every git command, so the
  \*      unit differs from its own record the first time anybody looks at it and
  \*      is reported locally modified forever. A false positive that never
  \*      clears, on exactly the units an agent works in.
  \* SKIP_GIT           -- THE OBVIOUS FIX, AND A DATA-LOSS DEFECT. .git is
  \*      excluded and only the worktree is compared. A commit moves bytes ONLY
  \*      inside .git, so a home whose agent committed reads as pristine, the
  \*      teardown gate clears, and the commits stop existing.
  GitProvenance,

  \* ------------------------------------------- home-to-home reconciliation
  \* The five dimensions of `home sync` / `home close-out`. Same discipline as
  \* every other policy constant in this model: each value names a behavior
  \* skill-manager has today, had before this epic, or must have -- and the
  \* FIRST value of each is the desired configuration, which is not always the
  \* one the code implements. Where they differ it is called out, because a
  \* desired-state model that quietly described the current code would have
  \* nothing to say.

  \* HOLD_BACK_OR_MERGE    -- today: a destination unit that carries work the
  \*      source does not have is held back, or three-way merged under --merge.
  \* OVERWRITE_DESTINATION -- a reconciler that treats the destination as
  \*      disposable, which is what "copy the newer home over the older one"
  \*      degenerates into.
  HomeSyncPolicy,

  \* THREE_WAY     -- today: four cases per path and no fifth (agree / only the
  \*      source moved / only the destination moved / both moved = conflict).
  \* PREFER_SOURCE -- "the source is newer, so it wins", which silently makes
  \*      every conflict a deletion of the destination's half.
  MergeAlgebra,

  \* SHARED_ANCESTOR    -- the merge base for a reconciliation from g into h is
  \*      a state g ITSELF passed through. WHAT THE CODE DOES, since CHM-10:
  \*      ChildHomeMaterializer.mergeBase takes the destination's record only
  \*      when it can be shown to be about this source, and a merge now records
  \*      the SOURCE's tree as the baseline rather than the merged result --
  \*      which is a state the source never passed through. Where neither home's
  \*      record can be shown to be shared there is no base and the unit
  \*      conflicts.
  \* DESTINATION_RECORD -- before CHM-10: the destination's own record, whoever
  \*      last wrote it. Its javadoc claimed "the cost of choosing wrong here is
  \*      a conflict a human resolves, not an edit nobody sees again"; that is
  \*      true only when the chosen base is OLDER than the true common ancestor.
  \*      When a previous reconciliation from a THIRD home advanced the
  \*      destination's record past anything the current source ever held, the
  \*      base is NEWER, the algebra concludes "only the source moved", and the
  \*      third home's work is taken away without a conflict.
  MergeBasePolicy,

  \* SOURCE_AWARE     -- a destination may be overwritten wholesale only when
  \*      its current content came from the home now pushing (or from nobody).
  \*      WHAT THE CODE DOES, since CHM-9: MaterializationRecord.source names
  \*      the home the bytes came from and ChildHomeMaterializer.reconcile now
  \*      READS it -- the field was always written and nothing consulted it.
  \* MERGE_KIND_ONLY  -- before CHM-9 (MaterializationRecord.reconcileKind
  \*      alone): a merge RESULT is protected, a pristine copy is not -- and a
  \*      pristine copy of a torn-down worktree's only version of an edit is
  \*      indistinguishable from a pristine copy of the root's. Reproduced end
  \*      to end.
  \* IGNORED          -- before commit 7468b4f: no reconcileKind at all, so the
  \*      second source change deleted everything the first merge folded in.
  ReconcileProvenance,

  \* What a completed reconciliation WRITES DOWN as the two homes' shared
  \* baseline. Separate from MergeBasePolicy on purpose, and the separation was
  \* forced by a defect: the two are the READ and the WRITE sides of the same
  \* rule, and a model that only varied the read side could not express a
  \* reconciler that reads soundly and then records something it has no right
  \* to. CHM-12 is exactly that, so it was invisible here until this constant
  \* existed.
  \*
  \* SHARED_ONLY -- today, since CHM-12: a path is claimed only where this pass
  \*      can SHOW both homes stood on the byte. Three showings and no fourth:
  \*      the pass just wrote it from the source; the two sides already agreed;
  \*      or the source is still standing on a base that was itself shared with
  \*      this destination. Everything else is recorded as NoBaseline, which
  \*      routes the next merge on that path to a conflict.
  \*      ChildHomeMaterializer.sharedAfterMerge.
  \* SOURCE_TREE -- before CHM-12: the SOURCE's whole tree, paths the
  \*      reconciliation DECLINED to write included. Sound whenever the base was
  \*      the destination's own record and a lie whenever it was the source's:
  \*      the destination's record then names bytes the destination has never
  \*      held, and the NEXT reconcile in the opposite direction reads `d = b`
  \*      as "the destination is standing on the base" and overwrites the work.
  \*      Reproduced end to end over four homes with every command reporting
  \*      clean=true.
  \* MERGED_TREE -- before CHM-10: the merged result, local work included. The
  \*      destination's own edits become part of its recorded ancestor.
  RecordWritePolicy,

  \* WHOLE_UNIT_OR_NOTHING -- today: a conflicted unit writes NOTHING, so the
  \*      destination stays coherent and both versions still exist.
  \* WRITE_THE_CLEAN_PATHS -- the helpful-looking alternative: write the paths
  \*      that did merge and report the rest. The unit is then half one version
  \*      and half another, and its record describes a tree nobody chose.
  ConflictHandling,

  \* REFUSE_WHILE_WORK_REMAINS -- today (`home close-out` exits non-zero).
  \* DISCARD_UNCONDITIONALLY   -- the pre-epic teardown: `rm -rf` the worktree.
  \*      It succeeds exactly as loudly whether the directory held work or not.
  CloseOutGate,

  \* How `project resolve` / `project sync` decide whether the project home's
  \* current bytes may be replaced with the parent store's. The SAME question
  \* ReconcileProvenance asks about `home sync`, asked by the other writer into
  \* the same home -- which is the whole of why it needs its own axis: the two
  \* commands consult one materialization record and, until CHM-15, read it
  \* differently.
  \*
  \* SOURCE_AWARE         -- today, since CHM-15: the record must be evidence
  \*      about the parent store. A record naming a worktree, or one written by
  \*      a merge, is evidence about a pair of homes this refresh is not part of.
  \* CONTENT_DIGEST_ONLY  -- before CHM-15: "does the destination still hold
  \*      exactly what its record says was written there", and nothing else. The
  \*      answer is YES for a project home a worktree just merged into -- the
  \*      merge rewrote the record -- so the refresh reads a pristine tree,
  \*      overwrites it from the store, and reports it as routine reconciliation
  \*      while the ticket's work stops existing anywhere.
  ChildRefreshProvenance,

  \* What a reconciliation may do to a destination that has NO materialization
  \* record at all. Its own axis, and the axis had to be added before the defect
  \* was expressible: Init used to hand EVERY tier a clone-time baseline, which
  \* is what `home clone` leaves behind and is exactly what the one destination
  \* an operator actually has does NOT have. A root home is INSTALLED into,
  \* never materialized into. The model asserted the problem away.
  \*
  \* SOURCE_WITNESSED    -- today, since #43: the destination's bytes may be
  \*      replaced wholesale when the SOURCE can be shown to have passed through
  \*      them. Different evidence, same rule -- the destroyed bytes are bytes
  \*      the source held. In the code that is the source's own record's
  \*      contentDigest ("the bytes this reconcile wrote HERE"), compared against
  \*      the destination's current tree; here it is sync_history, which is the
  \*      same claim without the digest.
  \* NEVER_DISPOSABLE    -- before #43: no record, no disposal, ever. SAFE and
  \*      USELESS, and that combination is why it needs its own axis rather than
  \*      a counterexample: it violates nothing, and it made
  \*      `home sync --from <project> --to ~/.skill-manager` report every shared
  \*      unit held-back and exit 0 having reconciled nothing. 6, 7 and 5 units
  \*      on three real repositories. A model can only say this one is wrong by
  \*      being able to say the other two are different.
  \* ALWAYS_DISPOSABLE   -- the tempting fix, and the dangerous one: treat a
  \*      record-less destination as pristine, or ADOPT a baseline into it, which
  \*      is the same thing written down. Both assert that two homes shared bytes
  \*      they may never have shared. The counterexample is a third home's work
  \*      sitting in the root home, which the pushing project has never seen.
  \*      External_regression_blindadoption.cfg.
  RecordlessDestinationPolicy,

  \* ------------------------------------------------ the two sync dimensions
  \* These are not policies. They are the two axes the sync slice is
  \* DECOMPOSED along, and they are constants so a config can pick one axis and
  \* pin the other. Checking both at full width put the slice at 456,874
  \* distinct states and still climbing at the 120s budget, and every extra
  \* state was a combination of a tier question with a path question that no
  \* invariant relates. See spec_manifest.yaml validation.decomposition.
  \*
  \* SyncTiers -- which home tiers exist. Three (root / project / worktree) is
  \*      what the provenance, merge-base and close-out properties need: each of
  \*      them turns on a destination whose content came from a home that is not
  \*      the one now pushing, which two homes cannot express. Two is enough for
  \*      everything about the merge algebra, which is a question about one pair.
  SyncTiers,

  \* SyncPaths -- the files inside the one unit. Two is what the merge algebra
  \*      needs and the minimum that does: it is the difference between "each
  \*      side moved a different file" and "both moved the same file", and it is
  \*      the only way a reconciliation can write SOME of a unit. One is enough
  \*      for every tier-level property, none of which is about more than one
  \*      file at a time.
  SyncPaths

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
\*
\* --------------------------------------------------- the three-tier level
\* sync_body    per tier, per path: what that home's copy of the unit holds.
\*              Per PATH rather than per unit, because that is the granularity
\*              the mechanism decides at: a whole-tree digest can only say "this
\*              unit changed", and merging two disjoint edits needs to know
\*              WHICH files each side changed.
\* sync_record  per tier: the materialization record -- the per-file baseline
\*              (MaterializationRecord.entryDigests) and where the current
\*              content came from (MaterializationRecord.reconcileKind,
\*              generalized from "was it a merge" to "whose bytes are these").
\* sync_history WITNESS. Every <<home, path, content>> that has ever been on
\*              disk at that location. Monotone: nothing in skill-manager may
\*              remove a triple. It answers the one question a merge base has to
\*              answer and no report can -- "did the SOURCE ever pass through
\*              the state we are calling the common ancestor" -- and it survives
\*              the overwrite that destroys the bytes it is a record of.
\* sync_unsound WITNESS. <<path, reason>> pairs: one per path a reconciliation
\*              wrote that it was not entitled to write, and why. Same shape and
\*              same reason as home_writes -- whether a write was entitled to
\*              happen is only observable at the instant of the write, and
\*              afterwards the overwritten bytes and the bytes that were always
\*              there are the same bytes. The reasons are evaluated by the MODEL
\*              from the pre-state, not reported by the policy, so a policy that
\*              writes anyway is caught by its own write log.
\*
\*              Recorded as reasons rather than as a full per-write log for a
\*              measured reason: the log form (one tuple per write, carrying the
\*              destination and three booleans) put the sync slice at 574,209
\*              distinct states and still climbing at the 120s tlc_seconds
\*              budget. See spec_manifest.yaml validation.decomposition.
\* sync_gone    WITNESS. What the worktree home held at the moment it was torn
\*              down. A teardown deletes both the work and the evidence of it,
\*              which is exactly why the evidence has to be taken first.
\* sync_git     per tier: the state of that home's own .git -- whether git has
\*              rewritten its bookkeeping since the record was written, and which
\*              commit HEAD stands on. Two components rather than one because the
\*              whole of issue #29 is that they are different propositions:
\*              bookkeeping is content nobody authored, and a commit is work that
\*              exists in one home only. A model with a single "the .git changed"
\*              bit collapses them, and then "the false positive cleared" and
\*              "the commits were destroyed" are the same proposition -- the same
\*              shape as the symlink child home, where "the edit survived" and
\*              "the store was untouched" could not be told apart.
\* sync_committed  WITNESS. Every commit that has ever existed in any home,
\*              recorded as it was made. Monotone: nothing in skill-manager may
\*              remove one. A reconciliation that replaces a home's .git destroys
\*              the commit and the evidence of it in the same step, so the
\*              evidence has to be taken first -- exactly sync_gone's argument,
\*              one level down.
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
  source_snapshot,
  sync_body,
  sync_record,
  sync_history,
  sync_unsound,
  sync_gone,
  sync_git,
  sync_committed

unit_vars == << store_body, child_home, agent_edits, pass >>
home_vars == << home, home_anchor, home_body, home_writes, home_authored,
                source_snapshot >>
sync_vars == << sync_body, sync_record, sync_history, sync_unsound, sync_gone,
                sync_git, sync_committed >>
\* Written out literally rather than as unit_vars \o home_vars: TLC recognizes
\* the subscript of [][A]_vars syntactically and warns that every variable is
\* missing from it when the tuple is built by concatenation.
vars == << store_body, child_home, agent_edits, pass,
           home, home_anchor, home_body, home_writes, home_authored,
           source_snapshot,
           sync_body, sync_record, sync_history, sync_unsound, sync_gone,
           sync_git, sync_committed >>

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

-----------------------------------------------------------------------------
\*                       THE THREE HOME TIERS
\*
\* The home-level section above models CLONING: one home is copied and the copy
\* must touch nothing else. This section models the RETURN PATH -- content
\* flowing back up and down between tiers after the copies have diverged, which
\* is a different proposition and was previously unsayable. `home_body` there is
\* written only by an agent or a clone; nothing reconciles two populated homes,
\* so "the reconciliation destroyed an edit" had no action to be about.
\*
\* Three tiers, not two, and that is load-bearing rather than decorative. The
\* defects this section exists to expose all need a THIRD home: work arrives in
\* the project from the worktree, the worktree is torn down, and then the ROOT
\* pushes -- against a baseline it never held. With two homes the source of a
\* reconciliation is always the home the destination's content came from, and
\* every one of those defects is unreachable.

RootHome     == "root"
ProjectHome  == "project"
WorktreeHome == "worktree"

AllSyncHomes == {RootHome, ProjectHome, WorktreeHome}

SyncHomes == SyncTiers

\* What a path holds: the bytes as installed, or the bytes an agent wrote
\* THROUGH a particular tier -- the home's own name is the token, exactly as in
\* the home-level section above, so `sync_body[g][p] = h` reads "g's copy of p
\* holds bytes authored in h".
SyncContents == {InstalledBytes} \cup SyncHomes

\* Where a home's current copy came from. This is MaterializationRecord's
\* reconcileKind, widened from a two-valued "copy or merge" to the question the
\* two-valued form could not answer: a pristine copy is only disposable with
\* respect to the home it is a copy OF.
AdoptedOrigin == "adopted"   \* no provenance: the home's own clone-time baseline
MergedOrigin  == "merged"    \* a three-way result; a copy of nothing
\* The parent store, which is not a tier: `project resolve` writes into a tier
\* from something that is not one of them. Naming it is what lets the refresh's
\* entitlement be stated in the same terms as a reconcile's -- see
\* RefreshProjectChildHomeFromStore and ChildRefreshProvenance.
StoreOrigin   == "store"
\* A home that was INSTALLED into rather than materialized into. It has the
\* bytes and no provenance whatever -- not a clone-time baseline, not a source,
\* nothing. This is what the operator's own root home is, and modelling every
\* tier as though it had a baseline is what made #43 unsayable here.
NoRecordOrigin == "norecord"
Origins == {AdoptedOrigin, MergedOrigin, StoreOrigin, NoRecordOrigin} \cup SyncHomes

\* Origins a refresh from the parent store may destroy. The store's own bytes
\* (however this home came to hold them) and nothing else: an origin naming a
\* worktree, or a merge, is evidence about a pair of homes the store is not part
\* of. RootHome is here as a plain token because a two-tier decomposition may
\* not have it, and a set literal that never matches is the correct behavior
\* there.
StoreSideOrigins == {AdoptedOrigin, StoreOrigin, RootHome}

SyncStatuses ==
  {"unchanged", "updated", "held_back", "merged", "conflicted"}

\* Why a write was not entitled to happen. One per substantive invariant.
DestinationMovedIt  == "destination_moved_it"
BaseNeverShared     == "base_never_shared"
UnitWasConflicted   == "unit_was_conflicted"
UnsoundReasons ==
  {DestinationMovedIt, BaseNeverShared, UnitWasConflicted}

\* A record that declines to claim a path at all.
\*
\* entryDigests is PARTIAL in the code -- a map, and a path simply missing from
\* it. There is no total-function equivalent, so the absence gets a value here.
\* It has to be one no home can ever hold, because the whole meaning of it is
\* "no byte is claimed for this path": read as a merge base it matches neither
\* side, which is what turns a path with no baseline into a conflict rather than
\* into a silent overwrite.
NoBaseline == "no_baseline"

RecordedContents == SyncContents \cup {NoBaseline}

\* A home's history: the SET of commits reachable from HEAD, not the tip alone.
\*
\* The set is not decoration. TLC produced a false violation against the tip form:
\* an agent commits, the commit is merged up into the project, and the project
\* then commits again -- at which point the first commit is no longer any home's
\* tip and a tip-only model calls it destroyed, when git has it as an ancestor and
\* nothing was lost. "Reachable from some live home's HEAD" is the proposition
\* that matters, so it is the one that is modelled.
\*
\* The empty set is the record's "claims nothing", the git-shaped NoBaseline: it
\* equals no home's history, so a record that does not name one is no evidence and
\* holds the unit back, exactly as a NoBaseline path does.
GitHeads == SUBSET SyncContents

\* TWO git fields, and the split is the baseline rule's two halves in one record
\* -- the same split as contentDigest versus entryDigests, and leaving it out is
\* how CHM-10 happened the first time.
\*
\* git_head is evidence about THIS HOME ALONE: "the commit we wrote here", read to
\* tell an untouched history from one somebody has committed into. It follows the
\* destination's own head on every pass.
\* git_base is evidence about a PAIR: "this destination and the home named in
\* origin both stood on this commit". It is the merge base, it is claimed only
\* where the pass can show it, and it is NoBaseline everywhere else -- which
\* routes the next pass to a conflict rather than to a take.
\*
\* TLC found this by writing the destination's own current head as the base: `d =
\* b` then held trivially, the next merge read it as "only the source moved", and
\* the agent's commit was taken away with no conflict and no report. That is
\* CHM-10 exactly, one dimension across.
SyncRecords == [entries: [SyncPaths -> RecordedContents], origin: Origins,
                git_head: GitHeads, git_base: GitHeads]

InitialSyncRecord ==
  [entries |-> [p \in SyncPaths |-> InstalledBytes], origin |-> AdoptedOrigin,
   git_head |-> {InstalledBytes}, git_base |-> {InstalledBytes}]

\* A git-backed home's .git as it stands before anybody has run a git command in
\* it: standing on the commit it was materialized at, nothing rewritten since.
InitialGitState == [churn |-> FALSE, head |-> {InstalledBytes}]

\* The record an installed home has: none. Every path claims NoBaseline, which
\* already means "this record makes no statement about this path" everywhere
\* else in this module, so nothing had to learn a new absence.
RecordlessSyncRecord ==
  [entries |-> [p \in SyncPaths |-> NoBaseline], origin |-> NoRecordOrigin,
   git_head |-> {}, git_base |-> {}]

\* --------------------------------------------------------------- policies
HasGitBackedUnits      == GitBackedUnits
SplitsAtGit            == GitProvenance = "SPLIT_AT_GIT"
DigestsTheWholeTree    == GitProvenance = "WHOLE_TREE_DIGEST"
SkipsGit               == GitProvenance = "SKIP_GIT"
HoldsBackOrMerges      == HomeSyncPolicy = "HOLD_BACK_OR_MERGE"
MergeIsThreeWay        == MergeAlgebra = "THREE_WAY"
UsesSharedAncestorBase == MergeBasePolicy = "SHARED_ANCESTOR"
SourceAwareChildRefresh == ChildRefreshProvenance = "SOURCE_AWARE"
WritesConflictedPaths  == ConflictHandling = "WRITE_THE_CLEAN_PATHS"
GatesTeardown          == CloseOutGate = "REFUSE_WHILE_WORK_REMAINS"

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
  \* Every tier starts as an unmodified copy of the same installed unit, with a
  \* clone-time baseline and no provenance -- which is exactly the state
  \* ChildHomeMaterializer.recordCloneBaselines leaves a freshly cloned home in.
  \*
  \* EXCEPT THE ROOT, and that exception is #43. A root home is installed into,
  \* never materialized into, so it carries no record at all. Handing it one
  \* here made every tier look like a clone and made the one destination an
  \* operator actually has unrepresentable -- so a reconciliation that could
  \* write nothing into it was a behaviour this model could not distinguish
  \* from a correct one.
  /\ sync_body = [h \in SyncHomes |-> [p \in SyncPaths |-> InstalledBytes]]
  /\ sync_record = [h \in SyncHomes |->
        IF h = RootHome THEN RecordlessSyncRecord ELSE InitialSyncRecord]
  /\ sync_history = {<<h, p, InstalledBytes>> : h \in SyncHomes, p \in SyncPaths}
  /\ sync_unsound = {}
  /\ sync_gone = [torn_down |-> FALSE,
                  body |-> [p \in SyncPaths |-> InstalledBytes]]
  /\ sync_git = [h \in SyncHomes |-> InitialGitState]
  /\ sync_committed = {}

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
  /\ sync_body \in [SyncHomes -> [SyncPaths -> SyncContents]]
  /\ sync_record \in [SyncHomes -> SyncRecords]
  /\ sync_history \subseteq (SyncHomes \X SyncPaths \X SyncContents)
  /\ sync_unsound \subseteq (SyncPaths \X UnsoundReasons)
  /\ sync_gone \in [torn_down: BOOLEAN, body: [SyncPaths -> SyncContents]]
  /\ sync_git \in [SyncHomes -> [churn: BOOLEAN, head: SUBSET SyncContents]]
  /\ sync_committed \subseteq SyncContents

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

\* ------------------------------------------------------- sync predicates

\* Homes that still exist. A torn-down worktree is not a place work can be.
SyncLive == IF sync_gone.torn_down THEN SyncHomes \ {WorktreeHome} ELSE SyncHomes

\* The three-way comparison, per path, exactly as ChildHomeMaterializer.mergePlan
\* makes it: source value, destination value, and the destination record's
\* per-file baseline. "Absent" needs no rule of its own here for the same reason
\* it needs none there -- it is a value like any other in the comparison.
AgreeAt(from, to, p) == sync_body[from][p] = sync_body[to][p]

\* Whether the destination's own record is evidence about THIS source at all --
\* ChildHomeMaterializer.describesSource. A record is evidence about ONE pair of
\* homes: the one it names, or (for a clone's own restated baseline, which names
\* nobody) the home it was copied from, which is what AdoptedOrigin stands for.
RecordIsAboutSource(from, to) ==
  sync_record[to].origin \in {AdoptedOrigin, from}

\* The per-path merge base, WITH the fallback the code has and this model did
\* not: under SHARED_ANCESTOR, when the destination's record is about some other
\* pair of homes, the SOURCE's record is used instead. That fallback is why the
\* record-WRITE side matters at all -- a base taken from the source's record
\* says what the SOURCE was handed and nothing whatever about this destination,
\* so anything unsound written into a record propagates into a later
\* reconcile's base. Without it modelled, RecordWritePolicy = "SOURCE_TREE" is
\* indistinguishable from "SHARED_ONLY" and CHM-12 is unreachable.
\*
\* DESTINATION_RECORD keeps taking the destination's record whether or not it is
\* about this source -- which is the pre-CHM-10 behavior and the whole content
\* of that policy.
BaseAt(from, to, p) ==
  IF UsesSharedAncestorBase /\ ~RecordIsAboutSource(from, to)
  THEN sync_record[from].entries[p]
  ELSE sync_record[to].entries[p]

\* Whether the base above is one the DESTINATION is on record as having shared
\* with this source, as opposed to one the source merely says it was handed.
\* Both are usable as a merge base -- the algebra enforces the destination's
\* half per path, live -- but only the first may be carried FORWARD into a new
\* record, because only the first is evidence about this pair.
BaseIsShared(from, to) == RecordIsAboutSource(from, to)

\* Whether the destination has moved off ITS OWN record. A question about the
\* destination alone, deliberately: it is what makes a tree "a pristine copy"
\* for the wholesale-copy path, and what makes a write to it an overwrite of
\* something somebody did here. Distinct from DestOnBaseAt below, which is the
\* merge algebra's `d = b` and is relative to whichever base was chosen.
\* Guarded by `# NoBaseline` for the same reason
\* EveryDivergenceFromARecordIsAnEditMadeInThatHome is: a record that declines
\* to claim a path makes no statement to have moved AWAY from, and reading the
\* absence of a claim as a claim of "different" would make every path of an
\* installed home permanently "moved" -- which would report the honest
\* wholesale copy of #43 as an edit loss while the record-less home in fact has
\* no recorded edit to lose. Where a record does claim a path this is unchanged.
DestMovedAt(to, p) ==
  /\ sync_record[to].entries[p] # NoBaseline
  /\ sync_body[to][p] # sync_record[to].entries[p]
DestOnBaseAt(from, to, p) == sync_body[to][p]  = BaseAt(from, to, p)
SrcMovedAt(from, to, p)  == sync_body[from][p] # BaseAt(from, to, p)

\* Whether the baseline this reconciliation merges against is a state BOTH homes
\* passed through. That is what makes it a common ancestor rather than merely a
\* recorded one, and it is the whole difference between "only the source moved"
\* and "the destination is carrying a third home's work that this source has
\* never seen".
\*
\* Both halves are stated even though the destination's is implied at take time
\* by DestOnBaseAt: this operator is also read by the write log, where nothing
\* has gated anything, and an invariant named "...BothHomesPassedThrough" that
\* only checked one home would be the sort of claim this directory exists to
\* stop making.
SharedBaseAt(from, to, p) ==
  /\ <<from, p, BaseAt(from, to, p)>> \in sync_history
  /\ <<to,   p, BaseAt(from, to, p)>> \in sync_history

\* Whether the .git in `to` still stands where its record says -- the half of
\* "has this destination moved" that a digest cannot answer. Issue #29.
\*
\* The three arms are the three readings of the policy, and the middle one is
\* the defect that made a git-backed unit permanently held back: `churn` is a
\* rewrite of git's own bookkeeping, and a whole-tree digest cannot tell it from
\* somebody's work. SKIP_GIT drops the question entirely, which is why it reports
\* a home holding commits as untouched.
GitStateUnmoved(to) ==
  \/ ~HasGitBackedUnits
  \/ CASE DigestsTheWholeTree -> /\ ~sync_git[to].churn
                                /\ sync_git[to].head = sync_record[to].git_head
       [] SkipsGit           -> TRUE
       [] OTHER              -> sync_git[to].head = sync_record[to].git_head

\* Whether the two homes' .git states agree, for the "nothing to do at all"
\* question. Same three arms and the same reason.
GitAgree(from, to) ==
  \/ ~HasGitBackedUnits
  \/ CASE DigestsTheWholeTree -> /\ ~sync_git[from].churn
                                /\ ~sync_git[to].churn
                                /\ sync_git[from].head = sync_git[to].head
       [] SkipsGit           -> TRUE
       [] OTHER              -> sync_git[from].head = sync_git[to].head

\* Whether a MERGE may carry the source's history over: the three-way algebra's
\* `d = b`, one dimension across. The destination is still standing on the head
\* its own record names, so it has nothing of its own there to lose, and the
\* source has something it does not have.
\*
\* This is how a commit travels UPWARD, and it is not a nicety -- without it
\* CommittedWorkIsNeverDestroyed would be unsatisfiable rather than true, and the
\* model would be describing a mechanism that holds back forever instead of one
\* that returns the work. In the code it falls out of the same mergePlan every
\* other path goes through, because .git's entries ARE in entryDigests: only the
\* DISPOSAL question splits the tree at .git, never the copy.
GitTakeAllowed(from, to) ==
  /\ HasGitBackedUnits
  /\ sync_git[from].head # sync_git[to].head
  \* `d = b` against the PAIR baseline, never against this home's own head --
  \* see SyncRecords.
  /\ sync_git[to].head = sync_record[to].git_base
  /\ (UsesSharedAncestorBase => BaseIsShared(from, to))

\* The history moved on both sides, or on one side against no baseline at all.
\* Nothing here is entitled to settle it, so it conflicts -- the same answer the
\* per-path algebra gives, and the only one that cannot destroy a commit.
GitConflict(from, to) ==
  /\ HasGitBackedUnits
  /\ ~GitAgree(from, to)
  /\ ~GitTakeAllowed(from, to)

\* What the record may claim as the shared history baseline afterwards: the same
\* three showings RecordedEntryAfter allows for a path, and no fourth.
RecordedGitBaseAfter(from, to, status) ==
  IF \/ status = "updated"                                  \* written from the source
     \/ GitTakeAllowed(from, to)                             \* taken by this merge
     \/ sync_git[from].head = sync_git[to].head              \* both already held it
     \/ (/\ sync_git[from].head = sync_record[to].git_base   \* source still on ...
         /\ BaseIsShared(from, to))                          \* ... a SHARED base
  THEN sync_git[from].head
  ELSE {}

\* The .git a reconciliation leaves behind. A WHOLESALE COPY replaces the
\* destination's tree outright, .git included, so it carries the source's history
\* over -- which is the step that destroys a commit the destination held unless
\* the entitlement above stopped it. A MERGE builds its result from the
\* DESTINATION's tree with taken paths replaced, so it keeps the destination's
\* history except where the algebra entitles it to take the source's.
GitStateAfter(from, to, status) ==
  CASE status = "updated" -> [churn |-> FALSE, head |-> sync_git[from].head]
    [] status = "merged"  -> IF GitTakeAllowed(from, to)
                             THEN [churn |-> sync_git[to].churn,
                                   head  |-> sync_git[from].head]
                             ELSE sync_git[to]
    [] OTHER              -> sync_git[to]

DestUnmoved(to) ==
  /\ \A p \in SyncPaths: ~DestMovedAt(to, p)
  /\ GitStateUnmoved(to)

\* Whether the destination may be replaced wholesale -- the fast path a plain
\* `home sync` takes when the destination "held no local work".
\*
\* The three values are three answers to "what does a pristine copy mean". A
\* pristine copy is disposable only relative to the home it is a copy OF: once
\* the project home holds a fast-forwarded copy of a worktree that has since
\* been torn down, those bytes exist nowhere else, and nothing about the
\* destination's own digest can say so.
\* A destination that was installed into rather than materialized into. It has
\* no record, so nothing about IT can say whether its bytes are disposable.
RecordlessDestination(to) == sync_record[to].origin = NoRecordOrigin
\* The evidence that replaces it: the SOURCE has passed through the bytes the
\* destination is standing on now, on every path. Then the bytes a wholesale
\* copy would destroy are bytes the source held -- the baseline rule met by the
\* source's own history rather than by the destination's record.
SourceHeldDestBytes(from, to) ==
  \A p \in SyncPaths: <<from, p, sync_body[to][p]>> \in sync_history

\* The same showing for the destination's HISTORY, and it has to be stated
\* separately or the #43 fast-forward becomes the #29 data loss.
\*
\* Found by TLC: with only the bytes half, a record-less destination that had
\* committed was fast-forwarded from a home whose worktree agreed with it, and the
\* commit was replaced by the source's. The code does not have that hole, because
\* the digest sourceHeldTheseBytes compares is the WHOLE tree, .git included, so a
\* destination standing on its own commit never matches -- but the model said
\* otherwise, and a desired-state model that is weaker than the code cannot
\* protect it.
SourceHeldDestGit(from, to) ==
  \/ ~HasGitBackedUnits
  \/ SkipsGit
  \/ sync_git[from].head = sync_git[to].head

RecordlessCopyAllowed(from, to) ==
  CASE RecordlessDestinationPolicy = "NEVER_DISPOSABLE"  -> FALSE
    [] RecordlessDestinationPolicy = "ALWAYS_DISPOSABLE" -> TRUE
    [] OTHER -> SourceHeldDestBytes(from, to) /\ SourceHeldDestGit(from, to)

WholesaleCopyAllowed(from, to) ==
  IF RecordlessDestination(to)
  THEN RecordlessCopyAllowed(from, to)
  ELSE
  /\ DestUnmoved(to)
  /\ CASE ReconcileProvenance = "IGNORED"         -> TRUE
       [] ReconcileProvenance = "MERGE_KIND_ONLY" ->
            sync_record[to].origin # MergedOrigin
       [] OTHER ->
            sync_record[to].origin \in {AdoptedOrigin, from}

\* Paths where the source has something the destination does not already have.
SyncCandidates(from, to) ==
  {p \in SyncPaths : ~AgreeAt(from, to, p) /\ SrcMovedAt(from, to, p)}

\* Of those, the ones a merge is entitled to take: the destination has not moved
\* them, and the baseline is one the source passed through.
SyncTakePaths(from, to) ==
  IF ~MergeIsThreeWay
  THEN SyncCandidates(from, to)
  ELSE {p \in SyncCandidates(from, to) :
          /\ DestOnBaseAt(from, to, p)
          /\ (UsesSharedAncestorBase => SharedBaseAt(from, to, p))}

\* Everything else a merge found: nothing here is entitled to settle it.
SyncConflictPaths(from, to) ==
  IF ~MergeIsThreeWay
  THEN {}
  ELSE SyncCandidates(from, to) \ SyncTakePaths(from, to)

SyncStatusOf(from, to, merge) ==
  IF (\A p \in SyncPaths: AgreeAt(from, to, p)) /\ GitAgree(from, to) THEN "unchanged"
  ELSE IF ~HoldsBackOrMerges THEN "updated"
  ELSE IF WholesaleCopyAllowed(from, to) THEN "updated"
  ELSE IF ~merge THEN "held_back"
  ELSE IF SyncConflictPaths(from, to) # {} \/ GitConflict(from, to) THEN "conflicted"
  \* GitAgree is asked again HERE and not only in the first branch. Reaching this
  \* line means no worktree path needs taking; a source holding a commit the
  \* destination does not have still has something to contribute, and reporting
  \* "unchanged" for it is what let `home close-out` clear a teardown for a
  \* worktree whose object store held the only copy of the work. Found by TLC on
  \* the DESIRED config.
  ELSE IF SyncTakePaths(from, to) = {} /\ GitAgree(from, to) THEN "unchanged"
  ELSE "merged"

\* Which paths of the destination the reconciliation actually rewrites.
SyncWrittenPaths(from, to, merge) ==
  LET status == SyncStatusOf(from, to, merge) IN
  CASE status = "updated"    -> {p \in SyncPaths : ~AgreeAt(from, to, p)}
    [] status = "merged"     -> SyncTakePaths(from, to)
    \* The one case where "helpful" is destructive: writing the paths that DID
    \* merge leaves the unit half one version and half another.
    [] status = "conflicted" -> IF WritesConflictedPaths
                                THEN SyncTakePaths(from, to)
                                ELSE {}
    [] OTHER                 -> {}

\* What one path of the destination's record says after a reconciliation --
\* the WRITE side of the baseline rule, and the only thing RecordWritePolicy
\* varies.
\*
\* A record's entries are a CLAIM: "this destination and the home named in
\* `origin` both stood on these bytes". SHARED_ONLY makes the claim only where
\* the pass can show it, and records NoBaseline everywhere else; the two older
\* policies claim a whole tree and are each wrong about a different part of it.
RecordedEntryAfter(from, to, written, newBody, p) ==
  CASE RecordWritePolicy = "MERGED_TREE" -> newBody[p]
    [] RecordWritePolicy = "SOURCE_TREE" -> sync_body[from][p]
    [] OTHER ->
         IF \/ p \in written                       \* just written here from the source
            \/ AgreeAt(from, to, p)                \* both sides already held it
            \/ (/\ ~SrcMovedAt(from, to, p)        \* source still on the base ...
                /\ BaseIsShared(from, to))         \* ... and the base was shared
         THEN sync_body[from][p]
         ELSE NoBaseline

NewSyncOrigin(from, to, status) ==
  CASE status = "updated" -> from
    [] status = "merged"  -> MergedOrigin
    [] status = "conflicted" -> IF WritesConflictedPaths
                                THEN MergedOrigin
                                ELSE sync_record[to].origin
    [] OTHER -> sync_record[to].origin

\* `home close-out` is a dry-run `home sync --merge` from the worktree into the
\* project, and it refuses on anything it would have had to write or hold back.
\* Implemented as the same computation deliberately: answering "would a merge
\* into the project lose anything" with a second comparison would give the gate
\* and the sync two different opinions about the same unit.
CloseOutIsSafe ==
  SyncStatusOf(WorktreeHome, ProjectHome, TRUE) = "unchanged"

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

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
  /\ UNCHANGED sync_vars

HomeNext ==
  \/ \E from \in Homes, dest \in Clones: CloneHomeIntoProject(from, dest)
  \/ \E h \in Homes, u \in HomeUnits: EditUnitThroughHome(h, u)
  \/ \E h \in Homes, u \in HomeUnits: UnbindUnitThroughHome(h, u)
  \/ \E h \in Homes: RelocateHome(h)
  \/ \E h \in Homes: ReprovisionToolchains(h)

-----------------------------------------------------------------------------
\* Three-tier reconciliation actions

\* @command EditUnitInHomeTier
\* @result HomeSyncResult
\* @port AgentWorkspace.edit_unit_in_home
\* An agent edits one file of the unit inside whichever tier's home its
\* SKILL_MANAGER_HOME names. No skill-manager code runs. The bytes are tagged
\* with the tier they were written through, which is what makes "this edit"
\* a thing the model can follow across a copy.
EditUnitInHomeTier(h, p) ==
  /\ h \in SyncLive
  /\ sync_body[h][p] # h
  /\ sync_body' = [sync_body EXCEPT ![h][p] = h]
  /\ sync_history' = sync_history \cup {<<h, p, h>>}
  /\ UNCHANGED << sync_record, sync_unsound, sync_gone, sync_git, sync_committed >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED home_vars

\* @command ChurnGitBookkeepingInHomeTier
\* @result GitBookkeepingChurn
\* @port AgentWorkspace.run_git_command_in_unit
\* Any git command inside a git-backed unit rewrites git's own bookkeeping: the
\* index on a `git status`, the reflog on a checkout, the pack files on a `gc`.
\* No skill-manager code runs and NOBODY AUTHORED ANYTHING, which is why this
\* leaves sync_history alone -- the whole point of the action is that it produces
\* bytes no home can be said to have written. A rule that cannot tell this from
\* an edit reports the unit locally modified forever (issue #29).
\*
\* One bit, deliberately: "has git rewritten itself since" is all any rule may
\* ask about it, and counting churns would multiply the state space to say
\* nothing more.
ChurnGitBookkeepingInHomeTier(h) ==
  /\ HasGitBackedUnits
  /\ h \in SyncLive
  /\ ~sync_git[h].churn
  /\ sync_git' = [sync_git EXCEPT ![h].churn = TRUE]
  /\ UNCHANGED << sync_body, sync_record, sync_history, sync_unsound, sync_gone,
                  sync_committed >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED home_vars

\* @command CommitInHomeTier
\* @result GitCommitInUnit
\* @port AgentWorkspace.commit_in_unit
\* The agent commits inside the unit. THE WORKTREE DOES NOT MOVE: a commit
\* records what is already there, so every byte outside .git is exactly what the
\* record describes, and that is the entire trap in issue #29. The work now
\* exists in this home's object store and nowhere else.
\*
\* The witness is written here rather than derived later because a reconciliation
\* that replaces this home's .git destroys the commit and the evidence of it in
\* the same step -- sync_gone's argument, one level down.
CommitInHomeTier(h) ==
  /\ HasGitBackedUnits
  /\ h \in SyncLive
  /\ h \notin sync_git[h].head
  /\ sync_git' = [sync_git EXCEPT ![h].head = sync_git[h].head \cup {h}]
  /\ sync_committed' = sync_committed \cup {h}
  /\ UNCHANGED << sync_body, sync_record, sync_history, sync_unsound, sync_gone >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED home_vars

\* @command ReconcileHomeTiers
\* @result HomeSyncReport
\* @port SkillManagerCli.home_sync
\* `skill-manager home sync --from <home> --to <home> [--merge]`.
\*
\* Direction is the caller's and safety is not: nothing here knows or cares
\* whether it is pushing a worktree's edits up or pulling the root's new skills
\* down, so `from` and `to` range over every ordered pair of live tiers. That is
\* also what makes the round trip -- worktree to project to root and back down
\* -- a reachable behavior of this model rather than a claim in a comment.
\*
\* One action for the whole per-unit decision, deliberately. Splitting "what
\* would happen" from "make it happen" is how a --dry-run comes to report
\* something the real run does not do; here as in ChildHomeMaterializer.reconcile
\* there is one decision and `merge` only chooses which branch of it applies.
ReconcileHomeTiers(from, to, merge) ==
  /\ from \in SyncLive
  /\ to \in SyncLive
  /\ from # to
  /\ LET status  == SyncStatusOf(from, to, merge)
         written == SyncWrittenPaths(from, to, merge)
         newBody == [p \in SyncPaths |->
                       IF p \in written THEN sync_body[from][p] ELSE sync_body[to][p]]
     IN
     /\ sync_body' = [sync_body EXCEPT ![to] = newBody]
     \* The record is rewritten per path by RecordedEntryAfter, which is the
     \* WRITE half of the baseline rule and the half CHM-12 got wrong. Nothing
     \* written, no record: that is what makes a conflicted unit
     \* indistinguishable from one the pass never reached.
     \* The record follows whatever this pass actually changed. `written = {}` is
     \* not the same question as "nothing happened" once the history is a
     \* dimension: a merge that took only the source's HEAD wrote something, and
     \* leaving the record naming the old head would put the unit permanently off
     \* its own record -- issue #29's false positive, reintroduced by the record
     \* write rather than by the digest.
     /\ sync_record' = IF written = {} /\ GitStateAfter(from, to, status) = sync_git[to]
                       THEN sync_record
                       ELSE [sync_record EXCEPT ![to] =
                               [entries |-> [p \in SyncPaths |->
                                   RecordedEntryAfter(from, to, written, newBody, p)],
                                origin  |-> NewSyncOrigin(from, to, status),
                                \* The head the destination is standing on AFTER
                                \* this pass, which is what the next pass will
                                \* measure. Evidence about this home alone, like
                                \* contentDigest and unlike entries.
                                git_head |->
                                  GitStateAfter(from, to, status).head,
                                git_base |->
                                  RecordedGitBaseAfter(from, to, status)]]
     /\ sync_git' = [sync_git EXCEPT ![to] = GitStateAfter(from, to, status)]
     /\ UNCHANGED sync_committed
     /\ sync_history' = sync_history \cup {<<to, p, newBody[p]>> : p \in SyncPaths}
     \* The write log. Every reason is computed here, from the PRE-state, by the
     \* model rather than by the policy -- a reconciler that writes anyway still
     \* logs that it was not entitled to.
     /\ sync_unsound' = sync_unsound
          \* Two shapes again, and for the same reason: the two paths ask the
          \* destination different questions. A MERGE measured each path against
          \* a base and is entitled to exactly the paths the destination is
          \* standing on -- relative to that base, not to a record that may be
          \* about some other pair of homes; a destination standing on the
          \* SOURCE's recorded base has nothing of its own there to destroy. A
          \* WHOLESALE COPY measured nothing per path: it declared the whole
          \* tree pristine on the strength of the destination's own record, so
          \* that record is the only thing its entitlement can rest on.
          \cup {<<p, DestinationMovedIt>> :
                  p \in {q \in written :
                           IF status \in {"merged", "conflicted"}
                           THEN ~DestOnBaseAt(from, to, q)
                           ELSE DestMovedAt(to, q)}}
          \* Two shapes of the same entitlement, because a wholesale copy and a
          \* merge do not consult the same thing. A merge measured against a
          \* base, so the base has to be one both homes passed through. A
          \* wholesale copy consulted NO base at all -- it replaced the
          \* destination outright -- so its entitlement is the CHM-9 question:
          \* are the bytes it is about to destroy bytes this source ever held?
          \* Stating only the merge form is what let a fast-forward from a home
          \* that had never seen the work look entitled.
          \cup {<<p, BaseNeverShared>> :
                  p \in {q \in written :
                           IF status \in {"merged", "conflicted"}
                           THEN ~SharedBaseAt(from, to, q)
                           ELSE <<from, q, sync_body[to][q]>> \notin sync_history}}
          \* Conditioned on the reported STATUS, not on the conflict set: a
          \* wholesale copy never ran a merge, so the paths a merge would have
          \* found irreconcilable are not something it declared and then wrote
          \* through. Conflating the two made a provenance defect show up as a
          \* conflict-atomicity one.
          \cup {<<p, UnitWasConflicted>> :
                  p \in (IF status = "conflicted" THEN written ELSE {})}
  /\ UNCHANGED sync_gone
  /\ UNCHANGED unit_vars
  /\ UNCHANGED home_vars

\* @command TearDownWorktreeHome
\* @result HomeCloseOutVerdict
\* @port Operator.remove_worktree
\* Removing a ticket worktree removes its Skill Manager home with it. Under the
\* gate this is enabled only when `home close-out` found nothing to lose;
\* without the gate it is always enabled, which is the whole of the pre-epic
\* behavior -- `rm -rf` reports exactly the same thing whether the directory
\* held work or not.
\*
\* The snapshot is taken BEFORE the home stops existing, because a teardown
\* destroys the evidence and the work in the same step, and an invariant with
\* nothing to compare against finds nothing.
TearDownWorktreeHome ==
  /\ WorktreeHome \in SyncHomes
  /\ ProjectHome \in SyncHomes
  /\ ~sync_gone.torn_down
  /\ (GatesTeardown => CloseOutIsSafe)
  /\ sync_gone' = [torn_down |-> TRUE, body |-> sync_body[WorktreeHome]]
  /\ UNCHANGED << sync_body, sync_record, sync_history, sync_unsound,
                  sync_git, sync_committed >>
  /\ UNCHANGED unit_vars
  /\ UNCHANGED home_vars

\* @command RefreshProjectChildHomeFromStore
\* @result ChildHomeResult
\* @port SkillManagerCli.resolve_project_child_home
\* `skill-manager project resolve` / `project sync` — the OTHER writer into the
\* project home, modelled here in the tier slice rather than in the unit slice
\* because the defect it carries is not about one home at all. It is about the
\* ORDER two commands touch one home.
\*
\* The unit slice already forbids destroying an agent's edit, and it always did.
\* What it cannot see is this: `home sync --merge` from a worktree leaves the
\* project home holding bytes that are pristine BY ITS OWN RECORD -- the merge
\* rewrote that record -- while being a wholesale copy of no store on earth. A
\* refresh that asks only "does the destination still hold what its record says"
\* reads that as a licence and deletes the ticket's work. Every command in the
\* sequence reports success. Ticket CHM-15.
\*
\* Same entitlement as ReconcileHomeTiers' wholesale-copy branch, and stated the
\* same way: the destroyed bytes must be bytes this source can be shown to have
\* held. Here the source is the parent store, whose bytes are InstalledBytes, so
\* the showing is that the destination's record names the store side -- its own
\* clone-time baseline, or the root tier -- and not a worktree or a merge.
RefreshProjectChildHomeFromStore ==
  /\ ProjectHome \in SyncLive
  \* GitStateUnmoved is ANDed in rather than folded into the origin test because
  \* it is a different showing of the same rule: `copyUnit` reads the whole of
  \* Disposal, so a project home standing on a commit the store never held is not
  \* refreshable however its origin reads. Without it the downward writer would
  \* answer issue #29 differently from `home sync`, which is CHM-15's shape.
  /\ LET entitled == /\ GitStateUnmoved(ProjectHome)
                     /\ \/ ~SourceAwareChildRefresh
                        \/ sync_record[ProjectHome].origin \in StoreSideOrigins
         stale    == {p \in SyncPaths : sync_body[ProjectHome][p] # InstalledBytes}
         written  == IF entitled THEN stale ELSE {}
         newBody  == [p \in SyncPaths |->
                        IF p \in written THEN InstalledBytes ELSE sync_body[ProjectHome][p]]
     IN
     /\ written # {}
     /\ sync_body' = [sync_body EXCEPT ![ProjectHome] = newBody]
     /\ sync_record' = [sync_record EXCEPT ![ProjectHome] =
                          [entries |-> [p \in SyncPaths |-> InstalledBytes],
                           origin  |-> StoreOrigin,
                           git_head |-> {InstalledBytes},
                           git_base |-> {InstalledBytes}]]
     /\ sync_git' = [sync_git EXCEPT ![ProjectHome] = InitialGitState]
     /\ UNCHANGED sync_committed
     /\ sync_history' = sync_history \cup {<<ProjectHome, p, newBody[p]>> : p \in SyncPaths}
     \* The write log, computed from the PRE-state by the model rather than by
     \* the policy: a refresh that writes anyway still records that it was not
     \* entitled to. Exactly the CHM-9 question the wholesale-copy branch asks,
     \* pointed at the store instead of at another tier.
     /\ sync_unsound' = sync_unsound
          \cup {<<p, BaseNeverShared>> :
                  p \in {q \in written :
                           sync_record[ProjectHome].origin \notin StoreSideOrigins}}
  /\ UNCHANGED sync_gone
  /\ UNCHANGED unit_vars
  /\ UNCHANGED home_vars

SyncNext ==
  \/ \E h \in SyncHomes, p \in SyncPaths: EditUnitInHomeTier(h, p)
  \/ \E h \in SyncHomes: ChurnGitBookkeepingInHomeTier(h)
  \/ \E h \in SyncHomes: CommitInHomeTier(h)
  \/ \E from \in SyncHomes, to \in SyncHomes, m \in BOOLEAN:
       ReconcileHomeTiers(from, to, m)
  \/ RefreshProjectChildHomeFromStore
  \/ TearDownWorktreeHome

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
\* A third spec was added for the sync slice on the same measured grounds. Its
\* variables are disjoint from both existing slices and no invariant crosses
\* them, so a combined Next would multiply 250 x 706 x (the sync slice) and
\* check nothing that the three do not already check separately.
Spec == Init /\ [][Next]_vars

HomeSpec == Init /\ [][HomeNext]_vars

SyncSpec == Init /\ [][SyncNext]_vars

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

-----------------------------------------------------------------------------
\* Three-tier reconciliation invariants
\*
\* Every guarantee the epic had built before this slice --
\* AgentEditedChildUnitsAreNeverDestroyed, the hold-back, the atomic swap --
\* stopped at the boundary of ONE home, and the boundary is where the work was
\* being lost. These four extend that reach across the boundary. They do not
\* restate AgentEditedChildUnitsAreNeverDestroyed: that invariant is about one
\* home's units and a materializer refreshing them from a store, and its
\* destroyer is a refresh policy. These are about two homes, per path, and their
\* destroyers are a merge base, a provenance rule and a teardown -- none of
\* which the unit-level slice has an action for.
\*
\* All four are stated over sync_unsound or sync_gone rather than over a
\* report, and tla-spec-dev issue #127 is why. A reporting invariant explored
\* the entire reachable state space and found nothing while a record was being
\* destroyed, because after the destruction the antecedent went false too. The
\* same trap is live here in a sharper form: after a reconciliation overwrites a
\* destination it also rewrites that destination's record, so the home is
\* PERFECTLY COHERENT afterwards and every consistency check passes over the
\* wreckage. EveryDivergenceFromARecordIsAnEditMadeInThatHome below is that
\* demonstration, kept as a config that must never fail.

\* @invariant AReconciliationOnlyWritesPathsTheDestinationDidNotMove
\* NO EDIT LOSS. Every path a reconciliation wrote was one the destination had
\* not moved away from its own recorded baseline. This is the "held back or
\* merged, never lost" claim stated as a fact about writes: a destination that
\* HAD moved a path is entitled to keep it, whatever the source has.
\*
\* Stated over the sync_unsound write log because entitlement is only observable
\* at the instant of the write. Afterwards the overwritten bytes and the bytes that
\* were always there are the same bytes, and the destination's record has been
\* rewritten to agree with both.
AReconciliationOnlyWritesPathsTheDestinationDidNotMove ==
  \A p \in SyncPaths: <<p, DestinationMovedIt>> \notin sync_unsound

\* @invariant AReconciliationOnlyWritesAgainstABaselineBothHomesPassedThrough
\* MERGE SOUNDNESS. A three-way merge folds in only changes ONE side made, and
\* "the source moved this and the destination did not" is only true relative to
\* a state the source actually passed through. A baseline the destination
\* recorded from some THIRD home is not a common ancestor of these two: measured
\* against it the source looks like the only side that moved, the third home's
\* work is taken away, and no conflict is ever reported.
\*
\* This is the invariant ChildHomeMaterializer failed in two independent ways --
\* mergeBase preferring the destination's record (MergeBasePolicy =
\* DESTINATION_RECORD) and reconcileKind protecting merge results only
\* (ReconcileProvenance = MERGE_KIND_ONLY). Both were fixed together, under one
\* rule stated in ChildHomeMaterializer's javadoc: a reconciliation may destroy
\* bytes only where it can show the SOURCE passed through them. The two configs
\* that model the old rules -- External_regression_mergebase.cfg and
\* External_regression_ffprovenance.cfg -- must keep producing counterexamples,
\* because they describe what the code no longer does. Tickets CHM-9 / CHM-10.
AReconciliationOnlyWritesAgainstABaselineBothHomesPassedThrough ==
  \A p \in SyncPaths: <<p, BaseNeverShared>> \notin sync_unsound

\* @invariant AConflictedUnitIsNeverPartiallyWritten
\* CONFLICT ATOMICITY. A unit with any conflicted path writes NOTHING -- not the
\* paths that would have merged cleanly. The destination therefore stays a
\* coherent version of something rather than half of two, and both versions
\* still exist for whoever resolves it.
\*
\* Deliberately not derivable from the loss invariant above: a partial write
\* takes only paths the destination had not moved, so it destroys no edit and
\* AReconciliationOnlyWritesPathsTheDestinationDidNotMove is satisfied
\* throughout. What it destroys is the unit's coherence, which is a different
\* thing and needs its own reason recorded. See External_sync_guard_conflict.cfg.
AConflictedUnitIsNeverPartiallyWritten ==
  \A p \in SyncPaths: <<p, UnitWasConflicted>> \notin sync_unsound

\* @invariant NoHomeIsTornDownWhileItHoldsUniqueWork
\* THE CLOSE-OUT GATE. A worktree home is not removed while it holds content
\* that exists in no other live home. This is the failure the whole slice exists
\* for and the only one that is completely silent: the teardown succeeds, and a
\* directory that held work and one that held nothing are deleted with exactly
\* the same message.
\*
\* sync_gone is a witness for the reason a teardown makes obvious -- it destroys
\* the work and the evidence of the work in one step, so the evidence has to be
\* taken before the step. Comparing against the home afterwards compares against
\* nothing.
\*
\* "Exists in another live home" is stated over sync_history rather than over
\* sync_body, and TLC is the reason -- the same shape of correction
\* WritesThroughOneHomeReachNoOtherHome needed. The body form produces a
\* counterexample at depth 5 that is not a defect: the worktree's edit reaches
\* the project, the project's own agent then edits that same path on top of it,
\* and the worktree is torn down still holding the older bytes. Nothing was
\* lost -- the project HAD the work and deliberately moved past it -- but by the
\* time of the teardown the bytes are no longer anywhere. What close-out
\* actually promises is that the work REACHED another tier, and a tier that has
\* held something is exactly what the history witness records.
NoHomeIsTornDownWhileItHoldsUniqueWork ==
  sync_gone.torn_down =>
    \A p \in SyncPaths:
      \E g \in SyncLive: <<g, p, sync_gone.body[p]>> \in sync_history

\* @invariant EveryDivergenceFromARecordIsAnEditMadeInThatHome
\* THE NEGATIVE RESULT, in its sharpest form yet. A home's bytes differ from its
\* own materialization record only where an agent edited that home.
\*
\* This is TRUE, and it is USELESS as a safety property, and both halves are the
\* point. Every destructive policy in this section satisfies it: an overwrite
\* rewrites the record along with the bytes, a prefer-source merge rewrites the
\* record along with the bytes, a partial conflicted write rewrites the record
\* along with the bytes. The destination is left perfectly self-consistent about
\* content that was chosen by nobody. It is carried as a config that must keep
\* returning "No error has been found" so that this stays written down: a
\* coherent home is not an intact one, and checking coherence is not checking
\* that nothing was lost.
\*
\* Guarded by `# NoBaseline` since CHM-12: a record that declines to claim a
\* path makes no statement to diverge from, and reading the absence of a claim
\* as a claim of "different" would turn the honest answer into a violation.
EveryDivergenceFromARecordIsAnEditMadeInThatHome ==
  \A h \in SyncHomes, p \in SyncPaths:
    /\ sync_record[h].entries[p] # NoBaseline
    /\ sync_body[h][p] # sync_record[h].entries[p]
    => sync_body[h][p] = h

\* @invariant ARecordClaimsNoBaselineTheDestinationNeverStoodOn
\* THE RECORD-WRITE RULE. A materialization record claims a byte as the baseline
\* two homes share only where BOTH of them have actually stood on it.
\*
\* This is the WRITE half of the rule whose READ half is
\* AReconciliationOnlyWritesAgainstABaselineBothHomesPassedThrough, and it needs
\* to be stated separately for the reason CHM-12 exists: a reconciliation can
\* read a perfectly sound base, write nothing it was not entitled to write, and
\* still record a claim it had no right to make. Every write in that pass is
\* entitled; the loss happens in the NEXT pass, in the other direction, when
\* that record becomes the base and the algebra reads `d = b` as "the
\* destination is standing on the base".
\*
\* Stated over sync_history rather than over sync_body because a record is a
\* claim about a state the two homes SHARED, not about the state they are in
\* now -- both may legitimately have moved on since. What may never be true is
\* that a home is on record as having shared bytes it has never held.
\*
\* The `origin \in SyncHomes` guard is not a weakening: AdoptedOrigin means "my
\* own clone-time baseline, whoever I was copied from", which names no pair, and
\* MergedOrigin means "a merge result" -- in both cases only the destination's
\* own half of the claim is checkable, and it is checked.
\* See External_regression_recordsourcetree.cfg, which models the old rule and
\* must keep producing a counterexample.
ARecordClaimsNoBaselineTheDestinationNeverStoodOn ==
  \A h \in SyncHomes, p \in SyncPaths:
    LET claimed == sync_record[h].entries[p] IN
    claimed # NoBaseline =>
      /\ <<h, p, claimed>> \in sync_history
      /\ (sync_record[h].origin \in SyncHomes =>
            <<sync_record[h].origin, p, claimed>> \in sync_history)

\* @invariant GitBookkeepingIsNeverReportedAsWork
\* ISSUE #29, THE FALSE POSITIVE HALF. Two homes that agree on every byte outside
\* .git and stand on the same commit have nothing to reconcile, whatever git has
\* done to its own bookkeeping in either of them.
\*
\* Stated over SyncStatusOf rather than over a variable because "held back" is
\* not a state, it is what the command says -- and what it says is the defect:
\* the bytes were never in danger, the unit was simply unreconcilable forever, so
\* `home close-out` could never clear and the worktree could never be torn down.
\* An invariant about sync_body alone cannot see that at all.
\*
\* NOT witness-dependent: it reads sync_git, which is live state, and its
\* antecedent is about two homes agreeing rather than about anything that may
\* have been destroyed. WHOLE_TREE_DIGEST violates it at depth 2 -- see
\* External_regression_wholetreegit.cfg.
GitBookkeepingIsNeverReportedAsWork ==
  \A from \in SyncLive, to \in SyncLive:
    (/\ from # to
     /\ \A p \in SyncPaths: AgreeAt(from, to, p)
     /\ sync_git[from].head = sync_git[to].head
     /\ sync_git[to].head = sync_record[to].git_head)
    => SyncStatusOf(from, to, FALSE) = "unchanged"

\* @invariant CommittedWorkIsNeverDestroyed
\* ISSUE #29, THE DATA-LOSS HALF -- the one the obvious fix breaks.
\*
\* A commit that has ever been made in a home must still be the history of some
\* home that still exists. It can travel: a wholesale copy from the home holding
\* it carries it to the destination, which is the return path. What may never
\* happen is that it stops being anywhere -- either because a reconciliation
\* replaced the .git that held it, or because a teardown removed the only home
\* that had it while the gate said there was nothing to lose.
\*
\* WITNESS-DEPENDENT, and this is the case the epic's sharpest negative result is
\* about. sync_committed survives the write that destroys the commit; sync_git
\* does not, because a reconciliation rewrites the head in the same step it
\* discards the objects, so afterwards the home is perfectly self-consistent
\* about a history that no longer exists. GUARD: External_sync_guard_skipgit.cfg
\* runs SKIP_GIT with this invariant REMOVED and must keep returning "No error
\* has been found" -- if it ever starts failing, the remaining invariants have
\* been strengthened into something else and the record of why this witness
\* exists is stale.
CommittedWorkIsNeverDestroyed ==
  \A c \in sync_committed:
    \E h \in SyncLive: c \in sync_git[h].head

\* @invariant NoWorktreeEditEverReachesTheRootHome
\* REACHABILITY PROBE -- THIS ONE MUST FAIL.
\*
\* Round-trip is not a safety property, so it cannot be stated as one. It is
\* stated as its negation instead: if TLC can find no trace in which bytes an
\* agent wrote in the worktree end up in the root home, then the return path
\* this whole slice exists to build does not exist, and every safety invariant
\* above is being satisfied by a mechanism that simply never moves anything.
\* Over-holding-back is safe and worthless, and this is the only check in the
\* directory that would notice.
\*
\* Its counterexample IS the round trip: the shortest trace TLC prints for it is
\* the demonstration that worktree -> project -> root delivers the edit intact.
NoWorktreeEditEverReachesTheRootHome ==
  RootHome \in SyncHomes =>
    \A p \in SyncPaths: sync_body[RootHome][p] # WorktreeHome

=============================================================================
