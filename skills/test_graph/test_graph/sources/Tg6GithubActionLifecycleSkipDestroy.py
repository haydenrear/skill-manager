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

from testgraphsdk import NodeResult, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from branch_environment_harness import destroyed_marker, provisioned_marker
from tg6_lifecycle_support import GITHUB_ACTION, lifecycle_spec


SPEC = lifecycle_spec(
    "tg6.github-action.lifecycle.skip-destroy",
    "assertion",
    GITHUB_ACTION,
    depends_on=("tg6.github-action.lifecycle.reset-markers",),
    outputs=("destroyRequested",),
    extra_tags=("destroy-guard",),
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get(GITHUB_ACTION.provision_node, "EnvironmentId") or ""
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
