# Validation report — 20260613-215413

**Overall**: PASSED
**Nodes**: 1 (passed=1, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `self.run.only.uv` | **PASS** | 27429ms | [context/self.run.only.uv.input.json](context/self.run.only.uv.input.json) | [node-logs/self.run.only.uv.stdout.log](node-logs/self.run.only.uv.stdout.log) |

## `self.run.only.uv` — **PASS**

executor start: `2026-06-13T21:54:13.215052Z`
executor end: `2026-06-13T21:54:40.644692Z`
spawn exit code: 0

**Input context**: [context/self.run.only.uv.input.json](context/self.run.only.uv.input.json)

### Assertions

| Name | Status |
|---|---|
| baseline_smoke_passed | **PASS** |
| smoke_run_id_found | **PASS** |
| smoke_build_dir_exists | **PASS** |
| run_only_uv_node_passed | **PASS** |
| selected_node_was_uv | **PASS** |
| did_not_continue_downstream | **PASS** |
| run_only_rejects_rerun_false | **PASS** |
| run_only_rerun_false_explained | **PASS** |

### Metrics

- `durationMs`: 27341

### Artifacts

- `project-smoke-report` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421/report.md`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421/report.md)

### Published context

- `smokeRunId`: `20260613-215421`

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
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 10s
5 actionable tasks: 1 executed, 4 up-to-date
run_only_user_seeded exited 0
> Task :smoke
[toolchain] jbang=0.137.0 (path: /opt/homebrew/bin/jbang)
[toolchain] uv=0.11.19 (path: /opt/homebrew/bin/uv)
testGraph 'smoke' run=20260613-215421 steps=6
  plan[1/6] app.running  [testbed, jbang]  sources/AppRunning.java
  plan[2/6] user.seeded  [fixture, uv]  sources/user_seeded.py
  plan[3/6] network.pingable  [evidence, jbang]  sources/NetworkPingable.java
  plan[4/6] login.smoke  [assertion, jbang]  sources/LoginSmoke.java
  plan[5/6] rerun.disabled.probe  [evidence, uv]  sources/RerunDisabledProbe.py
  plan[6/6] context.snapshots.present  [assertion, uv]  sources/ContextSnapshotsPresent.py
running only 'user.seeded' from /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421; not continuing downstream graph nodes
  [1/1] user.seeded (uv)
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-215421

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 7s
5 actionable tasks: 1 executed, 4 up-to-date
run_only_rerun_false exited 1
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

**Node-process stdout**: [node-logs/self.run.only.uv.stdout.log](node-logs/self.run.only.uv.stdout.log)

---
