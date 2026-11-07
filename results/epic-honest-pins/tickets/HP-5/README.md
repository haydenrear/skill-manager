# HP-5 (#376) evidence

Branch `feature/HP-5`, based on epic tip `433f1090ab6c`.

## What changed

| Defect | Fix |
| --- | --- |
| DEF-OHV-184: `onboard` writes no `bin/cli/skill-manager` | `OnboardCommand.ensureCliPin` -> `LauncherShims.pinIfAbsent` writes the launchers and the pin (the `home shims` shape) when the home has none; an existing pin is kept; an unlocatable CLI warns with the `home shims` remedy |
| DEF-OHV-186: `project resolve` prints "exit=0, errors=1" and names no error | `InstallUseCase.Report.failures` records `<Effect>: <message>` per FAILED/PARTIAL receipt; `ProjectDependencyResolver.publishFailureMessage` prints them, and shows a halt code only when there was one |
| DEF-OHV-183: onboard installs a vendored old skt | Already fixed before this epic (OUN-6/SI-18): nothing is vendored; onboard fetches `github:haydenrear/tla-spec-dev-plugin`. Recorded as DEF-HP5-002 |

## Real runs

- `onboard-fresh-home.log`: this branch's `./skill-manager onboard --skip-gateway` into an empty scratch home
  (`SKILL_MANAGER_HOME`, `CLAUDE_HOME`, `CODEX_HOME`, `GEMINI_HOME` all scratch). It prints
  `✓ pinned CLI: <home>/bin/cli/skill-manager -> /Volumes/dev/skill-manager-hp-5/skill-manager`.
  Exit 1 came from a codex `AGENT_SYNC_FAILED` because the scratch `CODEX_HOME` directory did not exist yet.
  That is my fixture, not the change.
- `onboard-fresh-home-cli-pin.txt`: `bin/cli` now holds `skill-manager` next to `skt`. The pinned entrypoint
  runs (`--version` exit 0). `skt check` changed from "(no-cli)" to "(timeout)" (DEF-HP5-003).
- `project-resolve-unreachable-gateway.log`: `project resolve` without `--skip-gateway`,
  `SKILL_MANAGER_GATEWAY_URL=http://gw.invalid:9`. It now says
  `✗ project dependency resolve failed publishing declared units: 1 effect(s) failed — EnsureGateway: remote gateway unreachable`, exit 1.

## Local signal (GOAL-a-pin-is-honoured, guard)

`run.py onboarding`, node `onboarding.bootstrapped`, new assertion
`the_onboarded_project_home_holds_bin_cli_skill_manager`: **passed**
(`onboarding-graph/onboarding.bootstrapped.json`). Classification: **moved as expected** for `skill-manager onboard`
(no pin before, pin after, shown by the real run above). For the graph's bootstrap-home.sh path it is
**no measurable movement**: `bootstrap-home.sh` already ran `home shims`, so that home had the pin before
this change too. The assertion now guards it.

## Graph run (`graph-run.sh onboarding --continue`, sweep 20261107-182306)

The graph FAILED: 18 nodes passed and 3 failed. The two law nodes (`HomeFixpointLaw`, `HomeMembershipLaw`) did
not run, because they depend on `onboarding.global.home.untouched`. None of the three failures touches code
this ticket changed:

- `onboarding.docs.and.scripts.static`, `the_extractor_found_the_four_known_good_scripts`: this checks the installed git-issue-workflow skill's docs and scripts. Those are tla-spec-dev plugin content.
- `onboarding.wt.contract.lines`, `wt_new_succeeded_in_exactly_one_line`: this checks the shape of `skt ticket new` output. `skt` printed a multi-line block when this worktree was created.
- `onboarding.global.home.untouched`, `the_operators_real_agent_homes_did_not_move`: 4 differences out of 107604 entries over 16 minutes. Five other ticket agents and this session were writing to the operator's agent homes during that time. Config registrations were unchanged (0).

`node-statuses.txt` and `sweep-summary.txt` hold the full record.

## Unit tests

`jbang RunTests.java`: ALL PASSED, 1637 cases, exit 0. That includes the new `OnboardCliPinTest` (6 cases).
The first full run's log ended in a stray `FAILURES: 1` line even though it had 0 `[FAIL]`, printed
`ALL PASSED`, and exited 0. I reran it clean (`ALL PASSED`, exit 0).

## Home close-out

`home-close-out.txt`: clean (exit 0).

## Spec unit

`uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
