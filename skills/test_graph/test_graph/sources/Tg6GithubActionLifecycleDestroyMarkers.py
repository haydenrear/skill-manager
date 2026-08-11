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
from tg6_lifecycle_support import GITHUB_ACTION, lifecycle_spec


SPEC = lifecycle_spec(
    "tg6.github-action.lifecycle.destroy-markers",
    "assertion",
    GITHUB_ACTION,
    depends_on=("tg6.github-action.lifecycle.destroy",),
    extra_tags=("destroy-guard",),
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get(GITHUB_ACTION.provision_node, "EnvironmentId") or ctx.get("tg6.github-action.lifecycle.destroy", "EnvironmentId") or ""
    requested = ctx.get("tg6.github-action.lifecycle.destroy", "destroyRequested") == "true"
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    destroyed = destroyed_marker(ctx.report_dir, environment_id)
    requests = destroy_request_markers(ctx.report_dir, environment_id, ctx.run_id)
    destroy_commands = environment_repository_command_labels(ctx.report_dir, "tg6.github-action.lifecycle.destroy")

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
