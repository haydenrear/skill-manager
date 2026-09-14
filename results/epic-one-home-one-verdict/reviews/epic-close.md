# Epic close review: `one-home-one-verdict` (#337)

Written 2026-09-14 against `epic/one-home-one-verdict` at `2b5f83ea`, plus the close-out commit. **Epic PR: #371 (draft).** `review_policy` was: an artifact per wave, gate waived by the owner at schedule revision 2, stop at finalization. The implementation is complete. Before merging to main, the eval suite, the TLA+ model and attribution update, the DEF-OHV-190 fix, and a final CI run all completed. See section 8.

## 1. What the epic was for

- **Verdicts disagreed.** Commands that judge a home disagreed with each other and with the disk. On the kickoff build (v0.27.2), `home verify` exited 0 while `home repair` found damage on 50 of 61 homes, and `home repair` exempted shims its own rewrite had left half-anchored.
- **CI had been red for a week.** `graph_set=full` failed 6 of 25 graphs.
- **Plugin updates didn't reach Claude** in some homes (#352).

At schedule revision 2 the owner dropped the two architectural refactors ("one reader", "one writer") and the TLA workflow. Each defect was fixed where it lives and pinned by a regression graph. At revision 4, after the operator's real root shims were overwritten through a test home's links, OHV-9 was added to remove that cause.

## 2. Goal ledger, final readings

The sources are OHV-8's evaluation (build `0.27.2+g85d0d4511eb0`) and the owner-approved real-home work at finalization (builds `be8b48dd` and `2b5f83ea`). The addenda are `goals/2026-09-14-final-home-fix/` and `goals/2026-09-14-final-sync/`. Where a later reading replaced OHV-8's, both are shown.

| Goal | Clause | Baseline | Final measured | Target | Verdict |
| --- | --- | --- | --- | --- | --- |
| one-verdict | (1) planted shapes reported by verify and repair | no graph | 11 of 11 (home-verdicts 18/18) | every | met |
| one-verdict | (2) unreported damaged facts, root/project | 4 / 4 | 0 / 0, and both homes verify 0, repair 0 after sync and prune | 0 | met |
| one-verdict | (3) fleet: verify 0 while repair reports damage | 50/61 | 0/57 | 0 | met |
| one-record | (1) revision-3 definition after a prune | unmeasured | **root 0, project 0 on a REAL prune** (root pruned 14 rows, kept 12; a second dry run plans 0). DEF-OHV-130: root 9, project 5. DEF-OHV-131: root 12, project 0 | 0 | met |
| one-record | (2) rows the re-record restores | 59 → 59 | 0 | 0 | met |
| one-record | (3) stale record versions | 5 / 1 | **0 / 0** after the owner-approved syncs (OHV-8 read 4 / 1) | 0 | met |
| no-own-home-path | (1) own-home spellings, root/project | 3 / 0 | **0 / 0** (OHV-8 read 3 / 0 after an external leak) | 0 | met |
| no-own-home-path | (2) copied-home probe | 2/3 · 3/3 | **3/3 · 3/3** after repair; re-probed after the syncs, see `final-sync/*/copied_home.final.json` | 3 of 3 | met |
| no-own-home-path | (3) fleet | 51/61 | 33/57 | context | reported |
| marketplace | (1a) four shapes reported and cleared | none | 4 of 4 | 4 of 4 | met |
| marketplace | (1b) 0 AGENT_SYNC_FAILED on the next sync | — | **0 in all 5 real-home syncs** (4 root, 1 project), plus OHV-6's four homes | 0 | met (OHV-8 marked it unmeasured; measured at finalization) |
| marketplace | (2) root / project | root shape 4 | 0 / 0 | 0 | met |
| marketplace | (3) fleet | 0/15/11 | 1/0/15/6 (repair kinds) | context | reported |
| ci-green | (1) full set on the tip | 25/19/6 | 26/26/0 (run 34862952922) | 0 failed | met; re-run on the final tip before merge |
| ci-green | (2) local sweep with a red graph | stops at the first red | 3 run, 1 failed, 0 not run | yes | met |
| verdict-names-its-build | verify, repair, drift, artifacts list | 0 of 5 | 4 of 4 across two builds | 5 of 5 | met (4 rows) |
| verdict-names-its-build | skt check | 0 | names its build on skt#46's branch | yes | met on the branch; the released-pair reading comes after skt#46 merges with this epic |
| writes-only-itself | (1) child install leaves parent byte-identical | no | node 9/9; red on the pre-fix code | yes | met in scratch |
| writes-only-itself | (2) write through a foreign target caught | no | 7/7 cases | yes | met |
| writes-only-itself | (3) every bin/cli writer guarded or shown safe | unmeasured | 7/7 cases; backend table checked against the code | yes | met |

