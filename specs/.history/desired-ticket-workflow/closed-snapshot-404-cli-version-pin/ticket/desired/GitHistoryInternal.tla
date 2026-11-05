--------------------------- MODULE GitHistoryInternal ---------------------------
\* #390 / #370 policy slice: how `home close-out` and `home sync` judge the .git
\* half of a git-backed unit shared by a worktree home (Src) and the project
\* home it closes into (Dst).
\*
\* WHY A SIBLING OF External AND NOT A DIMENSION INSIDE IT. External decides
\* .git by RECORD baselines (AllRefs(to) = sync_record[to].git_base) over
\* abstract ref sets with no order. The #390 defects are about what those sets
\* leave out: ANCESTRY (which side is ahead) and PUBLICATION (whether a ref's
\* commits already exist on the unit's remote). ChildHomeMaterializer answers
\* both by asking git, before any record is read (settledWithoutARecord,
\* gitDestIsBehind). Adding a trunk order and a remote to External multiplies
\* its already budget-limited sync slice; this module holds them alone.
\*
\* THE ABSTRACTION.
\*   Trunk commits are 0..MaxTrunk on one line, so "ancestor of" is <=.
\*   Side commits (SideCommits) are branch tips that are never on the trunk: a
\*   `skill/<ticket>-<unit>` publish branch whose PR was REBASE-merged lands on
\*   the trunk as a NEW commit, so the tip itself exists only on its branch.
\*   A home's ref set is: head (checked out, on the trunk), local (side
\*   branches), tracked (remote-tracking side refs), ttrunk (origin/main).
\*   Every trunk commit <= max(head, ttrunk) is reachable in that home.
\*   The remote holds rtrunk and the published side branches.
\*
\* WHAT THIS MODULE DOES NOT RESTATE.
\*   External.CommittedWorkIsNeverDestroyed -- the record-based take/conflict
\*     algebra. Here the same property is stated over ancestry and publication
\*     (UnpublishedWorkIsNeverDestroyed), which External cannot express.
\*   External.GitBookkeepingIsNeverReportedAsWork -- index/reflog churn.
\*
\* NO CASE ADAPTERS. The @command annotations name the production path; the
\* executable pins are HomeCloseOutPublishedRefsTest, HomeSyncGitUnitTest and
\* the home-sync / ticket-lifecycle graphs.
\*
\* THE REGRESSION CONFIGURATIONS ARE EXPECTED TO FAIL. Each flips ONE policy
\* constant back to the behaviour shipped before #390 and must keep producing a
\* counterexample to its target invariant.
\*
\* THE RECORD ROUTE (SM-390B). When git cannot settle a pair, the destination's
\* own materialization record decides whether it is disposable: it is
\* "untouched" when its history still matches what was recorded. Two policies
\* govern that and both regressed the same way #390's git route did:
\*   RecordRefs      -- what counts as the history moving. "ALL_REFS_DIGEST"
\*                      (pre-fix) reads a `git fetch` as local work;
\*                      "PUBLISHED_EXEMPT" asks whether HEAD is the recorded
\*                      revision and every local branch is published.
\*   DisposalGuard   -- "NONE" (pre-fix) lets an untouched record license
\*                      replacing a destination that is AHEAD of the source;
\*                      "NOT_WHEN_AHEAD" never does.
\*
\* HOW TO RUN:
\*   run_tlc.sh GitHistoryInternal.tla GitHistoryInternal.cfg             -> No error
\*   run_tlc.sh GitHistoryInternal.tla GitHistoryInternal_regression_*.cfg -> MUST FAIL

EXTENDS Naturals, FiniteSets

