# OHV-8 — terminal evaluation of `one-home-one-verdict`

The ticket whose whole slice is deciding the epic's seven goals on the integrated tip.
Performed 2026-09-14, 15:33Z to 16:10Z, on `feature/OHV-8` at `85d0d451`. That commit is the epic tip
`57b96b9d` plus a `results/`-only merge, so the code is identical. Every contributor
(OHV-0..7, OHV-9) was merged first.

- **Build that judged every real and scratch home:** this worktree's raw
  `./skill-manager` → `skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0`. Every verdict names this build.
- **Second builds:** released `/opt/homebrew/bin/skill-manager` 0.27.2 (`artifact 862d3a4c6017`), and
  `wt-ohv-9`'s raw build `0.27.2+g71aad2fdb576`.
- **Real homes were read-only:** no `--fix`, prune, sync, install or uninstall on `~/.skill-manager` or
  any IdeaProjects home. Everything that wrote ran in scratch homes under the session scratchpad.
- **Baselines** are the kickoff's (`baseline/2026-09-13-v0.27.2/`). **No target was edited.**

## Verdict: 19 met, 3 missed, 1 unmeasured, out of 23 clause rows

The misses:
- GOAL-one-record (3), stale record versions;
- GOAL-no-own-home-path (1) and (2), both on the root's latest reading.

The unmeasured row is GOAL-one-marketplace-identity (1b), AGENT_SYNC_FAILED on a sync.

The one thing to read before the table: **the root home was written three times on 2026-09-14 by
other sessions**. The last write happened *during* this evaluation (DEF-OHV-180):

| time (EDT) | writer | resulting root state |
| --- | --- | --- |
| 08:41 | CDC `cdcWorktreeOverlayIsolation`, through `bin/cli` links | DEF-OHV-011, fixed by the owner |
| 11:31 | CDC `epic/library-sharing` `skill-manager-worktree-lifecycle`, through the same kind of links | `computeq`/`monitoring`/`helm-deploy` run another home's test report |
| 11:50:45 | another session's **released 0.27.2** `home repair --fix` | the same three shims re-pointed at the root's own cache, **half-rewritten**: DEF-OHV-001's shape |

The epic build reports both states; released 0.27.2 reports the second as clean. GOAL-no-own-home-path
(1)/(2) read met at 15:39Z and missed at 15:55Z/15:59Z. Both runs are recorded below; the verdict is
taken from the latest reading.

## Per-clause table