**Summary: every clause is met, apart from the two context rows (reported, no threshold).** One clause rests on an unmerged sibling PR (skt#46), by the owner's decision to merge it together with the epic.

## 3. Tickets: 10 planned, 10 delivered, 0 retired

OHV-1 #360 · OHV-7 #363 · OHV-0 #359 · OHV-5 #362 · OHV-3 #361 · OHV-2 #364 · OHV-4 #365 · OHV-6 #366 · OHV-9 #368 · OHV-8 #369.

Promotion order held. Every wave produced a review artifact (`reviews/wave-1` … `wave-5`).

## 4. Schedule amendments

| Revision | Decision | Why |
| --- | --- | --- |
| 2 | dropped the one-reader and one-writer refactors and the TLA workflow; every fix pinned by `home-verdicts`; OHV-7 re-scoped (the validator refuses to retire one goal contributor, DEF-OHV-009) | nine of ten bugs had local fixes; the refactors rested on recurrence claims nobody could measure |
| 3 | GOAL-one-record clause (1) rescoped; skt#46 to merge at finalization | no prune can reach PATH-served shims or rows left by pre-OHV-3 removals |
| 4 | OHV-9 added, with GOAL-a-home-writes-only-itself | the root shims were overwritten through a test home's links (DEF-OHV-011) |

## 5. Real homes at close (owner-approved writes)

| Home | Writes |
| --- | --- |
| **root** | 13-finding `--fix` (OHV-6 build) · 3-finding `--fix` (epic build) · 4 unit syncs · real prune (14 rows; 5 stale `learning-app-exp-*` harness-instance dirs deleted) · drift acknowledged |
| **project** | 4-finding `--fix` · 1 sync (`skt`) · prune (nothing to prune) · drift acknowledged |

- **Result:** both homes verify 0 and repair 0.
- **Backups**, kept outside the repository: `/Users/hayde/IdeaProjects/.oh*-backup-2026-09-14*`.
- **Standing risk:** the root was overwritten twice on 2026-09-14 by commit-diff-context-parent test graphs running builds without OHV-9. It stays exposed until OHV-9 is released and commit-diff-context-parent#262 is fixed. Tracked as #378.

## 6. Deferred findings: 42, none pending

| Disposition | Count |
| --- | ---: |
| ticketed | 6 |
| fixed at finalization (DEF-OHV-181, DEF-OHV-182, DEF-OHV-190) | 3 |
| carried as grouped issues | 33 (10 of them from the eval run, in `tickets/OHV-evals/deferred.yaml`) |

- **Carried issues:** skill-manager #372–#378, #380, #383, #384 and #269, skt#47, git-epic-workflow#19, git-issue-workflow#31.
- **From the TLA+ modelling after close (#379):** DEF-OHV-190 to DEF-OHV-194.
  - **DEF-OHV-190 was blocking and is now fixed.** The shim anchor `${BASH_SOURCE[0]:-$0}` is a syntax error under dash, and OHV-4 widened the rewrite to sh and dash shims, so a rewritten `#!/bin/sh` shim broke on Debian and Ubuntu. Reproduced in `debian:stable-slim`.
    - Fixed by #381 (merged `bbc00af8`), which writes `${BASH_SOURCE:-$0}`. Existing sh and dash shims that carry the old line are reported and re-anchored; bash, zsh and ksh shims are left alone.
    - Evidence: `ShimAnchorRunsUnderEveryShellTest` went from 3/9 passing to 9/9; the full unit suite passes 1606/0; `home-verdicts` passes 18/18. See `tickets/OHV-dash/`.
  - **DEF-OHV-195 and DEF-OHV-196, found by that fix, are carried to #269.** A shim run through a symlink derives the link's home, and a shebang with flags is skipped.
  - **DEF-OHV-191 to DEF-OHV-194 are carried:** #380, and a comment on #374.

## 7. Guardrail overrides and implicit decisions

These are carried to the owner in epic PR #371.

1. CI seeds a synthetic 16-unit agent home so `ticket-lifecycle` clears its non-trivial-tree floor (OHV-5).
2. `home.fixpoint.law` skips homes that declare intentional damage (OHV-5).
3. Real-home `--fix` runs repair every finding kind (OHV-6; finalization repairs).
4. The skill-script guard detaches every `bin/cli` link that points outside the home, including brew cellar links (OHV-9).
5. A child home that actually runs its skill-script now owns its shim (OHV-9).
6. The epic agent's own process errors are recorded in `attribution/2026-09-13-kickoff-baseline.md`.

The owner granted "mildly destructive" latitude at close to reach a consistent state. The syncs, the prune and the drift acknowledgement above were done under it.

## 8. Merge evidence (all complete before merging #371)

1. **Evals** (#382, merged `677259d3`):
   - All 6 cases ran against a fatjar of the epic build, assembled the way `release.yml` does it: 12 runs, $7.74, every trace read. No regression is attributable to the epic.
   - The evals ran on `2b5f83ea`, before the DEF-OHV-190 fix. That fix only changes sh and dash shim anchors, and no case reaches one.

   | case | prior | score(s) | cause of a lower score |
   | --- | ---: | --- | --- |
   | epic-provisions-a-ticket-worktree | 1.00 | 1.00 | — |
   | ticket-agent-opens-a-ticket | 0.94 | 0.82 ×3 | harness: dirty fixture workspace (DEF-OHV-201) |
   | bootstraps-a-home-for-a-repo | 0.93 | 1.00 | the score is unsound: the replay exited 6, ran the brew CLI, and still passed (DEF-OHV-202, DEF-OHV-206) |
   | ticket-agent-closes-a-ticket | 0.90 | 1.00 | — |
   | syncs-a-stale-home-from-root | 0.71 | 0.14 (max_turns) / 0.71 / 0.71 | the fixture makes the sync exit 1 (DEF-OHV-209); #353 |
   | reconciles-a-worktree-into-the-project-home | 0.62 | 0.71 / 0.14 / 0.14 | the grader rejects a correct multi-line command (DEF-OHV-207) |

   - **Findings:** 10, DEF-OHV-200 to DEF-OHV-209, all carried.
     - DEF-OHV-203 is a product defect that predates the epic: one unit failing to fetch means no skills are projected. Filed as #383.
     - The other nine are harness defects, grouped in #384.
2. **TLA+ models and attribution** (#379, merged `f1e228a5`):
   - `HomeVerdictsInternal.tla` has 23 configurations. TLC matches the expected result on all 23, and the epic agent re-ran 6 of them independently.
   - Attribution is in `attribution/2026-09-14-epic-attribution.md` (59 rows).
3. **DEF-OHV-190 fix** (#381, merged `bbc00af8`).
4. **Final CI**, run 34886318464, `graph_set=full` plus the browser graphs, on `bbc00af8`:
   - 26 graphs selected, 26 executed, 26 passed, 0 failed.
   - `test-graph (selenium)` passed `browser-auth` and `password-reset`.
   - Unit tests and the gateway pytest passed; all 32 jobs succeeded.
   - Later commits (`677259d3` and after) touch only `results/`, `specs/evals/harness/lib.sh` and the eval runs. On the tip, the PR's unit and pytest jobs pass.
5. **DCO:** the epic's 84 commits carry no `Signed-off-by`, so the DCO app reports `action_required` on #371, skt#46 and deploy-helm#63.
   - No branch protection requires the check. Every ticket PR merged into the epic branch with the same status.
   - Adding sign-offs would mean rewriting published history, and this epic forbids rebasing or force-pushing. They merge without it; this is recorded here as a guardrail override.
6. **Merge:** #371, skt#46 and deploy-helm#63, on the owner's authorization ("ok to merge as long as we have sufficient evidence"). Afterwards, sweep the epic worktrees and delete the backup directories.
