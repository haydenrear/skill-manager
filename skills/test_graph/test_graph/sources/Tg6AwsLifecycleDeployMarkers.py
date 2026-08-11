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
from branch_environment_harness import (
    deployed_marker,
    environment_repository_command_labels,
    environment_repository_execution,
    provisioned_marker,
)
from tg6_lifecycle_support import AWS, aws_guard_reason, aws_lifecycle_enabled, lifecycle_spec


SPEC = lifecycle_spec(
    "tg6.aws.lifecycle.deploy-markers",
    "assertion",
    AWS,
    depends_on=(AWS.deploy_node,),
)


@node(SPEC)
def main(ctx):
    provision_commands = environment_repository_command_labels(ctx.report_dir, AWS.provision_node)
    deploy_commands = environment_repository_command_labels(ctx.report_dir, AWS.deploy_node)
    if not aws_lifecycle_enabled():
        reason = aws_guard_reason()
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("aws_marker_check_guarded_without_cloud", bool(reason))
            .assertion("aws_provision_runtime_not_invoked_when_guarded", not provision_commands)
            .assertion("aws_deploy_runtime_not_invoked_when_guarded", not deploy_commands)
        )

    environment_id = ctx.get(AWS.provision_node, "EnvironmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    deployed = deployed_marker(ctx.report_dir, environment_id)
    deploy_execution = environment_repository_execution(ctx.report_dir, AWS.deploy_node)

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("provisioned_marker_exists", provisioned.is_file())
        .assertion("deployed_marker_exists", deployed.is_file())
        .assertion("first_provision_applied_missing_cluster", "tofu-apply" in provision_commands)
        .assertion("deploy_existing_skipped_recreate_apply", "tofu-apply" not in deploy_commands)
        .assertion("deploy_execution_reported_reuse", deploy_execution.get("reused") is True)
    )


if __name__ == "__main__":
    main()
