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
from branch_environment_harness import (
    deployed_marker,
    environment_repository_command_labels,
    provisioned_marker,
    reset_markers,
)
from tg6_lifecycle_support import AWS, aws_guard_reason, aws_lifecycle_enabled, lifecycle_spec


SPEC = lifecycle_spec(
    "tg6.aws.lifecycle.reset-markers",
    "assertion",
    AWS,
    depends_on=(AWS.reset_node,),
)


@node(SPEC)
def main(ctx):
    reset_commands = environment_repository_command_labels(ctx.report_dir, AWS.reset_node)
    if not aws_lifecycle_enabled():
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_reset_marker_check_guarded_without_cloud", bool(reason))
            .assertion("aws_reset_runtime_not_invoked_when_guarded", not reset_commands)
        )

    environment_id = ctx.get(AWS.provision_node, "EnvironmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    deployed = deployed_marker(ctx.report_dir, environment_id)
    resets = reset_markers(ctx.report_dir, environment_id, ctx.run_id)

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("reset_kept_provisioned_marker", provisioned.is_file())
        .assertion("reset_cleared_deployed_marker", not deployed.exists())
        .assertion("reset_marker_exists", bool(resets))
        .assertion("reset_reapplied_environment", "tofu-apply" in reset_commands)
    )
    if resets:
        result.artifact("reset-marker", str(resets[-1]))
    return result


if __name__ == "__main__":
    main()
