# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Node used to validate failed-node rerun guidance."""
from __future__ import annotations

import os

from testgraphsdk import NodeResult, NodeSpec, node


SPEC = (
    NodeSpec("rerun.guidance.failure")
    .kind("assertion")
    .tags("rerun", "guidance")
    .timeout("30s")
    .rerun(True)
)


@node(SPEC)
def main(ctx):
    if os.environ.get("TESTGRAPH_FORCE_GUIDANCE_FAILURE") == "1":
        return NodeResult.fail(ctx.node_id, "forced failure for rerun guidance")
    return NodeResult.pass_(ctx.node_id).assertion("not_forced", True)


if __name__ == "__main__":
    main()
