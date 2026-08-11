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
from branch_environment_harness import provisioned_marker, read_json, reset_markers


SPEC = (
    NodeSpec("tg5.environment.markers.present")
    .kind("assertion")
    .depends_on("tg5.environment.reset")
    .tags("tg5", "environment", "markers")
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get("tg5.environment.provision", "environmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    resets = reset_markers(ctx.report_dir, environment_id, ctx.run_id)
    marker_json = read_json(provisioned)

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_available_from_context", bool(environment_id))
        .assertion("provisioned_marker_exists_after_provision", provisioned.is_file())
        .assertion("reset_marker_exists_after_reset", bool(resets))
        .assertion("reset_kept_provisioned_marker", provisioned.is_file())
        .assertion("marker_environment_id_matches_context", marker_json.get("environmentId") == environment_id)
        .assertion("provisioned_marker_is_from_this_run", marker_json.get("runId") == ctx.run_id)
    )
    if provisioned.is_file():
        result.artifact("provisioned-marker", str(provisioned))
    if resets:
        result.artifact("reset-marker", str(resets[-1]))
    return result


if __name__ == "__main__":
    main()
