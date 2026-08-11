# Validation report — 20260613-215257

**Overall**: PASSED
**Nodes**: 1 (passed=1, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `self.rerun.graph.jbang` | **PASS** | 23492ms | [context/self.rerun.graph.jbang.input.json](context/self.rerun.graph.jbang.input.json) | [node-logs/self.rerun.graph.jbang.stdout.log](node-logs/self.rerun.graph.jbang.stdout.log) |

## `self.rerun.graph.jbang` — **PASS**

executor start: `2026-06-13T21:52:57.815860Z`
executor end: `2026-06-13T21:53:21.307341Z`
spawn exit code: 0

**Input context**: [context/self.rerun.graph.jbang.input.json](context/self.rerun.graph.jbang.input.json)

### Assertions

| Name | Status |
|---|---|
| baseline_smoke_passed | **PASS** |
| smoke_run_id_found | **PASS** |
| smoke_build_dir_exists | **PASS** |
| resume_from_jbang_node_passed | **PASS** |
| selected_node_was_jbang | **PASS** |
| resume_continued_downstream | **PASS** |

### Metrics

- `durationMs`: 23412

### Artifacts

- `project-smoke-report` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308/report.md`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308/report.md)

### Published context

- `smokeRunId`: `20260613-215308`

### Inline logs

```
baseline_smoke exited 0
  plan[1/6] app.running  [testbed, jbang]  sources/AppRunning.java
  plan[2/6] user.seeded  [fixture, uv]  sources/user_seeded.py
  plan[3/6] network.pingable  [evidence, jbang]  sources/NetworkPingable.java
  plan[4/6] login.smoke  [assertion, jbang]  sources/LoginSmoke.java
  plan[5/6] rerun.disabled.probe  [evidence, uv]  sources/RerunDisabledProbe.py
  plan[6/6] context.snapshots.present  [assertion, uv]  sources/ContextSnapshotsPresent.py
  [1/6] app.running (jbang)
  [2/6] user.seeded (uv)
  [3/6] network.pingable (jbang)
  [4/6] login.smoke (jbang)
  [5/6] rerun.disabled.probe (uv)
  [6/6] context.snapshots.present (uv)
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 13s
5 actionable tasks: 1 executed, 4 up-to-date
resume_login exited 0
[toolchain] uv=0.11.19 (path: /opt/homebrew/bin/uv)
testGraph 'smoke' run=20260613-215308 steps=6
  plan[1/6] app.running  [testbed, jbang]  sources/AppRunning.java
  plan[2/6] user.seeded  [fixture, uv]  sources/user_seeded.py
  plan[3/6] network.pingable  [evidence, jbang]  sources/NetworkPingable.java
  plan[4/6] login.smoke  [assertion, jbang]  sources/LoginSmoke.java
  plan[5/6] rerun.disabled.probe  [evidence, uv]  sources/RerunDisabledProbe.py
  plan[6/6] context.snapshots.present  [assertion, uv]  sources/ContextSnapshotsPresent.py
resuming 'login.smoke' from /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308; skipping 3 already-passed dependency step(s)
  [1/3] login.smoke (jbang)
  [2/3] rerun.disabled.probe (uv)
  [3/3] context.snapshots.present (uv)
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215308

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 9s
5 actionable tasks: 1 executed, 4 up-to-date
```

**Node-process stdout**: [node-logs/self.rerun.graph.jbang.stdout.log](node-logs/self.rerun.graph.jbang.stdout.log)

---
