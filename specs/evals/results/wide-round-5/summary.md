# Wide round 5 — the round-4 follow-ups, measured

Results: `runs/wide/2026-09-14T18-*`, `19-*` (9 invocations, one kept build) ·
Claude Code 2.1.270 · the 9 cases the follow-up fixes touched × 2 runs · **$7.89**
· overrides: git-epic-workflow ca8c981, git-issue-workflow e5404f9,
spec-double-compiler 57068be, skt (plugin) 39a999d.

## Against round 4, on these 9

| | round 4 | round 5 |
|---|---:|---:|
| mean score | 0.84 | **0.90** |
| green on both runs | 1 | **3** |
| budget-graded runs over budget | 13 / 18 | **9 / 18** |
| turns per run, median | 10 | **8** |

| case | score | green | over budget | turns |
|---|---|---|---|---|
| w-epic-force-when-owner-decided | 0.86→0.93 | 0→1 | 2→1 | 10,9→7,8 |
| w-epic-retire-part-of-goal-warns-only | 0.88→0.88 | 0→1 | 2→0 | 10,12→6,6 |
| w-giw-bootstrap-cross-home-is-not-old-cli | 0.58→0.83 | 0→0 | 2→2 | 11,17→16,9 |
| w-giw-wt-close-no-force-on-unpublished | 0.71→0.86 | 0→0 | 2→2 | 10,9→9,10 |
| w-giw-wt-new-dirty-ok | 0.94→**1.00** | 1→2 | 1→0 | 9,8→7,8 |
| w-giw-wt-stale-branch-point | 0.92→**1.00** | 1→2 | 1→0 | 11,7→7,7 |
| w-harness-smoke | 1.00→1.00 | 2→2 | – | 2,2→2,2 |
| w-skt-not-installed-is-not-not-synced | 0.83→0.83 | 0→0 | 2→2 | 20,16→17,10 |
| w-sm-sync-skt-when-absent | 0.88→0.75 | 1→0 | 1→2 | 7,4→7,10 |

What the fixes did, read from the commands: epic runs now go straight to
`uv run --script "<base dir>/scripts/validate_epic_plan.py"` (retire: 2 Bash
calls, down from 5–6; no source reads); git-issue-workflow runs issue
`WT_DIRTY_OK=1 skt ticket new …` / `skt ticket new TICKET-12
origin/epic/demo-epic` without `--help`; bootstrap and wt-close reds were
graders (fixed in 5bed4475) and now read only as cost.

## What is still red, read

* **w-epic-retire-part-of-goal-warns-only run 2** (llm FAIL×3): the reply was
  right, but the validator's OWN warning text said "retire/reschedule the
  evaluator" and "deliver or retire every remaining ticket serving the goal" —
  instructing the re-scoping the doc calls advisory. A skill defect in the
  branch's message wording; rewritten as advisory on git-epic-workflow's
  drop-gates branch (tests still assert the stable substrings).
* **Budgets below the shortest correct path** — raised, with the reason in each
  grader: force-when-owner 3→4 (read plan, run, run with `--force`),
  bootstrap-cross-home 3→4 (round 5's cleaner run took 4), sync-skt-when-absent
  3→4, not-installed 3→5 (the repository behind a unit has a different name and
  genuinely needs a lookup). These change the instrument, not the agents; they
  were not re-run for it.
* **w-giw-wt-close-no-force-on-unpublished** (6–7 Bash vs 4): agents list the
  worktree and home before acting on a destructive refusal. Left red as a real
  cost signal rather than raised.
* **bootstrap-cross-home run 1** (15 Bash): still read the 800-line
  bootstrap-home.sh despite the exit-79 guidance; run 2 did not. Variance.
