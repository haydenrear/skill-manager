# OUN-0 — validation

Run on `feature/OUN-0` at `0583a11`, in `../wt-oun-0` with
`SKILL_MANAGER_HOME=$PWD/.skill-manager`.

| declared | command | result |
| --- | --- | --- |
| `repository_unit` | `jbang RunTests.java` | **ALL PASSED**, including the new `OUN-0 baseline: a plugin-contained skill is NOT addressable by name` |
| `graphs` | `python3 skills/test_graph/scripts/run.py home-integrity` | **BUILD SUCCESSFUL**, 18/18 nodes |
| `spec_unit` | see below | **1 failed, 10 passed** — pre-existing on `main`, `DEF-OUN-001` |
| `tlc` | — | N/A: no spec workflow is scaffolded for this epic |

## The `spec_unit` command in the assignment does not exist

Every assignment this epic's renderer produced declared:

```
python3 .skill-manager/skills/spec-double-compiler/scripts/run_spec_units.py
```

That script is in no installed version of `spec-double-compiler` and in no
checkout of this repository. It has been an unrunnable declaration, so the
declared spec validation was never actually run by anyone it was handed to.

The real suite is pytest, with its rootdir pinned by `specs/pytest.ini`:

```
uv run --with pytest pytest specs/program_model/tests -q
```

`scripts/render_epic_assignment.py` now emits that, and all nine OUN
assignments were re-rendered.

## Running it found a regression on `main`

```
1 failed, 10 passed
FAILED test_program_model_contract.py::test_program_model_accepts_bounded_cli_disclosure_case_surface
```

Bisected to `e07e577` — the workflow close-out — and **not** caused by this
ticket: `e07e577~1` is `11 passed`. Filed as `DEF-OUN-001`, severity
blocking, because `git-epic-workflow`'s precondition that
`specs/program_model` is a complete accepted baseline is not currently
satisfied. It is an owner decision, not a repair this ticket can make.

## Protected homes

No writes to `~/.skill-manager` or to
`/Users/hayde/IdeaProjects/skill-manager/.skill-manager`. The addressability
harness installs its two fixtures into a `home clone` in a scratch directory
and refuses by name to run against either protected home, or against any home
passed with `--home` that resolves to one.

## And CI has been red on `main` for a second, separate reason

`.github/workflows/ci.yml` hardcoded both suites:

```
uv run --with pytest pytest specs/program_model/tests specs/current/tests -q
```

`specs/current` exists only while a spec workflow is scaffolded. Closing one
removes it, so from `e07e577` onward **every CI run on every branch** failed
with `ERROR: file or directory not found: specs/current/tests` — before a
single assertion ran. `main`'s own CI run for that commit is red for exactly
this.

Fixed here rather than deferred: it is two lines, it is unambiguous, and
every remaining ticket in this epic would otherwise inherit a red required
check that says nothing about the ticket. The accepted baseline's suite now
always runs; the workflow's runs when there is a workflow.

With the path fixed, CI reaches the real failure — the one contract test of
`DEF-OUN-001`. That is the intended outcome: a red check that names the
actual defect is worth more than a red check that names a missing directory.

## Re-run after the wave-1 review

Six defects were found reviewing this ticket's own diff — one real parsing
bug, three harness-safety gaps, one documentation defect, and hygiene. Fixed,
then everything re-run:

| | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `home-integrity` graph | BUILD SUCCESSFUL |
| all four harnesses | unchanged verdicts; **8 and 6 importer counts identical after the parser fix**, which is the point of re-running them |
| `uv run --with pytest pytest specs/program_model/tests -q` | 11 passed |

The parser fix was verified not to move any measured number before it was
kept: had it changed the 8 or the 6, the baseline recorded in the plan would
have been wrong and the plan would have needed amending, not the harness.

See `../../reviews/wave-1.md` and `../../attribution/2026-09-05-by-component.md`.
