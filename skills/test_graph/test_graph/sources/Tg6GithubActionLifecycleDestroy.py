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
from tg6_lifecycle_support import GITHUB_ACTION, destroy_intent_absent_or_falsey, destroy_requested, lifecycle_spec


SPEC = lifecycle_spec(
    "tg6.github-action.lifecycle.destroy",
    "action",
    GITHUB_ACTION,
    action="destroy",
    depends_on=(GITHUB_ACTION.reset_node,),
    outputs=("destroyRequested", "EnvironmentId"),
    extra_tags=("destroy-guard",),
    enabled=destroy_requested(),
)


@node(SPEC)
def main(ctx):
    requested = destroy_requested()
    environment_id = ctx.get(GITHUB_ACTION.reset_node, "EnvironmentId") or os.environ.get("TEST_GRAPH_BRANCH_ENVIRONMENT_ID", "")
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("destroy_requires_explicit_intent", requested or destroy_intent_absent_or_falsey())
        .assertion("environment_id_available", bool(environment_id))
        .publish("destroyRequested", str(requested).lower())
        .publish("EnvironmentId", environment_id)
    )


if __name__ == "__main__":
    main()
