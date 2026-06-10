# ISSUE-91A Validation Evidence

Date: 2026-06-10

Ticket: ISSUE-91A - Add force-scripts command plumbing

Summary:

- Implemented `install --force-scripts` and `sync --force-scripts` for `skill-script:` CLI dependencies.
- Preserved normal fingerprint/binary-present skipping when force is not requested.
- Kept force scoped to skill-script reruns; policy gates still run before script execution.
- Left uninstall CLI dependency pruning as desired-only work for ISSUE-91B.

Commands and results:

- `uvx pytest specs/current/tests specs/desired_program_model/tests`
  - Passed, 3 tests.
- `bash specs/current/run_tlc.sh specs/current/SkillManager.tla specs/current/MC.cfg`
  - Passed with no invariant violations.
  - Generated states: 77,601,729.
  - Distinct states: 2,670,816.
  - Complete search depth: 23.
- `./RunTests.java`
  - Passed, final output `ALL PASSED`.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/discover.py`
  - Passed; listed registered graphs.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/discover.py smoke`
  - Passed; planned `skill.script.force.rerun` as smoke step 22.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py smoke`
  - Passed in run `20260610-184535`, 48/48 nodes.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py --all`
  - Passed; Gradle `validationRunAll` build successful in 13m 9s.
  - Smoke run `20260610-184858` passed, including `skill.script.force.rerun`.
  - Final graph run `20260610-190032` passed.

Graph evidence:

- `test_graph/build/validation-reports/20260610-184858/report.md`
- `test_graph/build/validation-reports/20260610-190032/report.md`

Environment note:

- The first smoke attempt failed before the ticket node because the local compose
  default image tag `localhost:5005/postgres-pgvector:latest` was missing/stale
  while the existing `skill-manager` Postgres volume was initialized by
  PostgreSQL 17. The validation environment was repaired non-destructively by
  pulling `pgvector/pgvector:pg17`, tagging it to the compose default, and
  recreating only the stopped container without deleting the volume.
