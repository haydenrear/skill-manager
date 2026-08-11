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

UPSTREAM = "tg5.environment.repository.provision"

SPEC = (
    NodeSpec("tg5.environment.repository.deploy")
    .kind("action")
    .depends_on(UPSTREAM)
    .tags("tg5", "environment", "deploy")
    .side_effects(SideEffect.environment("deploy"))
    .environment_repository(REPOSITORY)
    .output("EnvironmentId")
    .output("ApplicationReadyPath")
)


@node(SPEC)
def main(ctx):
    environment_id = os.environ.get("EnvironmentId", "")
    kubeconfig_raw = os.environ.get("KUBECONFIG", "")
    kubeconfig = Path(kubeconfig_raw) if kubeconfig_raw else Path(ctx.report_dir) / "missing-kubeconfig"
    application_ready = kubeconfig.with_name("example-application.ready")
    if kubeconfig.is_file():
        application_ready.write_text(f"deployed into {environment_id}\n", encoding="utf-8")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("deploy_targets_provisioned_environment", environment_id == ctx.get(UPSTREAM, "EnvironmentId"))
        .assertion("deploy_receives_kubeconfig", kubeconfig.is_file())
        .assertion("deploy_reuses_environment", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "true")
        .assertion("application_ready_marker_created", application_ready.is_file())
        .publish("EnvironmentId", environment_id)
        .publish("ApplicationReadyPath", str(application_ready))
    )


if __name__ == "__main__":
    main()
