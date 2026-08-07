# ISSUE-166 validation evidence — 2026-08-07

## Scope and integrity

- Ticket: `ISSUE-166`, preserve pinned revisions during automatic
  claiming-project refresh.
- Accepted-model baseline: `b5a0d800e28cf984887ab441b5c25746d7067d23`.
- Spec-first sealed commit: `86165bc`.
- `specs/program_model` has no diff from the accepted baseline.
- The ticket copy of `SkillManager.tla` has SHA-256
  `dbe57242be31f4c5b163fdce0df21d54d36ea07d9348cee3f7578488f6b65925`,
  identical to the accepted monolith. The ticket copy of `MC.cfg` has SHA-256
  `7a6825f956c94beba19324be6ed9c0a1d4cae5f39b226d41f74d1bdf075ed4f7`,
  also identical to the accepted model.

## Formal validation

All desired configurations completed without an invariant violation. Both
regression configurations failed with the named defect as their first
invariant violation; an expected-violation run is evidence only because its
failure identity was inspected.

| Model/configuration | Verdict | Generated | Distinct | Depth | Inspected result |
| --- | --- | ---: | ---: | ---: | --- |
| accepted `SkillManager.tla` / `MC.cfg` | passed | 10 | 1 | 1 | no invariant violation |
| preservation-only global `Internal.tla` / `Internal.cfg` | passed | 8,306 | 2,065 | 12 | no invariant violation |
| preservation-only global `External.tla` / `External.cfg` | passed | 1,302 | 250 | 9 | no invariant violation |
| `ClaimantRefreshInternal.tla` / `ClaimantRefreshInternal.cfg` | passed | 2 | 2 | 2 | no invariant violation |
| `ClaimantRefreshInternal.tla` / regression config | expected violation (exit 12) | 2 | 2 | 2 | first violation `AutomaticClaimantRefreshDoesNotPullTrunk`; defect action `PullTrunkThenReconcileClaimingProject` |
| `ClaimantRefreshExternal.tla` / `ClaimantRefreshExternal.cfg` | passed | 2 | 2 | 2 | no invariant violation |
| `ClaimantRefreshExternal.tla` / regression config | expected violation (exit 12) | 2 | 2 | 2 | first violation `SuccessfulNamedSyncHasOneSelectedRevision`; defect action `PublicNamedSyncWithIndependentTrunkPull` |

The ticket-scoped spec-unit command passed 14 contract tests and the one
generated claimant-refresh case. Capability inspection found ten adapter
mappings and one generated label. The generated case has output-conformance
coverage. It does not claim projector, effect, mutation, or port-swap oracle
coverage; production behavior is instead covered by the real-git JVM test and
the external Test Graph node below.

## External binding contract

The independent audit found the initial `real_cli` value was outside the
MF-015 `double|real` enum. The value was corrected to exact `real` in ticket
desired/current and global desired bindings. Contract tests now parse YAML and
assert the exact mode.

`enforce_external_bindings`, restricted to
`PublicNamedSyncPinnedRevision`, passed against all three binding files. The
ticket integration rung is `SkillManagerCli.sync_named_unit`; the global rung
retains its four real ports. Isolated claimant contract suites then passed:

- ticket desired: 7 passed;
- ticket current: 7 passed;
- global desired: 3 passed.

## JVM behavior validation

The first full JVM diagnostic exposed one fixture-comparison defect: the test
compared a raw `file:///.../skill/` origin to the persisted normalized
`file:///.../skill` origin. Production intentionally normalizes origin
identity through `UnitStore.normalizeOrigin` / `UnitStore.sameOrigin`. The
test and Test Graph fixture were corrected to use the production-equivalent
same-origin predicate; no production behavior was weakened.

The subsequent full `jbang RunTests.java` run passed 114 suites and 985 tests,
with zero failed tests. The report's incidental product-log uses of the word
“skipped” were inspected and were not test skip results. An independent
reviewer reran the full JVM suite with the same 114-suite / 985-pass / 0-fail
verdict.

The real-git regression establishes clean local repositories A and B, with the
selected source detached at A while upstream has B. It proves all prerequisite
unrelated state exists before sync, then proves these 22 assertions:

1. `fixture_project_resolve_exit_zero`
2. `fixture_unrelated_install_exit_zero`
3. `fixture_units_lock_stabilized`
4. `fixture_source_is_clean_detached_a_without_b`
5. `fixture_unrelated_installed_record_present`
6. `fixture_unrelated_parent_tree_nonempty`
7. `fixture_unrelated_child_tree_nonempty`
8. `fixture_unrelated_units_lock_row_present`
9. `fixture_unrelated_cli_lock_row_present`
10. `fixture_unrelated_parent_cli_shim_executable`
11. `fixture_unrelated_child_cli_shim_executable`
12. `named_sync_exit_zero`
13. `checkout_head_remains_selected_a`
14. `installed_git_hash_is_selected_a`
15. `units_lock_resolved_sha_is_selected_a`
16. `registered_project_revision_is_selected_a`
17. `child_realization_is_selected_a`
18. `project_lock_source_and_directness_preserved`
19. `claimant_refresh_did_not_fetch_or_merge_b`
20. `no_partial_or_error_receipt`
21. `unrelated_records_trees_locks_and_shims_byte_identical`
22. `every_git_tree_is_clean`

## Test Graph evidence

Every run below used a full plan. Every named assertion passed. Summary audits
found no missing, duplicate, unexpected, unknown-status, missing-trace,
invalid-trace, mismatched-trace, missing-context, invalid-context, unexpected-
context, provenance-violation, or replay-source-mismatch node. Envelope and
context size limits were not exceeded.

| Owner | Graph | Run ID | Trace ID | Nodes | Assertions | Non-passing |
| --- | --- | --- | --- | ---: | ---: | ---: |
| implementer | `project-smoke` | `20260807-231027` | `a3a7907e10f25532c5c264ab3d0b3ea6` | 7/7 | 101 | 0 |
| implementer | `spec-conformance` | `20260807-231142` | `6c26be721db82991b8e5f93c97aaa412` | 3/3 | 28 | 0 |
| independent reviewer | `project-smoke` | `20260807-230847` | `e828836913487d772189fc8e133ff345` | 7/7 | 101 | 0 |
| independent reviewer | `spec-conformance` | `20260807-230808` | `d97de3c749b937c1fdbe64472c45cd17` | 3/3 | 28 | 0 |

The implementer `project-smoke` fixpoint envelope checked three homes and
repaired zero; the implementer `spec-conformance` fixpoint envelope checked
two homes and repaired zero. Both passed
`every_home_verifies_or_its_own_remedy_repairs_it`. The pinned-sync node
checked four git trees and passed all 22 assertions in both graphs.

No skipped, waived, disabled, xfailed, missing, selectively replayed, or
invalid result is counted as passing evidence.
