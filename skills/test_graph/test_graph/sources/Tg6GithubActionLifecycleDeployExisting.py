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
from tg6_lifecycle_support import GITHUB_ACTION, application_ready_path, lifecycle_spec


SPEC = lifecycle_spec(
    GITHUB_ACTION.deploy_node,
    "action",
    GITHUB_ACTION,
    action="deploy",
    depends_on=(GITHUB_ACTION.provision_node,),
    outputs=("EnvironmentId", "ApplicationReadyPath"),
)


@node(SPEC)
def main(ctx):
    environment_id = os.environ.get("EnvironmentId", "")
    kubeconfig = Path(os.environ.get("KUBECONFIG", ""))
    application_ready = application_ready_path(kubeconfig, GITHUB_ACTION)
    if kubeconfig.is_file():
        application_ready.write_text(f"deployed into {environment_id}\n", encoding="utf-8")

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("deploy_targets_provisioned_environment", environment_id == ctx.get(GITHUB_ACTION.provision_node, "EnvironmentId"))
        .assertion("deploy_reuses_existing_environment", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "true")
        .assertion("deploy_receives_kubeconfig", kubeconfig.is_file())
        .assertion("application_ready_marker_created", application_ready.is_file())
        .publish("EnvironmentId", environment_id)
        .publish("ApplicationReadyPath", str(application_ready))
    )


if __name__ == "__main__":
    main()
