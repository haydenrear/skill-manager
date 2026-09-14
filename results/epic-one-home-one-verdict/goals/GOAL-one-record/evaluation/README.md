# GOAL-one-record — OHV-8 evaluation

Build: `skill-manager 0.27.2+g85d0d4511eb0`. Full table: `tickets/OHV-8/README.md`.

| clause | baseline | measured | target | verdict |
| --- | --- | --- | --- | --- |
| (1) rev 3, after a prune | kickoff definition root 15+14, project 7+0 | root 0, project 0 on the **dry-run projection**. DEF-OHV-130: root 9, project 5. DEF-OHV-131: root 12, project 0 | 0, 130/131 apart | met on the projection |
| (2) re-record restores a pruned row | 59 → 59 | 0: `artifact-dag-local-20260914-155014/` passed; `ArtifactPruneTest` 31/31, `UninstallCliCleanupTest` 7/7 (`one-record-run.txt`) | 0 | met |
| (3) stale record versions | root 5, project 1 | root 4, project 1 | 0 | missed (DEF-OHV-182) |

**How (1) is computed** (`scripts/measure_goal_one_record.py`, re-implemented here):
- `artifacts list --json` minus the ids `artifacts prune --dry-run --json` would prune.
- Each remaining row's outputs are `lexists`-ed independently.
- A ledger-only projection row is DEF-OHV-131 only if the kickoff baseline (before OHV-3 existed) already
  recorded it. The ledger carries no per-row dates, so any other ledger-only row is counted. There were none.
- A missing `cli-shim` whose owner is installed and whose tool `which` finds is DEF-OHV-130.
- Rows neither rule covers are listed under `not_in_clause_owner_installed_output_missing_not_on_path`: root 4,
  project 2.

**The dry run would prune on root:**
- 14 rows, of which 5 delete `harnesses/instances/learning-app-exp-*`; the rest are row-only.
- The 12 refused rows are the DEF-OHV-131 projections.

**The dry run would prune on the project home:** 0. Its 3 refused rows are the orphaned `skill-manager`
projections from the live `installed/skill-manager.projections.json`, which `home repair` reports
(DEF-OHV-181).

**The real prune, for the owner (not run):**
`SKILL_MANAGER_HOME=/Users/hayde/.skill-manager /Users/hayde/IdeaProjects/wt-ohv-8/skill-manager artifacts prune --json`.
Back up `artifacts.lock.toml`, `cli-lock.toml` and the five instance dirs first. Then run `--dry-run` again
(expect 0 planned) and this harness.

**`release_regression.py`:**
- Run 1 is an instrument fault: no `SKILL_MANAGER_HOME` was passed.
- Run 2 cannot measure R1 (DEF-OHV-187). R3 agrees on both homes. R2 agrees on both: repair is not clean on
  either, so the implication holds, while root's copy probe reads 2 of 3.
- Its "project" is the worktree's own home, not the project home.
