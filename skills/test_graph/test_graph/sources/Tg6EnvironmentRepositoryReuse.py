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
    "build/tg6-environment-repository-local-preview",
    "templates/branch-preview",
).with_target("local-preview").with_backend("local")

SPEC = (
    NodeSpec("tg6.environment.repository.reuse")
    .kind("testbed")
    .depends_on("tg6.environment.repository.provision")
    .tags("tg6", "environment", "repository-execution", "local")
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
        .assertion("same_environment_as_first_provision", environment_id == ctx.get("tg6.environment.repository.provision", "EnvironmentId"))
        .publish("EnvironmentId", environment_id)
        .publish("KUBECONFIG", kubeconfig)
        .publish("KUBECONTEXT", os.environ.get("KUBECONTEXT", ""))
    )


if __name__ == "__main__":
    main()
