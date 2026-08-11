# Program Model

This directory is the accepted whole-program TLA+ model for this repository.
It is the semantic baseline for future ticket workflows and the first context
source an agent should open when changing the test-graph behavior.

Files:

- `TestGraph.tla`: canonical whole-program state machine.
- `MC.cfg`: bounded TLC model for the accepted baseline.
- `Replay.cfg`: bounded three-node refinement model with one full source and
  one middle-node resume-tail or run-only attempt. The focused relation keeps
  closure, acquisition, tamper rejection, and both report writers reachable
  without multiplying equivalent concurrent replay histories.
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
  Envelope fingerprints include the closed v1 schema identity, status, trace,
  assertion consistency, and published-data fingerprint; the implementation
  applies one strict validator before publication, closure/replay eligibility,
  and report acceptance.
- Run-attempt identity distinct from graph identity, closure-bound replay-source
  provenance and captured evidence, attempt-local contexts/envelopes/reports,
  trace-carrier continuity, and scope-relative report completeness that cannot
  regenerate a partial attempt as passed. A replay source must be a closed
  full, non-replay attempt whose complete ordered plan exactly matches the
  current plan. Closure v2 binds the run ID, raw execution scope, raw carrier, and
  exact present context/envelope digest maps. Replay validates and captures the
  selected ordered context and carrier once; execution and reporting do not
  reopen source paths. Pre-acquisition tamper fails, while post-acquisition
  mutation cannot change the captured replay. The closure is an integrity—not
  authenticity—boundary unless anchored outside an owner-writable report tree.
- Executable refinement requires one strict, bounded
  `context/<nodeId>.input.json` snapshot per expected node before either inline
  or regenerated reports can claim completeness. Each snapshot must also equal
  the exact ordered published-data prefix for that node; a replay's selected
  source context must be byte-for-byte and semantically identical to the saved
  source snapshot and to the exact ordered current-plan prefix.
- Manual report regeneration revalidates closure v2 against current evidence;
  absent or changed closure state is `ERRORED`. Report parsing is bounded by
  aggregate byte and structural-token inventories.
- Managed process supervision targets macOS/Linux and requires Perl core
  `POSIX` and `Time::HiRes`; reaped-orphan terminal evidence is distinct from
  cleanup uncertainty, which withholds closure.
