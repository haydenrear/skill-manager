# Accepted Program Model

This directory is the durable whole-program semantic model for Skill Manager.
Feature-ticket workflows should start from this baseline, create temporary
`specs/current` and `specs/desired_program_model` directories, then promote the
converged model back here when the workflow closes.

## CLI Progressive Disclosure

The accepted model includes the progressive-disclosure CLI workflow promoted on
2026-06-28:

- `MC.cfg` is the bounded TLC and case-generation config for the CLI disclosure
  contract. It is intentionally small enough for the ticket/spec unit loop.
- `MC_program_promotion.cfg` preserves the broader whole-program TLC config for
  promotion-level checks.
- `SkillManager.tla` models the CLI command catalog, aliases, workflow links,
  root-help scope, command-help coverage, bundled skill documentation coverage,
  and opt-in agent-context coverage.
- `production_adapters.py`, `case_adapters.toml`, and `tests/` validate the
  accepted model against production CLI metadata, help hooks, bundled skill
  docs, and agent-context hooks.

Workflow history is append-only under `../.history/desired-ticket-workflow/`.
The closed progressive-disclosure workflow snapshot is recorded at
`../.history/desired-ticket-workflow/closed-snapshot/`.
