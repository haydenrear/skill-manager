# Program Model

This directory is the accepted whole-program TLA+ model for this repository.
It is the semantic baseline for future ticket workflows and the first context
source an agent should open when changing the test-graph behavior.

Files:

- `TestGraph.tla`: canonical whole-program state machine.
- `MC.cfg`: bounded TLC model for the accepted baseline.
- `spec_manifest.yaml`: manifest for generated cases, ports, invariants,
  adapter expectations, and onboarding status.
- `case_adapters.toml`: production adapter mapping for generated cases.
- `production_adapters.py`: repository-local adapter extension points.

Use `specs/current` and `specs/desired_program_model` only after this baseline
exists and a later ticket needs a planned destination. First onboarding should
not create those directories.

Current semantic scope:

- Scaffolded test_graph projects and package catalog coverage.
- Script-declared NodeSpec metadata and additive Gradle DSL overlays.
- Transitive dependency resolution from sourcesDir.
- Planned graph execution where dependencies must pass before dependents run.
- Canonical per-node envelopes, downstream context from published data only,
  inline reports for successful runs, report rebuilds, and clean behavior.
