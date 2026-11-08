# Ticket 404-cli-version-pin: A declared CLI version is never enforced

This directory is the active, ticket-local spec workflow for one ticket.

Layout:

- `ticket.yaml`: copied ticket-plan entry and lifecycle metadata.
- `desired/`: the whole-program state that should be true after this ticket.
- `testgraph/`: copied Test Graph bindings/selectors/assertions when present.
- `results/`: ticket-local TLC, adapter, Test Graph, and review evidence.

Workflow:

1. Edit `desired/`. It starts as a copy of the project current model; change
   its TLA+, configs and, when the project has them, generated-case metadata,
   spec adapters, tests, and Test Graph bindings so it represents the
   whole-program state after this ticket is done. Run TLC on it once.
2. Implement the ticket.
3. If this ticket adds spec-unit or Test Graph coverage, keep those adapters,
   tests, bindings, selectors, and assertions in the ticket directory.
4. Run TLC and, when the project has them, generated spec-unit adapters and
   Test Graph validation.
5. Mark the global ticket-plan entry done.
6. Run `tla-spec-dev --spec-root specs close ticket 404-cli-version-pin`. Closing replaces
   project `specs/current` with ticket `desired/`, merges ticket Test Graph
   config back into project specs, snapshots this directory into history, and
   removes the active ticket directory. If it refuses, rerun with `--force`
   and say why in `--summary`; never edit the plan's status to get past it.
7. Close-out skill feedback: a finding you record whose `target:` is in THIS
   repository owes this repository a change. Give each one a `skill_change:`
   of `applied(<commit>)` (the file is in your worktree, so prefer this),
   `proposed(<unit>, <diff or issue>)`, or `declined(<reason>)`. A proposal is
   a diff or an issue with a diff; prose alone reads as no proposal. The close
   prints one warning line per finding without one and PROCEEDS -- it is a
   report, not a gate, so there is nothing to force past.

Starting source: `/Users/hayde/IdeaProjects/skill-manager-404-cli-version-pin/specs/current`

Future worktree support should attach the ticket worktree here, but this
scaffold intentionally records only the spec-side state for now.
