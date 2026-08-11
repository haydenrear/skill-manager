# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import os

from testgraphsdk import NodeResult, NodeSpec, SideEffect, node


UPSTREAM = "tg5.environment.repository.provision"

SPEC = (
    NodeSpec("tg5.environment.context.env-all")
    .kind("assertion")
    .depends_on(UPSTREAM)
    .tags("tg5", "environment", "context-propagation")
    .side_effects(SideEffect.env_all())
)


@node(SPEC)
def main(ctx):
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_projected", os.environ.get("EnvironmentId") == ctx.get(UPSTREAM, "EnvironmentId"))
        .assertion("kubeconfig_projected", os.environ.get("KUBECONFIG") == ctx.get(UPSTREAM, "KUBECONFIG"))
        .assertion("kubecontext_projected", os.environ.get("KUBECONTEXT") == ctx.get(UPSTREAM, "KUBECONTEXT"))
        .assertion("all_mode_projects_extra_eligible_key", os.environ.get("EnvironmentRepositoryReused") == "false")
    )


if __name__ == "__main__":
    main()
