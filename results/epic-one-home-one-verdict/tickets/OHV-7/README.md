# OHV-7: a local graph sweep keeps going past a red and reports only what it executed

Issue: haydenrear/skill-manager#357. Owns DEF-OUN-023 and DEF-011 part 2.

## Change

- `skills/test_graph/scripts/run.py`: `--all` and any invocation with more than
  one graph now run as a **sweep**. Gradle gets `--continue` plus
  `--init-script scripts/sweep-ledger.init.gradle`. `--continue` on a single graph
  also adds the summary, and `--fail-fast` brings back stop-at-first-red.
  When the sweep ends it prints
  `graphs_selected / executed / passed / failed / not_run` and one line per graph
  giving its seconds and the run dir it created, then writes
  `test_graph/build/validation-sweeps/<sweepId>/sweep.json`. The exit code is
  Gradle's when that is non-zero, else 1 if any selected graph failed or did not
  run.
- `skills/test_graph/scripts/sweep-ledger.init.gradle`: records every root-project
  task the invocation scheduled and each task's outcome (and the run dirs
  it created under its own `reportRoot` while running), as JSON lines. The counts
  come only from this ledger. **Nothing reads `build/validation-reports/` to
  count.**
- A single `run.py <graph>` works exactly as before: same Gradle args, no
  init script and no summary. CI runs this per graph, so it is untouched.
- `_common.run_gradle` gains an optional `extra_env`, used for the ledger path.
- Docs: `CLAUDE.md` (the "Gradle stops at the first failing task" text is replaced),
  `references/workflows.md` and `references/reference.md`, plus a note in the
  `sweep.py` docstring.

## Local signal (`local-signal.txt`, `local-signal.sweep.json`, `local-signal.ledger.jsonl`)

```
python3 skills/test_graph/scripts/run.py doc-smoke artifact-dag sync-settles
== test graph sweep 20260913-233938 ==
graphs_selected=3  graphs_executed=3  graphs_passed=2  graphs_failed=1  graphs_not_run=0
  PASSED   doc-smoke     160.9s  build/validation-reports/20260913-234012
  FAILED   artifact-dag  84.1s  build/validation-reports/20260913-234232  (Execution failed for task ':artifact-dag'.)
  PASSED   sync-settles  24.8s  build/validation-reports/20260913-234350
run.py exit code: 1
```

**The plan's literal signal was `doc-smoke artifact-dag smoke --continue`; this run
swapped `smoke` for `sync-settles`.** Gradle runs graphs in `build.gradle.kts`
declaration order, not command-line order, and `smoke` is declared first, so it
would have run *before* the red graph and shown nothing about continuing past it.
`sync-settles` is declared after `artifact-dag` and is short.
The result matches expected_effect: 3 executed, 1 failed, and the graph after the
red one executed. `artifact-dag` fails on
`uninstall.prunes.the.subgraph`, which is the known baseline red, not a regression.

Stale reports: the ledger has no way to count a report left by an earlier run.
`tests/test_run.py::test_multi_graph_continues_and_stale_reports_are_not_counted`
plants an old passing `summary.json` for a graph that did not finish and checks
that the graph is reported `not_run`.

## Unit tests (`unit-tests.txt`, `runtests-tail.txt`)

- `python3 -m unittest discover -s scripts/tests` (in skills/test_graph): 52 tests
  OK, of which 11 are new in `tests/test_run.py`.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- `jbang RunTests.java`: ALL PASSED.

## Close-out

`skill-manager home close-out --home /Users/hayde/IdeaProjects/wt-ohv-7/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager`
-> `holds nothing that removing it would destroy`. No units changed.

## CI

See `ci.md`.

## Deferred

None.