| Goal | Clause | Kind | Baseline (kickoff) | Measured (epic build) | Target | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| GOAL-one-verdict | (1) planted shapes reported by both verify (non-zero) and repair | quality | no such graph | **11 of 11** planted shapes. Each node asserts repair exits 1 naming kind + subject, verify exits 1 naming it, `--fix` clears it. The shapes: frozen shim, foreign path in shim, misanchored link, unstamped pm, dangling agent link, orphaned projection record, half-rewritten shim, marketplace shapes 1–4. Plus the composite verify-names-every-finding node. Local run `20260914-153435`: 18/18 nodes, laws green | every planted shape reported by both | **met**. `/var` is not planted, see DEF-OHV-185 |
| GOAL-one-verdict | (2) facts an independent disk observation finds damaged while verify or repair exits 0 | quality | root 4, project 4 | **root 0, project 0** in both passes. 15:38Z: root 3 foreign-path shims, project 3 dangling links + 1 orphaned record, all reported by both (rc 1/1). 15:57Z: root 3 half-rewritten shims, the project unchanged, all reported by both. Excluded: `bin/cli/tlc2` → root, which verify names as the sanctioned parent-store shim (kickoff O8) | 0 on both, for the shapes verify/repair own | **met**. Both homes are still damaged, and released 0.27.2 exits 0 on them (DEF-OHV-180/181) |
| GOAL-one-verdict | (3) homes where verify exits 0 and repair reports damage | quality | 50 of 61 (released) | **0 of 57**. Excluded: `wt-epic-one-home-one-verdict` and `wt-ohv-0`..`wt-ohv-9` (11). verify≠repair in either direction: 0 | 0 | **met** |
| GOAL-one-record | (1) rev 3: rows whose owner and outputs are gone, plus post-OHV-3 ledger-only rows, after a prune | quality | kickoff definition: root 15+14, project 7+0; rev-3 definition unmeasured | Measured on the **dry-run projection** (`artifacts prune --dry-run --json`; no real prune). **root 0, project 0**. Reported apart: DEF-OHV-130 root 9, project 5; DEF-OHV-131 root 12, project 0. The dry run would prune root 14 (5 path-deleting harness instances) and project 0. Kickoff definition today: root 15+14, project 7+0 | 0 on both, 130/131 reported separately | **met** on the projection. The owner's real-prune command is below |
| GOAL-one-record | (2) rows a prune drops that the re-record restores | quality | 59 before, 59 after (#292) | **0**. `artifact-dag` green locally (`20260914-155014`, 10/10; `uninstall.prunes.the.subgraph` byte-comparable, census names nothing removed, 12→6). `ArtifactPruneTest` 31/31, including "a prune stays pruned". `UninstallCliCleanupTest` 7/7 | 0 | **met** |
| GOAL-one-record | (3) records whose version disagrees with the checkout while the hash agrees | quality | root 5, project 1 | **root 4, project 1** | 0 | **missed**. OHV-3 (c) is proven by `RecordVersionRefreshTest` 3/3 only; no epic-build sync has run on these homes (DEF-OHV-182) |
| GOAL-no-own-home-path | (1) bin/cli entries spelling the home's own path | quality | root 3, project 0 | 15:39Z: root 0, project 0. **15:55Z: root 3** (computeq, helm-deploy, monitoring, half-rewritten, all reported by epic `home repair`), project 0 | 0 | **missed** on the latest reading, after the 11:50:45 released-build `--fix` (DEF-OHV-180) |
| GOAL-no-own-home-path | (2) copied-home probe properties | quality | root 2 of 3, project 3 of 3 | 15:39Z: root 3/3, project 3/3. **15:52Z and 15:59Z: root 2/3** ((b) fails on the 3 shims). Project 3/3 | 3 of 3 on both | **missed** on the latest root reading |
| GOAL-no-own-home-path | (3) fleet homes with such an entry | quality | 51 of 61 | 33 of 57 (all 33 on running lines; `FROZEN_HOME_PATH_IN_SHIM` reported on the same 33) | reported, no threshold | **met** (reported) |
| GOAL-one-marketplace-identity | (1a) #352's four planted shapes reported and cleared | integration | no such graph | **4 of 4**. Each node asserts repair names the kind, verify exits 1, `--fix` then a separate detection is clean, verify is clean, and the untouched entries survive | 4 of 4 reported and cleared | **met** |
| GOAL-one-marketplace-identity | (1b) 0 AGENT_SYNC_FAILED on the next sync | integration | — | 0 AGENT_SYNC_FAILED in every home-verdicts node log. **No node runs a sync**, and the planted units are local-file installs that `sync` refuses (`NEEDS_GIT_MIGRATION`). Supporting evidence, not the measurement: OHV-6's four real-home `sync skt` runs, 0 each | 0 | **unmeasured**. The graph has no sync step; owner command below |
| GOAL-one-marketplace-identity | (2) shapes present on root and project | integration | root shape 4 (+2 exposed), project none | **root 0, project 0** by `home repair --json` MARKETPLACE_* kinds, both passes. The old harness: root none (shape 2 exposed), project none | 0 | **met** |
| GOAL-one-marketplace-identity | (3) fleet homes with shape 1, 3 or 4 | integration | 0/15/11, any 24 of 61 | **Corrected** (`home repair` kinds, 57 homes): shapes 1/2/3/4 = 1/0/15/6, any of 1/3/4 = 22. **Old harness** (68 homes, no exclusions): 0/17/7, any 22. It misses shape 1 (DEF-OHV-160) and over-counts 3 and 4 | reported, no threshold | **met** (reported) |
| GOAL-ci-green-fresh-runner | (1) executed vs selected, failed, exclusions | integration | 25 selected / 25 executed / 19 passed / 6 failed | **run 34862952922** (`graph_set=full`, epic tip `57b96b9d`): **26 selected / 26 executed / 26 passed / 0 failed**, 0 unaccounted, 359/359 nodes; 30 jobs success, 1 skipped (Selenium). Not selected (4): `refresh-flow` and `hyper-experiments`, both in EXCLUDED with reasons; `browser-auth` and `password-reset`, which the selector prints as browser graphs for the `test-graph-browser` job. That job was skipped because the harness command does not pass `run_browser_graphs=true` | executed == selected, 0 failed, exclusions carry a reason | **met**. Caveat: the two browser graphs' reason is printed by the selector, not held in EXCLUDED, and they ran nowhere in this measurement |
| GOAL-ci-green-fresh-runner | (2) a local multi-graph run with a failing graph reports every graph it ran and none it didn't | integration | Gradle stopped at the first red; stale reports counted | **yes**. `doc-smoke artifact-dag sync-settles`, with `artifacts.enumerated` made red by an uncommitted edit (reverted; `revert-proof.txt`). Result: 3 selected / 3 executed / 2 passed / 1 failed / 0 not_run; `sync-settles` ran after the red. The same session's earlier `home-verdicts` report was in `validation-reports/` and was not counted | yes | **met** |
| GOAL-verdict-names-its-build | (1) `home verify` names its build across two builds | quality | 0 of 5 | epic names `85d0d4511eb0`; OHV-9 build names `71aad2fdb576`; released names none | 5 of 5, distinguishable | **met** |
| GOAL-verdict-names-its-build | (2) `home repair` | quality | 〃 | same (text `build:` line, JSON `build` field) | 〃 | **met** |
| GOAL-verdict-names-its-build | (3) `home drift` | quality | 〃 | same (JSON `build`) | 〃 | **met** |
| GOAL-verdict-names-its-build | (4) `artifacts list` | quality | 〃 | same (text and JSON) | 〃 | **met** |
| GOAL-verdict-names-its-build | (5) `skt check` | quality | 〃 | **skt#46 branch `d3004cf`**, one scratch home, three pins. Each prints its own build: epic, OHV-9, released `0.27.2 @ artifact 862d3a4c6017`. JSON schema 6 `skill_manager_build` also differs per pin. With no pin: "no skill-manager build consulted (no-cli)", see DEF-OHV-184 | 〃 | **met on the PR branch**; the released-pair reading happens at finalization |
| GOAL-a-home-writes-only-itself | (1) child skill-script install leaves the parent byte-identical and gives the child its own shim | quality | no (real root overwritten) | **yes**. `home.verdicts.child.install.writes.only.itself` 9/9 (`parent.files.changed` 0, the child's shim is token-form and runs its own tool) | yes | **met** in scratch. On this machine the root was overwritten again by builds without OHV-9 (DEF-OHV-180) |
| GOAL-a-home-writes-only-itself | (2) an installer writing through a foreign target is caught and names the other home | quality | no | **yes**. `SkillScriptWriteThroughTest` 7/7 on the tip, including "writes the other home's file … fails the install, naming that home" and "recreates the link back out is a foreign write" | yes | **met** |
| GOAL-a-home-writes-only-itself | (3) every other bin/cli writer | quality | unmeasured | **yes**. `BinCliWritersDoNotFollowLinksTest` 7/7. OHV-9's table checked against the code on the tip (see goal README) | yes, or each backend named | **met** |

`GOAL-verdict-names-its-build` is five rows because its metric counts five commands. Whole-goal readings:
one-verdict **met**; one-record **not met** ((3)); no-own-home-path **not met** ((1), (2) on the latest
root reading); one-marketplace-identity **met with (1b) unmeasured**; ci-green **met**; verdict-names-its-build
**met on the PR branch**; a-home-writes-only-itself **met** (scratch and unit), with DEF-OHV-180 showing it
does not yet protect this machine.

### Unmeasured, and what would measure it

| clause | why | owner command |
| --- | --- | --- |
| marketplace (1b) | home-verdicts nodes run no sync; their units are local-file installs | on a real home with marketplace findings, back up, then `…/wt-ohv-8/skill-manager home repair --home <h> --fix --json`, `SKILL_MANAGER_HOME=<h> …/wt-ohv-8/skill-manager sync skt --yes 2>&1 \| grep -c AGENT_SYNC_FAILED` (OHV-6 did this on 4 homes: 0) |
| one-record (1) is read off a projection | a real prune writes a real home | root, after backing up `artifacts.lock.toml`, `cli-lock.toml` and `harnesses/instances/learning-app-exp-*`: `SKILL_MANAGER_HOME=/Users/hayde/.skill-manager /Users/hayde/IdeaProjects/wt-ohv-8/skill-manager artifacts prune --json`, then `… artifacts prune --dry-run --json` (expect 0 planned) and `SKILL_MANAGER_MEASURE_CLI=… python3.12 scripts/measure_goal_one_record.py`. The project home's dry run plans 0, so no prune is needed there |
| one-record (3), no-own-home-path (1)/(2) (missed) | need writes | `sync` commands in DEF-OHV-182; `home repair --fix` commands in DEF-OHV-180/181 |

## The two things never tested for real

**Onboard from a machine with no home** (`fresh-machine-onboard/`).
- **Setup:** a scratch `HOME` with `JAVA_TOOL_OPTIONS=-Duser.home=<scratch>`, `CLAUDE_HOME`,
  `CLAUDE_CONFIG_DIR`, `CODEX_HOME` and `GEMINI_HOME` inside it, and no `~/.skill-manager`. A tripwire hashed
  the real agent and root files before and after.
- **Path:** README "Install the CLI", with the epic build on PATH in place of `brew install`, then `--help`,
  `gateway up`, `onboard`, `list`, `gateway status`.
- **Run 1 (kept): an instrument fault.** A symlink on PATH cannot start `./skill-manager`, which resolves
  `SkillManager.java` beside the invoked path, so every step exited 2.
- **Run 2:** every step exited 0.
  - `gateway up` on port **51799**, because the operator's live gateway holds 51717. It installed
    virtual-mcp-gateway and wrote the three scratch agent configs.
  - `onboard`: `installed=2 gateway=up`, 7 s.
  - `home verify`/`repair`/`drift`/`artifacts list`: clean.
  - `gateway down` refused ("attached … does not own it"). My `SKILL_MANAGER_GATEWAY_URL` override makes
    `GatewayConfig` non-owning, so this is an artifact of the setup. The scratch gateway was stopped by pid
    after checking its `--config` named the scratch home.
  - The tripwire showed real files unchanged, and no root file newer than the start except `cache`/`logs`.
- **Failures a new user would hit:**
  - **DEF-OHV-183:** onboarding from a checkout installs the vendored skt **0.3.0**, and the first `skt check`
    says skt and skill-manager are stale.
  - **DEF-OHV-184:** no `bin/cli/skill-manager` pin is written, so `skt check` cannot name the skill-manager build.
- **Not measurable:** a Homebrew user's onboard, which fetches from GitHub. The epic build has no bottle.

**Child-home fan-out** (`child-home-fanout/`).
- **Setup:** a scratch project home `proj/.skill-manager` holding `u-shared`, and two child projects resolved
  from it. `child-a` claims `u-one` and `u-two`; `child-b` claims `u-one` and `u-three`.
- **Run 1 (kept):** resolve without `--skip-gateway`, against a gateway I had pointed at an unreachable host.
  It failed with a message that names no cause (DEF-OHV-186).
- **Run 2:** resolve with the graph's `--skip-gateway --json`.
  - Both children resolved (rc 0), and `home verify` is clean on the parent and both children.
  - Every child unit is a real directory inside its own home, not a symlink. `u-one`'s `SKILL.md` has a
    different inode in each child.
  - The parent records `child-homes/project_ohv8-child-a` and `project_ohv8-child-b`.
  - An install of `u-child-only` into child-a left **the parent byte-identical (27 entries)** and **the sibling
    child-b byte-identical (8 entries)**. The parent does not hold `u-child-only`.
  - The tripwire showed real files unchanged.
- **Graph evidence alongside it:** CI `project-child-home` (below) and home-verdicts' child-install node.

## Measurement code changed (measurement only, no product code)

| file | change |
| --- | --- |
| `scripts/home_observations.py` | `cli_for` honours `SKILL_MANAGER_MEASURE_CLI`, so one named build judges every home instead of each home's pin |
| `scripts/measure_goal_one_record.py` | clause (1) re-implemented for the revision-3 definition: a dry-run prune projection; owner-gone-and-outputs-gone rows; ledger-only rows not provably pre-OHV-3 (dated against the kickoff record); DEF-OHV-130/131 counted apart. The kickoff definition is still reported |
| `scripts/measure_fleet_one_home_one_verdict.py` (new) | the fleet pass judged by one build: verify/repair, own-path shims, and marketplace shapes from `home repair` kinds (DEF-OHV-160 corrected) beside the old harness's shapes |
| `scripts/release_regression.py` | the same build override. Run 1 passed no `SKILL_MANAGER_HOME` (instrument fault, kept); fixed for run 2. Its R1 is still unmeasurable (DEF-OHV-187) |
| `goals/GOAL-one-verdict/evaluation/observe_real_homes.py` (new) | clause (2)'s independent observation, adapted from the kickoff's `measure_baseline.py` |
| `goals/*/evaluation/Run*Tests.java`, `tickets/OHV-8/*/*.sh` | scratch runners for the focused unit cases, the onboarding and the fan-out |

## CI

**Run 34862952922** (https://github.com/haydenrear/skill-manager/actions/runs/34862952922)
- **Dispatch:** `gh workflow run ci.yml --ref epic/one-home-one-verdict -f graph_set=full` at 15:33Z, on `57b96b9d`; it completed at 16:03Z.
- **`graphs-executed.json`:** graphs_selected 26, graphs_executed 26, graphs_passed 26, graphs_failed 0,
  graphs_unaccounted 0, nodes 359/359.
- **This epic's graphs:** home-verdicts 18/18, project-child-home 13/13, artifact-dag 10/10, onboarding 23/23,
  home-integrity 19/19, plugin-smoke 28/28.
- **Jobs:** 30 success; 1 skipped, the Selenium browser job, since `run_browser_graphs` was not passed.
- **Files:** `goals/GOAL-ci-green-fresh-runner/evaluation/ci-34862952922/` (`graphs-executed.json`, `jobs.json`,
  `select-graph-set-full.txt`).

Against the kickoff (run 34786751392: 25/25/19/6), all six baseline failures are green. `home-verdicts` is the
26th graph.

**Local runs, advisory only; CI is the graph evidence:**
- `home-verdicts` `20260914-153435` 18/18;
- `artifact-dag` `20260914-155014` 10/10;
- the deliberate-red sweep `20260914-154005`.

## Deferred findings

`deferred.yaml`: **DEF-OHV-180** (major: root overwritten again after OHV-9, then half-rewritten by a released
`--fix`), **DEF-OHV-181** (project home never repaired), **DEF-OHV-182** (record versions, one-record (3)),
**DEF-OHV-183** (onboard installs vendored skt 0.3.0), **DEF-OHV-184** (fresh onboard writes no pin),
**DEF-OHV-185** (`/var` shape not in a graph), **DEF-OHV-186** (`project resolve` failure names no cause),
**DEF-OHV-187** (release_regression R1 instrument).

Eight findings. Three bear on goals the table reads as met in code:
- 180 and 181: the real homes are damaged, and the verdicts only agree about it.
- 185: one known shape is not in a graph.

That is the honest shape of this epic. The detectors and writers are fixed and pinned. The machine they
run on is not, because the fixes are unreleased, other sessions run 0.27.2 against the root, and no ticket
repaired the real homes.

## Close-out

`skill-manager home close-out --home /Users/hayde/IdeaProjects/wt-ohv-8/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager`
→ rc 0, "holds nothing that removing it would destroy" (`close-out.txt`).

## Evidence index

- `goals/GOAL-one-verdict/evaluation/`: `home-verdicts-local-20260914-153435/`, `real-homes/`, `real-homes-run2/` (verdicts from both builds plus `observation.json`), and the fleet summary.
- `goals/GOAL-one-record/evaluation/`: `measure_goal_one_record.json`, `artifact-dag-local-20260914-155014/`, `one-record-run.txt`, `release_regression.run1-instrument-fault.json`, `release_regression.run2.json`.
- `goals/GOAL-no-own-home-path/evaluation/`: `measure_goal_no_own_home_path.json` (15:39Z), `copied-home-{root,project}.json` (15:39Z). The 15:55Z reading is in `tickets/OHV-8/measure_goals-ledger.1555Z.txt`.
- `goals/GOAL-one-marketplace-identity/evaluation/`: the fleet summary and the old harness's output.
- `goals/GOAL-ci-green-fresh-runner/evaluation/`: `local-red-sweep/` and `ci-<run>/`.
- `goals/GOAL-verdict-names-its-build/evaluation/`: epic and released outputs, `ohv9-build-verdicts/`, `skt-check-pr46/`.
- `goals/GOAL-a-home-writes-only-itself/evaluation/`: `ohv9-unit-cases-run.txt`.
- `tickets/OHV-8/`: `fleet/fleet-epic-build.json` (57 homes), `leak-2026-09-14T1531/`, `fresh-machine-onboard/`, `child-home-fanout/`, `deferred.yaml`, `close-out.txt`.
