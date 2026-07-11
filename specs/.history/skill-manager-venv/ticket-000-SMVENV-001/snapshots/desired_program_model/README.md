# Desired Program Model

This directory describes the planned destination for the active ticket
workflow. It is both a formal model target and a structured implementation
plan.

Initial ticket: `SMVENV-001` - skill-manager venv: pinned skill versions, env overlays, agent shims

Baseline:

- Program model manifest: `../program_model/spec_manifest.yaml`
- Program model TLA module: `../program_model/Core.tla`

Files:

- `Core.tla`: desired whole-program model target. Start from the
  accepted program model, then add the desired semantic state.
- `MC.cfg`: bounded TLC model for the desired target.
- `spec_manifest.yaml`: desired generated-case manifest plus workflow status.
- `ticket_plan.yaml`: ticket breakdown with dependencies, current-model
  increments, adapter expectations, validation commands, and evidence slots.
- `desired_state.yaml`: human-readable index of modeled boundaries and
  implementation status.
