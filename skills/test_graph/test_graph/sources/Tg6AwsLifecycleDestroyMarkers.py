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
    destroy_request_markers,
    destroyed_marker,
    environment_repository_command_labels,
    provisioned_marker,
)
from tg6_lifecycle_support import AWS, aws_guard_reason, aws_lifecycle_enabled, lifecycle_spec


DESTROY = "tg6.aws.lifecycle.destroy"
SPEC = lifecycle_spec(
    "tg6.aws.lifecycle.destroy-markers",
    "assertion",
    AWS,
    depends_on=(DESTROY,),
    extra_tags=("destroy-guard",),
)


@node(SPEC)
def main(ctx):
    destroy_commands = environment_repository_command_labels(ctx.report_dir, DESTROY)
    if not aws_lifecycle_enabled():
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_destroy_marker_check_guarded_without_cloud", bool(reason))
            .assertion("aws_destroy_runtime_not_invoked_when_guarded", not destroy_commands)
        )

    environment_id = ctx.get(AWS.provision_node, "EnvironmentId") or ctx.get(DESTROY, "EnvironmentId") or ""
    requested = ctx.get(DESTROY, "destroyRequested") == "true"
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    destroyed = destroyed_marker(ctx.report_dir, environment_id)
    requests = destroy_request_markers(ctx.report_dir, environment_id, ctx.run_id)

    if requested:
        result = (
            NodeResult.pass_(ctx.node_id)
            .assertion("destroy_request_marker_exists", bool(requests))
            .assertion("destroyed_marker_exists", destroyed.is_file())
            .assertion("destroy_removed_provisioned_marker", not provisioned.exists())
            .assertion("destroy_ran_tofu_destroy", "tofu-destroy" in destroy_commands)
        )
        if requests:
            result.artifact("destroy-request-marker", str(requests[-1]))
        if destroyed.is_file():
            result.artifact("destroyed-marker", str(destroyed))
        return result

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("destroy_not_requested_by_default", not requested)
        .assertion("provisioned_marker_kept_without_destroy_intent", provisioned.is_file())
        .assertion("destroyed_marker_absent_without_destroy_intent", not destroyed.exists())
        .assertion("destroy_runtime_not_invoked_without_intent", not destroy_commands)
    )


if __name__ == "__main__":
    main()
