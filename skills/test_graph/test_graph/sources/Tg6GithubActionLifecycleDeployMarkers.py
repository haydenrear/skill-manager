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
from tg6_lifecycle_support import GITHUB_ACTION, lifecycle_spec


SPEC = lifecycle_spec(
    "tg6.github-action.lifecycle.deploy-markers",
    "assertion",
    GITHUB_ACTION,
    depends_on=(GITHUB_ACTION.deploy_node,),
)


@node(SPEC)
def main(ctx):
    environment_id = ctx.get(GITHUB_ACTION.provision_node, "EnvironmentId") or ""
    provisioned = provisioned_marker(ctx.report_dir, environment_id)
    deployed = deployed_marker(ctx.report_dir, environment_id)
    provision_commands = environment_repository_command_labels(ctx.report_dir, GITHUB_ACTION.provision_node)
    deploy_commands = environment_repository_command_labels(ctx.report_dir, GITHUB_ACTION.deploy_node)
    deploy_execution = environment_repository_execution(ctx.report_dir, GITHUB_ACTION.deploy_node)

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
