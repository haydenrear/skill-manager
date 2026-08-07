# ISSUE-166 post-close harness validation — 2026-08-07

The normal, non-weakened ISSUE-166 close created
`specs/.history/child-home-materialization-workflow/ticket-020-ISSUE-166`.
That append-only entry remains unchanged.

The required promoted project-scope spec-unit run then found a fresh-install
test-harness dependency: the newly added exact port-mode assertion imported
PyYAML, while the prescribed runner intentionally provisions only pytest. Test
collection failed with `ModuleNotFoundError: No module named 'yaml'`; no test
result from that attempt is counted as passing.

Production Java, TLA+ models, generated cases, bindings, graph nodes, and the
sealed close record were not changed. Only the live
`specs/current/tests/test_claimant_refresh_program_model.py` and the matching
future global-desired contract test were changed to extract the exact,
unique `SkillManagerCli.sync_named_unit` line using Python's standard library
and assert its value is `real`. This preserves the same contract without an
undeclared package dependency.

The first dependency-free rerun executed 15 tests and exposed two additional
promotion-scope harness assumptions; its 13 passing and 2 failing result is not
counted as a passing gate:

- the live claimant guard resolved `MODEL_ROOT.parent / "results"` to the
  repository-wide legacy `specs/results`, then failed on an unrelated older
  report. The live current and global desired guards now cover the exact
  ISSUE-166-owned result artifacts in the sealed ticket record, plus their
  respective live claimant model/generated artifacts;
- the preserved, unseeded `test_current_ticket_workflow.py` hardcoded
  `CHM-1`. It now extracts the live manifest's `active_ticket` with the Python
  standard library, finds exactly that ticket's canonical plan block, and
  requires a closed/done status. It is generic rather than ISSUE-166-specific.

## Final rerun

Command:

```text
UV_CACHE_DIR=<writable-cache> UV_OFFLINE=1 \
PYTHONDONTWRITEBYTECODE=1 \
.skill-manager/bin/cli/tla-spec-dev --spec-root specs \
  run spec-unit-tests --scope project
```

Verdict: passed. The promoted `specs/current` target ran all 15 tests: 15
passed, 0 failed, 0 skipped, and the workflow reported
`spec-unit validation passed for 1 target(s)`.

The matching global desired claimant contract suite also passed all three
tests. MF-015 external binding enforcement passed for the live current and
global desired mappings; the current rung is
`SkillManagerCli.sync_named_unit`, and the global rung retains all four real
ports.

The worktree-home close-out gate returned `safe: true`, exit 0, with zero
blockers; every listed skill, plugin, doc, and harness unit was unchanged. The
worktree remains standing for external review.
