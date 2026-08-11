# Validation report — 20260612-185639

**Overall**: FAILED  
**Nodes**: 1 (passed=0, failed=1, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `rerun.guidance.failure` | **FAIL** | 53ms | [context/rerun.guidance.failure.input.json](context/rerun.guidance.failure.input.json) | [node-logs/rerun.guidance.failure.stdout.log](node-logs/rerun.guidance.failure.stdout.log) |

## `rerun.guidance.failure` — **FAIL**

**Failure**: forced failure for rerun guidance

executor start: `2026-06-12T18:56:39.521628Z`  
executor end: `2026-06-12T18:56:39.574410Z`  
spawn exit code: 0

**Input context**: [context/rerun.guidance.failure.input.json](context/rerun.guidance.failure.input.json)

### Rerun guidance

Saved input context: [`context/rerun.guidance.failure.input.json`](context/rerun.guidance.failure.input.json)

Resume graph:

```bash
./gradlew 'rerunGuidance' --resume-from-build '/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260612-185639' --resume-from-node 'rerun.guidance.failure'
```

Run only this node:

```bash
./gradlew 'rerunGuidance' --resume-from-build '/Users/hayde/IdeaProjects/test_graph/project_sdk_sources/build/validation-reports/20260612-185639' --run-only-node 'rerun.guidance.failure'
```

### Metrics

- `durationMs`: 0

**Node-process stdout**: [node-logs/rerun.guidance.failure.stdout.log](node-logs/rerun.guidance.failure.stdout.log)

---

