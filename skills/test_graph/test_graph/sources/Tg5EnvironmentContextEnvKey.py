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
    NodeSpec("tg5.environment.context.env-key")
    .kind("assertion")
    .depends_on(UPSTREAM)
    .tags("tg5", "environment", "context-propagation")
    .side_effects(
        SideEffect.env("EnvironmentId"),
        SideEffect.env("KUBECONFIG"),
        SideEffect.env("KUBECONTEXT"),
    )
)


@node(SPEC)
def main(ctx):
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_id_projected", os.environ.get("EnvironmentId") == ctx.get(UPSTREAM, "EnvironmentId"))
        .assertion("kubeconfig_projected", os.environ.get("KUBECONFIG") == ctx.get(UPSTREAM, "KUBECONFIG"))
        .assertion("kubecontext_projected", os.environ.get("KUBECONTEXT") == ctx.get(UPSTREAM, "KUBECONTEXT"))
        .assertion("unrequested_key_not_projected", "EnvironmentRepositoryReused" not in os.environ)
    )


if __name__ == "__main__":
    main()
