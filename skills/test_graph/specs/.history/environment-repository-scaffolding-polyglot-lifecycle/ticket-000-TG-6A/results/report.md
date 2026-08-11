# Validation report — 20260628-154618

**Overall**: PASSED  
**Nodes**: 1 (passed=1, failed=0, errored=0)

| Node | Status | Duration | Input context | Captured stdout |
|---|---|---|---|---|
| `tg6.environment.repository.documentation` | **PASS** | 52ms | [context/tg6.environment.repository.documentation.input.json](context/tg6.environment.repository.documentation.input.json) | [node-logs/tg6.environment.repository.documentation.stdout.log](node-logs/tg6.environment.repository.documentation.stdout.log) |

## `tg6.environment.repository.documentation` — **PASS**

executor start: `2026-06-28T15:46:18.399321Z`  
executor end: `2026-06-28T15:46:18.451966Z`  
spawn exit code: 0

**Input context**: [context/tg6.environment.repository.documentation.input.json](context/tg6.environment.repository.documentation.input.json)

### Assertions

| Name | Status |
|---|---|
| environment_repositories_reference_exists | **PASS** |
| skill_routes_to_environment_repositories_reference | **PASS** |
| api_reference_points_to_environment_repositories_reference | **PASS** |
| workflow_guide_points_to_environment_repositories_reference | **PASS** |
| reference_contains_git_source_contract | **PASS** |
| reference_contains_git_fixture_initialization | **PASS** |
| reference_contains_git_fixture_add | **PASS** |
| reference_contains_git_fixture_commit | **PASS** |
| reference_contains_nested_git_rejected | **PASS** |
| reference_contains_tarball_rejected | **PASS** |
| reference_contains_local_preview_target | **PASS** |
| reference_contains_github_action_target | **PASS** |
| reference_contains_aws_preview_target | **PASS** |
| reference_contains_environment_id_output | **PASS** |
| reference_contains_kubeconfig_output | **PASS** |
| reference_contains_kubecontext_output | **PASS** |
| reference_contains_scaffold_tf_env_script | **PASS** |
| reference_contains_scaffold_env_script | **PASS** |
| reference_requires_local_k3d_setup | **PASS** |
| reference_requires_missing_cluster_deploy | **PASS** |
| reference_requires_existing_cluster_reuse | **PASS** |
| reference_requires_reset | **PASS** |
| reference_requires_explicit_teardown | **PASS** |
| reference_requires_skip_teardown | **PASS** |
| reference_requires_external_evidence | **PASS** |
| reference_requires_not_only_exit_code | **PASS** |
| ticket_plan_requires_local_graph_surface | **PASS** |
| ticket_plan_requires_github_actions_graph_surface | **PASS** |
| ticket_plan_requires_aws_graph_surface | **PASS** |
| ticket_plan_requires_functionality_graphs | **PASS** |
| ticket_plan_requires_exhaustive_environment_graphs | **PASS** |

### Metrics

- `durationMs`: 0

### Artifacts

- `environment-repositories-reference` — [`/Users/hayde/IdeaProjects/test_graph/references/environment-repositories.md`](/Users/hayde/IdeaProjects/test_graph/references/environment-repositories.md)

### Published context

- `environmentRepositoryReference`: `/Users/hayde/IdeaProjects/test_graph/references/environment-repositories.md`

### Inline logs

```
Validated TG-6A environment repository documentation routing and coverage requirements.
```

**Node-process stdout**: [node-logs/tg6.environment.repository.documentation.stdout.log](node-logs/tg6.environment.repository.documentation.stdout.log)

---

