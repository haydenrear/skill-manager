# Wide round 3 — every case, two runs each

`runs/wide/2026-09-14T12-39-07-909Z.json` · Claude Code 2.1.270 · all 46 `w-*`
cases × 2 runs, `-j 4` · **$60.58** (cap $60; the ceiling only skipped one paid
grader on the last run) · 4003 s · overrides: git-epic-workflow 4d6a335,
git-issue-workflow 9ae39f8, spec-double-compiler 82f5a9f · harness 2b65728c
(clone-placed units, 16/20-turn budgets, SessionEnd verifier, Bash rules on the
command field).

**The first round in which every run started and every run was graded.** 92 of
92 runs launched; none hit ENOSPC; all 46 cases have verdict diagnostics.

## Scorecard

| | |
|---|---|
| cases green on both runs | **15 / 46** |
| cases whose deciding (weight-3) verdict failed in ≥1 run | 9 |
| runs over their call budget | **36 of 56** that carry one |
| runs capped at max_turns | 6 of 92 (round 2: 12 of 26) |
| turns per run | median 11, p90 19, max 31 |
| cost per run | median $0.58, mean $0.66 |
| mean score by family | skt 0.89 · giw 0.83 · misc 0.82 · sm 0.80 · epic 0.77 · sdc 0.70 |

Two runs is enough to separate "fails every time" from "fails sometimes"; it is
not a rate. Cases that split 1/2 are marked as such and are the ones to repeat.

## Green on both runs (15)

`w-harness-smoke`, `w-giw-epic-ticket-stops-on-wrong-pr-base`,
`w-misc-issue-body-names-home-closeout`, `w-misc-issue-names-rubric-not-copies`,
`w-misc-otlp-endpoint-native-runner`, `w-sdc-close-workflow-is-close-tickets`,
`w-sdc-forced-close-names-guard-weakening`, `w-sdc-no-deferred-findings-at-root`,
`w-sdc-open-closed-ticket-adds-new-entry`, `w-skt-check-unknown-is-not-current`,
`w-skt-is-a-plugin-not-a-skill`, `w-skt-migration-no-import-edits`,
`w-skt-remedy-without-origin`, `w-skt-stale-artifacts-are-not-stale-home`,
`w-skt-ticket-verb-help-is-scoped`.

## The cost signal

Behaviour was right and only `within-budget` was red in 36 runs. The budgets
were written as "one or two commands"; the median run took 11 turns. With every
unit loaded, a one-command answer costs an agent reading a SKILL.md, a
reference or two, and usually a fixture — before the command. That gap between
the answer and the path to it is the thing this lane measures, and it is the
main optimisation target the round exposes. Per-case turn counts are in the
result JSON.

## Instrument finding: Stop does not fire on `error_max_turns`; SessionEnd does

Round 2 lost every verdict on 12 capped runs and could only infer why. Round 3
registered the verifier on both events and recorded which one wrote. Every run
that hit its ceiling was written by **SessionEnd only**
(`w-epic-plan-free-form-lane` both runs, and the capped runs of
`w-misc-plugin-repo-home-does-not-sandbox-install`,
`w-skt-migration-delete-project-block`, `w-sm-drift-ack-once`,
`w-sdc-open-closed-ticket-adds-new-entry`); every normal run was written by
Stop, sometimes followed by SessionEnd rewriting the same verdicts (86 Stop, 16
SessionEnd, 102 writes for 92 runs). Measured, not inferred: a verdict-path
grader that must survive a capped run needs a SessionEnd hook.

## Reds, read against their transcripts

Two independent readers took every behaviour red (23 runs) against its
transcript. **No run forced past a protection that should have held**: in the
unpublished-work case neither run put `--force` or `SKILL_GATES=off` on a
close; both tried the publish fix the refusal printed.

| class | runs | what it was |
|---|---:|---|
| CASE (grader/rule/fixture wrong, agent right) | 14 | multi-line `\` commands vs one-line rules; forbid rules matching a `grep` of the docs; a quote between `skt` and `ticket`; two llm graders stricter than the skill; a fixture whose close could never succeed; a fixture that never showed the remote tip; a regex that rejected a valid third fix |
| ENV (the run could not measure the branch) | 5 | overrides placed the branch SKILL.md but the CLIs on PATH ran the INSTALLED scripts (`tla-spec-dev` without `delivered`, a validator without `--force`); pypi.org blocked, so `uv run --script` validators could not fetch PyYAML |
| SKILL (the skill misled a correct-minded agent) | 2 | `plan-and-schedule.md` still named five "canonical" conflict lanes after the branch made them free-form; `case_modules.md` said adapters are bare paths without saying why, next to examples using the qualified form |
| VARIANCE (skill clear, agent erred) | 2 | ignored "answer before running anything" and probed a temp home; spent 15 calls hunting `[plugins.x]` syntax the status output had just given |

The five ENV runs matter most: four gate-case reds measured code the branch had
already changed. One of them is the exact bug the branch removes — the
installed CLI refused `status: delivered`, and the agent rewrote the plan to
`done` to get past it.

### What changed because of it

* **Harness** (85122d9e, 176fc959): overrides now also replace the unit in the
  build home and the workspace home, so the shims run branch code; pypi.org and
  files.pythonhosted.org join the network grant; `expect.py` joins
  `\`-continued lines before matching a command; the prompt note no longer
  claims the filesystem is read-only (Edit and Write succeed; only Bash writes
  are refused).
* **Cases**: every CASE row above fixed and spot-tested against the command or
  reply the agent actually produced.
* **Skills**, on the drop-gates branches, so the next round measures them:
  git-epic-workflow de9e2a1 (lanes are the plan's to name);
  spec-double-compiler — never rewrite a status to pass the close gate, why a
  ticket binding must be bare, and the Stop/SessionEnd fact in plugin_evals.md.

### Not fixed here, recorded

* skt SKILL.md has no guidance for a record that disagrees with its checkout;
  `skill-manager/references/projects.md` has no `[plugins.<name>]` example.
* plugin-repository states "SKILL_MANAGER_HOME does not sandbox the CLI" only in
  references/lifecycle.md, not SKILL.md.
* 36 of 56 budgeted runs were over budget with the right answer — the lane's
  main optimisation target, unchanged by any of the fixes above.