CONSTANTS
  MaxTrunk,         \* highest trunk commit the remote can reach
  SideCommits,      \* branch tips that are never on the trunk
  RefContainment,   \* "ALL_REFS" (pre-#390) | "UNPUBLISHED_REFS"
  FastForward,      \* "ANY_SOURCE_REF" (pre-#390) | "HEAD_ANCESTOR"
  RemedyPolicy,     \* "SYNC_REGARDLESS" (pre-#390) | "NEVER_BACKWARDS"
  RecordRefs,       \* "ALL_REFS_DIGEST" (pre-#390) | "PUBLISHED_EXEMPT"
  DisposalGuard     \* "NONE" (pre-#390) | "NOT_WHEN_AHEAD"

ASSUME RefContainment \in {"ALL_REFS", "UNPUBLISHED_REFS"}
ASSUME FastForward \in {"ANY_SOURCE_REF", "HEAD_ANCESTOR"}
ASSUME RemedyPolicy \in {"SYNC_REGARDLESS", "NEVER_BACKWARDS"}
ASSUME RecordRefs \in {"ALL_REFS_DIGEST", "PUBLISHED_EXEMPT"}
ASSUME DisposalGuard \in {"NONE", "NOT_WHEN_AHEAD"}

Homes == {"Src", "Dst"}
Trunk == 0..MaxTrunk

VARIABLES
  head,       \* [Homes -> Trunk]          the checked-out commit
  local,      \* [Homes -> SUBSET Side]    local side branches
  tracked,    \* [Homes -> SUBSET Side]    remote-tracking side refs
  ttrunk,     \* [Homes -> Trunk]          origin/main as that home last fetched it
  rtrunk,     \* Trunk                     the remote's main
  published,  \* SUBSET Side               side branches on the remote
  created,    \* SUBSET Side               every side commit ever made
  gone,       \* BOOLEAN                   Src was torn down by close-out
  rewound,    \* BOOLEAN                   a sync ever moved Dst's head backwards
  rec,        \* Dst's materialization record: the refs it held when written
  dstClean    \* BOOLEAN, fixed: Dst's worktree has nothing `git status` reports.
              \* False models a recorded-but-untracked file, which the git
              \* route declines on and the record route still reads as untouched.

vars == << head, local, tracked, ttrunk, rtrunk, published, created, gone, rewound, rec,
          dstClean >>

\* ------------------------------------------------------------- reachability

ReachTrunk(h) == IF head[h] >= ttrunk[h] THEN head[h] ELSE ttrunk[h]
ReachSide(h) == local[h] \cup tracked[h]
Reaches(h, c) == IF h = "Src" /\ gone THEN FALSE ELSE c \in ReachSide(h)

\* A local ref is published when one of the SAME home's remote-tracking refs
\* contains it -- GitOps.isPublished, offline evidence, no fetch.
UnpublishedLocal(h) == local[h] \ tracked[h]

\* What `historyContainedIn(h, other)` requires `other` to hold.
\*   ALL_REFS         -- every ref h has: head, locals, remote-tracking refs.
\*   UNPUBLISHED_REFS -- head, and only the locals no remote-tracking ref of h
\*                       already contains (#390).
SideWork(h) ==
  IF RefContainment = "ALL_REFS" THEN ReachSide(h) ELSE UnpublishedLocal(h)
TrunkWork(h) ==
  IF RefContainment = "ALL_REFS" THEN ReachTrunk(h) ELSE head[h]

ContainedIn(h, other) ==
  /\ TrunkWork(h) <= ReachTrunk(other)
  /\ SideWork(h) \subseteq ReachSide(other)

\* @command ChildHomeMaterializer.gitSourceIsBehind
SourceIsBehind == ContainedIn("Src", "Dst")

\* @command ChildHomeMaterializer.gitDestIsBehind
\*   ANY_SOURCE_REF -- Dst's head need only be REACHABLE in Src; a Src on an
\*                     older head whose origin/main names Dst's head qualifies.
\*   HEAD_ANCESTOR  -- Dst's head must also be an ancestor of Src's (#390).
DestIsBehind ==
  /\ dstClean
  /\ ContainedIn("Dst", "Src")
  /\ (FastForward = "HEAD_ANCESTOR" => head["Dst"] <= head["Src"])

DestAhead == head["Src"] < head["Dst"]

DstRefs == [head |-> head["Dst"], local |-> local["Dst"],
            tracked |-> tracked["Dst"], ttrunk |-> ttrunk["Dst"]]

\* @command ChildHomeMaterializer.gitHistoryMovedOn / onlyPublishedRefsBesideHead
RecordUntouched ==
  \/ DstRefs = [f \in {"head", "local", "tracked", "ttrunk"} |-> rec[f]]
  \/ /\ RecordRefs = "PUBLISHED_EXEMPT"
     /\ head["Dst"] = rec.head
     /\ UnpublishedLocal("Dst") = {}

\* @command ChildHomeMaterializer.Disposal.disposable
\* rec.about: the record was written by a reconcile FROM Src (or at clone,
\* where Src was copied from these bytes) -- describesSource /
\* sourceHeldTheseBytes. A record a plain `sync` wrote names the unit's remote
\* and licenses nothing about Src.
RecordLicensesReplacement ==
  /\ rec.about
  /\ RecordUntouched
  /\ (DisposalGuard = "NOT_WHEN_AHEAD" => ~DestAhead)

\* @command HomeCloseOut.inspect (a dry-run `home sync --merge`)
Verdict ==
  CASE head["Src"] = head["Dst"] /\ local["Src"] = local["Dst"]
         /\ tracked["Src"] = tracked["Dst"] /\ ttrunk["Src"] = ttrunk["Dst"]
                                -> "unchanged"
    [] SourceIsBehind           -> "unchanged"
    [] DestIsBehind             -> "updated"
    [] RecordLicensesReplacement -> "updated"
    [] OTHER                    -> "conflicted"

Safe == Verdict = "unchanged"

\* @command HomeCloseOut.remedyFor
Remedy ==
  IF Safe THEN "none"
  ELSE IF RemedyPolicy = "NEVER_BACKWARDS" /\ DestAhead THEN "publish"
  ELSE "sync"

\* ------------------------------------------------------------------ actions

Init ==
  /\ head = [h \in Homes |-> 0]
  /\ local = [h \in Homes |-> {}]
  /\ tracked = [h \in Homes |-> {}]
  /\ ttrunk = [h \in Homes |-> 0]
  /\ rtrunk = 0
  /\ published = {}
  /\ created = {}
  /\ gone = FALSE
  /\ rewound = FALSE
  /\ rec = [head |-> 0, local |-> {}, tracked |-> {}, ttrunk |-> 0, about |-> TRUE]
  /\ dstClean \in BOOLEAN

Live(h) == h = "Dst" \/ ~gone

\* An agent commits on a new side branch in h.
CommitOnSide(h, c) ==
  /\ Live(h)
  /\ c \notin created
  /\ local' = [local EXCEPT ![h] = @ \cup {c}]
  /\ created' = created \cup {c}
  /\ UNCHANGED << head, tracked, ttrunk, rtrunk, published, gone, rewound, rec >>

\* @command skill-manager unit publish -- pushes the branch, which also
\* records the remote-tracking ref in the publishing home.
Publish(h, c) ==
  /\ Live(h)
  /\ c \in local[h]
  /\ published' = published \cup {c}
  /\ tracked' = [tracked EXCEPT ![h] = @ \cup {c}]
  /\ UNCHANGED << head, local, ttrunk, rtrunk, created, gone, rewound, rec >>

\* The remote's main moves on: another PR, or a publish branch rebase-merged
\* (its tip stays off the trunk).
AdvanceRemote ==
  /\ rtrunk < MaxTrunk
  /\ rtrunk' = rtrunk + 1
  /\ UNCHANGED << head, local, tracked, ttrunk, published, created, gone, rewound, rec >>

\* `git fetch` of every branch: remote-tracking refs move, head does not.
Fetch(h) ==
  /\ Live(h)
  /\ ttrunk' = [ttrunk EXCEPT ![h] = rtrunk]
  /\ tracked' = [tracked EXCEPT ![h] = @ \cup published]
  /\ UNCHANGED << head, local, rtrunk, published, created, gone, rewound, rec >>

\* `skt sync` / a single-branch pull: head and origin/main move to the tip.
\* A sync re-records the destination's baseline (clone-time records are
\* written the same way).
PullTrunk(h) ==
  /\ Live(h)
  /\ head' = [head EXCEPT ![h] = rtrunk]
  /\ ttrunk' = [ttrunk EXCEPT ![h] = rtrunk]
  /\ rec' = IF h = "Dst"
            THEN [head |-> rtrunk, local |-> local["Dst"],
                  tracked |-> tracked["Dst"], ttrunk |-> rtrunk, about |-> FALSE]
            ELSE rec
  /\ UNCHANGED << local, tracked, rtrunk, published, created, gone, rewound >>

\* An operator checks out an older revision in Src (`git reset --hard A`), the
\* step that left a worktree copy behind a destination it was cloned from.
CheckoutOlder ==
  /\ ~gone
  /\ head["Src"] > 0
  /\ head' = [head EXCEPT !["Src"] = @ - 1]
  /\ UNCHANGED << local, tracked, ttrunk, rtrunk, published, created, gone, rewound, rec >>

\* @command skill-manager home sync --from Src --to Dst (applied)
\* An "updated" verdict replaces Dst's copy, .git included, with Src's.
SyncApply ==
  /\ ~gone
  /\ Verdict = "updated"
  /\ rewound' = (rewound \/ head["Src"] < head["Dst"])
  /\ head' = [head EXCEPT !["Dst"] = head["Src"]]
  /\ local' = [local EXCEPT !["Dst"] = local["Src"]]
  /\ tracked' = [tracked EXCEPT !["Dst"] = tracked["Src"]]
  /\ ttrunk' = [ttrunk EXCEPT !["Dst"] = ttrunk["Src"]]
  /\ rec' = [head |-> head["Src"], local |-> local["Src"],
             tracked |-> tracked["Src"], ttrunk |-> ttrunk["Src"], about |-> TRUE]
  /\ UNCHANGED << rtrunk, published, created, gone >>

\* @command wt close / skt ticket close -- removal only on a safe verdict.
CloseOut ==
  /\ ~gone
  /\ Safe
  /\ gone' = TRUE
  /\ UNCHANGED << head, local, tracked, ttrunk, rtrunk, published, created, rewound, rec >>

Next ==
  \/ \E h \in Homes, c \in SideCommits : CommitOnSide(h, c) \/ Publish(h, c)
  \/ AdvanceRemote
  \/ \E h \in Homes : Fetch(h) \/ PullTrunk(h)
  \/ CheckoutOlder
  \/ SyncApply
  \/ CloseOut

Spec == Init /\ [][Next /\ UNCHANGED dstClean]_vars

\* --------------------------------------------------------------- invariants

TypeOK ==
  /\ head \in [Homes -> Trunk]
  /\ local \in [Homes -> SUBSET SideCommits]
  /\ tracked \in [Homes -> SUBSET SideCommits]
  /\ ttrunk \in [Homes -> Trunk]
  /\ rtrunk \in Trunk
  /\ published \subseteq created
  /\ gone \in BOOLEAN
  /\ rewound \in BOOLEAN
  /\ rec \in [head : Trunk, local : SUBSET SideCommits,
             tracked : SUBSET SideCommits, ttrunk : Trunk, about : BOOLEAN]

\* @invariant UnpublishedWorkIsNeverDestroyed
\* Every side commit ever made is on the remote or still in a live home.
UnpublishedWorkIsNeverDestroyed ==
  \A c \in created : c \in published \/ \E h \in Homes : Reaches(h, c)

\* @invariant ASyncNeverMovesTheDestinationBackwards
ASyncNeverMovesTheDestinationBackwards == ~rewound

\* @invariant AnAncestorCopyWithNothingUnpublishedIsNeverBlocked
\* #390 bug 1 and #370: Src's head is at or behind Dst's, and everything Src
\* holds that is not already published, Dst holds too. Nothing would be lost,
\* so the gate must clear.
AnAncestorCopyWithNothingUnpublishedIsNeverBlocked ==
  (/\ ~gone
   /\ head["Src"] <= head["Dst"]
   /\ UnpublishedLocal("Src") \subseteq ReachSide("Dst"))
  => Safe

\* @invariant NoRemedySyncsTowardANewerDestination
\* #390 bug 2: a printed `home sync` never names a Src older than Dst.
NoRemedySyncsTowardANewerDestination ==
  (~gone /\ Remedy = "sync") => ~DestAhead

\* @invariant AFetchAloneNeverHoldsACopyBack
\* SM-390B: Dst still on its recorded head, every local branch published, and
\* not ahead of Src -- nothing of Dst's would be lost, so the record licenses
\* the replacement, whatever remote-tracking refs a fetch moved.
AFetchAloneNeverHoldsACopyBack ==
  (/\ ~gone
   /\ rec.about
   /\ head["Dst"] = rec.head
   /\ UnpublishedLocal("Dst") = {}
   /\ ~DestAhead)
  => Verdict # "conflicted"

\* ------------------------------------------------------------ reach probes
\* Healthy constants, run with `tlc2 -continue`: each MUST be violated, which
\* shows the invariants above were checked over states that matter rather
\* than passing because the model never got there.
ProbeNeverTornDownWithSideWork == ~(gone /\ created # {})
ProbeNeverSyncedAtAll == head["Dst"] = 0 \/ head["Src"] = 0 \/ ~DestIsBehind
ProbeRecordNeverLicenses ==
  ~(/\ ~gone /\ ~DestIsBehind /\ ~SourceIsBehind /\ RecordLicensesReplacement
    /\ DstRefs # [f \in {"head", "local", "tracked", "ttrunk"} |-> rec[f]])
ProbeNeverBlockedOnUnpublishedWork ==
  ~(~gone /\ UnpublishedLocal("Src") \ ReachSide("Dst") # {} /\ ~Safe)

=============================================================================
