# Wide round 2 — the first wide measurement

`runs/wide/2026-09-14T01-16-39-286Z.json` · Claude Code 2.1.270 · the 26 `w-s*`
cases (never started in round 1), 1 run each, `-j 3` · **$15.86** (rounds 1+2:
$22.06 of the $25 round budget, so part B was not run) · overrides:
git-epic-workflow 4d6a335, git-issue-workflow 9ae39f8, spec-double-compiler
82f5a9f · harness: clone-placed units (61ee4ebb), 10/12-turn budgets.

**Every number here is ONE run.** None is quotable as a rate; they say where to
look, not how often.

## Behaviour right, graded green (9)

`w-sdc-close-workflow-is-close-tickets`, `w-sdc-forced-close-names-guard-weakening`,
`w-sdc-ticket-binding-bare-adapter-module`, `w-skt-check-unknown-is-not-current`,
`w-skt-is-a-plugin-not-a-skill`, `w-skt-migration-no-import-edits`,
`w-skt-stale-artifacts-are-not-stale-home`, `w-skt-ticket-verb-help-is-scoped`,
`w-sm-cold-shim-means-build`.

## Behaviour right, over the call budget (4)

`w-skt-remedy-without-origin` (10 turns), `w-skt-sweep-requires-epic` (8),
`w-skt-ticket-path-must-be-sibling` (13), `w-sm-sync-retired-name-redirects` (13).
The deciding verdicts are green; only `within-budget` is red. This is the cost
signal the lane exists for: each of these is a one-command answer that took an
agent 8–13 turns with every unit loaded.

## Red, and read

| case | score | reading |
|---|---:|---|
| `w-sdc-no-deferred-findings-at-root` | 0.40 | **grader defect.** The agent wrote the ticket's own `results/attribution.yaml` (correct). `forbid-no-root-ledger` matched `specs/deferred_findings.yaml` inside a COMMENT in that file's content, because a rule searched the whole tool input. Fixed by `field` in expect.json rules. |
| `w-sdc-eval-run-has-case-glob` | 0.29 | undecided: the reply did not contain `plugin eval … --case`; it read plugin_evals.md and the example plugin's README first. No transcript was archived in this round to see what it said instead (archiving added after). |

## UNDECIDED — the Stop hook does not run on `error_max_turns` (12)

`w-sdc-attribution-before-close`, `w-sdc-close-ticket-delivered-status`,
`w-sdc-complexity-ledger-is-advisory`, `w-sdc-open-closed-ticket-adds-new-entry`,
`w-sdc-out-path-is-absolute`, `w-skt-check-record-disagrees-with-checkout`,
`w-skt-migration-delete-project-block`, `w-skt-not-installed-is-not-not-synced`,
`w-sm-drift-ack-once`, `w-sm-sync-skt-when-absent`, `w-sm-verify-is-not-currency`.

Every run that hit its ceiling has NO wide-verify diagnostics at all, and every
`file_exists` verdict in it is red — including forbid rules an idle-but-correct
agent would have earned. The only explanation that fits all twelve is that the
Stop hook is not invoked when a session ends on `error_max_turns`. So a ceiling
hit is not merely "no closing report" (plugin_evals.md §4): **it erases every
verdict-path grader**, which that reference says is what keeps such a run
legible. Recorded as an instrument fact for plugin_evals.md; NOT YET PROBED
directly (the probe: a one-turn case whose prompt needs two turns, with a Stop
hook that writes a marker).

That these twelve hit 10–12 turns is itself worth reading once transcripts are
archived: several are "which command would you issue" questions.
