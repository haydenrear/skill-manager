# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Probe node whose describe metadata opts out of direct rerun guidance."""
from __future__ import annotations

import os

from testgraphsdk import NodeResult, NodeSpec, node


SPEC = (
    NodeSpec("rerun.disabled.probe")
    .kind("evidence")
    .tags("metadata")
    .timeout("30s")
    .rerun(False)
    .output("rerun", "boolean")
)


@node(SPEC)
def main(ctx):
    if os.environ.get("TESTGRAPH_FORCE_RERUN_DISABLED_FAILURE") == "1":
        return NodeResult.fail(ctx.node_id, "forced rerun-disabled failure")
    return NodeResult.pass_(ctx.node_id).publish("rerun", "false")


if __name__ == "__main__":
    main()
