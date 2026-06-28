# Desired Program Model

This directory describes the planned destination for the active ticket
workflow. It is both a formal model target and a structured implementation
plan.

Initial ticket: `CLI-PROGDISC-001` - Progressive disclosure CLI documentation contract

Baseline:

- Program model manifest: `../program_model/spec_manifest.yaml`
- Program model TLA module: `../program_model/SkillManager.tla`

Files:

- `SkillManager.tla`: desired whole-program model target. Start from the
  accepted program model, then add the desired semantic state.
- `MC.cfg`: ticket-loop TLC and case-generation config for the CLI disclosure
  contract. It should complete within two minutes.
- `MC_program_promotion.cfg`: inherited whole-program TLC config. Use for
  promotion-level checks, not for the normal ticket loop.
- `spec_manifest.yaml`: desired generated-case manifest plus workflow status.
- `ticket_plan.yaml`: ticket breakdown with dependencies, current-model
  increments, adapter expectations, validation commands, and evidence slots.
- `desired_state.yaml`: human-readable index of modeled boundaries and
  implementation status.

CLI progressive disclosure target:

- The desired model treats the CLI command/workflow catalog as program state,
  not as loose help text.
- Root help is intentionally shallow: it exposes top-level commands and routes
  users or agents to command-specific help.
- Command help, bundled skill docs, and agent-facing context must cover the
  same modeled workflow catalog.
