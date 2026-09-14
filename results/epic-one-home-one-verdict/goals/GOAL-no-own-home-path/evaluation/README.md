# GOAL-no-own-home-path — OHV-8 evaluation

Build: `skill-manager 0.27.2+g85d0d4511eb0`. Full table: `tickets/OHV-8/README.md`.

| clause | baseline | 15:39Z | later | target | verdict |
| --- | --- | --- | --- | --- | --- |
| (1) own-path bin/cli entries | root 3, project 0 | root 0, project 0 (`measure_goal_no_own_home_path.json`) | **15:55Z root 3** half-rewritten, all reported by epic `home repair`; project 0 (`tickets/OHV-8/measure_goals-ledger.1555Z.txt`) | 0 | missed (latest) |
| (2) copied-home probe | root 2/3, project 3/3 | root 3/3, project 3/3 (`copied-home-*.json`) | **root 2/3** at 15:52Z and 15:59Z: (b) fails on the three shims (`../../GOAL-one-record/evaluation/release_regression.run*.json`) | 3/3 both | missed (latest) |
| (3) fleet | 51 of 61 | 33 of 57 (`fleet-epic-build.summary.json`) | — | reported | met (reported) |

**What happened between the readings:**
- At 15:50:45Z another session ran released 0.27.2 `home repair --fix` on the root
  (`tickets/OHV-8/leak-2026-09-14T1531/external-home-repair-20260914-115045-43f7.log`).
- 0.27.2's rewrite leaves exec lines literal: DEF-OHV-001, which OHV-4 fixed only in the epic build.
- The epic build reports all three shims, and home-verdicts' `half.rewritten.shim` node proves its `--fix`
  clears that shape.
- Nothing here ran `--fix` on the root. Owner command: in DEF-OHV-180.
