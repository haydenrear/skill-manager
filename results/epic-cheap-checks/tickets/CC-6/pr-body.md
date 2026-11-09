Refs #443

Epic `epic/cheap-checks` (#414), ticket CC-6, role evaluation. No TLA workflow. This ticket measures and changes no behavior.

Dependencies: PRs #445-#449 and #451 are merged into the epic, and tip 9af4f894 is merged here. Promotion predecessor CC-7 is merged.

**Both goals: every clause met.** Two graphs in the full sweep are red (`doc-smoke`, `onboarding`). Both were already red on main before this epic.

## Goal verdicts

Each run started with `test_graph/build` removed. Timing runs are sweeps 20261109-143450, -144121 and -144821. The full-sweep reading is 20261109-145430. Load average was 6-27 during the runs, so this was not a quiet machine.

| Goal | Clause | Kind | Baseline | Measured | Target | Verdict | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| checking-costs | (a) stale time | perf | 8.02 s | run medians 3.36, 3.48, 4.31; sweep 3.13 | < 6 s | met | `goals/GOAL-checking-costs-what-changed/summary.json` |
| checking-costs | (a) no file contents, clean | perf | 26,170 files | 0, all 4 runs (control: 26,170 dirty) | 0 | met | same |
| checking-costs | (a) planted edits detected | perf | 3 of 4 arms | 3 of 3; gitignored not detected | tracked, untracked, re-pointed link (owner, schedule_revision 2) | met | same |
| checking-costs | (b) close-out time | perf | 45.69 s | run medians 3.57, 3.48, 3.27; sweep 4.30 | < 5 s | met | same |
| checking-costs | (b) close-out RSS | perf | 1,277 MB | 232.6, 236.8, 232.4; sweep 236.9 MB | < 300 MB | met | same |
| checking-costs | (c) other installers re-run | perf | 2 of 2 | 0 of 2, all 4 runs | 0 | met | same |
| checking-costs | (d) other plugins re-projected | perf | 3 calls of 11 | 0 of 8, all 4 runs | 0 | met | same |
| excluded-paths | (a) files at excluded path | quality | 2 | 0, all 4 runs | 0 | met | `goals/GOAL-excluded-paths-keep-git/summary.json` |
| excluded-paths | (b) git operations | quality | 7 of 7 | 7 of 7, all 4 runs | all 7 | met | same |
| excluded-paths | (c) commit keeps tree | quality | yes | yes, all 4 runs | yes | met | same |

Stale readings by run: [3.35, 3.39, 3.36], [3.44, 3.48, 3.64], [6.31, 4.31, 3.67], sweep [3.13, 3.08, 3.15]. Median of the 9 timing-run readings is 3.48 s (3.415 s over all 12). One reading of 12 exceeded 6 s (6.31 s). The target is on the median, and that single reading is reported rather than dropped.

Close-out readings by run: [3.41, 3.73, 3.57], [3.44, 3.50, 3.48], [3.27, 3.19, 3.35], sweep [4.30, 4.30, 4.30].

## Validation

- Full sweep 20261109-145430, installed runner: `graphs_selected=28 graphs_executed=28 graphs_passed=26 graphs_failed=2 graphs_not_run=0` (`tickets/CC-6/full-sweep-summary.txt`). Failed: `doc-smoke`, `onboarding`. No retries were needed: no download failure turned a graph red (sweep stdout has no 5xx line; node logs were not searched), and `onboard` and `ticket-lifecycle` passed first time.
- `cheap-checks` passed in all 4 runs, so it leaves `EXCLUDED`. `select-graph-set.py --scope full` now selects 28 of 29 registered, and `run-graphs.py --list` shows 28 run and 1 opt-in.
- Spec unit: 11 passed. `jbang RunTests.java`: 1734 passed, 0 failed.
- Run outside the assignment's literal matrix: the 3 extra `cheap-checks` runs, requested for DEF-CC0-002.

## Deferred findings

- DEF-CC6-001, low: `doc-smoke` red, a hard-wrapped phrase in the plugin's SKILL.md. Pre-epic.
- DEF-CC6-002, low: `onboarding` red, `KNOWN_GOOD` still names `wt`. Pre-epic. This disposes of DEF-CC-EPIC-001: not a 5xx, and the `global.home.untouched` half did not reproduce.
- DEF-CC6-003, **medium**: `cheap-checks` is now in nightly `full`, but it needs BSD `time -l` and a checkout project home, and CI is ubuntu-only. Expect it to go red there. Owner decision before finalization.
- DEF-CC6-004, low: close-out with one edit in the large plugin takes 25-27 s. No target covers it, and clause (b) is on a clean close-out.

Dispositions of DEF-CC0-002, CC1-001/002, CC3-001/002, CC5-001 and CC7-001 are in `results/epic-cheap-checks/deferred/CC-6.yaml`.

## Home close-out

`safe: true`, exit 0, no blockers, nothing published (`tickets/CC-6/close-out-verdict.json`).

## Skill changes proposed

none met

## Review input

**Hot spots.** `.github/scripts/select-graph-set.py` (the EXCLUDED move, see DEF-CC6-003) and `CLAUDE.md` counts. No production code or harness changed.

**Decisions and overrides.**
- CC-6's status set to `pr_open`, matching HP-9.
- `TESTGRAPH_CONTINUE_AFTER_FAILURE=1` also applied to the full sweep, so a red node would not hide later ones.
- Full sweep run by the installed runner with the 28 graphs named, rather than `--all`, to keep the opt-in `hyper-experiments` out.
- Fresh start means `test_graph/build` removed; the jbang cache was kept, and the nodes exclude the CLI warm-up from their readings.
- Raw reports and node logs were not committed; only distilled JSON and summaries were. They remain under the worktree's ignored `test_graph/build/cc6-raw`.
- CC-7's `status` in the plan is still `planned` although merged; left for the epic agent.

**Where I'd look for bugs.**
- DEF-CC6-003 is the likeliest to bite first.
- The single 6.31 s stale reading: cheapest check is repeating the node on a quiet machine.
- The 'planted edits' clause: read it against the owner's amendment, since CC-1 changed that arm from detected to not-detected.

**Machinery friction.** None in this worktree's home, which is unchanged. `run-graphs.py` exits 0 regardless, so the verdict came from the installed runner.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
