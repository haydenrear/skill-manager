# Environment Repositories

Environment repositories are Git repositories that describe reusable branch
preview environments for test graph nodes. The application repository declares
which environment repository and template to use; the test graph runtime clones
or reuses that repository outside the application source tree, runs OpenTofu in
the selected template, and publishes environment outputs to downstream nodes.

The contract is provider-neutral. A deploy-helm repository can implement it,
but the SDK and test graph validation must not import deploy-helm or depend on
a deploy-helm worktree.

The shipped `monitoring.cluster.ensure` and
`monitoring.cluster.assert.ready` standard nodes deliberately do **not** use
this contract. Their installed deploy-cdc `monitoring` CLI already owns the
entire cluster, storage, Helm, and readiness lifecycle. Attaching
`environmentRepository` metadata to those nodes would run an additional
Git/OpenTofu prelude and create a second deployment authority.

## Node Contract

Nodes declare an environment repository through `NodeSpec.environmentRepository`
and pair it with an environment side effect:

```python
NodeSpec("preview.provision") \
    .kind("testbed") \
    .side_effects(SideEffect.environment("provision")) \
    .environment_repository(
        EnvironmentRepository.of(
            "git@github.com:example/environments.git",
            "templates/branch-preview"))
```

The serialized node metadata has this shape:

```json
{
  "environmentRepository": {
    "source": "git@github.com:example/environments.git",
    "template": "templates/branch-preview",
    "target": "local-preview",
    "backend": "local",
    "branch": "feature",
    "outputKeys": ["EnvironmentId", "KUBECONFIG", "KUBECONTEXT"]
  }
}
```

Fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `source` | yes | Ordinary Git URL or local Git repository path. Accepted forms include `git@...`, `https://...`, `ssh://...`, `git://...`, `file:///tmp/env-repo`, and local paths. Archives, zips, and tarballs are not valid primary sources. |
| `template` | yes | Relative path inside the environment repository. Absolute paths, `.`, `..`, and empty path segments are rejected. |
| `target` | no | Logical environment target. Current targets are `local-preview`, `local-github-action`, and `aws-preview`. |
| `backend` | no | Execution backend. Current backends are `local`, `github-action`, and `aws`. |
| `branch` | no | Branch scope selector. `feature` means the runtime branch from `TEST_GRAPH_FEATURE_BRANCH`, `GITHUB_HEAD_REF`, `GITHUB_REF_NAME`, then `local`. |
| `outputKeys` | no | Structured output keys. Every environment must expose `EnvironmentId`, `KUBECONFIG`, and `KUBECONTEXT`. |

## Repository Form

An environment repository is a normal Git repository. Contract tests must create
temporary Git repositories with `git init`, `git add`, and `git commit` during
the test. Do not check a nested `.git` directory into the application
repository, and do not use a tarball as the primary fixture.

A repository can keep one environment per template directory:

```text
<environment-repo>/
  templates/
    branch-preview/
      variables.tf
      outputs.tf
      local.tf
      local-github-action.tf
      aws.tf
```

Separate template directories are also valid when a repository wants clearer
provider isolation:

```text
<environment-repo>/
  templates/
    local-preview/
      main.tf
      variables.tf
      outputs.tf
    local-github-action/
      main.tf
      variables.tf
      outputs.tf
    aws-preview/
      main.tf
      variables.tf
      outputs.tf
```

Whichever layout is used, the selected template must be runnable with
OpenTofu from that directory and must return these outputs:

```hcl
output "EnvironmentId" { value = local.environment_id }
output "KUBECONFIG" { value = local.kubeconfig_path }
output "KUBECONTEXT" { value = local.kubecontext }
```

Additional outputs such as `API_BASE_URL` are allowed. Downstream nodes can
project one output with `env:[KEY]` or all eligible outputs with `env:[*]`.

## Lifecycle

The runtime lifecycle is:

1. Resolve the branch-scoped environment id:
   `<graph>__<branch>__<target>__<backend>`.
2. Clone or reuse `source` outside the application repository.
3. Enter the selected `template`.
4. Run `tofu init`.
5. For `environment:provision`, run `tofu apply -auto-approve` only when the
   environment is not already provisioned. If it exists, reuse it and read
   outputs without recreating it.
6. For `environment:deploy`, reuse the provisioned environment and deploy the
   application/chart into that cluster.
7. For `environment:reset`, rerun apply or the repository-defined reset path,
   clear deployed/application state, and keep the provisioned environment.
8. For `environment:destroy`, run `tofu destroy -auto-approve` only when
   explicit destroy intent is present.
9. Read `tofu output -json` and publish at least `EnvironmentId`,
   `KUBECONFIG`, and `KUBECONTEXT`.

Destroy requires `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` or
`TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=true`. AWS execution also requires
explicit selection plus AWS credentials such as `AWS_PROFILE`,
`AWS_ACCESS_KEY_ID`, or `AWS_WEB_IDENTITY_TOKEN_FILE`.

