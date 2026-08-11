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
    NodeSpec("tg6.local.lifecycle.provision-missing")
    .kind("testbed")
    .depends_on("tg6.environment.repository.scaffold.local")
    .tags("tg6", "environment", "local-lifecycle")
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
        .assertion("missing_environment_was_provisioned", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .assertion("environment_id_available", bool(environment_id))
        .assertion("kubeconfig_created", bool(kubeconfig) and Path(kubeconfig).is_file())
        .assertion("target_is_local_preview", os.environ.get("TEST_GRAPH_ENVIRONMENT_TARGET") == "local-preview")
        .assertion("backend_is_local", os.environ.get("TEST_GRAPH_ENVIRONMENT_BACKEND") == "local")
        .publish("EnvironmentId", environment_id)
        .publish("KUBECONFIG", kubeconfig)
        .publish("KUBECONTEXT", os.environ.get("KUBECONTEXT", ""))
    )


if __name__ == "__main__":
    main()
