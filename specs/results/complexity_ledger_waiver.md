# MF-019 complexity ledger — WAIVED, not satisfied

`child-home-materialization-workflow`, closed 2026-09-05 by owner override.

## What was skipped

`close_spec_workflow` requires `specs/results/complexity_ledger_input.yaml`.
That file is **an owner attestation**, not a measurement: `complexity_ledger.py`
reads `retention`, `validated_refactor`, `refinement`, `coverage_audit` and a
`justification`, and it explicitly rejects unfilled template prose.

The metrics half is mechanical and could have been taken. The justification
half is a statement about why the model's complexity is what it is, and who
validated it. **No agent can author that honestly on the owner's behalf**, so it
was not authored, not measured, and not satisfied — it was waived.

## What else was waived in the same close

Ticket completion. Tickets remained `open`, `delivered` and `blocked_on_owner`
across CHM, MIG, EFF, COH and HIS at the time of the close.

## What was actually done

`desired_program_model/` was promoted onto both `program_model/` and `current/`
(76 semantic files each, with binding maps re-rooted), and the active
`desired_program_model/` and `current/` trees were then removed from `main`.

The reason given: the program model had fallen far out of date, and the active
workflow trees were never meant to live on `main`.

## How to read this

The standing objective was **not** met. This file exists so that a later reader
finds the waiver rather than an absence, and so that no one mistakes a closed
workflow for a satisfied gate. If the objective matters for the next model, it
has to be measured then — nothing here carries forward as evidence.
