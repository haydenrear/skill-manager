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

PROVISION = "tg5.environment.repository.provision"
DEPLOY = "tg5.environment.repository.deploy"

SPEC = (
    NodeSpec("tg5.environment.repository.reset")
    .kind("action")
    .depends_on(DEPLOY)
    .tags("tg5", "environment", "reset")
    .side_effects(SideEffect.environment("reset"))
    .environment_repository(REPOSITORY)
    .output("EnvironmentId")
)


@node(SPEC)
def main(ctx):
    environment_id = os.environ.get("EnvironmentId", "")
    kubeconfig = Path(os.environ.get("KUBECONFIG", ""))
    old_application_ready = Path(ctx.get(DEPLOY, "ApplicationReadyPath") or "")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("reset_targets_provisioned_environment", environment_id == ctx.get(PROVISION, "EnvironmentId"))
        .assertion("reset_recreates_kubeconfig", kubeconfig.is_file())
        .assertion("reset_clears_application_state", not old_application_ready.exists())
        .assertion("reset_is_not_reported_as_reuse", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .publish("EnvironmentId", environment_id)
    )


if __name__ == "__main__":
    main()
