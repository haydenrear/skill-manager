# Ticket snapshot: HIS-19

- Workflow: `child-home-materialization-workflow`
- Entry: `ticket-042-HIS-19`
- Ticket: `HIS-19`

## Summary

HIS-19 (#246) / DEF-027: home shims writes the most DURABLE spelling of the located build (DurableCliPin), verified same-file by realpath. Revised at the review of #250: the PATH arm was REMOVED as unsafe and the class's downgrade claim was corrected against my own measurement; home shims now has a test that it prints the pin it wrote. No TLA+ surface change -- HIS-5 carries the model work.

## Snapshots

- `program_model`: `specs/.history/child-home-materialization-workflow/ticket-042-HIS-19/snapshots/program_model`
- `desired_program_model`: `specs/.history/child-home-materialization-workflow/ticket-042-HIS-19/snapshots/desired_program_model`
- `current`: `specs/.history/child-home-materialization-workflow/ticket-042-HIS-19/snapshots/current`
- `ticket_workdir`: `specs/.history/child-home-materialization-workflow/ticket-042-HIS-19/ticket`

## Follow-up

Review this append-only entry, then commit the history directory with the related spec changes.
