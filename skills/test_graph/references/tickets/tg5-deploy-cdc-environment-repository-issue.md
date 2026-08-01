# Add deploy-helm environment repository templates for test graph branch environments

## Background

`haydenrear/test_graph_skill` now has the SDK-side branch environment repository contract implemented and validated under TG-5A through TG-5F. This issue is for the deploy-cdc/deploy-helm repository to implement real environment repository templates that satisfy that contract.

The key boundary is intentional:

- `test_graph` depends only on a provider-neutral Git environment repository contract.
- `deploy-cdc` provides one implementation of that contract using its deploy-helm/OpenTofu/CompuTeQ/Kubernetes/Helm surfaces.
- SDK contract tests must not import deploy-helm or deploy-cdc.

The SDK fixture has already proven the local contract with a generated temporary Git repository. This issue should build production-grade local and AWS templates in deploy-cdc.

## Required Git Environment Repository Contract

The repository must be consumable as an ordinary Git URL or local Git repository path:

```text
<environment-repo>/
  templates/
    local-preview/
      main.tf
      variables.tf
      outputs.tf
    aws-preview/
      main.tf
      variables.tf
      outputs.tf
```

The SDK selects a template by relative path, for example `templates/local-preview` or `templates/aws-preview`. Absolute template paths, `..`, tarballs, archives, and checked-in nested `.git` fixtures are outside the contract.

Minimum environment repository metadata consumed by test graph nodes:

```json
{
  "environmentRepository": {
    "source": "git@github.com:haydenrear/deploy-cdc.git",
    "template": "templates/local-preview",
    "target": "local-preview",
    "backend": "local",
    "branch": "feature",
    "outputKeys": ["EnvironmentId", "KUBECONFIG", "KUBECONTEXT"]
  }
}
```

The standard command sequence run by the SDK is:

1. Clone or reuse the Git repository outside the application source tree.
2. Enter the selected template directory.
3. Run `tofu init`.
4. Run `tofu apply -auto-approve` for first provision and reset.
5. For reuse/deploy on an already provisioned branch environment, skip apply and read outputs.
6. Run `tofu output -json`.
7. Run `tofu destroy -auto-approve` only for merge-time destroy when explicit destroy intent is present.

The template must emit these outputs through `tofu output -json`:

- `EnvironmentId`: stable branch-scoped environment id or name.
- `KUBECONFIG`: absolute path to a kubeconfig usable by downstream test graph nodes.
- `KUBECONTEXT`: Kubernetes context name for the branch environment.

Optional useful outputs are welcome, for example `Namespace`, `ClusterName`, `RegistryHost`, `RegistryPort`, `ReleaseName`, `IngressBaseUrl`, and `HelmValuesPath`, but downstream portability depends on the three required keys above.

## Lifecycle Semantics To Support

The SDK-side semantics are already implemented:

- First provision runs `tofu init`, `tofu apply -auto-approve`, and `tofu output -json`.
- Reuse/deploy for an existing branch environment skips apply and reads outputs.
- Deploy nodes use the existing environment outputs to deploy application/chart state.
- Reset reruns `tofu apply -auto-approve`, clears app/deploy state as appropriate, and keeps the branch cluster alive.
- Destroy runs `tofu destroy -auto-approve` only when `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true` or `TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=true`.
- Normal validation and PR runs must not destroy the cluster.

The deploy-cdc templates should make those semantics meaningful:

- `local-preview` should create or reuse a local k3d/k3s preview cluster for a feature branch.
- `aws-preview` should create or reuse an AWS-backed preview cluster only when explicitly selected and credentials are present.
- Reset should be safe for redeploying the application into the existing branch environment without deleting the whole cluster.
- Destroy should cleanly deprovision the branch environment and leave no active cluster markers/resources behind.

## deploy-helm Context To Use

The deploy-helm skill describes deploy-cdc as the combined deploy and compute operations facade:

