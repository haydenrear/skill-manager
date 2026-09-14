# GOAL-ci-green-fresh-runner — OHV-8 evaluation

| clause | baseline | measured | target | verdict |
| --- | --- | --- | --- | --- |
| (1) a `graph_set=full` run on the epic tip | run 34786751392: 25/25/19/6 | **run 34862952922** on `57b96b9d`: 26 selected / 26 executed / 26 passed / 0 failed, 0 unaccounted, 359/359 nodes (`ci-34862952922/`) | executed == selected, 0 failed, exclusions carry a reason | met |
| (2) a local sweep with a red graph reports what it ran, and only that | stops at the first red; stale reports counted | `local-red-sweep/`: 3 selected / 3 executed / 2 passed / 1 failed / 0 not_run; the graph after the red ran; a stale report from earlier in the session was not counted | yes | met |

**Exclusions** (`ci-34862952922/select-graph-set-full.txt`):
- `refresh-flow` (integration-repo#53) and `hyper-experiments` (#143) are in EXCLUDED with reasons.
- `browser-auth` and `password-reset` are not selected. The selector prints them as browser graphs for the
  `test-graph-browser` job, which this dispatch skipped (`run_browser_graphs` not passed). Their reason is
  recorded in the selector's text rather than in EXCLUDED, and they did not run in this measurement.

**How the red was made** (`local-red-sweep/deliberate-red.diff`, `revert-proof.txt`):
- One uncommitted line at the top of `test_graph/sources/artifact-dag/ArtifactsEnumerated.java`:
  `if (Boolean.TRUE) return NodeResult.fail("artifacts.enumerated", "OHV-8 DELIBERATE RED …")`.
- The run: `python3.12 skills/test_graph/scripts/run.py doc-smoke artifact-dag sync-settles` under the session
  graph lock, with `GRADLE_OPTS=-Dorg.gradle.daemon=false` and `COMPOSE_PROJECT_NAME=skill-manager`.
- Reverted with `git checkout --` right after. `git status --short -- test_graph/` is empty, and the marker is
  grep-count 0.
- The metric names `run.py --all`. The multi-graph form uses the same sweep code path (OHV-7), and it keeps the
  run to about 3.5 minutes on a machine shared with other sessions.
