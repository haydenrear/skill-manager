# HP-4 evidence (#298, #103)

Base: `origin/epic/honest-pins` 69c64d15. Branch `feature/HP-4`.

## Local signal: `run.py honest-pins`, nodes record-b and pin-e

Expected effects: record shape (b) red -> green; pin shape (e) red -> green.
Baseline (HP-0, `results/epic-honest-pins/baseline/2026-11-07-1b52427/honest-pins.json`):
both red on `shape_*` only, every setup and control assertion green.

`graph-run.sh source-tracking smoke plugin-smoke honest-pins --continue`, sweep
20261107-204305 (`sweep.json`), honest-pins run 20261107-211546
(`honest-pins-local-signal.json`):

| node | baseline | HP-4 |
| --- | --- | --- |
| honest.record-b | red: after home sync manifest 0.2.0 / record 0.1.0; after sync --from manifest 0.3.0 / record 0.1.0 | **green**, all 6 assertions incl. control_git_latest_rewrites_record_version |
| honest.pin-e | red: v2 url served the 1.0 binary | **green**, all 3 incl. control_unchanged_spec_served_from_cache |
| pin-b, pin-c, pin-d, record-a, record-c, record-d | (green on the epic tip, from HP-2/3/5/6/7) | green |
| honest.record-e | red (#394, another ticket's shape) | red, same shape_* assertions; not in HP-4's slice |

## Other graphs (same sweep)

| graph | result |
| --- | --- |
| source-tracking | PASSED |
| smoke | not run meaningfully: docker unavailable. Executed, red: `postgres.up` (`docker compose up -d postgres` exit 1), so registry/publish/install nodes report "missing upstream context" and their dependants fail |
| plugin-smoke | not run meaningfully: docker unavailable. Same cascade from `postgres.up` / `registry.up` |

## Unit and pytest

- `jbang RunTests.java`: ALL PASSED, 1671 passed / 0 failed (log kept locally, `*.log` is gitignored).
- `virtual-mcp-gateway`: `uv run --all-extras pytest -q` 95 passed (4 new in
  `tests/test_binary_provisioning_digest.py`; red before the fix).
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.

## Real homes

`real-home.txt`, instrument `record-version-invariant.py` (the issue's suggested
check: every installed record's version equals its manifest's). Read-only on
the root home (13 units), the project home (3) and this worktree's home (3):
0 mismatched each (OHV-3 already fixed the git-HEAD case those homes are in).
Write test on COPIES of the worktree home (child-home.json removed first, per
DEF-HP2-002): bump tracing-observability 0.4.0 -> 0.4.1 in B, `./skill-manager
home sync --from B --to A --unit tracing-observability` -> `updated`, A's record
reads 0.4.1; second sync `unchanged`; invariant 0 mismatched.
