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

SPEC = (
    NodeSpec("tg6.local.lifecycle.deploy-existing")
    .kind("action")
    .depends_on(PROVISION)
    .tags("tg6", "environment", "local-lifecycle")
    .side_effects(SideEffect.environment("deploy"))
    .environment_repository(REPOSITORY)
    .output("EnvironmentId")
    .output("ApplicationReadyPath")
)


@node(SPEC)
def main(ctx):
    environment_id = os.environ.get("EnvironmentId", "")
    kubeconfig = Path(os.environ.get("KUBECONFIG", ""))
    application_ready = kubeconfig.with_name("tg6-local-lifecycle-application.ready")
    if kubeconfig.is_file():
        application_ready.write_text(f"deployed into {environment_id}\n", encoding="utf-8")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("deploy_targets_provisioned_environment", environment_id == ctx.get(PROVISION, "EnvironmentId"))
        .assertion("deploy_reuses_existing_environment", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "true")
        .assertion("deploy_receives_kubeconfig", kubeconfig.is_file())
        .assertion("application_ready_marker_created", application_ready.is_file())
        .publish("EnvironmentId", environment_id)
        .publish("ApplicationReadyPath", str(application_ready))
    )


if __name__ == "__main__":
    main()
