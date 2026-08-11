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
    NodeSpec("tg5.environment.repository.provision")
    .kind("testbed")
    .depends_on("tg5.environment.repository.fixture.stable")
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
    kubecontext = os.environ.get("KUBECONTEXT", "")
    environment_id = os.environ.get("EnvironmentId", "")
    repository_dir = os.environ.get("TEST_GRAPH_ENVIRONMENT_REPOSITORY_DIR", "")
    template_dir = os.environ.get("TEST_GRAPH_ENVIRONMENT_TEMPLATE_DIR", "")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_is_available", bool(environment_id))
        .assertion("kubeconfig_env_is_available", bool(kubeconfig) and Path(kubeconfig).is_file())
        .assertion("kubecontext_env_is_available", kubecontext.startswith("test-graph-"))
        .assertion("environment_was_not_reused_first_time", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .assertion("repository_clone_exists", bool(repository_dir) and Path(repository_dir).is_dir())
        .assertion("template_directory_exists", bool(template_dir) and Path(template_dir).is_dir())
        .publish("nodeSawEnvironmentId", environment_id)
        .publish("nodeSawKubeconfig", kubeconfig)
        .publish("nodeSawKubecontext", kubecontext)
    )


if __name__ == "__main__":
    main()
