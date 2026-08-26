# Ticket snapshot: HIS-5

- Workflow: `child-home-materialization-workflow`
- Entry: `ticket-038-HIS-5`
- Ticket: `HIS-5`

## Summary

HIS-5 (#217): nine invariants over HomeIntegrityInternal, one per behavioural fix this epic made, each with an expected-violation cfg that fails on its OWN invariant (measured: 14 cross-checks with the target removed, all green); one guard cfg for the single witness-dependent invariant; two reachability probes that must fail; all five pre-existing regression cfgs still fail.

## Snapshots

- `program_model`: `specs/.history/child-home-materialization-workflow/ticket-038-HIS-5/snapshots/program_model`
- `desired_program_model`: `specs/.history/child-home-materialization-workflow/ticket-038-HIS-5/snapshots/desired_program_model`
- `current`: `specs/.history/child-home-materialization-workflow/ticket-038-HIS-5/snapshots/current`
- `ticket_workdir`: `specs/.history/child-home-materialization-workflow/ticket-038-HIS-5/ticket`

## Follow-up

Review this append-only entry, then commit the history directory with the related spec changes.
