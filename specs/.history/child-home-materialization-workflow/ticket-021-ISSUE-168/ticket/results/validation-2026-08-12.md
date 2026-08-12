# ISSUE-168 — atomic clean-home project resolution: validation evidence

Ticket: `specs/tickets/ISSUE-168` (workflow `child-home-materialization-workflow`)
Issue: https://github.com/haydenrear/skill-manager/issues/168
Branch: `feature/168-atomic-project-resolve`

## Model change (scoped to ResolveProjectDependencies and direct dependencies)

- `UnitMarkdownImportEdges` — markdown `skill-imports` relation, distinct from
  `ReferenceEdges` (a hard reference PULLS its target into the closure; a
  markdown import only NAMES a unit that must be present when validated).
  Bounded model carries the defect's reciprocal fixture `UnitA <-> UnitB`.
- `ProjectImportCandidates(project)` — the candidate closure validation is
  judged against: everything the resolve is about to publish plus everything
  already installed.
- `ProjectMissingMarkdownImports(project)` — imports of staged closure units
  whose target is in neither set.
- `ResolveProjectDependencies` — new typed branch
  `Reject("PROJECT_IMPORT_MISSING")` with `UNCHANGED state_vars` and
  `project_model` unchanged (registration retains declared intent), entered
  when the staged-closure validation finds a missing target; the publish
  branch is unchanged and remains one atomic step over the whole closure.
  No state variable added, no invariant changed.
- Reject-branch reachability: in this bounded model every markdown import's
  target belongs to ProjectA's own declared closure, so the branch has no
  reachable witness here (stated in a model comment). Its witnesses are
  `ProjectResolveAtomicClosureTest`'s planted ghost import (unit test) and
  the end-to-end negative case below.

## TLC

- `bash run_tlc.sh SkillManager.tla MC.cfg` (ticket current): PASSED —
  "Model checking completed. No error has been found." 10 states generated,
  1 distinct, depth 1 — identical numbers to the unmodified baseline
  (`specs/current`), as expected: `CliDisclosureNext` does not include the
  project actions. MC.cfg is the recorded whole-program gate per the
  ticket-128 and ISSUE-166 precedents.
- `MC_program_promotion.cfg` (SPECIFICATION Spec, full Next): bounded 120 s
  probe on BOTH the unmodified baseline and the ISSUE-168 model — both still
  exploring at the cap with the same profile (~1.7M states generated, ~126K
  distinct at 120 s) and NO violation found in either run. Pre-existing
  scale, unchanged by this slice (the new branch adds no state and is
  condition-false in this bounded model). Per the 120 s tlc_seconds cap this
  config is not the ticket gate; recorded here as a fact, not as evidence of
  completion.

## Spec-unit tests

- `uv run --with pytest --with pyyaml pytest specs/tickets/ISSUE-168/current/tests`
  — 14 passed, 0 failed, 0 skipped.
- `test_claimant_refresh_program_model.py::test_claimant_views_are_additive_to_the_accepted_monolith`
  updated: ISSUE-166 pinned the working copy byte-identical to
  `specs/program_model`; ISSUE-168 advances the monolith, so the test now
  asserts (a) the four ISSUE-168 markers exist in the working copy and not in
  the accepted baseline, (b) every line of the accepted monolith survives
  verbatim (the slice is line-additive), (c) MC.cfg is unchanged.

## Production validation

- `jbang RunTests.java` — ALL PASSED (includes the new
  `ProjectResolveAtomicClosureTest`: one-pass mutual-import resolve,
  order-reversal closure identity, pre-commit typed refusal with
  byte-identical home outside the registration, and the preserved direct
  `install` exit-11 contract).
- End-to-end CLI against isolated scratch homes (offline local fixtures):
  - forward order: `project resolve --project-dir <fixture> --skip-gateway`
    exit 0, installed 2, resolved 2 — ONE invocation from a brand-new home;
  - reversed order: exit 0, identical resolved closure {alpha, beta};
  - planted `ghost-unit` import: exit 11, evidence names
    `alpha (skill): docs/ghost.md ... missing unit \`ghost-unit\``;
    `skills/` absent, no project lock, no child home, no projections;
    registration retains declared intent.
- Test graphs (each full pass required): project-resolve PASSED;
  project-smoke PASSED; onboarding PASSED; spec-conformance PASSED.
