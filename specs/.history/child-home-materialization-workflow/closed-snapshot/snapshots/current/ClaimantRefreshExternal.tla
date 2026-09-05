----------------------- MODULE ClaimantRefreshExternal -----------------------
\* ISSUE-166 public revision-coherence slice.
\*
\* The carried-forward SkillManager.tla remains authoritative for every
\* accepted program behavior. This isolated external slice adds only the
\* public named-sync/automatic-claimant-refresh observation introduced by
\* ISSUE-166, so pending child-home tickets are not represented as current.
\*
\* Refinement record: this bounded observation composes the accepted
\* SkillManager.SyncUnit -> SkillManager.SyncClaimingProjectChildHomes path
\* and adds revision identity at that seam. It is not a replacement external
\* model for the planned whole-program migration.

EXTENDS Integers

VARIABLES
  named_selected_revision,
  named_checkout_revision,
  named_installed_revision,
  named_units_lock_revision,
  named_registration_revision,
  named_child_revision,
  named_trunk_revision,
  named_fetches,
  named_receipt

vars == << named_selected_revision, named_checkout_revision,
           named_installed_revision, named_units_lock_revision,
           named_registration_revision, named_child_revision,
           named_trunk_revision, named_fetches, named_receipt >>

NamedSyncRevisions == {"A", "B"}
NamedSyncReceipts == {"pending", "ok", "partial"}

Init ==
  /\ named_selected_revision = "A"
  /\ named_checkout_revision = "A"
  /\ named_installed_revision = "A"
  /\ named_units_lock_revision = "A"
  /\ named_registration_revision = "A"
  /\ named_child_revision = "A"
  /\ named_trunk_revision = "B"
  /\ named_fetches = 0
  /\ named_receipt = "pending"

\* @invariant TypeOK
TypeOK ==
  /\ named_selected_revision \in NamedSyncRevisions
  /\ named_checkout_revision \in NamedSyncRevisions
  /\ named_installed_revision \in NamedSyncRevisions
  /\ named_units_lock_revision \in NamedSyncRevisions
  /\ named_registration_revision \in NamedSyncRevisions
  /\ named_child_revision \in NamedSyncRevisions
  /\ named_trunk_revision \in NamedSyncRevisions
  /\ named_fetches \in 0..1
  /\ named_receipt \in NamedSyncReceipts

\* @command PublicNamedSyncPinnedRevision
\* @result NamedSyncOutcome
\* @port SkillManagerCli.sync_named_unit
PublicNamedSyncPinnedRevision ==
  /\ named_receipt = "pending"
  /\ named_child_revision' = named_checkout_revision
  /\ named_receipt' = "ok"
  /\ UNCHANGED << named_selected_revision, named_checkout_revision,
                  named_installed_revision, named_units_lock_revision,
                  named_registration_revision, named_trunk_revision,
                  named_fetches >>

\* Regression-only behavior: the automatic follow-up independently pulls B,
\* mutating checkout/install/child while the outer lock and registration say A.
\* @command PublicNamedSyncWithIndependentTrunkPull
\* @result NamedSyncOutcome
\* @port SkillManagerCli.sync_named_unit_regression
PublicNamedSyncWithIndependentTrunkPull ==
  /\ named_receipt = "pending"
  /\ named_checkout_revision' = named_trunk_revision
  /\ named_installed_revision' = named_trunk_revision
  /\ named_child_revision' = named_trunk_revision
  /\ named_fetches' = 1
  /\ named_receipt' = "ok"
  /\ UNCHANGED << named_selected_revision, named_units_lock_revision,
                  named_registration_revision, named_trunk_revision >>

NamedSyncNext == PublicNamedSyncPinnedRevision

NamedSyncRegressionNext == PublicNamedSyncWithIndependentTrunkPull

NamedSyncSpec == Init /\ [][NamedSyncNext]_vars

NamedSyncRegressionSpec == Init /\ [][NamedSyncRegressionNext]_vars

\* @invariant SuccessfulNamedSyncHasOneSelectedRevision
SuccessfulNamedSyncHasOneSelectedRevision ==
  named_receipt = "ok" =>
    /\ named_checkout_revision = named_selected_revision
    /\ named_installed_revision = named_selected_revision
    /\ named_units_lock_revision = named_selected_revision
    /\ named_registration_revision = named_selected_revision
    /\ named_child_revision = named_selected_revision

\* @invariant AutomaticClaimantRefreshPerformsNoSourceSelection
AutomaticClaimantRefreshPerformsNoSourceSelection == named_fetches = 0

=============================================================================
