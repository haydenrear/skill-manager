# Skill feedback — spec-double-compiler / tla-spec-dev

`references/migration.md` Phase 6: a migration is not done when the models
converge. It is done when everything the skill **could not express** has been
turned into a concrete recommendation against the skill repository. The skill
improves only through what real migrations fail to express.

This file is **append-only by convention**. Close-out creates it once and
thereafter only appends. Never rewrite or delete an existing finding — a filled
finding is evidence.

## How to use this file

1. At each close-out the CLI appends a `## Close-out …` entry below.
2. Fill in that entry's `feedback_status`, then record one `### SF-NNN` finding
   per thing the skill could not express.
3. **Turn every finding into a ticket or PR against the spec-double-compiler
   repository** — that is the point of this file, not the record-keeping:

   ```
   gh issue create --repo haydenrear/tla-spec-dev \
     --title "<SF-NNN one-line title>" --body-file <extract of the finding>
   ```

   Then set `recommendation:` to the resulting URL and `status: filed`.
4. If you looked and there was genuinely nothing, set
   `feedback_status: none-found`. **Silence is not an answer** — an unreviewed
   entry is recorded as unresolved in the close history.

## The four prompt categories

- `surviving-mutants` — Surviving mutants
- `unmodelable-effects` — Unmodelable effects
- `budget-and-metric` — Budget adjustments and metric calibration
- `profile-schema-cli` — Profile, schema, and CLI workarounds

## Finding format

Every finding is a `### SF-NNN` heading followed by `- key: value` lines. The
close path parses these, so keep the shape.

Fields required on every finding:

- `category:` one of `surviving-mutants`, `unmodelable-effects`, `budget-and-metric`, `profile-schema-cli`
- `target:` the exact tool surface that proved inadequate — command, script
  path and function, budget key, profile rule, or manifest field. Not "the CLI".
- `observed_on:` the real repository/module/ticket it was run against. A finding
  without a real target is a wish, not evidence.
- `evidence:` a durable path (command output, TLC log, report) — not prose.
- `severity:` one of `blocks-migration`, `silent-data-loss`, `wrong-result`, `manual-workaround`, `friction`
- `root_cause:` one of `tool`, `spec`, `target`, `unknown` — whether the
  tool's code, its specification, or the target under migration was at fault.
  A correct implementation of a wrong spec is `spec`; filing it against the
  code files it in the wrong place.
- `workaround_applied:` what the migration had to do to proceed, or `none`.
- `recommendation:` `ticket <url>` or `PR <url>` against spec-double-compiler / tla-spec-dev
- `status:` `open`, `filed`, or `wontfix`

Category-specific fields, so the common cases are structured rather than prose:

- `surviving-mutants` — `mutant:`, `operator:`, `location:`, `why_unreached:`
  (which generator, strategy, or profile rule could not reach it)
- `unmodelable-effects` — `effect:`, `why_not_port_state:`, `modeled_as:`
  (or `unmodeled`)
- `budget-and-metric` — `budget_key:`, `default_value:`, `value_used:`,
  `gated_quantity:` vs `measured_quantity:` (name both when a gate compares
  quantities that are not commensurable), `metric_blind_spot:` (what a passing
  metric failed to notice)
- `profile-schema-cli` — `surface:`, `forced_workaround:`, `data_loss:`
  (`yes`/`no`)

## Worked examples

These are real findings this epic produced *before* this template existed. They
are the calibration for what a good finding looks like; they are recorded here
as `SF-000x` examples and are excluded from filing status.

### SF-000a — Projected complexity reduction required deleting real behavior
- category: budget-and-metric
- target: scripts/analyze_complexity.py — projected-reduction reporting
- observed_on: tla-spec-dev @ MF-020 (ticket_phase ordinal collapse)
- evidence: specs/.history/modular-fuzzing-epic/ticket-*-MF-020/
- severity: wrong-result
- root_cause: tool
- gated_quantity: distinct reachable states
- measured_quantity: generated states
- metric_blind_spot: deleted self-loops. Reproducing the projected -13.1%
  required tightening a guard from `>= 2` to `= 2`, deleting a legitimate
  idempotent re-fire transition. The distinct-state gate is structurally blind
  to that, so a behavior deletion scored as a re-representation win.
