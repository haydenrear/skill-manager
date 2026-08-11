# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import os
import sys
from pathlib import Path

from testgraphsdk import NodeResult, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from tg6_lifecycle_support import GITHUB_ACTION, lifecycle_spec


SPEC = lifecycle_spec(
    GITHUB_ACTION.provision_node,
    "testbed",
    GITHUB_ACTION,
    action="provision",
    depends_on=(GITHUB_ACTION.scaffold_node,),
    outputs=("EnvironmentId", "KUBECONFIG", "KUBECONTEXT"),
)


@node(SPEC)
def main(ctx):
    kubeconfig = Path(os.environ.get("KUBECONFIG", ""))
    environment_id = os.environ.get("EnvironmentId", "")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("missing_environment_was_provisioned", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .assertion("environment_id_available", bool(environment_id))
        .assertion("kubeconfig_created", kubeconfig.is_file())
        .assertion("target_is_local_github_action", os.environ.get("TEST_GRAPH_ENVIRONMENT_TARGET") == GITHUB_ACTION.target)
        .assertion("backend_is_github_action", os.environ.get("TEST_GRAPH_ENVIRONMENT_BACKEND") == GITHUB_ACTION.backend)
        .publish("EnvironmentId", environment_id)
        .publish("KUBECONFIG", str(kubeconfig))
        .publish("KUBECONTEXT", os.environ.get("KUBECONTEXT", ""))
    )


if __name__ == "__main__":
    main()
