# ISSUE-91C Validation

Ticket: Add E2E force-sync graph coverage.

## Spec And Unit Validation

- `uvx pytest specs/current/tests specs/desired_program_model/tests`
  - Passed: 3 tests.
- `jbang --quiet RunTests.java`
  - Passed: `ALL PASSED`.
- `bash specs/current/run_tlc.sh specs/current/SkillManager.tla specs/current/MC.cfg`
  - Passed: model checking completed with no errors.
  - States generated: 67,930,849.
  - Distinct states: 2,337,728.
  - Depth: 23.

## Test Graph Validation

- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/discover.py smoke`
  - Passed; regenerated `test_graph/docs/smoke.dot` and `test_graph/docs/smoke.png`.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/discover.py plugin-smoke`
  - Passed; regenerated `test_graph/docs/plugin-smoke.dot` and `test_graph/docs/plugin-smoke.png`.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py plugin-smoke`
  - Passed: `test_graph/build/validation-reports/20260610-203346/report.md`.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py smoke`
  - Passed after tightening the MCP assertion to the CLI sync result block:
    `test_graph/build/validation-reports/20260610-203854/report.md`.
- `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py --all`
  - Passed in 13m 3s.
  - Smoke graph evidence: `test_graph/build/validation-reports/20260610-204218/report.md`.
  - Plugin-smoke graph evidence: `test_graph/build/validation-reports/20260610-204958/report.md`.
  - Final graph evidence: `test_graph/build/validation-reports/20260610-205350/report.md`.

## Behavioral Evidence

- `skill.script.force.sync.with.mcp` installs a skill with a skill-script CLI
  dep and MCP dep, confirms noop sync skips the script, and confirms
  `sync --force-scripts` increments the script run counter while the MCP sync
  result reports the server deployed.
- `plugin.skill_script.force.sync` installs a plugin with pip CLI deps, MCP
  deps, and a plugin-level skill-script dep, confirms noop sync skips the
  script, and confirms `sync --force-scripts` increments the plugin script run
  counter while plugin CLI and MCP side effects remain valid.

These graph nodes are validation evidence for `SyncUnitForceScripts`; they are
not additional TLA+ program actions.
