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
from tg6_lifecycle_support import AWS, aws_guard_reason, aws_lifecycle_enabled, lifecycle_spec


ENABLED = aws_lifecycle_enabled()
SPEC = lifecycle_spec(
    AWS.provision_node,
    "testbed",
    AWS,
    action="provision",
    depends_on=("tg6.aws.lifecycle.guard",),
    outputs=("EnvironmentId", "KUBECONFIG", "KUBECONTEXT", "awsGuardReason"),
    enabled=ENABLED,
)


@node(SPEC)
def main(ctx):
    if not aws_lifecycle_enabled():
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_provision_guarded_without_cloud", bool(reason))
            .assertion("aws_environment_outputs_absent_when_guarded", not os.environ.get("EnvironmentId"))
            .publish("awsGuardReason", reason)
        )

    kubeconfig = Path(os.environ.get("KUBECONFIG", ""))
    environment_id = os.environ.get("EnvironmentId", "")
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("missing_environment_was_provisioned", os.environ.get("TEST_GRAPH_ENVIRONMENT_REUSED") == "false")
        .assertion("environment_id_available", bool(environment_id))
        .assertion("kubeconfig_created", kubeconfig.is_file())
        .assertion("target_is_aws_preview", os.environ.get("TEST_GRAPH_ENVIRONMENT_TARGET") == AWS.target)
        .assertion("backend_is_aws", os.environ.get("TEST_GRAPH_ENVIRONMENT_BACKEND") == AWS.backend)
        .publish("EnvironmentId", environment_id)
        .publish("KUBECONFIG", str(kubeconfig))
        .publish("KUBECONTEXT", os.environ.get("KUBECONTEXT", ""))
        .publish("awsGuardReason", "")
    )


if __name__ == "__main__":
    main()
