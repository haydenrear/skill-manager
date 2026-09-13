# OHV-0 evidence — home-verdicts graph and the goal harnesses

Ticket: #356, branch `feature/OHV-0` from `origin/epic/one-home-one-verdict` at `de423138`.

## 1. What today's tree says about each planted shape

Measured first by hand (a scratch home laid out from nothing, this tree's
`./skill-manager`), then pinned as graph nodes:

| shape | node | `home repair --json` | `home verify` |
| --- | --- | --- | --- |
| clean home, laid out from nothing | `home.verdicts.clean.home` | 0, `clean:true` | 0 |
| fully frozen own-home shim | `home.verdicts.frozen.shim` | 1, `FROZEN_HOME_PATH_IN_SHIM` | **0** |
| foreign path in a shim | `home.verdicts.foreign.path.in.shim` | 1, `FOREIGN_PATH_IN_SHIM` | 1, names it |
| misanchored agent link | `home.verdicts.misanchored.agent.link` | 1, `MISANCHORED_AGENT_LINK` | **0** |
| unstamped pm tree | `home.verdicts.unstamped.pm.tree` | 1, `UNSTAMPED_PM_TREE` | **0** |

GOAL-one-verdict clause (1) on today's tree: **1 of 4** planted shapes reported
by both readers (foreign path). Each shape node also runs `home repair --fix`,
then a separate detection and `home verify`, and both are clean afterwards.

Pending shapes are listed in `HomeVerdictsFixture.java`'s header and in the
graph's registration comment, each naming the ticket that adds its node.

## 2. Goal harnesses vs the kickoff baseline

`python3 scripts/measure_goals.py --epic one-home-one-verdict` (run with
/usr/bin/python3 3.9.6): `measure_goals-ledger.txt`, 0 of 3 met, exit 1.

| harness | kickoff (71c51464) | today (this branch) | file |
| --- | --- | --- | --- |
| `measure_goal_one_record.py` | root 15 missing + 14 ledger-only, versions 5; project 7 + 0, versions 1 | **identical** | `measure_goal_one_record.json` |
| `measure_goal_no_own_home_path.py` | root 3 (computeq, helm-deploy, monitoring), half-rewritten, unreported; project 0 | **identical** (3 half-rewritten, 3 unreported by `home repair`) | `measure_goal_no_own_home_path.json` |
| `measure_goal_one_marketplace_identity.py` | root shape 4, shape 2 exposed; project none | **identical** | `measure_goal_one_marketplace_identity.json` |

Fleet context (`--fleet`, file reads only; not a target): 60 homes today
against 61 at kickoff. Own-path `bin/cli` homes: 39, against 51. #352 shapes
1/3/4: 0/16/11, any 25, against 0/15/11, any 24. The fleet moved between the two
readings, and nothing here claims those numbers.

"This project home" is the main checkout's home
(`/Users/hayde/IdeaProjects/skill-manager/.skill-manager`), found through git's
common dir, so the harnesses measure the baseline's home from a worktree.

Every harness is read-only: `artifacts list --json`, `home repair --json`
(without `--fix`), `git rev-parse`, and file reads.

## 3. Validation

- `jbang RunTests.java`: ALL PASSED, exit 0.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- `validate_epic_plan.py` with OHV-0 `status: closed`: OK.
- `select-graph-set.py --scope full --print`: 26 selected, `home-verdicts`
  included, and CORE/EXCLUDED unedited.

## Deferred findings

None.
