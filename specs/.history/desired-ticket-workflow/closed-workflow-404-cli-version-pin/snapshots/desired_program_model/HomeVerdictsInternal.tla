-------------------------- MODULE HomeVerdictsInternal --------------------------
\* one-home-one-verdict (#337) policy slice. Written after the epic closed, at
\* the owner's request (2026-09-14), so the epic's corrected behaviours and the
\* defects they replaced are held by a model and not only by prose and graphs.
\* The epic itself ran with no TLA workflow (dropped at schedule_revision 2);
\* nothing here was used to decide any of its tickets.
\*
\* WHY A SIBLING OF HomeIntegrityInternal AND NOT MORE GROUPS INSIDE IT.
\* HomeIntegrityInternal's healthy specification INTERLEAVES all of its groups,
\* so its state space is the PRODUCT of theirs (24,000 distinct states at
\* 833ae0d7). Six more groups of tens to thousands of states each would multiply
\* that past what TLC can check in a unit loop. Here Init picks ONE group per
\* behaviour (hv_group) and every action is guarded on it, so the state space is
\* the SUM of the groups. That loses no behaviour any invariant here can see:
\* the groups write DISJOINT variables, every invariant reads only its own
\* group's, and an interleaving of two groups projects onto one behaviour of
\* each. That disjointness has to keep holding -- an invariant that reads two
\* groups would only ever be checked here over states in which one of them sits
\* at Init, and would pass without having looked.
\*
\* WHAT THIS MODULE DOES NOT RESTATE (planning_rules.dual_representation_rule):
\*
\*   HomeIntegrityInternal.ADamagedHomeIsNeverReportedClean -- ONE observer
\*     reporting what is there. The epic's defect was TWO observers of one home
\*     disagreeing (verify 0 while repair reported damage, 50 of 61 homes at
\*     kickoff). The verdicts group is stated over the pair.
\*   HomeIntegrityInternal.AnObservationNeverRepairsWhatItObserved -- verify now
\*     RUNS HomeRepair.detect, which opens nothing for writing
\*     (HomeCommand.VerifyCmd, OHV-2). That invariant still governs it.
\*   External.WritesThroughOneHomeReachNoOtherHome -- cross-home reach at rest,
\*     over copies. It could not see DEF-OHV-011, and that is a BLIND record, not
\*     a restatement: External has no action in which a writer FOLLOWS a bin/cli
\*     symlink, and no forked writer the product does not control. The writes
\*     group models exactly that, and nothing else.
\*   External.AHomeIsAPureFunctionOfItsRoot (HomeSelfReferenceEncoding) -- that a
\*     home's surfaces CAN be encoded root-independently. The shims group is about
\*     the detector and the rewrite that leave, or fail to report, a
\*     half-anchored shim, which that constant cannot express.
\*
\* NO CASE ADAPTERS. As in HomeIntegrityInternal, the @command annotations name
\* the production path an action stands for; nothing in case_adapters.toml maps
\* them and nothing generates cases from this module. The executable pins are
\* the home-verdicts graph nodes and unit tests named beside each group.
\*
\* THE REGRESSION CONFIGURATIONS ARE EXPECTED TO FAIL (regression_config_rule).
\* Each flips ONE policy constant back to the shipped pre-fix behaviour, names
\* the historical defect and its origin commit in its header, and must keep
\* producing a counterexample to its target invariant. One that starts passing
\* is a defect in the invariant, not good news.
\*
\* HOW TO RUN (a scratch copy is recommended; TLC writes states/ into the cwd):
\*   specs/program_model/run_tlc.sh specs/program_model/HomeVerdictsInternal.tla \
\*       specs/program_model/HomeVerdictsInternal.cfg          -> No error
\*   ... HomeVerdictsInternal_regression_<name>.cfg             -> MUST FAIL
\*   ... HomeVerdictsInternal_finding_<name>.cfg                -> MUST FAIL
\*   tlc2 -continue -config HomeVerdictsInternal_probe_reach.cfg HomeVerdictsInternal.tla
\*                                                              -> 6 violations
\* Commands, state counts and outcomes are recorded in
\* results/epic-one-home-one-verdict/attribution/2026-09-14-epic-attribution.md.

EXTENDS Integers, FiniteSets

CONSTANTS
  \* verdicts group (OHV-2 #339, OHV-5 #343, OHV-1 #338)
  VerifyComposition,    \* "COMPOSES_REPAIR" | "OWN_WALK_ONLY" | "COMPOSES_WITHOUT_EXEMPTION"
  DetectorReach,        \* "AGENT_LINKS_AND_RECORDS" | "STORE_ONLY"
  DetectorSpellings,    \* "ALL_ALIASES" | "GIVEN_AND_REAL"
  VerdictStamp,         \* "STAMPED" | "UNSTAMPED"
  \* shims group (OHV-4 #341, DEF-OHV-001, DEF-OHV-180)
  ShimDetection,        \* "CONTENT" | "REWRITE_AVAILABLE"
  ShimRewriteScope,     \* "EVERY_LINE" | "TOKEN_MEANS_DONE"
  \* prune group (OHV-3 #340, #292, DEF-OHV-003)
  PruneRerecord,        \* "EXCLUDE_PROVEN_GONE" | "REBUILD_FROM_INDEX"
  TeardownReaping,      \* "REAP_PROVEN_ABSENT" | "LEAVE_ROWS" | "REAP_UNCONDITIONALLY"
  \* records group (OHV-3 (c), DEF-OHV-004)
  RecordVersionPolicy,  \* "RESTATE_AT_HEAD" | "HASH_ONLY"
  \* marketplace group (OHV-6 #352, DEF-OHV-005)
  MarketplaceMatch,     \* "IDENTITY_AND_PATH" | "SUBSTRING"
  ManifestIdentity,     \* "DERIVED" | "TRUSTED_FROM_DISK"
  MarketplaceRepair,    \* "REPORT_AND_FIX" | "NONE"
  \* writes group (OHV-9 #367, DEF-OHV-011)
  BinWritePolicy,       \* "DETACH_FOREIGN_LINKS" | "WRITE_THROUGH"
  DetachRestore,        \* "RESTORE" | "DETACH_ONLY"
  ForeignWriteCheck,    \* "STAT_AND_REFUSE" | "NONE"
  LauncherWrite         \* "UNLINK_THEN_WRITE" | "FOLLOW_LINK"

VARIABLES
  hv_group,

  \* verdicts
  v_phase,          \* planting damage, or both verdicts have been taken
  v_damage,         \* the damaged facts actually on disk
  v_sanctioned,     \* the foreign shim is a parent-store shim verify's walk sanctions
  v_repair,         \* the kinds `home repair --json` reported
  v_verify_exit,    \* `home verify`'s exit code
  v_build,          \* the build that ran both
  v_verdict_build,  \* the build the verdict names ("none" = names none)

  \* shims: one bin/cli shim, two running lines and one comment line
  s_export,         \* the export line: "token" | "literal" (own home) | "foreign"
  s_exec,           \* the exec line, same domain
  s_comment,        \* a # comment spelling this home
  s_last,           \* the last event that touched the shim
  s_settled,        \* the last write was this build's install or --fix
  s_copy_runs,      \* after `cp -R` of the home: whose tool the copy's shim runs
  s_observed,       \* `home repair` has judged the current bytes
  s_obs_frozen,     \* ... and reported FROZEN_HOME_PATH_IN_SHIM
  s_obs_foreign,    \* ... and reported FOREIGN_PATH_IN_SHIM

  \* prune: one unit, two ledger rows
  p_installed,
  p_link,           \* its agent projection's link, OUTSIDE the home
  p_shim,           \* its cli-shim output, INSIDE the home
  p_rows,           \* artifacts.lock.toml rows
  p_last_pruned,    \* the rows the last prune (or removal's reap) dropped
  p_removal,        \* "none" | "captured" (post-OHV-3) | "legacy" (pre-OHV-3)
  p_proven_absent,  \* the removal's captured evidence proved the link absent

  \* records: installed/<unit>.json against its checkout
  r_head, r_hash, r_version, r_synced,

  \* marketplace: this home H's registrations in its own agent configs
  m_phase, m_manifest, m_reg, m_enabled, m_sync_failed, m_fixed,

  \* writes: a child home whose bin/cli may link into its parent's
  w_phase, w_entry, w_parent, w_outcome, w_script, w_linked_at_install

vars == << hv_group,
           v_phase, v_damage, v_sanctioned, v_repair, v_verify_exit, v_build,
           v_verdict_build,
           s_export, s_exec, s_comment, s_last, s_settled, s_copy_runs,
           s_observed, s_obs_frozen, s_obs_foreign,
           p_installed, p_link, p_shim, p_rows, p_last_pruned, p_removal,
           p_proven_absent,
           r_head, r_hash, r_version, r_synced,
           m_phase, m_manifest, m_reg, m_enabled, m_sync_failed, m_fixed,
           w_phase, w_entry, w_parent, w_outcome, w_script,
           w_linked_at_install >>

verdict_vars == << v_phase, v_damage, v_sanctioned, v_repair, v_verify_exit,
                   v_build, v_verdict_build >>
shim_vars    == << s_export, s_exec, s_comment, s_last, s_settled,
                   s_copy_runs, s_observed, s_obs_frozen, s_obs_foreign >>
prune_vars   == << p_installed, p_link, p_shim, p_rows, p_last_pruned,
                   p_removal, p_proven_absent >>
record_vars  == << r_head, r_hash, r_version, r_synced >>
market_vars  == << m_phase, m_manifest, m_reg, m_enabled, m_sync_failed,
                   m_fixed >>
write_vars   == << w_phase, w_entry, w_parent, w_outcome, w_script,
                   w_linked_at_install >>

Groups == {"verdicts", "shims", "prune", "records", "marketplace", "writes"}

\* ---------------------------------------------------------------- domains ---
\* verdicts. ABSTRACT kinds: one representative per detector family whose
\* behaviour the epic changed. (The marketplace kinds are ordinary repair
\* findings here once OHV-6 made them repair kinds; their shapes are the
\* marketplace group's.)
Kinds == {"foreign_shim", "frozen_shim", "alias_spelled_shim",
          "dangling_agent_link", "orphaned_projection_record"}
ProjectionKinds == {"dangling_agent_link", "orphaned_projection_record"}
Builds == {"b1", "b2"}

Lines == {"token", "literal", "foreign"}
ShimEvents == {"none", "installed", "contaminated", "fixed", "copied"}
CopyRuns == {"n/a", "own", "source", "other"}

\* prune. The projection's output is a link OUTSIDE the home, which the ledger
\* never records; the cli-shim's is an in-home file.
Rows == {"projection", "shim"}
LinkStates == {"present", "dangling", "absent"}

Commits == {"c1", "c2"}
Manifest(c) == IF c = "c1" THEN "v1" ELSE "v2"

\* marketplace. id_H is this home's derived identity (skill-manager-<hash of
\* its path>), id_O another home's, and legacy the plain `skill-manager` name,
\* which every hashed identity CONTAINS as a substring.
MNames == {"id_H", "legacy", "id_O"}
MPaths == {"none", "H", "O"}

Entries == {"tool", "launcher"}
Scripts == {"none", "own_entry", "parent_path", "nothing"}

Init ==
  /\ hv_group \in Groups
  /\ v_phase = "planting"
  /\ v_damage = {}
  /\ v_sanctioned = FALSE
  /\ v_repair = {}
  /\ v_verify_exit = 0
  /\ v_build = "none"
  /\ v_verdict_build = "none"
  /\ s_export = "token"
  /\ s_exec = "token"
  /\ s_comment = FALSE
  /\ s_last = "none"
  /\ s_settled = TRUE
  /\ s_copy_runs = "n/a"
  /\ s_observed = FALSE
  /\ s_obs_frozen = FALSE
  /\ s_obs_foreign = FALSE
  /\ p_installed = TRUE
  /\ p_link = "present"
  /\ p_shim = "present"
  /\ p_rows = Rows
  /\ p_last_pruned = {}
  /\ p_removal = "none"
  /\ p_proven_absent = FALSE
  /\ r_head = "c1"
  /\ r_hash = "c1"
  /\ r_version = "v1"
  /\ r_synced = FALSE
  /\ m_phase = "clean"
  /\ m_manifest = "id_H"
  /\ m_reg = [n \in MNames |-> IF n = "id_H" THEN "H" ELSE "none"]
  /\ m_enabled = {"id_H"}
  /\ m_sync_failed = FALSE
  /\ m_fixed = FALSE
  /\ w_phase = "clean"
  /\ w_entry = [e \in Entries |-> "absent"]
  /\ w_parent = [e \in Entries |-> "parent_built"]
  /\ w_outcome = "none"
  /\ w_script = "none"
  /\ w_linked_at_install = FALSE

\* @invariant TypeOK
TypeOK ==
  /\ hv_group \in Groups
  /\ v_phase \in {"planting", "observed"}
  /\ v_damage \subseteq Kinds
  /\ v_sanctioned \in BOOLEAN
  /\ v_repair \subseteq Kinds
  /\ v_verify_exit \in {0, 1}
  /\ v_build \in Builds \cup {"none"}
  /\ v_verdict_build \in Builds \cup {"none"}
  /\ s_export \in Lines
  /\ s_exec \in Lines
  /\ s_comment \in BOOLEAN
  /\ s_last \in ShimEvents
  /\ s_settled \in BOOLEAN
  /\ s_copy_runs \in CopyRuns
  /\ s_observed \in BOOLEAN
  /\ s_obs_frozen \in BOOLEAN
  /\ s_obs_foreign \in BOOLEAN
  /\ p_installed \in BOOLEAN
  /\ p_link \in LinkStates
  /\ p_shim \in {"present", "absent"}
  /\ p_rows \subseteq Rows
  /\ p_last_pruned \subseteq Rows
  /\ p_removal \in {"none", "captured", "legacy"}
  /\ p_proven_absent \in BOOLEAN
  /\ r_head \in Commits
  /\ r_hash \in Commits
  /\ r_version \in {"v1", "v2"}
  /\ r_synced \in BOOLEAN
  /\ m_phase \in {"clean", "planted", "fixed", "synced"}
  /\ m_manifest \in MNames
  /\ m_reg \in [MNames -> MPaths]
  /\ m_enabled \subseteq MNames
  /\ m_sync_failed \in BOOLEAN
  /\ m_fixed \in BOOLEAN
  /\ w_phase \in {"clean", "mirrored", "installed"}
  /\ w_entry \in [Entries -> {"link", "own", "absent"}]
  /\ w_parent \in [Entries -> {"parent_built", "child_built"}]
  /\ w_outcome \in {"none", "ok", "refused"}
  /\ w_script \in Scripts
  /\ w_linked_at_install \in BOOLEAN

KeepAllBut_verdicts == UNCHANGED << hv_group, shim_vars, prune_vars, record_vars, market_vars, write_vars >>
KeepAllBut_shims    == UNCHANGED << hv_group, verdict_vars, prune_vars, record_vars, market_vars, write_vars >>
KeepAllBut_prune    == UNCHANGED << hv_group, verdict_vars, shim_vars, record_vars, market_vars, write_vars >>
KeepAllBut_records  == UNCHANGED << hv_group, verdict_vars, shim_vars, prune_vars, market_vars, write_vars >>
KeepAllBut_market   == UNCHANGED << hv_group, verdict_vars, shim_vars, prune_vars, record_vars, write_vars >>
KeepAllBut_writes   == UNCHANGED << hv_group, verdict_vars, shim_vars, prune_vars, record_vars, market_vars >>

------------------------------------------------------------------------------
\* ============================================================== VERDICTS ===
\* OHV-2 (#339): `home verify` fails on what `home repair` finds, and both see
\* agent links and projection records. OHV-5 (#343): a /var-spelled reference
\* in a home given as /private/var. OHV-1 (#338): every verdict names its build.
\*
\* Pinned by: home.verdicts.verify.names.every.repair.finding,
\* home.verdicts.dangling.agent.link, home.verdicts.orphaned.projection.record
\* (graph); DamagedHomeIsRepairableTest, ChildHomeShimIsolationTest,
\* HomeVerifyPathSpellingTest, VerdictsNameTheirBuildTest (unit).
\*
\* THE MODEL OF ONE OBSERVATION. Damage is planted, then BOTH verdicts are taken
\* of the same bytes in one step. The step is atomic on purpose: two commands a
\* minute apart over a home something else is writing CAN legitimately disagree,
\* and this is not a claim about that. It is a claim about two readers of
\* identical bytes, which is what the kickoff measured (50 of 61).
\*
\* THE ONE EXEMPTION is evidence verify holds and repair cannot: a
\* FOREIGN_PATH_IN_SHIM on a link verify's isolation walk sanctioned as a
\* parent-store shim (HIS-7 / #223 -- every ticket-worktree clone). Repair judges
\* the copy alone and reports it; verify prints it and does not count it.
\*
\* NOT MODELLED: the /var alias applies when the home is GIVEN as /private/var
\* (macOS). Linux has no top-level aliases, so the regression below is
\* unreachable on a Linux runner and HomeVerifyPathSpellingTest returns early
\* there; no graph node plants it (DEF-OHV-185, #377).

Detect(damage) ==
  (damage \ (IF DetectorReach = "STORE_ONLY" THEN ProjectionKinds ELSE {}))
          \ (IF DetectorSpellings = "GIVEN_AND_REAL" THEN {"alias_spelled_shim"} ELSE {})

\* What verify counts of what repair found: everything but the sanctioned link.
Counted(found) == IF v_sanctioned THEN found \ {"foreign_shim"} ELSE found

\* verify's own isolation walk, which existed before OHV-2 and still runs.
OwnWalkRefuses(found) == "foreign_shim" \in found /\ ~v_sanctioned

VerifyExitOf(found) ==
  CASE VerifyComposition = "COMPOSES_REPAIR" ->
         IF OwnWalkRefuses(found) \/ Counted(found) # {} THEN 1 ELSE 0
    [] VerifyComposition = "OWN_WALK_ONLY" ->
         IF OwnWalkRefuses(found) THEN 1 ELSE 0
    [] VerifyComposition = "COMPOSES_WITHOUT_EXEMPTION" ->
         IF OwnWalkRefuses(found) \/ found # {} THEN 1 ELSE 0

\* @command PlantAHomeDefectShape
\* @port HomeVerdictsFixture
PlantDamage ==
  /\ hv_group = "verdicts"
  /\ v_phase = "planting"
  /\ \E k \in Kinds \ v_damage : v_damage' = v_damage \cup {k}
  /\ UNCHANGED << v_phase, v_sanctioned, v_repair, v_verify_exit, v_build,
                  v_verdict_build >>
  /\ KeepAllBut_verdicts

\* @command PlantAParentStoreShimTheIsolationWalkSanctions
\* @port HomeVerdictsFixture
PlantSanctionedParentShim ==
  /\ hv_group = "verdicts"
  /\ v_phase = "planting"
  /\ "foreign_shim" \notin v_damage
  /\ v_damage' = v_damage \cup {"foreign_shim"}
  /\ v_sanctioned' = TRUE
  /\ UNCHANGED << v_phase, v_repair, v_verify_exit, v_build, v_verdict_build >>
  /\ KeepAllBut_verdicts

\* @command JudgeOneHomeWithHomeVerifyAndHomeRepair
\* @port SkillManagerCli.home_verify
ObserveWithVerifyAndRepair ==
  /\ hv_group = "verdicts"
  /\ v_phase = "planting"
  /\ v_phase' = "observed"
  /\ v_repair' = Detect(v_damage)
  /\ v_verify_exit' = VerifyExitOf(Detect(v_damage))
  /\ \E b \in Builds :
       /\ v_build' = b
       /\ v_verdict_build' = IF VerdictStamp = "STAMPED" THEN b ELSE "none"
  /\ UNCHANGED << v_damage, v_sanctioned >>
  /\ KeepAllBut_verdicts

\* @invariant EveryPlantedFactIsFoundByRepair
\* The detector half. Every damaged fact on disk is a repair finding. Kept
\* apart from the agreement invariants below because a verify that composes a
\* BLIND repair agrees with it perfectly -- verify 0, repair 0, over a dangling
\* link -- which is DEF-OHV-002 as it read on this repository's own project home.
\* NOT WITNESS-DEPENDENT: v_damage is the files; v_repair is what was printed.
EveryPlantedFactIsFoundByRepair ==
  v_phase = "observed" => v_repair = v_damage

\* @invariant VerifyFailsOnEveryCountedRepairFinding
\* The agreement, one direction: anything repair reports that verify does not
\* hold sanctioned fails verify. Pre-OHV-2 verify ran its own walk only.
VerifyFailsOnEveryCountedRepairFinding ==
  (v_phase = "observed" /\ Counted(v_repair) # {}) => v_verify_exit = 1

\* @invariant VerifyPassesWhenRepairCountsNothing
\* The other direction, and the reason the exemption exists: composing repair
\* without it turned ChildHomeShimIsolationTest ("a COPY of a sanctioned child
\* inherits the sanction") red during OHV-2 -- every ticket-worktree clone.
VerifyPassesWhenRepairCountsNothing ==
  (v_phase = "observed" /\ Counted(v_repair) = {}) => v_verify_exit = 0

\* @invariant AVerdictNamesTheBuildThatProducedIt
\* #338. Across two builds the verdict is attributable. NOT WITNESS-DEPENDENT:
\* v_build is the binary that ran; v_verdict_build is the printed build line.
AVerdictNamesTheBuildThatProducedIt ==
  v_phase = "observed" => v_verdict_build = v_build

\* ================================================================= SHIMS ===
\* OHV-4 (#341): a shim never spells its own home, and a half-rewritten one is
\* reported. DEF-OHV-001 (kickoff: three root shims, export token-derived, exec
\* literal, `home repair` 0 findings) and DEF-OHV-180's second write (a released
\* 0.27.2 `home repair --fix` re-creating that shape on the root).
\*
\* Pinned by: home.verdicts.half.rewritten.shim, home.verdicts.frozen.shim,
\* home.verdicts.foreign.path.in.shim (graph); ShimSurvivesACopyTest,
\* DamagedHomeIsRepairableTest, SkillScriptBackendTest (unit).
\*
\* THE ABSTRACTION. A shell shim is two running lines (export, exec) and one
\* comment line. Each running line holds the ${SKILL_MANAGER_SHIM_HOME} token,
\* a literal spelling of THIS home (any PathSpellings alias, whole prefix), or
\* another home's path. Venv-internal shebangs (never a finding: the kernel
\* reads them literally) and non-shell text files (reported, not rewritable)
\* are NOT modelled.
\*
\* TWO RULES, and DEF-OHV-001 is the pair of them shipped together:
\*   ShimRewriteScope   what ShimHomeContract.selfDerivingRewrite re-anchors.
\*                      TOKEN_MEANS_DONE is 43e5fb99: a body containing the
\*                      token anywhere returned null ("already rewritten").
\*   ShimDetection      what HomeRepair.scanFrozenShims reports.
\*                      REWRITE_AVAILABLE is ffa2108b: reported only when that
\*                      rewrite returned non-null.
\* The rewrite is called by THREE writers, each an action below: the
\* skill-script installer after its script runs, `home repair --fix` for a
\* FROZEN finding, and `--fix` for a FOREIGN finding after mapping the other
\* home's path onto this one.

Tok(l) == IF l = "literal" THEN "token" ELSE l

\* The rewrite reads every line after the shebang, comments included, and
\* leaves no spelling of the home behind. <<export, exec, comment>>.
Rewrite(e, x, c) ==
  IF ShimRewriteScope = "TOKEN_MEANS_DONE" /\ (e = "token" \/ x = "token")
    THEN << e, x, c >>
    ELSE << Tok(e), Tok(x), FALSE >>

LiteralIn(e, x) == e = "literal" \/ x = "literal"
ForeignIn(e, x) == e = "foreign" \/ x = "foreign"

\* CONTENT reads running lines only: a comment-only spelling is prose.
\* REWRITE_AVAILABLE asks whether the rewrite would change the bytes.
DetectsFrozen(e, x, c) ==
  IF ShimDetection = "CONTENT"
    THEN LiteralIn(e, x)
    ELSE Rewrite(e, x, c) # << e, x, c >>

HasLiteral == LiteralIn(s_export, s_exec)
HasForeign == ForeignIn(s_export, s_exec)

\* A skill-script installer writes the shim in any mix of token and literal
\* lines (deploy-helm's writes both literal; a half-adopted recipe writes a
\* token export and a literal exec), and SkillScriptBackend.reportFrozenShims
\* then applies the rewrite to what it wrote.
\* @command SkillScriptInstallWritesAndReanchorsAShim
\* @port SkillScriptBackend.install
InstallShim ==
  /\ hv_group = "shims"
  /\ \E e \in {"token", "literal"}, x \in {"token", "literal"}, c \in BOOLEAN :
       LET w == Rewrite(e, x, c) IN
       /\ s_export' = w[1]
       /\ s_exec' = w[2]
       /\ s_comment' = w[3]
  /\ s_last' = "installed"
  /\ s_settled' = TRUE
  /\ s_copy_runs' = "n/a"
  /\ s_observed' = FALSE
  /\ s_obs_frozen' = FALSE
  /\ s_obs_foreign' = FALSE
  /\ KeepAllBut_shims

\* Anything that is not this build's install or --fix writes the shim: an
\* older release (every root shim at kickoff), another home's installer
\* writing THROUGH a bin/cli link (DEF-OHV-011 / DEF-OHV-180, 08:41 and
\* 11:31 EDT), a hand edit. Any bytes at all.
\* @command SomethingOtherThanThisBuildWritesTheShim
\* @port none
SomethingElseWritesTheShim ==
  /\ hv_group = "shims"
  /\ \E e \in Lines, x \in Lines, c \in BOOLEAN :
       /\ << e, x, c >> # << s_export, s_exec, s_comment >>
       /\ s_export' = e
       /\ s_exec' = x
       /\ s_comment' = c
  /\ s_last' = "contaminated"
  /\ s_settled' = FALSE
  /\ s_copy_runs' = "n/a"
  /\ s_observed' = FALSE
  /\ s_obs_frozen' = FALSE
  /\ s_obs_foreign' = FALSE
  /\ KeepAllBut_shims

\* `home repair --fix` acts only on findings. FOREIGN_PATH_IN_SHIM's fix maps
\* the other home's path onto this one and then calls the rewrite; that is
\* exactly how the released 0.27.2 left the root half-rewritten at 11:50:45 EDT.
\* @command HomeRepairFixRewritesAShimItReported
\* @port SkillManagerCli.home_repair
RepairFixShim ==
  /\ hv_group = "shims"
  /\ DetectsFrozen(s_export, s_exec, s_comment) \/ HasForeign
  /\ LET me == IF s_export = "foreign" THEN "literal" ELSE s_export
         mx == IF s_exec = "foreign" THEN "literal" ELSE s_exec
         w  == Rewrite(me, mx, s_comment)
     IN /\ s_export' = w[1]
        /\ s_exec' = w[2]
        /\ s_comment' = w[3]
  /\ s_last' = "fixed"
  /\ s_settled' = TRUE
  /\ s_copy_runs' = "n/a"
  /\ s_observed' = FALSE
  /\ s_obs_frozen' = FALSE
  /\ s_obs_foreign' = FALSE
  /\ KeepAllBut_shims

\* `cp -R` of the home, then run the copy's shim with the source's tool gone
\* (scripts/measure_goal_a_home_survives_being_copied.py property (b)).
\* @command CopyTheHomeAndRunItsShim
\* @port none
CopyHome ==
  /\ hv_group = "shims"
  /\ s_last # "copied"
  /\ s_copy_runs' = CASE HasForeign -> "other"
                      [] HasLiteral -> "source"
                      [] OTHER      -> "own"
  /\ s_last' = "copied"
  /\ UNCHANGED << s_export, s_exec, s_comment, s_settled, s_observed,
                  s_obs_frozen, s_obs_foreign >>
  /\ KeepAllBut_shims

\* @command HomeRepairJudgesTheShim
\* @port SkillManagerCli.home_repair
ObserveShim ==
  /\ hv_group = "shims"
  /\ ~s_observed
  /\ s_observed' = TRUE
  /\ s_obs_frozen' = DetectsFrozen(s_export, s_exec, s_comment)
  /\ s_obs_foreign' = HasForeign
  /\ UNCHANGED << s_export, s_exec, s_comment, s_last, s_settled, s_copy_runs >>
  /\ KeepAllBut_shims

\* @invariant AShimSpellingItsOwnHomeIsReported
\* OHV-4 (a). Detected on CONTENT: a running line that spells this home is a
\* finding whether or not a rewrite is on offer. "Can I rewrite this" is not
\* "does this name the home".
AShimSpellingItsOwnHomeIsReported ==
  (s_observed /\ HasLiteral) => s_obs_frozen

\* @invariant ACommentOnlySpellingIsNotAFinding
\* OHV-4's stated contract, NOT a historical defect: since OHV-2 verify fails on
\* every repair finding, so a comment-only match would turn verify red on a shim
\* that runs correctly. Whether 0.27.2's detector reported comment-only shims is
\* NOT established (frozenHomePaths' tokenizer was not re-read for it), so no
\* regression configuration claims it; this invariant is carried with no
\* expected-violation config and says so here.
ACommentOnlySpellingIsNotAFinding ==
  (s_observed /\ ~HasLiteral) => ~s_obs_frozen

\* @invariant AFreshlyWrittenShimNamesNoHome
\* OHV-4 (c). An installer that half-adopted the recipe leaves no literal line.
AFreshlyWrittenShimNamesNoHome ==
  s_last = "installed" => ~HasLiteral

\* @invariant ARepairedShimNamesNoHome
\* OHV-4 (b). Whatever --fix rewrote spells neither this home nor another.
ARepairedShimNamesNoHome ==
  s_last = "fixed" => (~HasLiteral /\ ~HasForeign)

\* @invariant ACopyOfASettledHomeRunsItsOwnShims
\* GOAL-no-own-home-path clause (2), the copied-home probe: a home whose shim
\* was last written by this build's install or --fix runs its OWN tool from a
\* copy. A home last written by something else may not, and is instead
\* required to be REPORTED (the first invariant of this group).
ACopyOfASettledHomeRunsItsOwnShims ==
  (s_last = "copied" /\ s_settled) => s_copy_runs = "own"

\* ================================================================= PRUNE ===
\* OHV-3 (#340, carrying #292): the census names nothing the disk does not
\* hold, and a prune stays pruned. DEF-OHV-003 / DEF-HBR-003.
\*
\* Pinned by: artifact-dag uninstall.prunes.the.subgraph (graph);
\* ArtifactPruneTest "a prune stays pruned", "a projection whose link is still on
\* disk keeps its row", UninstallCliCleanupTest (unit).
\*
\* THE ABSTRACTION. One unit, two ledger rows. The projection row records NO
\* output (the ledger never records an external path), so the only proof its
\* link is gone is the evidence the removal program captures while the unit is
\* still installed (ArtifactPrune.outputsOf, carried in PruneOrphanArtifacts).
\* A whole-home prune over a PAST removal has no such evidence and refuses it.
\* NOT MODELLED: unit-store / unit-digest rows, harness instances,
\* discardCreatedLedger (step d), and DEF-OHV-130's PATH-served shims.

Derived == IF p_installed THEN Rows ELSE {}

\* ArtifactPrune.provenGone: not derived, and every recorded in-home output
\* missing. A projection row records no output, so "every" is vacuously true.
ProvenGone(r, installedAfter, shimAfter) ==
  /\ ~installedAfter
  /\ (r = "shim" => shimAfter = "absent")

\* ArtifactPrune.apply's re-record. REBUILD_FROM_INDEX is 468daf8f's
\* `ArtifactLedger.of(ArtifactIndex.of(store).artifacts())`: the index merges
\* the disk WITH the ledger, so every pruned row came back (#292, 59 -> 59).
Rerecord(installedAfter, ledger, pruned, shimAfter) ==
  LET index == (IF installedAfter THEN Rows ELSE {}) \cup ledger
  IN IF PruneRerecord = "REBUILD_FROM_INDEX"
       THEN index
       ELSE index \ {r \in pruned : ProvenGone(r, installedAfter, shimAfter)}

\* `uninstall` (and retirement, which builds the same program). The shim is
\* deleted; the agent link is removed, or left dangling where the removal did
\* not walk (a checkout's .claude/skills, DEF-OHV-002's shape).
\* REAP_UNCONDITIONALLY is 252c6c48, reverted the same day by c4f7dff8: a row
\* with no outputs became a row-only PRUNE, the link it named was still on
\* disk, and "no later cleanup can reach" it because teardown reads the ledger.
\* @command UninstallReapsTheRowsItCanProveGone
\* @port RemoveUseCase.buildProgram
Uninstall ==
  /\ hv_group = "prune"
  /\ p_installed
  /\ \E l \in {"absent", "dangling"} :
       LET reaped == CASE TeardownReaping = "REAP_PROVEN_ABSENT" ->
                            {"shim"} \cup (IF l = "absent" THEN {"projection"} ELSE {})
                       [] TeardownReaping = "LEAVE_ROWS" -> {}
                       [] TeardownReaping = "REAP_UNCONDITIONALLY" -> Rows
       IN /\ p_link' = l
          /\ p_rows' = Rerecord(FALSE, p_rows, reaped, "absent")
          /\ p_last_pruned' = p_rows \cap reaped
          /\ p_proven_absent' = (l = "absent")
  /\ p_installed' = FALSE
  /\ p_shim' = "absent"
  /\ p_removal' = "captured"
  /\ KeepAllBut_prune

\* A removal by a build before OHV-3: rows left, no evidence captured. The
\* root home's 14 ledger-only rows at kickoff (DEF-OHV-131's residue).
\* @command ARemovalByABuildBeforeOHV3
\* @port none
LegacyRemoval ==
  /\ hv_group = "prune"
  /\ p_installed
  /\ \E l \in {"absent", "dangling"} : p_link' = l
  /\ p_installed' = FALSE
  /\ p_shim' = "absent"
  /\ p_removal' = "legacy"
  /\ p_proven_absent' = FALSE
  /\ p_last_pruned' = {}
  /\ UNCHANGED p_rows
  /\ KeepAllBut_prune

\* `artifacts prune` over the whole home. A projection row has no evidence
\* here and is REFUSED, by name; a cli-shim row whose output is missing goes.
\* @command ArtifactsPruneOverTheWholeHome
\* @port SkillManagerCli.artifacts_prune
PruneHome ==
  /\ hv_group = "prune"
  /\ LET ledgerOnly == p_rows \ Derived
         prunable(r) == IF TeardownReaping = "REAP_UNCONDITIONALLY"
                          THEN TRUE
                          ELSE r = "shim" /\ p_shim = "absent"
         pruned == {r \in ledgerOnly : prunable(r)}
     IN /\ pruned # {}
        /\ p_rows' = Rerecord(p_installed, p_rows, pruned, p_shim)
        /\ p_last_pruned' = pruned
  /\ UNCHANGED << p_installed, p_link, p_shim, p_removal, p_proven_absent >>
  /\ KeepAllBut_prune

\* `home repair --fix` removes a DANGLING_AGENT_LINK (OHV-2). It does not touch
\* the ledger.
\* @command HomeRepairFixRemovesADanglingAgentLink
\* @port SkillManagerCli.home_repair
RemoveDanglingLink ==
  /\ hv_group = "prune"
  /\ ~p_installed
  /\ p_link = "dangling"
  /\ p_link' = "absent"
  /\ UNCHANGED << p_installed, p_shim, p_rows, p_last_pruned, p_removal,
                  p_proven_absent >>
  /\ KeepAllBut_prune

\* @command InstallTheUnitAgain
\* @port SkillManagerCli.install
Reinstall ==
  /\ hv_group = "prune"
  /\ ~p_installed
  /\ p_installed' = TRUE
  /\ p_link' = "present"
  /\ p_shim' = "present"
  /\ p_rows' = Rows
  /\ p_last_pruned' = {}
  /\ p_removal' = "none"
  /\ p_proven_absent' = FALSE
  /\ KeepAllBut_prune

\* @invariant PruneStaysPruned
\* #292 / GOAL-one-record clause (2). A row the last prune dropped is not in
\* the ledger it re-recorded. p_last_pruned is what the prune printed as
\* PRUNE, so this is not a ghost.
PruneStaysPruned == p_last_pruned \cap p_rows = {}

\* @invariant RowsProvenAbsentAtRemovalAreReaped
\* DEF-OHV-003 / DEF-HBR-003. A post-OHV-3 removal leaves no row whose
\* outputs it proved gone: the shim row always (in-home kind, output deleted),
\* the projection row when the captured link is absent.
RowsProvenAbsentAtRemovalAreReaped ==
  p_removal = "captured" =>
    /\ "shim" \notin p_rows
    /\ (p_proven_absent => "projection" \notin p_rows)

\* @invariant ALinkStillOnDiskKeepsItsRow
\* #292's dangling-symlink trap, and the reason the invariant above is not the
\* whole property: reaping everything satisfies it completely and strands a
\* link the ledger was the only record of (252c6c48, reverted).
ALinkStillOnDiskKeepsItsRow ==
  (~p_installed /\ p_link # "absent") => "projection" \in p_rows

\* NOT IN THE HEALTHY CONFIGURATION -- see HomeVerdictsInternal_finding_prunedresidue.cfg.
\* The stronger reading of GOAL-one-record clause (1): no post-OHV-3 removal
\* leaves a projection row whose link is gone. FALSE of the delivered product,
\* found while writing this model: a removal that leaves the link dangling keeps
\* the row (correctly), `home repair --fix` then removes the link (OHV-2), and no
\* later prune can prove it gone (the evidence rode only in the removal's own
\* effect). So OHV-3 still PRODUCES DEF-OHV-131's shape through that path.
\* @invariant NoProjectionRowOutlivesItsLink
NoProjectionRowOutlivesItsLink ==
  (~p_installed /\ p_link = "absent" /\ p_removal = "captured")
    => "projection" \notin p_rows

\* =============================================================== RECORDS ===
\* OHV-3 (c), DEF-OHV-004: installed/<unit>.json `version` restated from the
\* checkout's manifest when the record's gitHash IS the checkout's HEAD.
\* Pinned by: RecordVersionRefreshTest (unit only; no graph node). HASH_ONLY is
\* 39e42838's InstalledUnit.withGitMoved, which moved the hash and never the
\* version, plus the up-to-date early return that wrote nothing.

\* @command SyncMovesTheCheckoutToANewCommit
\* @port SyncGitHandler.refreshSourceRecord
SyncToNewCommit ==
  /\ hv_group = "records"
  /\ r_head = "c1"
  /\ r_head' = "c2"
  /\ r_hash' = "c2"
  /\ r_version' = IF RecordVersionPolicy = "RESTATE_AT_HEAD" THEN Manifest("c2") ELSE r_version
  /\ r_synced' = TRUE
  /\ KeepAllBut_records

\* @command SyncFindsTheCheckoutAlreadyUpToDate
\* @port SyncGitHandler.refreshSourceRecord
SyncAlreadyUpToDate ==
  /\ hv_group = "records"
  /\ r_hash = r_head
  /\ r_version' = IF RecordVersionPolicy = "RESTATE_AT_HEAD" THEN Manifest(r_head) ELSE r_version
  /\ r_synced' = TRUE
  /\ UNCHANGED << r_head, r_hash >>
  /\ KeepAllBut_records

\* A record an older build left: hash current, version anything.
\* @command ARecordLeftByAnOlderBuild
\* @port none
RecordLeftByAnOlderBuild ==
  /\ hv_group = "records"
  /\ ~r_synced
  /\ r_hash = r_head
  /\ \E v \in {"v1", "v2"} : r_version' = v
  /\ UNCHANGED << r_head, r_hash, r_synced >>
  /\ KeepAllBut_records

\* @invariant ASyncedRecordAgreesWithItsCheckout
ASyncedRecordAgreesWithItsCheckout ==
  (r_synced /\ r_hash = r_head) => r_version = Manifest(r_head)

\* =========================================================== MARKETPLACE ===
\* OHV-6 (#352): a home's plugin marketplace has one identity, and every agent
\* registration agrees with it. Shapes: (1) this path registered under another
\* name; (2) the identity enabled but not registered here; (3) a manifest
\* carrying another home's identity; (4) another home's marketplace enabled
\* here (DEF-OHV-005, the root's Claude and Codex configs).
\*
\* Pinned by: home.verdicts.marketplace.under.another.name,
\* .marketplace.identity.unregistered, .copied.marketplace.identity,
\* .foreign.marketplace.registration (graph); HarnessPluginCliTest,
\* MarketplaceRegistrationsTest, HomeCloneTest, HomeSyncTest (unit).
\*
\* THE ABSTRACTION. One agent config (Claude's and Codex's behave alike here),
\* a registration as a function name -> path (both CLIs key it by name), and a
\* set of names plugins are enabled under. The CLI behaviour measured in OHV-6
\* is modelled where it decides the outcome: `marketplace add` of a path
\* already registered under another name keeps the old name.
\* NOT MODELLED: the Claude exemption's user-level/project-level split beyond
\* "an unenabled foreign registration is not reported", installed_plugins.json,
\* Gemini, and CLAUDE_CONFIG_DIR redirection.

\* "skill-manager" is a substring of every "skill-manager-<hash>".
Contains(big, small) ==
  \/ big = small
  \/ (small = "legacy" /\ big \in {"id_H", "id_O"})

Shape1(reg) == \E n \in MNames \ {"id_H"} : reg[n] = "H"

\* Any mix of the four shapes, as homes on this machine carried them.
\* @command PlantMarketplaceShapes
\* @port HomeVerdictsFixture
PlantMarketplaceShapes ==
  /\ hv_group = "marketplace"
  /\ m_phase = "clean"
  /\ \E man \in MNames, reg \in [MNames -> MPaths], en \in SUBSET MNames :
       /\ reg["id_H"] \in {"none", "H"}
       /\ en \subseteq ({n \in MNames : reg[n] # "none"} \cup {"id_H"})
       /\ m_manifest' = man
       /\ m_reg' = reg
       /\ m_enabled' = en
  /\ m_phase' = "planted"
  /\ UNCHANGED << m_sync_failed, m_fixed >>
  /\ KeepAllBut_market

\* `home clone` of another home into this one (HomeCloner.rederiveMarketplaceIdentity).
\* @command CloneAnotherHomeIntoThisPath
\* @port SkillManagerCli.home_clone
CloneAnotherHomesMarketplace ==
  /\ hv_group = "marketplace"
  /\ m_phase = "clean"
  /\ m_manifest' = IF ManifestIdentity = "DERIVED" THEN "id_H" ELSE "id_O"
  /\ m_phase' = "planted"
  /\ UNCHANGED << m_reg, m_enabled, m_sync_failed, m_fixed >>
  /\ KeepAllBut_market

\* `home repair --fix` over the four MARKETPLACE_* kinds, manifest first. A
\* foreign enablement of a plugin this home's manifest carries is RE-POINTED at
\* the identity rather than dropped (the model's single plugin is always one
\* this home carries). An unenabled foreign registration is exempt.
\* @command HomeRepairFixMarketplaceShapes
\* @port SkillManagerCli.home_repair
RepairFixMarketplace ==
  /\ hv_group = "marketplace"
  /\ m_phase = "planted"
  /\ m_phase' = "fixed"
  /\ m_fixed' = TRUE
  /\ IF MarketplaceRepair = "NONE"
       THEN UNCHANGED << m_manifest, m_reg, m_enabled >>
       ELSE LET another == {n \in MNames \ {"id_H"} : m_reg[n] = "H"}
                foreign == {n \in m_enabled : m_reg[n] = "O"}
                gone    == another \cup foreign
                en2     == (m_enabled \ gone)
                             \cup (IF m_enabled \cap gone # {} THEN {"id_H"} ELSE {})
            IN /\ m_manifest' = "id_H"
               /\ m_enabled' = en2
               /\ m_reg' = [n \in MNames |->
                              IF n \in gone THEN "none"
                              ELSE IF n = "id_H" /\ (another # {} \/ "id_H" \in en2)
                                     THEN "H"
                                     ELSE m_reg[n]]
  /\ UNCHANGED m_sync_failed
  /\ KeepAllBut_market

\* A plugin sync: ensureMarketplaceAdded, then install the plugins under the
\* name. IDENTITY_AND_PATH is OHV-6 (a)+(b): match by exact name and path,
\* remove this path under another name, add, re-list, and judge by what is
\* registered. SUBSTRING is 61e9553b: `list.stdout().contains(name)`, with the
\* add's exit code trusted.
\* @command PluginSyncRegistersTheMarketplaceAndInstalls
\* @port HarnessPluginCli.ensureMarketplaceAdded
SyncMarketplace ==
  /\ hv_group = "marketplace"
  /\ m_phase \in {"planted", "fixed"}
  /\ m_phase' = "synced"
  /\ LET name == IF ManifestIdentity = "DERIVED" THEN "id_H" ELSE m_manifest IN
     /\ m_manifest' = name
     /\ IF MarketplaceMatch = "IDENTITY_AND_PATH"
          THEN LET removed == {n \in MNames \ {name} : m_reg[n] = "H"} IN
               /\ m_reg' = [n \in MNames |->
                              IF n = name THEN "H"
                              ELSE IF n \in removed THEN "none" ELSE m_reg[n]]
               /\ m_enabled' = (m_enabled \ removed) \cup {name}
               /\ m_sync_failed' = FALSE
          ELSE LET already  == \E n \in MNames : m_reg[n] # "none" /\ Contains(n, name)
                   occupied == \E n \in MNames : m_reg[n] = "H"
                   reg2     == IF already \/ occupied
                                 THEN m_reg
                                 ELSE [m_reg EXCEPT ![name] = "H"]
                   ok       == reg2[name] = "H"
               IN /\ m_reg' = reg2
                  /\ m_enabled' = IF ok THEN m_enabled \cup {name} ELSE m_enabled
                  /\ m_sync_failed' = ~ok
  /\ UNCHANGED m_fixed
  /\ KeepAllBut_market

\* @invariant ASyncRegistersUnderItsOwnIdentity
\* After any sync: no AGENT_SYNC_FAILED, the manifest names this home, and this
\* path is registered under this home's identity and no other name.
ASyncRegistersUnderItsOwnIdentity ==
  m_phase = "synced" =>
    /\ ~m_sync_failed
    /\ m_manifest = "id_H"
    /\ m_reg["id_H"] = "H"
    /\ \A n \in MNames \ {"id_H"} : m_reg[n] # "H"

\* @invariant AfterRepairAndSyncNothingForeignIsEnabled
\* GOAL-one-marketplace-identity clause (1): reported, cleared by --fix, and the
\* next sync leaves every enabled name registered at THIS home.
AfterRepairAndSyncNothingForeignIsEnabled ==
  (m_phase = "synced" /\ m_fixed) => \A n \in m_enabled : m_reg[n] = "H"

\* ================================================================ WRITES ===
\* OHV-9 (#367): a skill-script install never writes through a link into
\* another home. DEF-OHV-011 (08:41 EDT) and its recurrence DEF-OHV-180
\* (11:31 EDT, a build without OHV-9).
\*
\* Pinned by: home.verdicts.child.install.writes.only.itself (graph);
\* SkillScriptWriteThroughTest, BinCliWritersDoNotFollowLinksTest (unit).
\*
\* THE ABSTRACTION. A child home's bin/cli/tool (written by a FORKED
\* skill-script the product does not control) and bin/cli/skill-manager (the
\* launcher entrypoint, written in-JVM by LauncherShims). Either may be a
\* symlink into the parent's bin/cli -- ChildHomeMaterializer.mirrorExistingShim
\* makes exactly that shape, and so did commit-diff-context-parent's test
\* fixture over the operator's real root (CDC#262). A script either writes
\* "$SKILL_MANAGER_HOME/bin/cli/tool" (`cat >` follows a link), spells the
\* parent's path outright, or writes nothing.
\* NOT MODELLED: pip/uv (guarded the same way, but not provable -- a process
\* the unit suite cannot run hermetically), npm/brew/tar (delete-then-place,
\* which cannot follow), concurrency while the link is detached.

\* @command MaterializeAChildHomeMirroringItsParentsShims
\* @port ChildHomeMaterializer.mirrorExistingShim
MirrorParentShims ==
  /\ hv_group = "writes"
  /\ w_phase = "clean"
  /\ w_entry' = [e \in Entries |-> "link"]
  /\ w_phase' = "mirrored"
  /\ UNCHANGED << w_parent, w_outcome, w_script, w_linked_at_install >>
  /\ KeepAllBut_writes

\* SkillScriptBackend.install around Shell.runToLog. DETACH_FOREIGN_LINKS is
\* ForeignBinLinks.detach / restore / requireNoForeignWrite. WRITE_THROUGH is
\* the pre-OHV-9 backend (9c8480fb's unguarded run; 3cbcba85's binStamps with
\* NOFOLLOW_LINKS and reportFrozenShims skipping links, so nothing saw it).
\* Detach takes away EVERY bin/cli link resolving outside the home, the
\* launcher's included.
\* @command SkillScriptInstallInAChildHome
\* @port SkillScriptBackend.install
SkillScriptInstall ==
  /\ hv_group = "writes"
  /\ w_phase \in {"clean", "mirrored"}
  /\ w_phase' = "installed"
  /\ \E s \in {"own_entry", "parent_path", "nothing"} :
       LET linked        == w_entry["tool"] = "link"
           detaching     == BinWritePolicy = "DETACH_FOREIGN_LINKS"
           during        == IF detaching /\ linked THEN "absent" ELSE w_entry["tool"]
           afterScript   == IF s = "own_entry" /\ during # "link" THEN "own" ELSE during
           parentWritten == (s = "parent_path") \/ (s = "own_entry" /\ during = "link")
           restoredTool  == IF detaching /\ linked /\ afterScript = "absent"
                                 /\ DetachRestore = "RESTORE"
                              THEN "link" ELSE afterScript
           launcherAfter == IF detaching /\ w_entry["launcher"] = "link"
                                 /\ DetachRestore = "DETACH_ONLY"
                              THEN "absent" ELSE w_entry["launcher"]
           caught        == detaching /\ linked /\ parentWritten
                              /\ ForeignWriteCheck = "STAT_AND_REFUSE"
       IN /\ w_script' = s
          /\ w_linked_at_install' = linked
          /\ w_entry' = [e \in Entries |-> IF e = "tool" THEN restoredTool ELSE launcherAfter]
          /\ w_parent' = [w_parent EXCEPT !["tool"] =
                            IF parentWritten THEN "child_built" ELSE @]
          /\ w_outcome' = IF caught THEN "refused" ELSE "ok"
  /\ KeepAllBut_writes

\* LauncherShims.write. FOLLOW_LINK is Files.writeString on a link
\* (c94e791b / e65962ef), found red by OHV-9's unit case: the parent's
\* entrypoint got the child's pin.
\* @command LauncherShimsWriteTheChildsEntrypoint
\* @port LauncherShims.write
LauncherShimsWrite ==
  /\ hv_group = "writes"
  /\ w_entry["launcher"] # "own"
  /\ IF w_entry["launcher"] = "link" /\ LauncherWrite = "FOLLOW_LINK"
       THEN /\ w_parent["launcher"] = "parent_built"
            /\ w_parent' = [w_parent EXCEPT !["launcher"] = "child_built"]
            /\ UNCHANGED w_entry
       ELSE /\ w_entry' = [w_entry EXCEPT !["launcher"] = "own"]
            /\ UNCHANGED w_parent
  /\ UNCHANGED << w_phase, w_outcome, w_script, w_linked_at_install >>
  /\ KeepAllBut_writes

\* @invariant AnOwnEntryWriteStaysInItsHome
\* GOAL-a-home-writes-only-itself clause (1): an installer writing this home's
\* own bin/cli path leaves the parent byte-identical.
AnOwnEntryWriteStaysInItsHome ==
  w_script = "own_entry" => w_parent["tool"] = "parent_built"

\* @invariant TheChildOwnsTheShimItInstalled
TheChildOwnsTheShimItInstalled ==
  (w_script = "own_entry" /\ w_outcome = "ok") => w_entry["tool"] = "own"

\* @invariant AWriteThroughADetachedLinkIsRefused
\* Clause (2): bytes cannot be un-written, so the honest property is that an
\* install that changed the file a detached link pointed at never reports
\* success. Scoped to entries that WERE links: see the finding config below for
\* the unscoped reading, which the delivered guard does not satisfy.
AWriteThroughADetachedLinkIsRefused ==
  (w_linked_at_install /\ w_parent["tool"] = "child_built") => w_outcome = "refused"

\* @invariant ALinkTheInstallerDidNotReplaceIsPutBack
ALinkTheInstallerDidNotReplaceIsPutBack ==
  (w_phase = "installed" /\ w_linked_at_install /\ w_script \in {"nothing", "parent_path"})
    => w_entry["tool"] = "link"

\* @invariant AGeneratedLauncherIsWrittenIntoItsOwnHome
AGeneratedLauncherIsWrittenIntoItsOwnHome ==
  w_parent["launcher"] = "parent_built"

\* NOT IN THE HEALTHY CONFIGURATION -- see HomeVerdictsInternal_finding_outrightpath.cfg.
\* The unscoped reading of clause (2). ForeignBinLinks' own javadoc says it does
\* not cover "an installer that spells the other home's path outright" when no
\* link was detached; this makes that limit executable.
\* @invariant AnyWriteIntoAnotherHomeIsRefused
AnyWriteIntoAnotherHomeIsRefused ==
  w_parent["tool"] = "child_built" => w_outcome = "refused"

\* ============================================================ SPECIFICATION ===

Next ==
  \/ PlantDamage \/ PlantSanctionedParentShim \/ ObserveWithVerifyAndRepair
  \/ InstallShim \/ SomethingElseWritesTheShim \/ RepairFixShim \/ CopyHome
  \/ ObserveShim
  \/ Uninstall \/ LegacyRemoval \/ PruneHome \/ RemoveDanglingLink \/ Reinstall
  \/ SyncToNewCommit \/ SyncAlreadyUpToDate \/ RecordLeftByAnOlderBuild
  \/ PlantMarketplaceShapes \/ CloneAnotherHomesMarketplace
  \/ RepairFixMarketplace \/ SyncMarketplace
  \/ MirrorParentShims \/ SkillScriptInstall \/ LauncherShimsWrite

Spec == Init /\ [][Next]_vars

\* ==================================================== REACHABILITY PROBES ===
\* EACH MUST FAIL against the healthy configuration (run with -continue). Every
\* invariant above is an implication, and one whose antecedent the healthy model
\* never reaches passes without having been evaluated. One probe per group,
\* each a state in which that group's least obvious antecedent is live. What a
\* probe does NOT show: that every OTHER antecedent of its group is reached --
\* for those, the regression configurations are the evidence, because each
\* reaches its target's antecedent under a one-constant change.

\* @invariant ProbeVerdicts
ProbeVerdicts ==
  ~ /\ v_phase = "observed"
    /\ v_sanctioned
    /\ (ProjectionKinds \cup {"alias_spelled_shim", "frozen_shim"}) \subseteq v_damage
    /\ v_verify_exit = 1

\* @invariant ProbeShims -- the DEF-OHV-001 shape, observed and reported.
ProbeShims ==
  ~ /\ s_observed
    /\ s_export = "token"
    /\ s_exec = "literal"
    /\ s_obs_frozen

\* @invariant ProbePrune -- a post-OHV-3 removal that left the link dangling.
ProbePrune ==
  ~ /\ ~p_installed
    /\ p_removal = "captured"
    /\ p_link = "dangling"
    /\ "projection" \in p_rows
    /\ p_last_pruned = {"shim"}

\* @invariant ProbeRecords -- a stale record an older build left, then synced.
ProbeRecords ==
  ~ /\ r_synced
    /\ r_head = "c1"
    /\ r_version = "v1"

\* @invariant ProbeMarketplace -- an unenabled foreign registration survived.
ProbeMarketplace ==
  ~ /\ m_phase = "synced"
    /\ m_fixed
    /\ m_reg["id_O"] = "O"
    /\ m_enabled = {"id_H"}

\* @invariant ProbeWrites -- a write through a detached link, refused, link back.
ProbeWrites ==
  ~ /\ w_linked_at_install
    /\ w_script = "parent_path"
    /\ w_outcome = "refused"
    /\ w_entry["tool"] = "link"
    /\ w_parent["tool"] = "child_built"

===============================================================================
