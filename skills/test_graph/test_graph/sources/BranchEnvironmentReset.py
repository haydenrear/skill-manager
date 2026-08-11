# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import os

from testgraphsdk import NodeResult, NodeSpec, SideEffect, node


SPEC = (
    NodeSpec("tg5.environment.reset")
    .kind("action")
    .depends_on("tg5.environment.provision")
    .tags("tg5", "environment", "reset")
    .side_effects(SideEffect.environment("reset"))
)


@node(SPEC)
def main(ctx):
    upstream_environment_id = ctx.get("tg5.environment.provision", "environmentId")
    current_environment_id = os.environ.get("TEST_GRAPH_BRANCH_ENVIRONMENT_ID", "")
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_was_assigned", bool(current_environment_id))
        .assertion("reset_targets_same_environment", upstream_environment_id == current_environment_id)
    )


if __name__ == "__main__":
    main()
