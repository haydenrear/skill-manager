# HP-10 (#412) evidence — record shape (f): damaged unit Git

Worktree `/Volumes/dev/skill-manager-hp-10`, branch `feature/HP-10`, base epic tip `433f1090`.
Every graph ran through `/Volumes/dev/.honest-pins/graph-run.sh` (TESTGRAPH_CONTINUE_AFTER_FAILURE=1,
daemon off, IPv6, COMPOSE_PROJECT_NAME=skill-manager, SKILL_MANAGER_HOME unset).

## Goal-2 (f) baseline: RED, on the unfixed code (433f1090 + the new node only)

`graph-run.sh honest-pins --continue` → sweep `20261107-184035`, run `20261107-184251` (`red/`).
`honest.record-f` failed; setup and the four plain controls passed, so the red is the shape:

    close-out rc=1 partial=updated missing=updated valid=unchanged diverged=conflicted linked=new;
    sync rc=1 partial=updated missing=updated; projectUntouched=false

So the damaged worktree copy was reported `updated` with remedy `home sync`, and a real
`home sync --merge` REPLACED the project's valid copy with it (files `.git/HEAD`, `.git/config`).
Plain git confirms the hazard (`setup_partial_unit_discovers_enclosing_repo`): from the
partial unit, `rev-parse --show-toplevel` answers with the enclosing application repository.
See DEF-HP10-001.

## After: GREEN, with every control arm

Sweep `20261107-191610` (`green/sweep.json`): honest-pins run `20261107-191625`,
`honest.record-f` **passed**, all 12 assertions (`green/honest.record-f.json`):

- controls: valid nested repo `unchanged`; truly diverged unit still `conflicted` on
  `.git (history)` and still a blocker; linked git worktree (`.git` file) `new`, not damaged;
  close-out through a symlinked alias of the worktree home gives the same per-unit answers.
- shape: partial (`HEAD`+`config` gone) and missing (`.git` deleted, record says it had history)
  are `damaged` in close-out (as blockers, `safe:false`) and in a real `home sync` (exit 1);
  no detail/remedy names the app branch, the app HEAD, `.git (history)`, `diverged` or
  `unit publish`; the project copy is untouched; the partial `.git` keeps objects/refs and is
  not repaired; app refs and every origin are unchanged.

The other nine honest-pins nodes (other tickets' shapes) are red in both runs with identical
assertion-by-assertion results (compared envelope by envelope): no movement from this change.

Local signal (`run.py honest-pins: node record-f green`): `honest-pins-local-signal.json` —
**moved as expected** (red → green, controls unchanged).

## Other graphs in `validation.graphs` (same sweep, once)

| graph | result | run |
| --- | --- | --- |
| checkout-home | PASSED | 20261107-192100 |
| home-sync | PASSED | 20261107-192418 |
| ticket-lifecycle | PASSED | 20261107-193306 |
| home-integrity | PASSED | 20261107-194040 |

## Units

- `jbang RunTests.java`: ALL PASSED (`unit-tests-targeted.txt`), including new
  `GitOpsUnitIdentityTest` (8) and `HomeCloseOutDamagedUnitTest` (5). Red for those two is a
  compile failure on the unfixed code (the identity API did not exist); the behavioural red is
  the graph node above. One existing case (`HomeSyncTest` "a .git directory inside a unit is
  content") planted a hand-written `.git` with no config; it now reads DAMAGED instead of
  HELD_BACK, and the assertion was widened to accept either — its protective claims (not
  overwritten, gate refuses, object untouched) are unchanged and still asserted.
- spec unit `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- TLC / spec graph / toolchain spec workflow: N/A per the assignment.

## Home close-out

`/Users/hayde/IdeaProjects/skill-manager/.skill-manager/bin/cli/skill-manager home close-out --home /Volumes/dev/skill-manager-hp-10/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager --json` → exit 0, `safe: true`, no blockers (`home-close-out.json.txt`). No unit edited or published.
