# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../../sdk/python", editable = true }
# ///
"""Provision (or reuse) the branch-scoped skill-manager test environment.

Uses the environment repository contract: the deploy-cdc repository hosts
the skill-manager environment repository (template
environments/skill-manager/templates/branch-preview), and the test-graph
runtime clones it outside the working tree, runs OpenTofu in the selected
template, and injects EnvironmentId/KUBECONFIG/KUBECONTEXT.

Modes:
- ``SKILL_MANAGER_SPEC_KUBECONTEXT`` or ``TEST_GRAPH_SM_KUBE_CONTEXT`` set:
  passthrough — honor the pinned context and do not touch the environment
  repository.
- otherwise: declare ``environment:provision`` so the runtime creates or
  reuses the per-branch k3d cluster. Destroy never happens here; it is
  gated behind ``TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true`` in a
  dedicated lifecycle graph.
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

from testgraphsdk import EnvironmentRepository, NodeResult, NodeSpec, SideEffect, node

NODE_ID = "sm.environment.provisioned"
REPO_ROOT = Path(__file__).resolve().parents[3]

PINNED_CONTEXT = (
    os.environ.get("SKILL_MANAGER_SPEC_KUBECONTEXT", "")
    or os.environ.get("TEST_GRAPH_SM_KUBE_CONTEXT", "")
)
ENV_SOURCE = os.environ.get(
    "TEST_GRAPH_SM_ENV_SOURCE", "https://github.com/haydenrear/deploy-cdc"
)
ENV_TEMPLATE = os.environ.get(
    "TEST_GRAPH_SM_ENV_TEMPLATE",
    "environments/skill-manager/templates/branch-preview",
)

SPEC = (
    NodeSpec(NODE_ID)
    .kind("testbed")
    .tags("skill-manager", "environment", "branch-preview")
    .timeout("15m")
    .output("EnvironmentId")
    .output("KUBECONFIG")
    .output("KUBECONTEXT")
    .output("mode")
)

if PINNED_CONTEXT:
    SPEC = SPEC.side_effects("net:local")
else:
    SPEC = (
        SPEC
        .side_effects(SideEffect.environment("provision"))
        .environment_repository(EnvironmentRepository.of(ENV_SOURCE, ENV_TEMPLATE))
    )


@node(SPEC)
def main(ctx):
    if PINNED_CONTEXT:
        reachable = _context_reachable(PINNED_CONTEXT)
        return (
            (NodeResult.pass_(NODE_ID) if reachable else NodeResult.fail(
                NODE_ID, f"pinned kube context {PINNED_CONTEXT} is not reachable"))
            .assertion("pinned_context_reachable", reachable)
            .publish("EnvironmentId", "")
            .publish("KUBECONFIG", os.environ.get("KUBECONFIG", ""))
            .publish("KUBECONTEXT", PINNED_CONTEXT)
            .publish("mode", "pinned-context")
            .log(f"honoring pinned kube context {PINNED_CONTEXT}; environment repository not used")
        )

    environment_id = os.environ.get("EnvironmentId", "")
    kubeconfig = os.environ.get("KUBECONFIG", "")
    kubecontext = os.environ.get("KUBECONTEXT", "")
    kubeconfig_ok = bool(kubeconfig) and Path(kubeconfig).is_file()
    reused = os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED", "")
    reachable = _context_reachable(kubecontext, kubeconfig) if kubecontext else False

    result = NodeResult.pass_(NODE_ID) if (environment_id and kubeconfig_ok and reachable) else NodeResult.fail(
        NODE_ID,
        "environment repository provisioning did not yield a reachable cluster "
        f"(EnvironmentId={environment_id!r}, KUBECONFIG={kubeconfig!r}, KUBECONTEXT={kubecontext!r})",
    )
    return (
        result
        .assertion("environment_id_present", bool(environment_id))
        .assertion("kubeconfig_written", kubeconfig_ok)
        .assertion("branch_cluster_reachable", reachable)
        .metric("environmentReused", 1 if reused == "true" else 0)
        .publish("EnvironmentId", environment_id)
        .publish("KUBECONFIG", kubeconfig)
        .publish("KUBECONTEXT", kubecontext)
        .publish("mode", "branch-environment")
        .log(f"branch environment {environment_id} reused={reused or 'unknown'}")
    )


def _context_reachable(context: str, kubeconfig: str | None = None) -> bool:
    env = os.environ.copy()
    if kubeconfig:
        env["KUBECONFIG"] = f"{kubeconfig}{os.pathsep}{env.get('KUBECONFIG', '')}".rstrip(os.pathsep)
    probe = subprocess.run(
        ["kubectl", "--context", context, "cluster-info", "--request-timeout=10s"],
        capture_output=True,
        text=True,
        timeout=60,
        env=env,
    )
    return probe.returncode == 0


if __name__ == "__main__":
    main()
