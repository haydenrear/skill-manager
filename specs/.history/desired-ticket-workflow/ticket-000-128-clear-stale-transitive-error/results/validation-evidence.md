# Validation evidence — 128-clear-stale-transitive-error

Issue: https://github.com/haydenrear/skill-manager/issues/128
Branch: `fix/128-clear-stale-transitive-error` (base `main` @ `06d6fbe`, tree `a49dec9`)

## Production change

- `src/main/java/dev/skillmanager/effects/ResolveGraphHandlers.java` — sync's
  `TRANSITIVE_RESOLVE_FAILED` clear loops now iterate every live skill
  (`liveParents`), not only parents still declaring references, so a parent
  whose offending (only) reference was removed clears its persisted error.
  Added `allReferencesMetInStore` as the shared cheap store-membership probe.
- `src/main/java/dev/skillmanager/effects/LiveInterpreter.java` —
  `validateAndClear` is now an exhaustive switch expression (a new
  `ErrorKind` fails compilation until it gets a reconcile decision). New
  `TRANSITIVE_RESOLVE_FAILED` case: zero declared refs clears
  unconditionally; otherwise clears only when every declared ref is met by
  an installed unit. `PROJECT_SYNC_FAILED` is explicitly in the no-cheap-probe
  group (clears on the next successful parent-home project refresh).

## Model

- `specs/current/SkillManager.tla` == `specs/desired_program_model/SkillManager.tla`
  (verified by `diff`): `SyncUnit` / `SyncUnitForceScripts` success branches
  now clear the unit's persisted resolve error (`cli_errors' = cli_errors \ {u}`).
- TLC: `bash specs/current/run_tlc.sh SkillManager.tla MC.cfg` —
  "Model checking completed. No error has been found." (2026-07-20)
- Note: `specs/program_model` is the single-module baseline (no
  Internal.tla/External.tla), so no spec-generated Test Graph cases exist to
  run; external coverage is provided by the hand-written test_graph node below.

## Unit tests

- `jbang RunTests.java` — ALL PASSED (2026-07-20), including new
  `ResolveGraphDirectGitSyncTest` cases:
  - "removing the offending reference clears the stale error on the next sync"
  - "reconcile validateAndClear clears the error only once refs are met or removed"
  - existing attribution case stays green.
- `uv run --with pytest pytest specs/current/tests specs/tickets/128-clear-stale-transitive-error/tests`
  — 9 passed.

## Test graphs (all from the worktree, COMPOSE_PROJECT_NAME=skill-manager,
GRADLE_OPTS=-Dorg.gradle.daemon=false, -Djava.net.preferIPv6Addresses=true)

- `source-tracking` — BUILD SUCCESSFUL (run `20260721-004324`); new node
  `source.sync.clears_stale_transitive_error` passed all three assertions:
  `failing_sync_records_error`, `ref_removed_sync_clears_error`,
  `ref_removed_sync_exits_zero`
  (`test_graph/build/validation-reports/20260721-004324/`).
- `smoke` — BUILD SUCCESSFUL.
- `project-resolve` — BUILD SUCCESSFUL.
- `hyper-experiments` (includes `hyper.sync.clean.noop`) — BUILD SUCCESSFUL.
- Full `--all` sweep — BUILD SUCCESSFUL in 16m 22s (exit 0), no failed
  tasks; latest reports under
  `test_graph/build/validation-reports/` (runs `20260721-010648` and earlier
  in the same sweep).
