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
PROVISION = "tg6.local.lifecycle.provision-missing"
DEPLOY = "tg6.local.lifecycle.deploy-existing"

SPEC = (
    NodeSpec("tg6.local.lifecycle.reset")
    .kind("action")
    .depends_on(DEPLOY)
    .tags("tg6", "environment", "local-lifecycle")
    .side_effects(SideEffect.environment("reset"))
    .environment_repository(REPOSITORY)
    .output("EnvironmentId")
)


@node(SPEC)
def main(ctx):
    environment_id = os.environ.get("EnvironmentId", "")
    application_ready = Path(ctx.get(DEPLOY, "ApplicationReadyPath") or "")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("reset_targets_provisioned_environment", environment_id == ctx.get(PROVISION, "EnvironmentId"))
        .assertion("reset_is_not_reuse", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .assertion("reset_clears_application_state", not application_ready.exists())
        .publish("EnvironmentId", environment_id)
    )


if __name__ == "__main__":
    main()
