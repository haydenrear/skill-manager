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

## Home verdicts (one-home-one-verdict, #337)

`HomeVerdictsInternal.tla` is a bounded policy slice added after the epic
closed (2026-09-14). Beside `HomeIntegrityInternal.tla` it holds six disjoint
groups, one per corrected behaviour: `home verify` fails iff `home repair`
counts a finding; shims are detected on content and re-anchored on every
line; a prune stays pruned and removals reap only what they prove gone;
installed record versions follow the checkout; one marketplace identity per
home; and no install writes through a `bin/cli` link into another home.

- `HomeVerdictsInternal.cfg`: healthy; no error.
- `HomeVerdictsInternal_regression_*.cfg`: each flips one policy constant back
  to a shipped pre-fix behaviour (origin commit in its header) and MUST FAIL.
- `HomeVerdictsInternal_finding_*.cfg`: the healthy model against a stronger
  reading it does not satisfy; MUST FAIL, and the counterexample is the finding.
- `HomeVerdictsInternal_probe_reach.cfg`: run with `tlc2 -continue`; MUST report
  6 violations.

Like `HomeIntegrityInternal`, it has no case adapters. Its TLC results and the
epic's bug attribution are in
`results/epic-one-home-one-verdict/attribution/`.

Workflow history is append-only under `../.history/desired-ticket-workflow/`.
The closed progressive-disclosure workflow snapshot is recorded at
`../.history/desired-ticket-workflow/closed-snapshot/`.
