# Program Model

This directory is the accepted whole-program TLA+ model for this repository.
It is the semantic baseline for future ticket workflows.

Files:

- `SkillManager.tla`: canonical whole-program state machine.
- `MC.cfg`: bounded TLC model for the accepted baseline.
- `spec_manifest.yaml`: manifest for generated cases, ports, invariants,
  adapter expectations, and onboarding status.
- `case_adapters.toml`: production adapter mapping for generated cases.
- `production_adapters.py`: repository-local adapter extension points.

Current modeled components:

- skill-manager CLI: effect-program execution, rollback, local unit store,
  installed records, lockfile, bindings, default projections, gateway config,
  registry config, recursive transitive installs, staged sync continuations,
  CLI package records, skill-script execution records, force script rerun
  actions, and orphan CLI dependency cleanup on uninstall.
- bindings and projections: doc-repo managed copies, import directives,
  projection rows, doc-repo sync repair, harness template instances, and
  harness-created projection rows.
- virtual MCP gateway: dynamic server catalog, global/session deployments,
  active tools, session disclosures, deployment errors, sticky initialization.
- skill-manager server: authenticated publishing, registry unit/version/package
  storage, and search/read behavior.

`MC.cfg` is intentionally a small smoke model: it aliases the second abstract
unit/server/tool/session atom to the first so TLC can check onboarding
invariants quickly. Broader configs should be added as the adapter cases become
useful.

Use `specs/current` and `specs/desired_program_model` only after this baseline
exists and a later ticket needs a planned destination. First onboarding should
not create those directories.
