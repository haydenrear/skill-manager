# Ticket SMVENV-001: Content-addressed skill store: skills/<name>/<sha>/ plus a moving latest pointer

This directory is the active, ticket-local spec workflow for one ticket.

Layout:

- `ticket.yaml`: copied ticket-plan entry and lifecycle metadata.
- `current/`: the whole-program state this ticket started from.
- `desired/`: the whole-program state that should be true after this ticket.
- `testgraph/`: copied Test Graph bindings/selectors/assertions when present.
- `results/`: ticket-local TLC, adapter, Test Graph, and review evidence.

Workflow:

1. Edit `desired/` first. It starts as a copy of the project current model;
   change its TLA+, configs, generated-case metadata, spec adapters, tests, and
   Test Graph bindings so it represents the whole-program state after this
   ticket is done.
2. Implement the ticket, then update `current/` to the behavior that actually
   landed. At close time, ticket `current/` and `desired/` must match.
3. If this ticket adds spec-unit or Test Graph coverage, keep those adapters,
   tests, bindings, selectors, and assertions in the ticket directory.
4. Run TLC, generated spec-unit adapters, and Test Graph validation as needed.
5. Mark the global ticket-plan entry closed.
6. Run `tla-spec-dev --spec-root specs close ticket SMVENV-001`. Closing validates ticket
   `current/ == desired/`, replaces project `specs/current` with ticket
   `desired/`, merges ticket Test Graph config back into project specs,
   snapshots this directory into history, and removes the active ticket
   directory.

Starting source: `/Users/hayde/IdeaProjects/skill-manager/specs/current`

Future worktree support should attach the ticket worktree here, but this
scaffold intentionally records only the spec-side state for now.
