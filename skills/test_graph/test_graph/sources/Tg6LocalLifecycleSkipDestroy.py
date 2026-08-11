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
from branch_environment_harness import destroyed_marker, provisioned_marker


PROVISION = "tg6.local.lifecycle.provision-missing"
RESET_MARKERS = "tg6.local.lifecycle.reset-markers"

SPEC = (
    NodeSpec("tg6.local.lifecycle.skip-destroy")
    .kind("assertion")
    .depends_on(RESET_MARKERS)
    .tags("tg6", "environment", "local-lifecycle", "destroy-guard")
    .output("destroyRequested")
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get(PROVISION, "EnvironmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    destroyed = destroyed_marker(ctx.report_dir, environment_id)

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("skip_destroy_keeps_environment_active", provisioned.is_file())
        .assertion("skip_destroy_does_not_write_destroyed_marker", not destroyed.exists())
        .publish("destroyRequested", "false")
    )


if __name__ == "__main__":
    main()
