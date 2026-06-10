# ISSUE-91B Validation

Date: 2026-06-10

Scope:

- Implemented uninstall CLI dependency orphan pruning.
- Advanced `specs/current` so `RemoveUnit` prunes only `CliDepsFor({u}) \ CliDepsFor(remaining)`.
- Advanced `RunEffectProgramFailure` to roll back package and skill-script CLI deps through `CliDepsFor`.
- Added ownership invariants for CLI artifacts, CLI lock rows, and skill-script runs.

Validation:

- `jbang --quiet RunTests.java`
  - Result: passed (`ALL PASSED`).
- `uvx pytest specs/current/tests specs/desired_program_model/tests`
  - Result: passed (`3 passed`).
- `bash specs/current/run_tlc.sh specs/current/SkillManager.tla specs/current/MC.cfg`
  - Result: passed, no errors.
  - States generated: 67,930,849.
  - Distinct states: 2,337,728.
  - Depth: 23.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/discover.py`
  - Result: passed.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/discover.py smoke`
  - Result: passed.
  - New node: `skill.script.uninstall.prunes.cli`.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py --all`
  - Result: passed (`BUILD SUCCESSFUL in 13m 27s`).
  - Smoke report: `test_graph/build/validation-reports/20260610-194034/report.md`.
  - Full run completed through `skill-dev-smoke`: `test_graph/build/validation-reports/20260610-195224/report.md`.

Production coverage:

- `UninstallCliCleanupTest`
  - Orphaned skill-script binary and `cli-lock.toml` row are removed.
  - Shared CLI binary and lock row are preserved with `requested_by` rewritten to surviving units.
  - Plugin-level and contained-skill CLI deps are re-walked before plugin uninstall cleanup.
  - Lower-level `remove` leaves CLI artifacts and lock rows alone.
- `CompensationOrphanTest`
  - Rollback compensation now uses the same CLI cleaner, preserving shared artifacts and removing orphaned `bin/cli` entries.
- `skill.script.uninstall.prunes.cli`
  - End-to-end command smoke installs the existing skill-script fixture, verifies `bin/cli` and `cli-lock.toml`, uninstalls it, then verifies both are pruned.
