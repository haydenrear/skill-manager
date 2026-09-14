## Discovery

Some discovery text.

<!-- git-epic-workflow:assignment:start -->
## Epic execution — REQUIRED

```yaml
version: 1
epic:
  id: EPIC-7
  workflow: cut_the_apparatus
  branch: epic/cut-the-apparatus
  base_sha: 1f2e3d4c5b6a7988
  plan_commit: aabbccddeeff0011
  schedule_revision: 2
  default_branch: main
ticket:
  spec_id: CA-03
  feature_branch: feature/141-trim-adapter
  worktree: ../wt-141-trim-adapter
  pr_base: main
  depends_on:
  - CA-01
  blocks:
  - CA-05
  wave: 2
  promotion_order: 30
  promotion_predecessor: CA-01
  role: implementation
  conflict_keys:
    production:
    - src/adapter.py
    tla: []
    adapters:
    - adapter
    test_graph: []
    workflow: []
goals:
- goal: GOAL-1
  kind: perf
  statement: the adapter stops dominating p99
  metric: p99 latency of the read path
  baseline: 412ms at 1f2e3d4c
  target: under 300ms
  decided_by:
    ticket: CA-09
    harness: uv run bench/read_path.py --full
  contribution: direct
  expected_effect: -120ms p99
  local_signal: uv run bench/read_path.py --quick
validation:
  tlc: 'N/A: no TLA delta in this slice'
  spec_unit: uv run pytest specs/tests
  repository_unit: uv run pytest tests/adapter
  graphs:
  - adapter-graph
  spec_graph: spec-conformance
  toolchain_spec_workflow: 'N/A: this repository is not tla-spec-dev'
  evidence_root: results/CA-03
review:
  mode: external
  ticket_agent_stops_after: pr_open
  merged_by: epic-owner
  cadence: wave
  artifact_root: results/epic-cut-the-apparatus/review
deferment:
  mode: batch
  blocking: escalate
  budget: 5
  backlog: specs/desired_program_model/deferred_findings.yaml
```

Do the work.
<!-- git-epic-workflow:assignment:end -->
