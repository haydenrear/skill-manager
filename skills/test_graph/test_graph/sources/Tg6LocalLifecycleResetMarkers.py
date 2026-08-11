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
    environment_repository_command_labels,
    provisioned_marker,
    reset_markers,
)


PROVISION = "tg6.local.lifecycle.provision-missing"
RESET = "tg6.local.lifecycle.reset"

SPEC = (
    NodeSpec("tg6.local.lifecycle.reset-markers")
    .kind("assertion")
    .depends_on(RESET)
    .tags("tg6", "environment", "local-lifecycle")
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get(PROVISION, "EnvironmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    deployed = deployed_marker(ctx.report_dir, environment_id)
    resets = reset_markers(ctx.report_dir, environment_id, ctx.run_id)
    reset_commands = environment_repository_command_labels(ctx.report_dir, RESET)

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("reset_kept_provisioned_marker", provisioned.is_file())
        .assertion("reset_cleared_deployed_marker", not deployed.exists())
        .assertion("reset_marker_exists", bool(resets))
        .assertion("reset_reapplied_environment", "tofu-apply" in reset_commands)
    )
    if resets:
        result.artifact("reset-marker", str(resets[-1]))
    return result


if __name__ == "__main__":
    main()
