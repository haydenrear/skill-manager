# Desired Program Model

This directory describes the planned destination for the active ticket
workflow. It is both a formal model target and a structured implementation
plan.

Initial ticket: `115-direct-git-references` - Traverse direct-git skill references in resolver graphs

Baseline:

- Program model manifest: `../program_model/spec_manifest.yaml`
- Program model: `../program_model/` (Core.tla, Internal.tla, External.tla)

Files:

- `Core.tla`: shared constants and operators for the desired target.
- `Internal.tla` / `Internal.cfg`: desired internal view. Drives spec-unit cases.
- `External.tla` / `External.cfg`: desired external view. Drives Test Graph
  cases. Edit this whenever the ticket changes publicly observable behavior.
- `actions.yml`: keep in sync with both modules.
- `case_adapters.toml` / `testgraph_bindings.yml`: spec-unit and Test Graph
  adapter mappings. A new action is not done until it is mapped in the one that
  matches its layer.
- `spec_manifest.yaml`: desired generated-case manifest plus workflow status.
- `ticket_plan.yaml`: ticket breakdown with dependencies, current-model
  increments, adapter expectations, validation commands, and evidence slots.
- `desired_state.yaml`: human-readable index of modeled boundaries and
  implementation status.
