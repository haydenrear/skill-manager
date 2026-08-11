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
from branch_environment_harness import ticket_plan_text


SPEC = (
    NodeSpec("tg5.future.workflow.plan")
    .kind("evidence")
    .depends_on("tg5.environment.repository.contract")
    .tags("tg5", "environment", "planning")
)


@node(SPEC)
def main(ctx):
    plan = ticket_plan_text()
    result = NodeResult.pass_(ctx.node_id)
    for ticket in ("TG-5C", "TG-5D", "TG-5E", "TG-5F", "TG-5G"):
        result.assertion(f"{ticket}_is_planned", ticket in plan)
    return result.log("Future TG-5 nodes are represented by the ticket plan and will be strengthened as each slice lands.")


if __name__ == "__main__":
    main()
