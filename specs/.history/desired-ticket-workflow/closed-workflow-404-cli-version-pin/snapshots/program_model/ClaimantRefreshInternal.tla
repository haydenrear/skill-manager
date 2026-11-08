----------------------- MODULE ClaimantRefreshInternal -----------------------
\* ISSUE-166 internal policy slice.
\*
\* The accepted whole-program model remains SkillManager.tla, byte-for-byte.
\* This deliberately non-overlapping slice starts after public named sync has
\* selected immutable A in the parent store. It models only the automatic
\* claimant refresh decision; explicit `project sync` is outside this action
\* and retains its public trunk-pull policy.
\*
\* Refinement record: this bounded policy view strengthens the revision
\* identity intentionally abstracted by the accepted whole-program action
\* SkillManager.SyncClaimingProjectChildHomes. It does not replace that model.

EXTENDS Integers

VARIABLES
  claimant_selected_revision,
  claimant_parent_revision,
  claimant_trunk_revision,
  claimant_child_revision,
  claimant_fetches,
  claimant_phase

vars == << claimant_selected_revision, claimant_parent_revision,
           claimant_trunk_revision, claimant_child_revision,
           claimant_fetches, claimant_phase >>

ClaimantRevisions == {"A", "B"}
ClaimantPhases == {"selected", "complete"}

Init ==
  /\ claimant_selected_revision = "A"
  /\ claimant_parent_revision = "A"
  /\ claimant_trunk_revision = "B"
  /\ claimant_child_revision = "A"
  /\ claimant_fetches = 0
  /\ claimant_phase = "selected"

\* @invariant TypeOK
TypeOK ==
  /\ claimant_selected_revision \in ClaimantRevisions
  /\ claimant_parent_revision \in ClaimantRevisions
  /\ claimant_trunk_revision \in ClaimantRevisions
  /\ claimant_child_revision \in ClaimantRevisions
  /\ claimant_fetches \in 0..1
  /\ claimant_phase \in ClaimantPhases

\* @command ReconcileClaimingProjectFromSelectedParent
\* @result ClaimantRefreshOutcome
\* @port SkillManagerCli.sync_claiming_projects
ReconcileClaimingProjectFromSelectedParent ==
  /\ claimant_phase = "selected"
  /\ claimant_child_revision' = claimant_parent_revision
  /\ claimant_phase' = "complete"
  /\ UNCHANGED << claimant_selected_revision, claimant_parent_revision,
                  claimant_trunk_revision, claimant_fetches >>

\* Regression-only action: the pre-#166 automatic follow-up called the public
\* pull-by-default project sync and selected B after the outer command chose A.
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

ClaimantRefreshNext == ReconcileClaimingProjectFromSelectedParent

ClaimantRefreshRegressionNext == PullTrunkThenReconcileClaimingProject

ClaimantRefreshSpec == Init /\ [][ClaimantRefreshNext]_vars

ClaimantRefreshRegressionSpec == Init /\ [][ClaimantRefreshRegressionNext]_vars

\* @invariant AutomaticClaimantRefreshUsesSelectedParentRevision
AutomaticClaimantRefreshUsesSelectedParentRevision ==
  claimant_phase = "complete" =>
    /\ claimant_parent_revision = claimant_selected_revision
    /\ claimant_child_revision = claimant_selected_revision

\* @invariant AutomaticClaimantRefreshDoesNotPullTrunk
AutomaticClaimantRefreshDoesNotPullTrunk == claimant_fetches = 0

=============================================================================