- workaround_applied: projection withdrawn by hand after transition-level diff
- recommendation: ticket (example only)
- status: wontfix

### SF-000b — Promotion destroyed files unique to specs/current
- category: profile-schema-cli
- target: scripts/spec_evolution.py::replace_tree (ticket-close promotion)
- observed_on: tla-spec-dev @ MF-012, MF-020, MF-021
- evidence: tests/test_promotion_preserves_current.py
- severity: silent-data-loss
- root_cause: tool
- surface: `tla-spec-dev close ticket` promotion step
- forced_workaround: restore deleted regression tests from git history
- data_loss: yes
- recommendation: ticket https://github.com/haydenrear/tla-spec-dev/issues/22
- status: filed

### SF-000c — PATH wrapper ran pre-epic code for an entire epic
- category: profile-schema-cli
- target: `tla-spec-dev` PATH wrapper -> ~/.skill-manager/skills/spec-double-compiler
- observed_on: tla-spec-dev @ modular-fuzzing epic (all tickets)
- evidence: specs/desired_program_model/ticket_plan.yaml (toolchain_rule)
- severity: wrong-result
- root_cause: tool
- surface: skill installation / PATH shim
- forced_workaround: pin every lifecycle command to
  `python3 scripts/tla_spec_dev.py --spec-root specs ...`
- data_loss: yes — the stale wrapper is why the promotion defect fired three
  times, including once after its fix had merged
- recommendation: ticket (example only)
- status: wontfix

### SF-000d — Bound gate compared incommensurable quantities
- category: budget-and-metric
- target: scripts/analyze_complexity.py — state-space bound gate
- observed_on: tla-spec-dev @ MF-011
- evidence: specs/.history/modular-fuzzing-epic/ticket-*-MF-011/
- severity: blocks-migration
- root_cause: spec
- budget_key: max_distinct_states
- default_value: 50000
- value_used: new `max_state_space_bound` added (MF-022)
- gated_quantity: static state-space upper bound (1,179,648)
- measured_quantity: actual reachable distinct states (2,923)
- metric_blind_spot: a ~400x over-approximation failed a model 17x *under* its
  own budget; the tool's own recommended optimum still failed the gate.
- workaround_applied: none — gate reported its own failure rather than tuning
- recommendation: ticket https://github.com/haydenrear/tla-spec-dev/issues/28
- status: filed

---

## Close-out ticket ISSUE-166

- close_scope: ticket
- close_id: ISSUE-166
- workflow: child-home-materialization-workflow
- closed_at: 2026-08-07T23:23:56+00:00
- summary: (none given)
- feedback_status: items-recorded

Set `feedback_status` to `none-found` or `items-recorded`, then record findings as `### SF-NNN` blocks below using the field list above.
Every finding must become a ticket or PR against spec-double-compiler / tla-spec-dev; put its URL in `recommendation:` and set `status: filed`.

### SF-001 — Close manifest records an absolute skill-feedback path
- category: profile-schema-cli
- target: scripts/spec_evolution.py — close-ticket manifest serialization of skill_feedback.path
- observed_on: haydenrear/skill-manager ISSUE-166 normal ticket close
- evidence: specs/.history/child-home-materialization-workflow/ticket-020-ISSUE-166/manifest.json
- severity: wrong-result
- root_cause: tool
- surface: `tla-spec-dev --spec-root specs close ticket ISSUE-166`
- forced_workaround: none; preserve the sealed manifest as the honest pre-review snapshot and track the portability defect upstream
- data_loss: no
- workaround_applied: none
- recommendation: ticket https://github.com/haydenrear/tla-spec-dev/issues/193
- status: filed

