# Validation report — 20260613-213003

**Overall**: PASSED  
**Nodes**: 1 (passed=1, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `self.run.only.jbang` | **PASS** | 20325ms | [context/self.run.only.jbang.input.json](context/self.run.only.jbang.input.json) | [node-logs/self.run.only.jbang.stdout.log](node-logs/self.run.only.jbang.stdout.log) |

## `self.run.only.jbang` — **PASS**

executor start: `2026-06-13T21:30:03.677573Z`  
executor end: `2026-06-13T21:30:24.002033Z`  
spawn exit code: 0

**Input context**: [context/self.run.only.jbang.input.json](context/self.run.only.jbang.input.json)

### Assertions

| Name | Status |
|---|---|
| baseline_smoke_passed | **PASS** |
| smoke_run_id_found | **PASS** |
| smoke_build_dir_exists | **PASS** |
| run_only_jbang_node_passed | **PASS** |
| selected_node_was_jbang | **PASS** |
| did_not_continue_downstream | **PASS** |

### Metrics

- `durationMs`: 20231

### Artifacts

- `project-smoke-report` — [`/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011/report.md`](/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011/report.md)

### Published context

- `smokeRunId`: `20260613-213011`

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
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 10s
5 actionable tasks: 1 executed, 4 up-to-date
run_only_login exited 0
> Task :smoke
[toolchain] jbang=0.137.0 (path: /opt/homebrew/bin/jbang)
[toolchain] uv=0.11.19 (path: /opt/homebrew/bin/uv)
testGraph 'smoke' run=20260613-213011 steps=6
  plan[1/6] app.running  [testbed, jbang]  sources/AppRunning.java
  plan[2/6] user.seeded  [fixture, uv]  sources/user_seeded.py
  plan[3/6] network.pingable  [evidence, jbang]  sources/NetworkPingable.java
  plan[4/6] login.smoke  [assertion, jbang]  sources/LoginSmoke.java
  plan[5/6] rerun.disabled.probe  [evidence, uv]  sources/RerunDisabledProbe.py
  plan[6/6] context.snapshots.present  [assertion, uv]  sources/ContextSnapshotsPresent.py
running only 'login.smoke' from /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011; not continuing downstream graph nodes
  [1/1] login.smoke (jbang)
wrote /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011/summary.json + /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011/report.md
testGraph 'smoke' done. reports: /Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260613-213011

[Incubating] Problems report is available at: file:///Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 8s
5 actionable tasks: 1 executed, 4 up-to-date
```

**Node-process stdout**: [node-logs/self.run.only.jbang.stdout.log](node-logs/self.run.only.jbang.stdout.log)

---

