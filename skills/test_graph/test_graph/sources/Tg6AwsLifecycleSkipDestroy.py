# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import sys
from pathlib import Path

from testgraphsdk import NodeResult, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from branch_environment_harness import destroyed_marker, provisioned_marker
from tg6_lifecycle_support import AWS, aws_guard_reason, aws_lifecycle_enabled, lifecycle_spec


SPEC = lifecycle_spec(
    "tg6.aws.lifecycle.skip-destroy",
    "assertion",
    AWS,
    depends_on=("tg6.aws.lifecycle.reset-markers",),
    outputs=("destroyRequested", "awsGuardReason"),
    extra_tags=("destroy-guard",),
)


@node(SPEC)
def main(ctx):
    if not aws_lifecycle_enabled():
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_skip_destroy_guarded_without_cloud", bool(reason))
            .publish("destroyRequested", "false")
            .publish("awsGuardReason", reason)
        )

    environment_id = ctx.get(AWS.provision_node, "EnvironmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    destroyed = destroyed_marker(ctx.report_dir, environment_id)

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("skip_destroy_keeps_environment_active", provisioned.is_file())
        .assertion("skip_destroy_does_not_write_destroyed_marker", not destroyed.exists())
        .publish("destroyRequested", "false")
        .publish("awsGuardReason", "")
    )


if __name__ == "__main__":
    main()
