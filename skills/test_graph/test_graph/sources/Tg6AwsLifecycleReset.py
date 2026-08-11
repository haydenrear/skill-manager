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
from tg6_lifecycle_support import AWS, aws_guard_reason, aws_lifecycle_enabled, lifecycle_spec


ENABLED = aws_lifecycle_enabled()
SPEC = lifecycle_spec(
    AWS.reset_node,
    "action",
    AWS,
    action="reset",
    depends_on=(AWS.deploy_node,),
    outputs=("EnvironmentId", "awsGuardReason"),
    enabled=ENABLED,
)


@node(SPEC)
def main(ctx):
    if not aws_lifecycle_enabled():
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_reset_guarded_without_cloud", bool(reason))
            .assertion("aws_reset_did_not_receive_environment_outputs", not os.environ.get("EnvironmentId"))
            .publish("awsGuardReason", reason)
        )

    environment_id = os.environ.get("EnvironmentId", "")
    application_ready = Path(ctx.get(AWS.deploy_node, "ApplicationReadyPath") or "")
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("reset_targets_provisioned_environment", environment_id == ctx.get(AWS.provision_node, "EnvironmentId"))
        .assertion("reset_is_not_reuse", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .assertion("reset_clears_application_state", not application_ready.exists())
        .publish("EnvironmentId", environment_id)
        .publish("awsGuardReason", "")
    )


if __name__ == "__main__":
    main()
