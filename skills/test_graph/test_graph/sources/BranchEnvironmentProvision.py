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
    NodeSpec("tg5.environment.provision")
    .kind("action")
    .tags("tg5", "environment", "provisioning")
    .side_effects(SideEffect.environment("provision"))
    .output("environmentId", "string")
    .output("stateDir", "string")
)


@node(SPEC)
def main(ctx):
    environment_id = os.environ.get("TEST_GRAPH_BRANCH_ENVIRONMENT_ID", "")
    state_dir = os.environ.get("TEST_GRAPH_PROVISIONING_STATE_DIR", "")
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_was_assigned", bool(environment_id))
        .assertion("state_dir_was_assigned", bool(state_dir))
        .publish("environmentId", environment_id)
        .publish("stateDir", state_dir)
    )


if __name__ == "__main__":
    main()
