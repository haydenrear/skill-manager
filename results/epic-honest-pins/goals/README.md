# honest-pins terminal evaluation (HP-9, #413)

Measured on 2026-11-07 on the integrated epic tip `origin/epic/honest-pins` **b535623abf11**
(all ten implementation tickets merged: HP-1 #405, HP-0 #415, HP-3 #418, HP-5 #416, HP-6 #421,
HP-7 #420, HP-2 #417, HP-4 #423, HP-8 #422, HP-10 #419), in worktree
`/Volumes/dev/skill-manager-hp-9`, fresh build, `SKILL_MANAGER_HOME` unset,
`GRADLE_OPTS=-Dorg.gradle.daemon=false JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true
COMPOSE_PROJECT_NAME=skill-manager`. Docker was not running on this host.

Every number below is read from a sweep ledger or a node envelope, never from
`build/validation-reports/` as a whole. No target or baseline was edited.

## Goal verdicts

| Goal | Clause | Baseline | Measured | Target | Verdict |
| --- | --- | --- | --- | --- | --- |
| GOAL-a-pin-is-honoured | 5 planted pin shapes at the pin or refused by name | 0 of 5 at 3b92ed6e; 1 of 5 at 1b52427 (HP-0) | **5 of 5** | 5 of 5 | **met** |
| GOAL-the-record-describes-the-home | false/missing reports across shapes (a)-(f) | 6 of 6 shapes report falsely (a-e measured by HP-0 at 1b52427; f by HP-10's red run at 433f1090) | **0** of 6 shapes (all 14 shape assertions across the 6 record nodes green) | 0 | **met** |
| GOAL-the-record-describes-the-home | each shape's control arm still detected | every control green at baseline | **every control green** (11 control assertions) | all detected | **met** |
| GOAL-the-record-describes-the-home | `artifacts stale --json` false stale cli-shims, this repo's project home | 7 (brew docker, gh, helm, k3d, kubernetes-cli, opentofu; pip pytest) | **0** (15 cli-shims, 15 current) | 0 | **met** |

Harness run: `/Volumes/dev/.honest-pins/graph-run.sh honest-pins cli-pin --continue`
(the wrapper sets `TESTGRAPH_CONTINUE_AFTER_FAILURE=1`; the plan's `local_signal` string omits
it, and without it one red node skips the rest). Sweep **20261107-212708**:
`graphs_selected=2 graphs_executed=2 graphs_passed=2 graphs_failed=0`
(`honest-pins-cli-pin.sweep.json`, `honest-pins-cli-pin.run.log`).

### GOAL-a — per node (envelopes in `honest-pins-run/`, `cli-pin-run/`)

| shape | node | baseline (HP-0) | measured | assertions |
| --- | --- | --- | --- | --- |
| (a) #404 unit bumps its own CLI pin | `cli.pin.bump.reinstalls` (cli-pin, run 20261107-212730) | honoured (HP-1) | **passed** | 9/9 — v1 reports 1.0, bump reinstalls 2.0, cli-lock records 2.0, downloaded once, no-op sync keeps 2.0 and does not redownload |
| (b) #406 lock resolved_sha bumped | `honest.pin-b` | off-pin | **passed** | 6/6 — control_unchanged_lock_is_noop, shape_lock_pin_honoured_or_refused_by_name, shape_no_false_convergence |
| (c) #407 installed ahead of older pin | `honest.pin-c` | off-pin | **passed** | 5/5 — control_resolve_at_pin_keeps_pin, shape_resolve_restores_pin_or_refuses_by_name, shape_no_false_convergence |
| (d) #407 same, one local edit | `honest.pin-d` | off-pin | **passed** | 6/6 — control_local_edit_never_lost, shape_resolve_restores_pin_keeping_edit_or_refuses_by_name, shape_no_false_convergence |
| (e) #103 MCP binary url changed | `honest.pin-e` | off-pin | **passed** | 3/3 — control_unchanged_spec_served_from_cache, shape_changed_url_serves_new_binary |

### GOAL-b — per node (honest-pins run 20261107-212823)

| shape | node | baseline | measured | control arm(s) | shape assertion(s) |
| --- | --- | --- | --- | --- | --- |
| (a) #403 home sync arms drift | `honest.record-a` | false (pending=false) | **passed** 4/4 | control_noop_sync_records_nothing ✓ | shape_changing_sync_leaves_pending_drift ✓ |
| (b) #298 record version vs manifest | `honest.record-b` | false, 2 clauses | **passed** 6/6 | control_git_latest_rewrites_record_version ✓ | shape_home_sync_record_matches_manifest ✓, shape_sync_from_record_matches_manifest ✓ |
| (c) #409 system CLI as stale shim | `honest.record-c` | false (tar/sh stale) | **passed** 4/4 | control_installed_shim_present_is_not_stale ✓, control_deleted_installed_shim_is_stale ✓ | shape_system_cli_not_reported_stale ✓ |
| (d) #400 __pycache__ published | `honest.record-d` | false | **passed** 6/6 | control_real_edit_is_published ✓, control_real_edit_still_refuses_sync ✓ | shape_bytecode_not_published ✓, shape_bytecode_alone_does_not_refuse_sync ✓ |
| (e) #394 root sync removes declared plugin | `honest.record-e` | false, 2 clauses | **passed** 5/5 | control_undeclared_unmodified_unit_removed ✓ | shape_unit_declared_by_project_manifest_kept ✓, shape_removal_audited_in_child_home ✓ |
| (f) #412 damaged unit .git | `honest.record-f` | false (HP-10 red run 20261107-184251) | **passed** 12/12 | valid nested repo unchanged ✓, truly diverged still conflicted ✓, linked worktree not damaged ✓, symlinked home same answers ✓ | 6 shape assertions ✓ (damaged as blockers in close-out and sync, no enclosing identity/publish remedy, project untouched, partial .git preserved, no ref mutation) |

### Second instrument — `artifacts stale --json` on this repository's project home

`SKILL_MANAGER_HOME=/Users/hayde/IdeaProjects/skill-manager/.skill-manager ./skill-manager artifacts stale --json`
with the worktree build, read-only (an mtime digest of `installed/`, `bin/` and
`units.lock.toml` was identical before and after):

- `--kind cli-shim`: artifacts 15, **stale 0**, current 15 (baseline: 7 stale) —
  `project-home-artifacts-stale-cli-shim.json`.
- all kinds: artifacts 41, stale 0, current 38, unverifiable 3 (three `unit-digest` rows a
  read does not walk) — `project-home-artifacts-stale.json`.

The control arm for this instrument (a deleted installed shim must still be reported) is
`honest.record-c`'s `control_deleted_installed_shim_is_stale`, green above.

## The full run (owner: "at the end we'll do a full run")

`python3 test_graph/run-graphs.py` (the 27 graphs meant to run here at the time; honest-pins
was still opt-in and is measured above). Sweep **20261107-213337** (`full-run.sweep.json`,
`full-run.log`):

**selected 27 · executed 27 · passed 19 · failed 8 · not run 0**

| graph | result | first red node | class |
| --- | --- | --- | --- |
| smoke | FAILED | postgres.up — `docker compose up -d postgres exited 1` | (i) docker unavailable |
| onboard | FAILED | postgres.up — same | (i) docker unavailable |
| sponsored | FAILED | postgres.up — same | (i) docker unavailable |
| plugin-smoke | FAILED | postgres.up — same | (i) docker unavailable |
| doc-smoke | FAILED | skill-manager.skill.docs.projected — installed SKILL.md lacks "project child homes" | (ii) host: installed tla-spec-dev skill text drifted; **red identically on main 3b92ed6e** (DEF-HP9-002) |
| onboarding | FAILED | onboarding.docs.and.scripts.static — installed git-issue-workflow docs no longer reference `wt` | (ii) host: installed skill text drifted; **red identically on main** (DEF-HP9-002) |
| ticket-lifecycle | FAILED | ticket.lifecycle.global.home.untouched — only mtime changes (sizes equal) to `.last-complete-round` / `manifest.json` under `~/.claude/skills/synced/` | (ii) host noise: the live Claude session |
| home-tripwire | FAILED | sandbox.env.contract — `unrouted=[honest-pins/HonestPinsKit.java]` | **(iii) real regression, caused by HP-0** (DEF-HP9-001) |
| source-tracking, git-latest-source-tracking, harness-smoke, project-manifest, project-resolve, project-smoke, spec-conformance, project-child-home, home-clone, checkout-home, home-sync, project-env, project-libs, project-profiles, artifact-dag, home-integrity, home-verdicts, sync-settles, cli-pin | PASSED | — | — |

Not run (opt-in): refresh-flow, hyper-experiments, browser-auth, password-reset, and
honest-pins (run separately above).

### Confirming the non-docker reds against main

The worktree was detached to **3b92ed6e** and `run.py doc-smoke home-tripwire onboarding`
run there (sweep 20261107-225927, `main-3b92ed6e-confirm.*`):

| graph | main 3b92ed6e | epic tip | conclusion |
| --- | --- | --- | --- |
| doc-smoke | FAILED, same node and assertion | FAILED | pre-existing, not the epic |
| onboarding | FAILED, same node and assertion | FAILED | pre-existing, not the epic |
| home-tripwire | **PASSED** | FAILED | **epic regression** |

home-tripwire re-run alone on the epic tip: FAILED on the same node (sweep 20261107-230703,
`home-tripwire-confirm.*`). Re-run with `TESTGRAPH_CONTINUE_AFTER_FAILURE=1` to measure the
rest of the graph (sweep 20261107-230741, `home-tripwire-continue.*`): the other seven nodes
pass; `sandbox.env.contract` is the only red.

**Attribution.** `test_graph/sources/honest-pins/HonestPinsKit.java` was added by HP-0
(45128031, #408/#415) and no later ticket touched it. It calls `SmEnv.cli()`/`SmEnv.apply()`
but has no `//SOURCES ../lib/SmEnv.java` of its own (every node file has one), and the
tripwire judges each `.java` file by its own `//SOURCES` closure. It is a harness conformance
defect, not a product regression: every honest-pins node does reach SmEnv and runs sandboxed.
Not fixed here — an evaluation ticket does not repair what it measures, and the file is outside
HP-9's conflict keys.

Graph unions: every graph in every ticket's validation matrix (cli-pin, smoke, home-integrity,
home-verdicts, artifact-dag, project-child-home, home-clone, checkout-home, ticket-lifecycle,
home-sync, onboarding, onboard, project-resolve, source-tracking, git-latest-source-tracking,
project-smoke, sync-settles, plugin-smoke, honest-pins) is inside the full run plus the
honest-pins run.

## CI selection

honest-pins is fully green, so it was **removed from `EXCLUDED`** in
`.github/scripts/select-graph-set.py`; `--scope full --print` now selects **28** graphs
including honest-pins (`select-graph-set-full.txt`). CLAUDE.md's counts follow (28 run here,
4 opt-in; two graphs excluded from every automatic set). Note the full set also contains
home-tripwire, which will be red until DEF-HP9-001 is fixed.

## Unit and spec tests

- `jbang RunTests.java` → **ALL PASSED** (1689 `[PASS]` lines, 0 `[FAIL]`; `unit-tests.txt`).
- `uv run --with pytest pytest specs/program_model/tests -q` → **11 passed** (`spec-unit.txt`).

## Open deferred findings (results/epic-honest-pins/deferred/*.yaml)

| id | severity | from | summary |
| --- | --- | --- | --- |
| DEF-HP9-001 | major | HP-9 | home-tripwire red on the epic: HonestPinsKit.java resolves the CLI without its own `//SOURCES` to SmEnv (HP-0). **Regression.** |
| DEF-HP9-002 | minor | HP-9 | doc-smoke and onboarding assert installed tla-spec-dev skill text that has moved on; red on main too; downstream nodes unmeasured |
| DEF-HP2-002 | major | HP-2 | a byte copy of a project home keeps its project registration, and a sync in the copy materializes into the REAL project home |
| DEF-HP0-001 | minor | HP-0 | git 2.56 background maintenance leaves `objects/maintenance.lock` during unit-tree copies (two unit-test flakes) |
| DEF-HP0-002 | minor | HP-0 (re-read by HP-2) | `install git+file://` writes the units.lock row without origin/ref/resolved_sha until `sync --refresh` |
| DEF-HP2-001 | minor | HP-2 | a no-op `sync` of a sha-pinned unit reports "1 merged" |
| DEF-HP4-001 | minor | HP-4 | `home sync` fast-forward moves HEAD but leaves installed record gitHash stale |
| DEF-HP5-001 | minor | HP-5 | PARTIAL receipts' error messages are never printed by the console renderer |
| DEF-HP5-003 | minor | HP-5 | `skt check` times out probing the jbang-launcher pinned build |
| DEF-HP6-001 | minor | HP-6 | bytecode already tracked in a unit is never removed from the index by `unit publish` |
| DEF-HP8-001 | minor | HP-8 | root sync's summary line does not mention units removed from child homes |
| DEF-HP10-002 | minor | HP-10 | `--resume-from-build` refuses a continue-after-failure run (no attempt-closure written) |
| HP-7-D1 | (unrated; non-blocking) | HP-7 | `skt ticket new` loses a race on the shared index-base retention ref |
| DEF-HP5-002 | info | HP-5 | DEF-OHV-183 no longer reproduces (recorded so it can be closed) |
| DEF-HP6-002 | trivial | HP-6 | `unit publish --dry-run` wording says "would commit" for bytecode-only divergence |

Closed: DEF-HP10-001 (major, fixed inline by HP-10). `backlog.yaml` is empty; HP-3 filed none.
