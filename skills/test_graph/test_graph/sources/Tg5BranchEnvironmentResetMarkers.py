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
from branch_environment_harness import deployed_marker, provisioned_marker, read_json, reset_markers


PROVISION = "tg5.environment.repository.provision"
RESET = "tg5.environment.repository.reset"

SPEC = (
    NodeSpec("tg5.environment.repository.reset.markers")
    .kind("assertion")
    .depends_on(RESET)
    .tags("tg5", "environment", "reset")
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get(PROVISION, "EnvironmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    deployed = deployed_marker(ctx.report_dir, environment_id)
    resets = reset_markers(ctx.report_dir, environment_id, ctx.run_id)
    marker_json = read_json(provisioned)

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_available_from_context", bool(environment_id))
        .assertion("reset_kept_provisioned_marker", provisioned.is_file())
        .assertion("reset_cleared_deployed_marker", not deployed.exists())
        .assertion("reset_marker_exists", bool(resets))
        .assertion("marker_environment_id_matches_context", marker_json.get("environmentId") == environment_id)
    )
    if provisioned.is_file():
        result.artifact("provisioned-marker", str(provisioned))
    if resets:
        result.artifact("reset-marker", str(resets[-1]))
    return result


if __name__ == "__main__":
    main()
