----------------------------- MODULE CliPinInternal -----------------------------
\* #404 policy slice: what `install` / `sync` do with a CLI dep whose declaring
\* unit bumped ITS OWN pin since this home installed it.
\*
\* WHY A SIBLING OF SkillManager AND NOT A DIMENSION INSIDE IT. SkillManager
\* models cli_cli_lock and cli_tool_records as SETS of packages: a row is there
\* or it is not. #404 is entirely about what that abstraction leaves out -- the
\* VERSION a row records versus the version of the bytes in bin/cli. Adding a
\* version to every package in SkillManager multiplies a monolith for one
\* question; this module holds it alone, the way GitHistoryInternal holds
\* ancestry for External.
\*
\* THE ABSTRACTION. One governed dep (pip / npm / tar) in one home.
\*   declared   -- the pin the unit's manifest names now (CliPin.of)
\*   installed  -- the pin of the binary at bin/cli/<name>, or NoPin
\*   recorded   -- the pin cli-lock.toml's row says was installed, or NoPin
\*   refused    -- the last pass refused (a loud error, nothing recorded)
\*   settled    -- the last pass returned normally
\*
\* THE POLICY CONSTANTS, one per pre-#404 behaviour:
\*   PinGate    -- "PRESENCE" (pre-#404): a present, executable binary is
\*                 ALREADY_PRESENT whatever was declared. "DECLARED_PIN":
\*                 InstallerRegistry.installOne compares the row's declared_pin
\*                 with CliPin.of(dep) first and forces a reinstall on a move.
\*   SkipPolicy -- what a forced reinstall the backend could not perform
\*                 (SKIPPED: no target for this platform) does. "RECORD" lets the
\*                 caller write the new pin over the old binary; "REFUSE" throws.
\*
\* NO CASE ADAPTERS. The @command annotation names the production path; the
\* executable pins are CliPinTest (unit) and the cli-pin test graph.
\*
\* SCOPE: one unit, one home. A row claimed by SEVERAL units (two units
\* declaring one tar:tool) is outside this model: a moved pin there is a
\* conflict CliPin.sharedConflict reports before installOne runs, pinned by
\* CliPinTest's shared-CLI cases on the bulk path (CliInstallRecorder.run).
\*
\* HOW TO RUN:
\*   run_tlc.sh CliPinInternal.tla CliPinInternal.cfg                      -> No error
\*   run_tlc.sh CliPinInternal.tla CliPinInternal_regression_presence.cfg  -> MUST FAIL
\*   run_tlc.sh CliPinInternal.tla CliPinInternal_regression_skiprecord.cfg -> MUST FAIL

EXTENDS Naturals, FiniteSets

CONSTANTS
  Pins,          \* the versions a unit can declare
  NoPin,         \* nothing installed / no row
  PinGate,       \* "PRESENCE" (pre-#404) | "DECLARED_PIN"
  SkipPolicy     \* "RECORD" (pre-#404) | "REFUSE"

ASSUME PinGate \in {"PRESENCE", "DECLARED_PIN"}
ASSUME SkipPolicy \in {"RECORD", "REFUSE"}
ASSUME NoPin \notin Pins

VARIABLES declared, installed, recorded, canReinstall, refused, settled

vars == << declared, installed, recorded, canReinstall, refused, settled >>

TypeOK ==
  /\ declared \in Pins
  /\ installed \in Pins \cup {NoPin}
  /\ recorded \in Pins \cup {NoPin}
  /\ canReinstall \in BOOLEAN
  /\ refused \in BOOLEAN
  /\ settled \in BOOLEAN

Init ==
  /\ declared \in Pins
  /\ installed = NoPin
  /\ recorded = NoPin
  /\ canReinstall \in BOOLEAN
  /\ refused = FALSE
  /\ settled = FALSE

\* A unit edits its manifest: the pin moves. No skill-manager code runs.
BumpDeclaredPin ==
  /\ \E p \in Pins \ {declared}: declared' = p
  /\ settled' = FALSE
  /\ refused' = FALSE
  /\ UNCHANGED << installed, recorded, canReinstall >>

\* The platform's install target appears or disappears (tar: no target for
\* this os/arch is SKIPPED). Models every reason a backend cannot reinstall.
FlipBackendAbility ==
  /\ canReinstall' = ~canReinstall
  /\ UNCHANGED << declared, installed, recorded, refused, settled >>

\* Would this pass's gate treat the present binary as already provided?
PinMoved == recorded # NoPin /\ recorded # declared

Present == installed # NoPin

\* @command InstallCliDep
\* @result InstallOutcome
\* @port InstallerRegistry.installOne + CliInstallRecorder.record
InstallCliDep ==
  LET force == PinGate = "DECLARED_PIN" /\ PinMoved
  IN
  \/ \* ALREADY_PRESENT: the presence check wins; the row is (re)recorded from
     \* the CURRENT declaration -- which is the pre-#404 lie when it moved.
     /\ Present /\ ~force
     /\ recorded' = declared
     /\ settled' = TRUE /\ refused' = FALSE
     /\ UNCHANGED << declared, installed, canReinstall >>
  \/ \* INSTALLED: absent, or forced by a moved pin, and the backend can act.
     /\ (~Present \/ force) /\ canReinstall
     /\ installed' = declared
     /\ recorded' = declared
     /\ settled' = TRUE /\ refused' = FALSE
     /\ UNCHANGED << declared, canReinstall >>
  \/ \* SKIPPED on a first install: nothing landed, nothing to contradict.
     /\ ~Present /\ ~canReinstall
     /\ recorded' = declared
     /\ settled' = TRUE /\ refused' = FALSE
     /\ UNCHANGED << declared, installed, canReinstall >>
  \/ \* SKIPPED on a forced reinstall: the old binary is still there.
     /\ Present /\ force /\ ~canReinstall
     /\ IF SkipPolicy = "REFUSE"
          THEN /\ refused' = TRUE /\ settled' = FALSE
               /\ UNCHANGED recorded
          ELSE /\ recorded' = declared
               /\ settled' = TRUE /\ refused' = FALSE
     /\ UNCHANGED << declared, installed, canReinstall >>

Next == BumpDeclaredPin \/ FlipBackendAbility \/ InstallCliDep

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
\* INVARIANTS

\* #404's goal, verbatim: after a pass that returned normally, the binary in
\* this home is the declared one (or there is none at all) -- never the old one.
NoSilentlyKeptPin ==
  settled => (installed = NoPin \/ installed = declared)

\* The lock row describes the bytes that are actually there.
LockDescribesBinary ==
  (installed # NoPin /\ recorded # NoPin) => recorded = installed

\* A refusal leaves the lock describing the old binary rather than the new pin.
RefusalRecordsNothing ==
  refused => recorded = installed

=============================================================================
