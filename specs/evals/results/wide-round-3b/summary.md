# Wide round 3b — the 28 cases round 3 could not measure, rerun

Results: `runs/wide/2026-09-14T14-1*`, `14-2*`, `14-3*`, `14-5*` (8 invocations,
one kept build) · Claude Code 2.1.270 · 28 cases × 2 runs, `-j 4` · **$42.27**
(round 3 + 3b: $102.85) · overrides: git-epic-workflow de9e2a1,
git-issue-workflow 9ae39f8, spec-double-compiler b5bc9c3 — **placed into the
wrappers AND the homes a run executes**, which round 3 did not do.

The 18 cases green on both runs in round 3, and not affected by any fix, were
not repeated.

## What changed between 3 and 3b

* Harness: overrides reach the CLIs on PATH (85122d9e); pypi reachable; Bash
  rules join `\`-continued lines; the "filesystem is read-only" prompt note
  corrected (176fc959); a source home with shims into a third home is branched
  by repairing the COPY (533e219d, 4cb7eca0).
* Cases: 14 grader/rule/fixture defects found by reading round 3's transcripts.
* Skills (drop-gates branches): lanes are the plan's to name (de9e2a1); never
  rewrite a status to pass the close gate, why a ticket binding is bare, and
  the Stop/SessionEnd fact in plugin_evals.md (b5bc9c3).

## Before / after on the 28

| | round 3 | round 3b |
|---|---:|---:|
| mean score | 0.74 | **0.85** |
| cases with every deciding verdict passing (of 25 that have one) | 16 | **23** |
| cases up / down / unchanged | | 11 / 4 / 13 |
| runs over their call budget | 22 | 23 |
| runs capped at max_turns | 5 | 4 |

**The gate branches work when their code is what runs.** Every spec-double-
compiler case but one is green on both runs, including the three that measured
the installed CLI in round 3 (`close-ticket-delivered-status` 0.70→1.00,
`complexity-ledger-is-advisory` 0.69→1.00, `out-path-is-absolute` 0.00→1.00).
Every git-epic-workflow and git-issue-workflow gate case now passes its
deciding verdict on both runs (`force-when-owner-decided` 3/4→4/4,
`wt-close-no-force-on-unpublished` 1/2→2/2); what remains red there is cost.

Per case (mean score, deciding verdicts passed, runs green):

| case | score | deciding | green |
|---|---|---|---|
| w-epic-assignment-no-force-on-blocking | 0.88→0.88 | 2/2→2/2 | 0→0 |
| w-epic-force-when-owner-decided | 0.64→0.86 | 3/4→4/4 | 0→0 |
| w-epic-merged-by-is-epic-owner | 0.83→0.83 | 2/2→2/2 | 0→0 |
| w-epic-plan-free-form-lane | 0.75→0.75 | 2/2→2/2 | 0→0 |
| w-epic-retire-part-of-goal-warns-only | 0.75→0.88 | 2/2→2/2 | 0→0 |
| w-giw-bootstrap-cross-home-is-not-old-cli | 0.83→0.83 | 2/2→2/2 | 0→0 |
| w-giw-epic-ticket-plan-values-win | 0.83→0.83 | 2/2→2/2 | 1→1 |
| w-giw-epic-ticket-stops-on-wrong-pr-base | 1.00→1.00 | 2/2→2/2 | 2→2 |
| w-giw-exit6-is-unreadable-frontmatter | 0.86→0.86 | 2/2→2/2 | 0→0 |
| w-giw-wt-close-no-force-on-unpublished | 0.64→0.86 | 1/2→2/2 | 0→0 |
| w-giw-wt-new-dirty-ok | 0.78→0.89 | 4/4→4/4 | 0→0 |
| w-giw-wt-refusal-quotes-subject | 0.88→0.88 | 2/2→2/2 | 0→0 |
| w-giw-wt-stale-branch-point | 0.83→**0.33** | 2/2→**0/2** | 0→0 |
| w-harness-smoke | 1.00→1.00 | – | 2→2 |
| w-misc-debug-bounded-wait | 0.50→**0.25** | – | 0→0 |
| w-misc-plugin-repo-home-does-not-sandbox-install | 0.50→1.00 | – | 1→2 |
| w-sdc-attribution-before-close | 0.86→**0.64** | 2/2→2/2 | 1→0 |
| w-sdc-close-ticket-delivered-status | 0.70→1.00 | 1/2→2/2 | 1→2 |
| w-sdc-close-workflow-is-close-tickets | 1.00→1.00 | 2/2→2/2 | 2→2 |
| w-sdc-complexity-ledger-is-advisory | 0.69→1.00 | 1/2→2/2 | 1→2 |
| w-sdc-eval-run-has-case-glob | 0.14→1.00 | 0/2→2/2 | 0→2 |
| w-sdc-forced-close-names-guard-weakening | 1.00→1.00 | 2/2→2/2 | 2→2 |
| w-sdc-no-deferred-findings-at-root | 1.00→1.00 | 2/2→2/2 | 2→2 |
| w-sdc-open-closed-ticket-adds-new-entry | 1.00→1.00 | 2/2→2/2 | 2→2 |
| w-sdc-out-path-is-absolute | 0.00→1.00 | 0/2→2/2 | 0→2 |
| w-sdc-ticket-binding-bare-adapter-module | 0.58→1.00 | 1/2→2/2 | 1→2 |
| w-skt-check-record-disagrees-with-checkout | 0.50→0.92 | 0/2→2/2 | 0→1 |
| w-skt-migration-delete-project-block | 0.64→**0.29** | 1/2→**0/2** | 1→0 |

Two runs per round each: a move of one run is not a trend. The bold rows are
the four that went down, read below.

## Setup finding: the operator's root home

`~/.skill-manager/bin/cli/{computeq,helm-deploy,monitoring}` point into
`~/IdeaProjects/wt-240-packed-publication/test_graph/build/validation-reports/
20260914-123718/.../.skill-manager` — another session's validation artifacts.
Every `home clone` of the root home now fails verification. The harness
branches around it by repairing and quarantining in the COPY only; the root
home was not written. Repairing it (`skill-manager home repair --home
~/.skill-manager --fix`, then `skill-manager build` for deploy-helm's entry
points) is the operator's call.

## Reds that went down, read against their transcripts

Six remaining reds, every run read. Four were the cases, two the skills, and
one environment defect ran through two of them. Every capped run was graded
(SessionEnd wrote its verdicts), so none of these is a lost measurement.

| case | class | what it was | fixed |
|---|---|---|---|
| w-giw-wt-stale-branch-point | CASE | both runs issued the right command as `"$WT" new TICKET-12 origin/epic/demo-epic` — the spelling git-issue-workflow's SKILL.md teaches — and the rule only knew `wt new` | rule accepts `"$WT"`; 4/4 spot tests |
| w-epic-plan-free-form-lane | CASE | "don't run `--strict`" is not in the skill (both ran it once, after a clean run, to double-check); and `reports-ready` PASSED on replies that said "not ready" | forbid removed; prompt asks what the validator accepts; regex rejects "not accepted/valid" (4/4) |
| w-misc-debug-bounded-wait | CASE | both replies moved the test's own timeout under the job limit and watched for failure — sound, and not in the grader's two allowed fixes | grader and regex accept the third fix (3/3) |
| w-giw-epic-ticket-plan-values-win | CASE | the reply took the plan's values; a closing question about a missing remote read to one judge as a send-back | grader says owner questions about the environment are not a send-back |
| w-skt-migration-delete-project-block | SKILL | the status report gives the exact `[plugins.skt]` line; both agents still hunted 13+ calls for a manifest schema skt's `references/projects.md` does not show, and never replied | budget 24 turns; **the doc example belongs in the skt repo — not changed here** |
| w-sdc-attribution-before-close | SKILL | both runs spent 7–9 calls filling a complexity ledger because `complexity_ledger.py:1168` still said close refuses without it — stale since the ledger became advisory | comment corrected on spec-double-compiler's drop-gates branch; budget 30 turns |

**ENV:** the fixture's untracked `.eval-bin/` made the repository dirty and
tripped `wt new`'s clean-tree gate in three runs — no verdict flipped, but it
cost calls and invites `SKILL_GATES=off`. Fixed: the fixture `.gitignore`
covers it (lib.sh and the deep epic case).

**Also found, not fixed:** the harness places only a plugin's contained skills
into its wrapper, so a plugin's own top-level `references/` (skt's migration
notes among them) never reaches a run, where a real session would have it.

These fixes are not yet measured: a rerun of the six is the next round's first
item, and it should be cheap (6 cases × 2 runs ≈ $10).
