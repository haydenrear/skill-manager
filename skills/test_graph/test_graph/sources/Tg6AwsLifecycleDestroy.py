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
from tg6_lifecycle_support import AWS, aws_guard_reason, aws_lifecycle_enabled, destroy_intent_absent_or_falsey, destroy_requested, lifecycle_spec


ENABLED = aws_lifecycle_enabled() and destroy_requested()
SPEC = lifecycle_spec(
    "tg6.aws.lifecycle.destroy",
    "action",
    AWS,
    action="destroy",
    depends_on=(AWS.reset_node,),
    outputs=("destroyRequested", "EnvironmentId", "awsGuardReason"),
    extra_tags=("destroy-guard",),
    enabled=ENABLED,
)


@node(SPEC)
def main(ctx):
    lifecycle_enabled = aws_lifecycle_enabled()
    requested = destroy_requested()
    environment_id = ctx.get(AWS.reset_node, "EnvironmentId") or os.environ.get("TEST_GRAPH_BRANCH_ENVIRONMENT_ID", "")

    if not lifecycle_enabled:
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_destroy_guarded_without_cloud", bool(reason))
            .assertion("destroy_runtime_not_enabled_without_aws_guard", not os.environ.get("TEST_GRAPH_BRANCH_ENVIRONMENT_ID"))
            .publish("destroyRequested", "false")
            .publish("EnvironmentId", environment_id)
            .publish("awsGuardReason", reason)
        )

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("destroy_requires_explicit_intent", requested or destroy_intent_absent_or_falsey())
        .assertion("environment_id_available", bool(environment_id))
        .publish("destroyRequested", str(requested).lower())
        .publish("EnvironmentId", environment_id)
        .publish("awsGuardReason", "")
    )


if __name__ == "__main__":
    main()
