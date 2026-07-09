# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../../sdk/python", editable = true }
# ///
"""Publish the Kubernetes context for the skill-manager spec external phase.

Prefers a pinned context (``SKILL_MANAGER_SPEC_KUBECONTEXT`` /
``TEST_GRAPH_SM_KUBE_CONTEXT``), otherwise the branch environment published
by ``sm.environment.provisioned``. Never creates clusters itself.
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node

NODE_ID = "sm.cluster.ready"
REPO_ROOT = Path(__file__).resolve().parents[3]

SPEC = (
    NodeSpec(NODE_ID)
    .kind("testbed")
    .depends_on("sm.environment.provisioned")
    .tags("skill-manager", "kubernetes", "k3d")
    .timeout("10m")
    .side_effects("net:local")
    .output("kubeContext", "string")
    .output("kubeConfig", "string")
    .output("mode", "string")
)


def _reachable(context: str, kubeconfig: str | None = None) -> bool:
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


@node(SPEC)
def main(ctx):
    pinned = (
        os.environ.get("SKILL_MANAGER_SPEC_KUBECONTEXT", "")
        or os.environ.get("TEST_GRAPH_SM_KUBE_CONTEXT", "")
    )
    if pinned and _reachable(pinned):
        return (
            NodeResult.pass_(NODE_ID)
            .assertion("pinned_context_reachable", True)
            .publish("kubeContext", pinned)
            .publish("kubeConfig", os.environ.get("KUBECONFIG", ""))
            .publish("mode", "pinned-context")
            .log(f"using pinned Kubernetes context: {pinned}")
        )

    branch_context = ctx.get("sm.environment.provisioned", "KUBECONTEXT")
    branch_kubeconfig = ctx.get("sm.environment.provisioned", "KUBECONFIG")
    if branch_context and _reachable(branch_context, branch_kubeconfig):
        return (
            NodeResult.pass_(NODE_ID)
            .assertion("branch_environment_context_used", True)
            .publish("kubeContext", branch_context)
            .publish("kubeConfig", branch_kubeconfig or "")
            .publish("mode", "branch-environment")
            .log(f"using branch environment context: {branch_context}")
        )

    return (
        NodeResult.fail(
            NODE_ID,
            "no reachable Kubernetes context: pin SKILL_MANAGER_SPEC_KUBECONTEXT / "
            "TEST_GRAPH_SM_KUBE_CONTEXT or let sm.environment.provisioned provision "
            "the branch environment",
        )
        .assertion("cluster_reachable", False)
        .publish("kubeContext", pinned or branch_context or "")
        .publish("kubeConfig", branch_kubeconfig or "")
        .publish("mode", "unavailable")
    )


if __name__ == "__main__":
    main()
