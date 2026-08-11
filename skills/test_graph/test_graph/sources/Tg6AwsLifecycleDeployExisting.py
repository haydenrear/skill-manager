# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import os
import sys
from pathlib import Path

from testgraphsdk import NodeResult, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from tg6_lifecycle_support import AWS, application_ready_path, aws_guard_reason, aws_lifecycle_enabled, lifecycle_spec


ENABLED = aws_lifecycle_enabled()
SPEC = lifecycle_spec(
    AWS.deploy_node,
    "action",
    AWS,
    action="deploy",
    depends_on=(AWS.provision_node,),
    outputs=("EnvironmentId", "ApplicationReadyPath", "awsGuardReason"),
    enabled=ENABLED,
)


@node(SPEC)
def main(ctx):
    if not aws_lifecycle_enabled():
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_deploy_guarded_without_cloud", bool(reason))
            .assertion("aws_deploy_did_not_receive_environment_outputs", not os.environ.get("EnvironmentId"))
            .publish("awsGuardReason", reason)
        )

    environment_id = os.environ.get("EnvironmentId", "")
    kubeconfig = Path(os.environ.get("KUBECONFIG", ""))
    application_ready = application_ready_path(kubeconfig, AWS)
    if kubeconfig.is_file():
        application_ready.write_text(f"deployed into {environment_id}\n", encoding="utf-8")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("deploy_targets_provisioned_environment", environment_id == ctx.get(AWS.provision_node, "EnvironmentId"))
        .assertion("deploy_reuses_existing_environment", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "true")
        .assertion("deploy_receives_kubeconfig", kubeconfig.is_file())
        .assertion("application_ready_marker_created", application_ready.is_file())
        .publish("EnvironmentId", environment_id)
        .publish("ApplicationReadyPath", str(application_ready))
        .publish("awsGuardReason", "")
    )


if __name__ == "__main__":
    main()
