# Validation report — 20260613-215322

**Overall**: PASSED
**Nodes**: 1 (passed=1, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `self.rerun.graph.uv` | **PASS** | 28988ms | [context/self.rerun.graph.uv.input.json](context/self.rerun.graph.uv.input.json) | [node-logs/self.rerun.graph.uv.stdout.log](node-logs/self.rerun.graph.uv.stdout.log) |

## `self.rerun.graph.uv` — **PASS**

executor start: `2026-06-13T21:53:22.151079Z`
executor end: `2026-06-13T21:53:51.139131Z`
spawn exit code: 0

**Input context**: [context/self.rerun.graph.uv.input.json](context/self.rerun.graph.uv.input.json)

### Assertions

| Name | Status |
|---|---|
| baseline_smoke_passed | **PASS** |
| smoke_run_id_found | **PASS** |
| smoke_build_dir_exists | **PASS** |
| resume_from_uv_node_passed | **PASS** |
| selected_node_was_uv | **PASS** |
| resume_continued_downstream | **PASS** |
| resume_rejects_rerun_false | **PASS** |
| resume_rerun_false_explained | **PASS** |

### Metrics

- `durationMs`: 28894

### Artifacts

- `project-smoke-report` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330/report.md`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330/report.md)

### Published context

- `smokeRunId`: `20260613-215330`

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
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 10s
5 actionable tasks: 1 executed, 4 up-to-date
resume_user_seeded exited 0
  plan[1/6] app.running  [testbed, jbang]  sources/AppRunning.java
  plan[2/6] user.seeded  [fixture, uv]  sources/user_seeded.py
  plan[3/6] network.pingable  [evidence, jbang]  sources/NetworkPingable.java
  plan[4/6] login.smoke  [assertion, jbang]  sources/LoginSmoke.java
  plan[5/6] rerun.disabled.probe  [evidence, uv]  sources/RerunDisabledProbe.py
  plan[6/6] context.snapshots.present  [assertion, uv]  sources/ContextSnapshotsPresent.py
resuming 'user.seeded' from /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330; skipping 1 already-passed dependency step(s)
  [1/5] user.seeded (uv)
  [2/5] network.pingable (jbang)
  [3/5] login.smoke (jbang)
  [4/5] rerun.disabled.probe (uv)
  [5/5] context.snapshots.present (uv)
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215330

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 9s
5 actionable tasks: 1 executed, 4 up-to-date
resume_rerun_false exited 1
> Task :smoke FAILED

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.
5 actionable tasks: 1 executed, 4 up-to-date


FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':smoke'.
> cannot resume from node 'rerun.disabled.probe': node metadata has rerun=false

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 7s
```

**Node-process stdout**: [node-logs/self.rerun.graph.uv.stdout.log](node-logs/self.rerun.graph.uv.stdout.log)

---
