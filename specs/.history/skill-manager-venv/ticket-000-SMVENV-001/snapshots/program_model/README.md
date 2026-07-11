# Accepted Program Model

This directory is the durable whole-program semantic model for Skill Manager.
Feature-ticket workflows should start from this baseline, create temporary
`specs/current` and `specs/desired_program_model` directories, then promote the
converged model back here when the workflow closes.

## CLI Progressive Disclosure

The accepted model includes the progressive-disclosure CLI workflow promoted on
2026-06-28:

The model now uses the Core/Internal/External view layout:

- `Core.tla` holds the shared constants and constant-level helpers (domain
  sets, dependency edges, CLI catalogs, closure/selector functions).
- `Internal.tla` is the accepted whole-program state machine (CLI store,
  gateway, registry server, project model, and CLI disclosure), including the
  CLI command catalog, aliases, workflow links, root-help scope, command-help
  coverage, bundled skill documentation coverage, and opt-in agent-context
  coverage. `External.tla` (public harness-drivable view) arrives in a later
  phase.
- `Internal.cfg` is the whole-program long-running TLC config; `MC.cfg` is a
  compatibility alias that mirrors it.
- `Internal*Cases.cfg` are bounded feature-slice case configs (CLI store,
  gateway, server registry, project, CLI disclosure), each constrained by a
  matching `*CaseEnvelope` in `Internal.tla`.
- `MC_program_promotion.cfg` preserves the broader whole-program TLC config for
  promotion-level checks.
- `production_adapters.py`, `case_adapters.toml`, and `tests/` validate the
  accepted model against production CLI metadata, help hooks, bundled skill
  docs, and agent-context hooks.

Workflow history is append-only under `../.history/desired-ticket-workflow/`.
The closed progressive-disclosure workflow snapshot is recorded at
`../.history/desired-ticket-workflow/closed-snapshot/`.
