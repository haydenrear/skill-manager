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