## Local k3d Setup

A local-preview environment should be able to create or reuse a k3d cluster for
the branch. The expected local template behavior is:

1. Derive a stable cluster name from `EnvironmentId`.
2. Check whether that k3d cluster already exists.
3. Create it only when missing.
4. Write a kubeconfig file under the environment repository work directory or
   another report/build-local path.
5. Return `KUBECONFIG` as that file path and `KUBECONTEXT` as the cluster
   context name.
6. On reset, leave the cluster in place and reset application state.
7. On destroy, delete the k3d cluster only when explicit destroy intent is set.

The local template should be safe to run repeatedly for the same branch. A
second deploy for the same branch must not recreate the cluster.

## Required Test Graph Coverage

Every ticket that adds functionality, scripts, scaffolding, generated node
templates, SDK behavior, or deployment lifecycle behavior must add a new test
graph or add explicit nodes and assertions to an existing graph.

Environment repository scaffolding and deployment lifecycle behavior must be
covered across these graph surfaces:

| Graph Surface | Required Coverage |
| --- | --- |
| local | Missing cluster deploy, existing cluster reuse without recreation, reset, explicit teardown, and skip teardown. |
| GitHub Actions | Scaffolded `local-github-action` template behavior, CI-safe keep-alive behavior, destroy guard behavior, and context projection. |
| AWS | Guarded discovery in normal CI, explicit selection and credential checks, missing environment deploy, existing environment reuse, reset, explicit teardown, and skip teardown. |

The graph assertions must inspect external evidence such as provisioning marker
files, generated repository contents, OpenTofu outputs, kubeconfig paths,
context projection, and application health/reachability. Do not rely only on a
node process exit code.

Current local validation graphs:

- `environmentRepositoryLocalLifecycle` provisions a missing local-preview
  environment, deploys into the existing environment without rerunning apply,
  resets application state while keeping the provisioned environment, and
  validates skip-destroy keep-alive behavior.
- `environmentRepositoryLocalLifecycleDestroy` is guarded by default. Without
  destroy intent it proves no destroy runtime is invoked; with
  `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` it validates `tofu destroy`,
  destroy-requested markers, destroyed markers, and provisioned marker removal.
- `environmentRepositoryGithubActionLifecycle` runs the same missing deploy,
  existing reuse, reset, and skip-destroy checks against the
  `local-github-action` target/backend. It uses the scaffolded environment
  repository shim, so it is safe for CI and does not create cloud resources.
- `environmentRepositoryGithubActionLifecycleDestroy` validates the
  `local-github-action` destroy guard. Run it normally to prove destroy is not
  invoked by default, and run it with
  `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` to prove explicit teardown
  writes destroy-requested/destroyed markers and removes the active marker.
- `environmentRepositoryAwsLifecycle` is discoverable and safe in normal CI.
  Without `TEST_GRAPH_RUN_AWS_LIFECYCLE=true` and AWS credentials, it records
  guarded no-op lifecycle assertions and verifies no environment repository
  provision/deploy/reset runtime was invoked. With explicit selection and
  credentials, it runs the missing deploy, existing reuse, reset, and
  skip-destroy checks against `aws-preview`/`aws`.
- `environmentRepositoryAwsLifecycleDestroy` applies the same AWS guard to
  teardown. Destroy only declares an `environment:destroy` side effect when AWS
  lifecycle execution is explicitly selected, AWS credentials are present, and
  `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` or
  `TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=true` is set.

AWS validation opt-in examples:

```bash
TEST_GRAPH_RUN_AWS_LIFECYCLE=true AWS_PROFILE=preview \
  ./scripts/run.py environmentRepositoryAwsLifecycle --test-graph-root test_graph

TEST_GRAPH_RUN_AWS_LIFECYCLE=true AWS_PROFILE=preview \
  TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true \
  ./scripts/run.py environmentRepositoryAwsLifecycleDestroy --test-graph-root test_graph
```

## Scaffold Scripts

Use these scripts to create or extend an environment repository:

```bash
scripts/scaffold-tf-env.py <environment-repo-dir>
scripts/scaffold-env.py <environment-repo-dir> --target local-preview
scripts/scaffold-env.py <environment-repo-dir> --target local-github-action
scripts/scaffold-env.py <environment-repo-dir> --target aws-preview
```

`scaffold-tf-env.py` creates the environment repository skeleton with starter
OpenTofu files. `scaffold-env.py` adds an environment template to an existing
environment repository. The default template path is
`templates/branch-preview`; target-specific files coexist there as `local.tf`,
`local-github-action.tf`, and `aws.tf`.

For local validation graphs only, pass `--include-tofu-shim` to add a
repository-local `bin/tofu` shim. Normal environment repositories should replace
the starter HCL with real provider modules and rely on the system OpenTofu
binary.

Both scripts are validated through test graph fixtures that initialize a
temporary Git repository during the run.
