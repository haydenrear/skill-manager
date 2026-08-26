# HIS-6 — goal contribution

**Role:** `evaluation`. This ticket owns all nine epic goals and its expected effect is
*"decides the goal; adds no behavioural delta."*

## What this ticket changed, exhaustively

**No production source was modified.** `src/main/java` is byte-identical to
`epic/home-integrity-sync` @ `1764870`. That is the acceptance condition for an
evaluation ticket, and it is checkable with one `git diff --stat`.

Two files changed, both in the harness, and both required by Phase 5:

1. `test_graph/sources/git-latest-source-tracking/GlsConflict.java` — **a stale
   assertion repaired.** The node required the CLI to strand a unit's repository in a
   merge conflict; the product stopped doing that because
   `GOAL-symlink-merge-settles` asked it to. Two assertions unchanged, two inverted,
   three added, each paired with a control. **This changes `graphs_passed`, and both
   numbers are reported separately.**
2. `src/test/java/dev/skillmanager/store/DamagedHomeIsRepairableTest.java` — **one new
   case, and it is RED on the tree that ships it.** DEF-115. HIS-6 may not repair the
   product to make a goal it grades come out green, and it may not leave a found bug
   without a check. Where those conflict the instruction is: file the fix, report the
   number as measured, and say so. The check is in the tree, red, with two green
   in-run controls proving the red is the claim rather than the setup.

## Per goal

| goal | verdict | what decided it |
| --- | --- | --- |
| GOAL-no-spurious-holdback | **PASS / PASS** | `ProjectChildHomeMaterializationTest` both clauses; confirmed at the CLI by a whole-home sync that held back a genuinely divergent unit while promoting another |
| GOAL-sync-quiet | **PASS / PASS** | the committed baseline record rendered through `DriftReport`: **8 lines / 473 chars** against **896 lines / 88,308 chars**, a **99.4644%** reduction, detail unchanged |
| GOAL-gate-settles | **PASS / PASS**, clause 2 narrower than it reads | real-CLI surfacing 4 lines → 1 line → 1 line → `--detail` full → ack; **and `home sync` arms no gate at all (DEF-116)** |
| GOAL-symlink-merge-settles | **PASS / PASS** | `sync-settles` green; and DEF-108 is a graph asserting the pre-goal behaviour, which is the strongest available evidence the behaviour changed |
| GOAL-home-invariants | **PASS / PASS**, clause 2 held by hand | 14 expected-violation cfgs through HIS-5's runner with `SPEC_REQUIRE_TLC=1`; the 5 pre-existing cfgs re-run by hand, each naming a distinct invariant, all rc=12 |
| GOAL-mechanism-documented | **PASS / PASS at root** | clause 1 decided by **reading the prose**: stated once in `skt/references/derived-artifacts.md`, linked by the other three, no copy anywhere. `check-docs-coverage.py` = 4 of 4, reported as a reachability count and not as clause 1 |
| GOAL-one-home-one-answer | **FAIL / PASS / PASS** | **DEF-115**: verify and repair name different subject sets for a symlinked shim, with a wrapper control proving they can agree. Clause 3 measured on the REAL project home: `home verify` exit 0 with the five parent-store shims sanctioned and no `--against` |
| GOAL-no-destructive-recovery | **PASS / PASS / PASS**, with a live counterexample | the walk-back cases and `home-integrity` are green; and a home damaged during this evaluation is called clean by both readers, because it is a fifth shape the enumeration does not name (DEF-121) |
| GOAL-progressive-disclosure | **PASS at root; FAIL at project and worktree / PARTIAL / PARTIAL** | the root corpus is real for the first time (`exit 79` present). **DEF-096's remedy has still not been run**: the project home holds 4 skills, no plugins, empty `projects/`, against a manifest declaring `skt` and `skill-manager`. HIS-20's lower-tier AFTER readings remain counterfactual on this machine. |

## The rules, and how they were kept

- **No goal was fixed.** The one clause that fails (GOAL-one-home-one-answer clause 1)
  has a product fix available and obvious — teach `HomeRepair` to ask the same
  foreign-home question of a link target. It was **not made**, because making it would
  turn a FAIL into a PASS on a goal this ticket grades.
- **No target was edited**, and no number was obtained by re-running selectively. The
  sweep was run once, whole, in registered order, with resumption available and unused.
- **The harness was fixed**, twice, and both changes are named above with their effect
  on the numbers.
- **Deferment:** budget 5, mode batch, blocking escalate. Seven findings.
  **DEF-115 and DEF-116 are blocking and were ESCALATED** rather than deferred, so they
  do not consume the budget. **DEF-117, DEF-118, DEF-119, DEF-120, DEF-121 are the five
  deferred.** Every one carries an `owed_check`.

## What this ticket did NOT establish

- **No upgrade was measured.** Every home here was built by one build or cloned from
  one. `brew upgrade` across a version boundary — DEF-012's shape — is covered by a
  unit fixture and nothing else.
- **No second session was measured.** The drift gate exists for the boundary between
  sessions, and every measurement here is single-session.
- **The backlog is still not reconciled.** 121 findings, **96 open**; only 9 name a
  graph this sweep decides and 87 name none. DEF-043 and DEF-103 are now **closed** with
  their evidence — but 94 entries remain unfalsifiable by any instrument this epic has,
  and this ticket measured the size of that gap rather than closing it.
- **The detector for the epic's own worst live defect was already written and left on
  the test side of the fence.** `HomeMembershipLaw.java:384` emits exactly the verdict
  DEF-121 needs — *"GAINED [x] — present in the home, and no `installed/` record names
  them"* — and it ran in **24 of the 26 graphs** of this sweep, green, every time. No
  product command asks it about a real home. Correcting this ticket's own estimate:
  closing DEF-121's product half is **a port, not a build**.
- **The judged reads are still not blind.** Four inbound channels are documented and
  none is closed; a subagent dispatched from this session inherits all four by
  construction. The root-tier read run here is evidence about the corpus, not about a
  fresh agent, and is reported with its own contamination audit.
