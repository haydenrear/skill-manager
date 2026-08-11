# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import os
from pathlib import Path

from testgraphsdk import EnvironmentRepository, NodeResult, NodeSpec, SideEffect, node


REPOSITORY = EnvironmentRepository.of(
    "build/tg5-environment-repository-source",
    "templates/local-preview",
).with_output_keys("EnvironmentId", "KUBECONFIG", "KUBECONTEXT")

SPEC = (
    NodeSpec("tg5.environment.repository.reuse")
    .kind("testbed")
    .depends_on("tg5.environment.repository.provision")
    .tags("tg5", "environment", "repository-execution")
    .side_effects(SideEffect.environment("provision"))
    .environment_repository(REPOSITORY)
    .output("EnvironmentId")
    .output("KUBECONFIG")
    .output("KUBECONTEXT")
)


@node(SPEC)
def main(ctx):
    kubeconfig = os.environ.get("KUBECONFIG", "")
    environment_id = os.environ.get("EnvironmentId", "")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_is_available", bool(environment_id))
        .assertion("kubeconfig_env_is_available", bool(kubeconfig) and Path(kubeconfig).is_file())
        .assertion("environment_was_reused", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "true")
        .assertion("same_environment_as_first_provision", environment_id == ctx.get("tg5.environment.repository.provision", "EnvironmentId"))
        .publish("nodeSawEnvironmentId", environment_id)
        .publish("nodeSawKubeconfig", kubeconfig)
    )


if __name__ == "__main__":
    main()