- Use the repository deployment CLI where possible: `helm-deploy` or `PYTHONPATH=src python -m helm_deploy`.
- Local cluster setup is k3d/k3s plus Docker registry, chart dependencies, Kubernetes secrets, MinIO/Kafka helpers, and optional Kueue.
- Important local inputs include `REG_HOST`, `REG_PORT`, `NETWORK_NAME`, `CLUSTER_NAME`, `K3D_CONFIG_PATH`, `HELM_RELEASE_NAME`, `HELM_CHART_PATH`, and `CDC_CHART_PATH`.
- CDC chart installation is through `charts/cdc`; build dependencies before installation with `helm-deploy deploy --build_dependencies`.
- CDC install should use `helm-deploy deploy --build_dependencies --install_cdc` where the CLI is sufficient, or raw `helm template`/`helm install` only where the CLI has a documented gap.
- Kueue support lives in deploy-helm as an installable controller/control-plane surface, with chart-defined ResourceFlavor, ClusterQueue, and LocalQueue resources where enabled.
- CompuTeQ/OpenTofu owns native runner machine provisioning, runner inventory, project-local workspaces under `.computeq/tofu`, and native runner/Kueue bridge integration.

Known deploy-helm edges to account for:

- Prefer the installed `helm-deploy` console script, or `PYTHONPATH=src python -m helm_deploy`; plain `python -m helm_deploy` from repo root can resolve the wrong namespace.
- `deploy-basic` does not currently install CDC by default.
- `install_chart()` currently needs namespace behavior verified before relying on non-default namespaces.
- Docker must be running for local registry and k3d work.
- AWS preview validation must be explicitly selected and credential-gated so CI does not accidentally create cloud infrastructure.

## Implementation Tasks

- Add environment repository templates under deploy-cdc for `templates/local-preview` and `templates/aws-preview`.
- Make `local-preview` provision a branch-scoped local k3d/k3s environment with deterministic naming from the feature branch/environment id.
- Make `local-preview` produce a kubeconfig path and context that downstream test graph nodes can use without extra discovery.
- Add Helm deployment wiring for the example/default deploy-cdc chart path so a test graph can deploy into the provisioned environment.
- Add reset behavior that clears application deployment state or reruns the application install without destroying the local cluster.
- Add guarded destroy behavior through OpenTofu for merge cleanup.
- Add `aws-preview` as explicitly selected and credential-gated. It should fail fast with a clear message when selected without AWS credentials.
- Add documentation showing the `NodeSpec.environmentRepository(...)` metadata that points test graph nodes at deploy-cdc.
- Add a deploy-cdc test graph that validates the contract locally:
  - provision `local-preview`;
  - deploy an example application/chart;
  - assert the deployment is reachable/healthy;
  - reset/redeploy or prove reset state;
  - deprovision only in an explicit destroy validation path.
- Add a GitHub Actions workflow in deploy-cdc that runs the local preview validation and keeps AWS validation opt-in.

## Acceptance Criteria

- `tofu init`, `tofu apply -auto-approve`, `tofu output -json`, and guarded `tofu destroy -auto-approve` work from each selected template directory.
- `local-preview` returns `EnvironmentId`, `KUBECONFIG`, and `KUBECONTEXT`.
- `local-preview` can deploy the deploy-cdc chart/application into the branch environment and prove it is healthy.
- A second deploy/reuse pass does not recreate the cluster unnecessarily.
- Reset redeploys/clears app state while preserving the cluster.
- Destroy is only executed under explicit destroy intent.
- `aws-preview` is present but cannot run accidentally in ordinary CI.
- The deploy-cdc implementation remains an adapter to the Git environment repository contract; no `test_graph` SDK code imports deploy-cdc.

## Upstream Contract Evidence

The SDK contract was implemented in `haydenrear/test_graph_skill` PR #6:

- TG-5C: provider-neutral Git environment repository contract.
- TG-5D: generated temporary Git fixture without checked-in nested Git repositories.
- TG-5E: OpenTofu init/apply/output execution and downstream env projection.
- TG-5F: deploy, reset, and merge-gated destroy lifecycle.

Useful test graph commands from the upstream PR:

```bash
./scripts/run.py environmentRepositoryContract --test-graph-root test_graph
./scripts/run.py branchEnvironmentReset --test-graph-root test_graph
TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT=1 ./scripts/run.py branchEnvironmentMergeDestroy --test-graph-root test_graph
```
