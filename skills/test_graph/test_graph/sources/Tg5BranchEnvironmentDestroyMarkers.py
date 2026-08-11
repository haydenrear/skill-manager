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

from testgraphsdk import NodeResult, NodeSpec, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from branch_environment_harness import (
    deployed_marker,
    destroy_request_markers,
    destroyed_marker,
    provisioned_marker,
)


PROVISION = "tg5.environment.repository.provision"
DESTROY = "tg5.environment.repository.destroy"

SPEC = (
    NodeSpec("tg5.environment.repository.destroy.markers")
    .kind("assertion")
    .depends_on(DESTROY)
    .tags("tg5", "environment", "destroy")
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get(PROVISION, "EnvironmentId") or ""
    requested = ctx.get(DESTROY, "destroyRequested") == "true"
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    deployed = deployed_marker(ctx.report_dir, environment_id)
    destroyed = destroyed_marker(ctx.report_dir, environment_id)
    requests = destroy_request_markers(ctx.report_dir, environment_id, ctx.run_id)

    if requested:
        result = (
            NodeResult.pass_(ctx.node_id)
            .assertion("destroy_request_marker_exists", bool(requests))
            .assertion("destroyed_marker_exists", destroyed.is_file())
            .assertion("destroy_removed_provisioned_marker", not provisioned.exists())
            .assertion("destroy_removed_deployed_marker", not deployed.exists())
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
        .assertion("deployed_marker_kept_without_destroy_intent", deployed.is_file())
        .assertion("destroyed_marker_absent_without_destroy_intent", not destroyed.exists())
    )


if __name__ == "__main__":
    main()
