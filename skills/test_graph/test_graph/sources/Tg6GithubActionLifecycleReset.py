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
from tg6_lifecycle_support import GITHUB_ACTION, lifecycle_spec


SPEC = lifecycle_spec(
    GITHUB_ACTION.reset_node,
    "action",
    GITHUB_ACTION,
    action="reset",
    depends_on=(GITHUB_ACTION.deploy_node,),
    outputs=("EnvironmentId",),
)


@node(SPEC)
def main(ctx):
    environment_id = os.environ.get("EnvironmentId", "")
    application_ready = Path(ctx.get(GITHUB_ACTION.deploy_node, "ApplicationReadyPath") or "")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("reset_targets_provisioned_environment", environment_id == ctx.get(GITHUB_ACTION.provision_node, "EnvironmentId"))
        .assertion("reset_is_not_reuse", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .assertion("reset_clears_application_state", not application_ready.exists())
        .publish("EnvironmentId", environment_id)
    )


if __name__ == "__main__":
    main()
