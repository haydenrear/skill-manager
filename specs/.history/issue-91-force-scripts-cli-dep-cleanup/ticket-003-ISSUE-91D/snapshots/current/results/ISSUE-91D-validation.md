# ISSUE-91D Validation

Ticket: Close docs and promote spec evidence.

## Spec And Unit Validation

- `bash specs/current/run_tlc.sh specs/current/SkillManager.tla specs/current/MC.cfg`
  - Passed: model checking completed with no errors.
  - States generated: 67,930,849.
  - Distinct states: 2,337,728.
  - Depth: 23.
- `jbang --quiet RunTests.java`
  - Passed: `ALL PASSED`.
  - Added assertions for CLI help `--force-scripts` coverage and bundled
    skill documentation coverage.
- `uvx pytest specs/current/tests specs/desired_program_model/tests`
  - Passed: 3 tests.
- `cmp` checks confirmed promoted `specs/program_model/SkillManager.tla`
  and `specs/program_model/MC.cfg` are byte-identical to `specs/current`.

## Local File Install Dry Runs

Each command ran with an isolated temporary `SKILL_MANAGER_HOME`:

- `./skill-manager install file:///Users/hayde/IdeaProjects/skill-manager/skill-manager-skill --dry-run --yes`
  - Passed.
- `./skill-manager install file:///Users/hayde/IdeaProjects/skill-manager/skill-publisher-skill --dry-run --yes`
  - Passed.
- `./skill-manager install file:///Users/hayde/IdeaProjects/skill-manager/skill-dev-skill --dry-run --yes`
  - Passed.

## Test Graph Validation

- First `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py --all`
  - Infrastructure failure after doc-smoke: Gradle daemon stopped while
    starting `project-manifest`.
  - Completed graph evidence before the infrastructure failure included:
    - `test_graph/build/validation-reports/20260612-181304/report.md`
    - `test_graph/build/validation-reports/20260612-182439/report.md`
    - `test_graph/build/validation-reports/20260612-182700/report.md`
- Second `python3 /Users/hayde/.skill-manager/skills/test-graph/scripts/run.py --all`
  - Passed: `BUILD SUCCESSFUL in 14m 22s`.
  - Key ISSUE-91 evidence:
    - Smoke: `test_graph/build/validation-reports/20260612-182831/report.md`
    - Plugin smoke: `test_graph/build/validation-reports/20260612-183652/report.md`
    - Doc smoke: `test_graph/build/validation-reports/20260612-183912/report.md`
    - Skill-dev smoke: `test_graph/build/validation-reports/20260612-184111/report.md`

## Behavioral Evidence

- CLI help documents both `install --force-scripts` and
  `sync --force-scripts`.
- Skill-script docs now replace the old uninstall/reinstall limitation
  with orphan-only managed CLI cleanup and shared-claim preservation.
- Skill-manager, skill-publisher, and skill-dev skill docs install from
  local file sources in dry-run mode.
- `specs/current` and `specs/desired_program_model` converged, and the
  converged TLA/CFG were promoted into `specs/program_model`.
