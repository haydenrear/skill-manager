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

The nodes read `home repair --json` stdout by parsing it; nothing matches it as
text. `HomeVerdictsSupport.RepairReport` uses the SDK's Jackson mapper with
`FAIL_ON_TRAILING_TOKENS` and matches `kind` and `subject` by field, regardless
of key order or whitespace. `home.verdicts.clean.home` checks the parser itself:
reordered keys match, while a near-miss subject, a stdout banner and trailing
content do not. The epic agent asked for this change after the first local run.
That run (`20260913-234634`) matched findings by exact key order.

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

- `run.py home-verdicts` (local macOS, run `20260913-234634`): **9 of 9 nodes
  passed**, BUILD SUCCESSFUL in 1m31s. `home.fixpoint.law` checked 1 home and
  repaired 0. `home.membership.law` checked 1 home, observed 0 units and passed
  its self-test. The damaged homes never reached either law.
  Copied to `graph-home-verdicts-local/`. That was the first version, with the
  text matcher.
- `run.py home-verdicts` after the JSON-parser change (local macOS, run
  `20260914-000125`): **9 of 9 nodes passed**, BUILD SUCCESSFUL in 1m08s,
  including the parser self-check in `home.verdicts.clean.home`. This run is the
  local evidence for the PR head. Copied to `graph-home-verdicts-local-json/`.
- **Reconciled with the epic tip.** `git merge --no-ff
  origin/epic/one-home-one-verdict` at `b218322c` (after OHV-7 #363, bringing
  OHV-1's build stamp) produced `e70e491a` with no conflicts, and OHV-0 is
  still `status: closed`. `run.py home-verdicts` on the merged tree (local
  macOS, run `20260914-015344`): **9 of 9 nodes passed**, BUILD SUCCESSFUL in
  12m32s. The run is Docker-free, so the unresponsive local Docker did not
  touch it. The slowdown is unexplained; a recompile after the merge is the
  likely cause. `home repair --json` stdout now carries
  `"build":"skill-manager 0.27.2+ge70e491a1378 @ e70e491a1378 (refs/heads/feature/OHV-0)"`
  between `home` and `examined`, and `home verify` starts with a `build:` line.
  The parser matched by field regardless, and no assertion changed. Copied to
  `graph-home-verdicts-local-merged/`.
- `run.py home-integrity` (local macOS, run `20260913-235031`): 19 of 19 nodes
  passed, BUILD SUCCESSFUL in 2m19s.
- `skill-manager home close-out --home <worktree>/.skill-manager --into
  <project>/.skill-manager`: exit 0, "holds nothing that removing it would
  destroy". No units were changed in the worktree home.
- `jbang RunTests.java`: ALL PASSED, exit 0.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- `validate_epic_plan.py` with OHV-0 `status: closed`: OK.
- `select-graph-set.py --scope full --print`: 26 selected, `home-verdicts`
  included, and CORE/EXCLUDED unedited.

## 4. CI — the graph evidence

Run [34791432559](https://github.com/haydenrear/skill-manager/actions/runs/34791432559)
ran `workflow_dispatch` with `graph_set=full` on `feature/OHV-0` at `2bc05b0e`,
the head with the JSON parser. Its artifact is
`ci-34791432559/graphs-executed.json`.

| | this run | main baseline (34786751392) |
| --- | ---: | ---: |
| selected | 26 | 25 |
| executed | **26** | 25 |
| passed | **20** | 19 |
| failed | **6** | 6 |
| unaccounted | 0 | — |

- **`home-verdicts`: executed, 9 of 9 nodes attempted, passed on Linux.**
- `home-integrity`: 19 of 19, passed.
- Failed: `artifact-dag`, `checkout-home`, `home-clone`, `home-tripwire`,
  `onboarding`, `ticket-lifecycle`. That is exactly main's baseline set, so
  there is **no new failure**. The +1 executed and +1 passed is `home-verdicts`.
- `skill-manager unit tests (RunTests.java + spec models)` and
  `virtual-mcp-gateway pytest` passed.
- Run 34790739553, on the earlier text-matcher head `7d2eef26`, was cancelled
  after the parser change and is not evidence.

## Deferred findings

None.