## Close-out ticket ISSUE-168

- close_scope: ticket
- close_id: ISSUE-168
- workflow: child-home-materialization-workflow
- closed_at: 2026-08-12T22:17:04+00:00
- summary: ISSUE-168: ResolveProjectDependencies gains staged-closure markdown-import validation and a typed PROJECT_IMPORT_MISSING reject with UNCHANGED state; publish remains one atomic step over the whole declared closure. TLC MC.cfg gate green, 14 spec-unit tests green, RunTests ALL PASSED, project-resolve/project-smoke/onboarding/spec-conformance graphs green.
- feedback_status: unreviewed

Set `feedback_status` to `none-found` or `items-recorded`, then record findings as `### SF-NNN` blocks below using the field list above.
Every finding must become a ticket or PR against spec-double-compiler / tla-spec-dev; put its URL in `recommendation:` and set `status: filed`.

## Close-out ticket HIS-8

- close_scope: ticket
- close_id: HIS-8
- workflow: child-home-materialization-workflow
- closed_at: 2026-08-24T00:55:27+00:00
- summary: >
    HIS-8 (skill-manager#224): the derived-artifact contract is now stated ONCE,
    in skt's references/derived-artifacts.md, and the three other units that
    instruct agents about homes link to it rather than restate it. Zero model
    change (variables=38 actions=9, delta zero); the whole slice is markdown in
    four OTHER repositories, published as four PRs. GOAL-mechanism-documented
    clause 1 moved 0 of 4 -> 4 of 4 instructing units; clause 2's judged read
    went 0 of 3 -> 3 of 3, twice, with different agents. pytest specs/ 38 passed,
    RunTests.java ALL PASSED, home-integrity graph BUILD SUCCESSFUL 18/18.
    Ticket current == desired, byte-identical, so promotion carried nothing.
    CLOSE TAKEN UNDER --allow-open, i.e. CloseTicketWeakened, for the reason in
    SF-002; the ticket agent stops at PR open by rule 7, so no honest plan status
    satisfied the gate.
- feedback_status: items-recorded

### SF-002 — `close ticket`'s accepted plan statuses exclude every status an unmerged epic ticket can honestly hold
- category: profile-schema-cli
- target: scripts/spec_evolution.py — `TICKET_CLOSED_STATUSES` at the `close ticket` gate
- observed_on: haydenrear/skill-manager HIS-8 (#224), epic/home-integrity-sync, epic ticket close
- evidence: results/epic-home-integrity-sync/probes/his-8/tla-spec-dev-close.out
- severity: forced-workaround
- root_cause: tool
- surface: `tla-spec-dev --spec-root specs close ticket HIS-8`
- detail: >
    `TICKET_CLOSED_STATUSES = {accepted, closed, complete, completed, done}`. In
    the git-epic-workflow model a ticket agent STOPS AT PR OPEN and the epic
    owner merges, and this plan's own word for a landed ticket is `delivered`
    (15 tickets carry it). `delivered` is not in the set, so at close time the
    agent may pick a refused status or make a false claim. `--allow-open` is the
    only route, and it correctly records the close as `CloseTicketWeakened` --
    but for a reason unrelated to the ticket's evidence, which dilutes exactly
    the signal that flag exists to preserve.
- forced_workaround: `--allow-open`, recorded in the ticket's complexity-ledger narrative so the terminal evaluation reads the weaker claim
- data_loss: no
- workaround_applied: yes
- recommendation: ticket https://github.com/haydenrear/tla-spec-dev/issues/288
- status: filed

### SF-003 — the ticket plan parses again: DEF-069 is closed by measurement
- category: profile-schema-cli
- target: scripts/spec_evolution.py — ticket_plan.yaml reader
- observed_on: haydenrear/skill-manager HIS-8 (#224)
- evidence: results/epic-home-integrity-sync/probes/his-8/tla-spec-dev-open.out
- severity: none — RESOLVED, recorded so nobody re-files it
- root_cause: tool (fixed upstream)
- surface: `tla-spec-dev --spec-root specs open ticket HIS-8`
- detail: >
    Every prior ticket in this epic recorded `open ticket` as unusable because
    the toolchain's YAML reader could not parse this plan (DEF-069). Re-measured
    here: `open ticket HIS-8` succeeded, scaffolded 83 ticket-local workflow
    files, and `close ticket HIS-8` then completed and promoted. HIS-8 is the
    first ticket in this epic to run either half. Recording the positive result
    because a defect nobody re-measures stays "open" forever, which this epic's
    own backlog reconciliation note warns about.
- forced_workaround: none
- data_loss: no
- workaround_applied: none
- recommendation: no ticket needed — fixed in skill-manager 6d4c6a4 and verified here
- status: resolved

## Close-out ticket HIS-5

- close_scope: ticket
- close_id: HIS-5
- workflow: child-home-materialization-workflow
- closed_at: 2026-08-24T02:21:26+00:00
- summary: >
    HIS-5 (#217): HomeIntegrityInternal goes from 4 invariants / 5 regression
    configurations to 13 / 14, plus one guard configuration and two reachability
    probes. Nine new invariants, one per behavioural fix this epic made. Every
    expected-violation configuration was re-run with its TARGET REMOVED and the
    other thirteen kept -- fourteen greens -- so each counterexample violates
    exactly one invariant and it is the declared one. All five pre-existing
    regression configurations still fail, each on its own invariant. pytest
    specs/ 38 passed, RunTests.java ALL PASSED (146 suites / 1384 cases),
    home-integrity graph BUILD SUCCESSFUL 18/18.
    CLOSE TAKEN UNDER --allow-open for the reason HIS-8 already filed as SF-002
    below; the ticket agent stops at PR open by rule 7, so no honest plan status
    satisfied the gate.
- feedback_status: items-recorded
- duplicate_avoided: >
    I had written my own SF-002 for the `--allow-open` gate before rebasing onto
    the epic tip, and HIS-8 had already filed it -- with an upstream URL. It was
    DROPPED at the rebase rather than appended. Recorded because "I filed a
    finding as new that another ticket had recorded two days earlier" is this
    epic's recurring defect, and the only reason it did not recur here is that
    the rebase conflict forced both versions of this file to be read side by
    side. Nothing would have caught it had the two tickets touched different
    files.

### SF-004 — the ledger's delta reports zero for a module that grew 2.8x
- category: metric-blind-spot
- target: complexity ledger (MF-019) — `complexity_ledger_model` scoping
- observed_on: haydenrear/skill-manager HIS-5 (#217)
- gated_quantity: variables/actions of `SkillManager.tla` + `MC.cfg`
- measured_quantity: variables/actions of the module the ticket actually changed
- evidence: >
    The close printed `measured: variables=38 actions=9` and
    `delta: direction=zero (vs ISSUE-168)`. Both are true of the declared
    ledger model, which this ticket does not touch. `HomeIntegrityInternal`
    went from 10 variables / 8 actions / 6 configurations to 28 / 23 / 18, and
    its healthy run from 10 distinct states to 24,000 — none of which the
    delta can see, because the module is a declared non-replacement view and
    is not the ledger's model.
- metric_blind_spot: >
    A repository whose accepted monolith is frozen while all real work happens
    in bounded non-replacement views gets `direction=zero` on every close, so
    the standing objective is measured against a model nobody is editing.
- workaround_applied: >
    none — the real numbers were written into `justification:` by hand, which
    is the only place they exist. A reader of `specs/results/complexity_ledger.json`
    alone would see zero.
- recommendation: >
    Let the ledger measure the union of the modules a ticket's `tla:` conflict
    keys name, falling back to `complexity_ledger_model` when that set is
    empty; or refuse `direction=zero` when the ticket's conflict keys name a
    module the ledger does not measure.
- status: not-filed — filing against tla-spec-dev is outside a ticket agent's mandate. Reported in the PR body
  so the epic owner can decide whether to file it.
## Close-out ticket HIS-19

- close_scope: ticket
- close_id: HIS-19
- workflow: child-home-materialization-workflow
- closed_at: 2026-08-24T04:25:59+00:00
- summary: HIS-19 (#246) / DEF-027: home shims writes the most DURABLE spelling of the located build (DurableCliPin), verified same-file by realpath. Revised at the review of #250: the PATH arm was REMOVED as unsafe and the class's downgrade claim was corrected against my own measurement; home shims now has a test that it prints the pin it wrote. No TLA+ surface change -- HIS-5 carries the model work.
- feedback_status: items-recorded

### SF-005 — SF-002 has now been forced THREE times: HIS-8, HIS-5, HIS-19
- category: profile-schema-cli
- target: scripts/spec_evolution.py — `TICKET_CLOSED_STATUSES` at the `close ticket` gate
- observed_on: haydenrear/skill-manager HIS-19 (#246), epic/home-integrity-sync, epic ticket close
- id_note: >
    Filed as SF-004 on this branch and RENUMBERED to SF-005 at the rebase onto
    the epic tip, where HIS-5 had already taken SF-004 for a different finding.
    Recorded rather than silently shifted, because a renumbered id in someone
    else's citation is the failure this note prevents. Same reason HIS-5's
    `duplicate_avoided:` block above exists, and the same near-miss: nothing
    but the rebase conflict put the two lists side by side.
- evidence: results/epic-home-integrity-sync/tickets/HIS-19/goal-contribution.md; the refusal was `ERROR: ticket HIS-19 is not closed in ticket_plan.yaml: status=delivered`
- severity: forced-workaround
- root_cause: tool
- surface: `tla-spec-dev --spec-root specs close ticket HIS-19`
- detail: >
    NOT A NEW FINDING. This is SF-002, and after the rebase it is measurably
    worse than the recurrence I first wrote it up as: HIS-8 filed it, HIS-5's
    close-out records taking `--allow-open` for the same reason, and this is the
    THIRD. Recorded rather than re-filed, because three occurrences is the datum
    an upstream maintainer does not otherwise get — the first report reads as one
    agent's unusual plan, and three consecutive epic tickets do not. The verbatim
    refusal is above; `delivered` is this plan's word for a landed ticket and the
    ticket agent stops at PR open by rule 7, so no honest status satisfies the
    gate.

    ONE THING SF-002 DID NOT RECORD, and it is the more useful half: the FIRST
    close attempt with `--allow-open` was still refused, by the complexity
    ledger, with a verdict naming both missing fields (`refinement.searched` and
    `narrative`). That refusal is the tool working exactly as designed and it is
    worth stating beside the complaint, because it is why `--allow-open` does
    not weaken the close as much as SF-002 implies: the substantive gate is
    downstream of it and did not yield.
- forced_workaround: `--allow-open`, declared here and in the ticket's complexity-ledger narrative and in the PR body, so the terminal evaluation reads the weaker claim
- data_loss: no
- workaround_applied: yes
- recommendation: https://github.com/haydenrear/tla-spec-dev/issues/288 (SF-002's ticket; this is the THIRD occurrence on it, not a new issue -- verified open with `gh issue view 288 -R haydenrear/tla-spec-dev`)
- status: filed

Set `feedback_status` to `none-found` or `items-recorded`, then record findings as `### SF-NNN` blocks below using the field list above.
Every finding must become a ticket or PR against spec-double-compiler / tla-spec-dev; put its URL in `recommendation:` and set `status: filed`.

## Close-out ticket HIS-20

- close_scope: ticket
- close_id: HIS-20
- workflow: child-home-materialization-workflow
- closed_at: 2026-08-24T12:44:03+00:00
- summary: Progressive disclosure measured in both directions at all three tiers, before and after; gaps closed by linking, in two units published leaf-first
- feedback_status: items-recorded

### SF-002 (fourth occurrence, not re-filed)

- root_cause: tool
- surface: `tla-spec-dev --spec-root specs close ticket HIS-20`
- detail: >
    NOT A NEW FINDING. `close ticket` refused with
    `ERROR: ticket HIS-20 is not closed in ticket_plan.yaml: status=planned`,
    which is SF-002 exactly. HIS-8 filed it, HIS-5 recorded taking `--allow-open`
    for the same reason, HIS-19 recorded it as the third, and this is the FOURTH
    consecutive epic ticket to hit it. Recorded rather than re-filed.

    Two things worth adding to the run of four, because they change what the
    upstream maintainer should conclude:

    (a) It is now unanimous across the epic's implementation tickets, not a
        pattern in a subset. Every ticket that stops at `pr_open` by rule 7 hits
        it, because rule 7 and the close gate disagree about what "done" means:
        the gate wants a terminal plan status, and the agent is forbidden from
        reaching one.

    (b) HIS-19 noted that the substantive gate is downstream of `--allow-open`
        and did not yield. For HIS-20 the ledger gate DID yield on the first try,
        because the ledger had already been filled before the close was
        attempted. So the reassurance in HIS-19's note is conditional on the
        order the agent happens to work in, not on the tool. Worth stating so the
        run of four is not read as four identical data points.
- forced_workaround: `--allow-open`, declared here, in the ticket's complexity-ledger narrative, and in the PR body, so the terminal evaluation reads the weaker claim (modeled state `CloseTicketWeakened`, not `CloseTicket`)
- data_loss: no
- workaround_applied: yes
- recommendation: https://github.com/haydenrear/tla-spec-dev/issues/288 (SF-002's ticket; this is the FOURTH occurrence on it, not a new issue)
- status: filed

### SF-003 — promotion drops the executable bit

- root_cause: tool
- surface: `tla-spec-dev --spec-root specs open|close ticket HIS-20`
- detail: >
    CORRECTED AFTER CHECKING, and the correction matters for where the fix goes.
    My first write-up blamed the promotion step. The ticket-local copy is already
    `-rw-r--r--`:

      $ ls -l specs/.history/.../ticket-043-HIS-20/ticket/desired/run_tlc.sh
      -rw-r--r--  1 hayde  staff  968  run_tlc.sh

    So the bit is lost at OPEN, when `current/` is seeded into the ticket
    workspace, and promotion merely copies the already-broken mode back. Both
    steps copy without preserving mode; open is where it starts.

    Measured on this close, on a ticket whose `current/` and `desired/` were
    byte-identical and which changed no spec file at all:

      $ git diff specs/current/run_tlc.sh
      old mode 100755
      new mode 100644

    Nothing else in the tree changed mode. `run_tlc.sh` is the script
    `specs/program_model` invokes to run the model checker, so a promotion that
    silently un-executes it breaks the next TLC run in a way that looks like a
    model problem rather than a promotion problem. It is also invisible to a
    content diff: `diff -rq` reports the two trees identical, because mode is not
    content.

    Worth flagging beyond the one file: this promotion carried ZERO content
    changes and still produced a working-tree modification. Any ticket that runs
    the close will see it, and the natural reading of `M specs/current/run_tlc.sh`
    on a docs-only ticket is "something went wrong with my ticket".
- forced_workaround: `chmod +x specs/current/run_tlc.sh` after the close, before committing
- data_loss: no (mode only; recoverable and recovered)
- workaround_applied: yes
- recommendation: https://github.com/haydenrear/tla-spec-dev/issues/289
- status: filed

Set `feedback_status` to `none-found` or `items-recorded`, then record findings as `### SF-NNN` blocks below using the field list above.
Every finding must become a ticket or PR against spec-double-compiler / tla-spec-dev; put its URL in `recommendation:` and set `status: filed`.
