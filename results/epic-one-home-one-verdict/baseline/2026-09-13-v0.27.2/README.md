# Kickoff baseline — `one-home-one-verdict` on v0.27.2

Measured 2026-09-13 at `main` `71c51464` (v0.27.2 + two handoff doc commits),
before any epic branch or ticket existed. Released CLI: `skill-manager 0.27.2`
(`artifact 862d3a4c6017`), `skt 0.8.2`. Read-only throughout: no `--fix`,
`--record`, `--ack` or sync.

Homes: `~/.skill-manager` (root) and this repo's project home. The 53-home
fleet was **not** re-measured here; #352's fleet numbers stand as last measured.

## Instruments

| file | what |
| --- | --- |
| `measure_baseline.py` | the five verdict commands (released CLI, plus the project home's pinned repo build), then eight observations read off disk without the product's rules |
| `baseline.json`, `raw/*.log` | its output, and every command's stdout/stderr/rc |
| `release_regression.json` | `scripts/release_regression.py` (R1 census, R2 repair vs copy probe, R3 record vs checkout) |
| `copied-home-root.json` | `scripts/measure_goal_a_home_survives_being_copied.py ~/.skill-manager` |
| `one-unit-one-name-goal-ledger.txt` | `scripts/measure_goals.py` for the previous epic, re-run on 0.27.2 |
| `pre-sweep-registrations.json` | agent/home records naming the previous epic's worktrees (none) |

## 1. The verdicts — every command says the home is fine

| command | root | project |
| --- | --- | --- |
| `home verify` | rc 0 — "every link and generated script … resolves" | rc 0 — "every reference … resolves" |
| `home repair` | rc 0 — 0 findings, 78 examined | rc 0 — 0 findings, 43 examined |
| `home drift` | rc 0 — nothing pending | rc 0 — nothing pending |
| `artifacts list` | rc 0 — 291 artifacts | rc 0 — 54 artifacts |
| `skt check` | rc 0 — all current | rc 0 — all current |
| names the build that answered | **0 of 5** | **0 of 5** (pinned repo build `0.27.2+g71c5146` gives identical text to released `0.27.2`) |

## 2. What the disk says instead

| observation | root | project | verdict that should have said so |
| --- | --- | --- | --- |
| **O1** generated files spelling the home's own absolute path (venv shebangs excluded) | **3 `bin/cli` shims** — `computeq`, `helm-deploy`, `monitoring` (DEF-OHV-001); plus `projects/meta-harness/skill-project.toml`, `installed/doc-repo-devops.projections.json` (unclassified) | 0 | `home repair`, `home verify` |
| copied-home probe (a/b/c) | **2 of 3** — (b) fails on the 3 shims | 3 of 3 | `home repair` (R2 disagrees) |
| **O2** census vs disk | 291 declared · 33 declared-only · **15 missing outputs** (stat: 18; 3 are `unknown`) · **14 ledger-only rows** (DEF-OHV-003) | 54 declared · 10 declared-only · **7 missing outputs** · 0 ledger-only | `artifacts list` exits 0 on its own phantoms |
| **O3** agent links outside the home | 54 links, 0 dangling | **3 dangling** — `.claude`/`.codex`/`.gemini/skills/skill-manager` → retired store dir (DEF-OHV-002) | `home verify` says they resolve; `artifacts list` marks the same 3 `disagrees` |
| **O4** orphaned `installed/*.projections.json` | 0 | **1** — `skill-manager.projections.json` | none |
| **O5** marketplace identity (#352) | generated `skill-manager`; shape 2 exposed (9 `skill-manager-<hash>` names registered); **shape 4**: Codex registers `skill-manager-919db26e` (commit-diff-context-parent) and Claude enables `skt@` and `cdc-agent-substrate-plugin@skill-manager-919db26e` (DEF-OHV-005) | clean — `skill-manager-fd6e57d4` agrees in Claude, Claude settings, Codex | none |
| **O6** unstamped `pm/` versions | 0 of 4 | 0 of 2 | — |
| **O7** installed record vs checkout | hash: 0 disagree · **version: 5 disagree** (DEF-OHV-004) | hash: 0 · **version: 1** (`skt` 0.8.1 vs 0.8.2) | `skt check` (compares hash only) |
| **O8** `bin/` reaching another home | 0 | 1 — `bin/cli/tlc2` → root (`home verify` names it the sanctioned parent-store shim) | — |
| `/var` vs `/private/var` (#343) | not observable on `/Users/...` homes; fixture-only shape | | |

`release_regression.py`: **3 of 6 checks disagree** (R1 root, R1 project, R2 root).

Previous epic's ledger on 0.27.2: **3 of 6 met**, against 5 of 6 at its close.
`GOAL-a-home-survives-being-copied-into-an-image` is back to NOT MET
(DEF-OHV-001). `GOAL-the-front-door-is-found` reads 5 of 6 because
`syncs-a-stale-home-from-root`'s latest run hit the turn cap (#353).

## 3. Baselines, by proposed goal

| goal | baseline on v0.27.2 |
| --- | --- |
| G1 one verdict | 5 of 5 commands exit 0 on both homes; independent observation finds 4 damaged facts on root and 4 on project; `release_regression` 3 of 6 disagree |
| G2 one record | root 15 missing outputs + 14 ledger-only rows; project 7 + 0 |
| G3 no own-home path | root 3 shims (copy probe 2 of 3); project 0 |
| G4 one marketplace identity | root shape 2 exposed + shape 4 (Codex and Claude); project clean. Fleet: see §4 |
| G5 CI green on a fresh runner | run 34786751392 (`workflow_dispatch`, `graph_set=full`, `main` 71c51464): **25 selected, 25 executed, 19 passed, 6 failed**. The failures are `artifact-dag`, `checkout-home`, `home-clone`, `home-tripwire`, `onboarding` and `ticket-lifecycle`, exactly the handoff's set. `ci-34786751392-graphs-executed.json`, `ci-34786751392-jobs.tsv` |
| build stamp | 0 of 5 verdict commands name their build |

## 4. Fleet — every home on this machine (`measure_fleet.py`, `fleet.json`)

61 homes: root, plus every `<dir>/.skill-manager` under `~/IdeaProjects` to
depth 2, excluding the epic's own new worktree. This includes the 14 unswept
worktrees of the previous epic. The pass was read-only: afterwards, no home file
outside `cache/`/`logs/` was newer than the script, and meta-orchestrator's
`audit.log` had no new entries.

| measure | homes |
| --- | ---: |
| `home verify` exits 0 | 60 of 61 |
| `home repair` exits 0 with 0 findings | **9 of 61** |
| G3: a `bin/cli` file spells the home's own absolute path | 51 |
| G4 shape 1 (Claude registers this path under another name) | 0 |
| G4 shape 3 (generated marketplace name equals another home's) | 15 |
| G4 shape 4 (Codex or Claude registers/enables another home's marketplace) | 11 |
| G4 any of shapes 1, 3, 4 | 24 |
| dangling agent links in the checkout | 4 |
| orphaned `installed/*.projections.json` | 7 |
| damaged by any measure above, while `home repair` exits 0 | **2** (root, this project home) |

Reading it:
- **`home repair` is not silent fleet-wide.** It reports on 52 homes, mostly
  `FROZEN_HOME_PATH_IN_SHIM` (`tla-spec-dev`, the deploy-helm skill-scripts) and
  `UNSTAMPED_PM_TREE`. The homes it calls clean are the ones where the repair
  path already ran, and those are exactly where DEF-OHV-001's half-rewritten
  shims hide.
- **G3's 51 mixes two classes.** Some shims `home repair` already reports
  (`tla-spec-dev`). Others it does not: the `jinja2` entrypoint's venv-python
  shebang, and half-rewritten skill-script exec lines. OHV-4 has to separate
  them.
- **Shape 1 reads 0, where #352 counted 2.** tla-spec-dev and meta-orchestrator
  now register their generated names. Registration was repaired between #352's
  measurement and this one, while meta-orchestrator's repair output still
  replays recorded `AGENT_SYNC_FAILED` errors for `skill-manager-88340088`.
- **Shape 3 reads 15, where #352 counted 13.** Two more cloned homes carry a
  source's marketplace identity.
- **Instrument defect (mine):** the fleet script parsed stdout+stderr together,
  so four homes whose repair wrote a stderr banner (commit-diff-context-parent,
  meta-orchestrator, skill-manager-integration-repository, tla-spec-dev) have
  `repair_findings: null`. Their rc 1 is correct; their finding counts are not
  recorded.

## 5. Not done, and why

- **The 14-worktree sweep did not run.** `skt ticket sweep --yes` was refused
  by the session's permission classifier, and the owner chose to wait. Every PR
  is verified merged: the seven ticket PRs based on `main` are commit-contained,
  and the six based on the epic branch reached `main` through #335's squash,
  with a tree diff of 0 files. Evidence is in the kickoff attribution.
