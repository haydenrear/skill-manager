# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

from testgraphsdk import NodeResult, NodeSpec, deploy_cluster, node


TARGETS = (
    ("local-preview", "local", "local", False),
    ("local-github-action", "github-action", "github-action", False),
    ("aws-preview", "aws", "aws", True),
)

SPEC = (
    NodeSpec("tg6.lifecycle.python.deploy-cluster")
    .kind("assertion")
    .tags("tg6", "environment", "lifecycle-template", "uv")
    .output("Runtime")
    .output("DeployCases")
)


@node(SPEC)
def main(ctx):
    plans = [
        deploy_cluster(target, backend, environment_exists=environment_exists)
        for target, backend, _dispatch, _requires_explicit in TARGETS
        for environment_exists in (False, True)
    ]

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("all_targets_covered", len(plans) == len(TARGETS) * 2)
        .assertion("deploy_uses_provision_action", all(plan.environment_action == "provision" for plan in plans))
        .assertion("deploy_uses_apply_command", all(plan.tofu_command == ("tofu", "apply", "-auto-approve") for plan in plans))
        .assertion("missing_environment_is_provisioned", all(
            deploy_cluster(target, backend, environment_exists=False).expected_state == "provision-missing"
            for target, backend, _dispatch, _requires_explicit in TARGETS
        ))
        .assertion("existing_environment_is_reused", all(
            deploy_cluster(target, backend, environment_exists=True).expected_state == "reuse-existing"
            for target, backend, _dispatch, _requires_explicit in TARGETS
        ))
        .assertion("dispatch_metadata_matches_target_matrix", all(
            deploy_cluster(target, backend).dispatch_key == dispatch
            for target, backend, dispatch, _requires_explicit in TARGETS
        ))
        .assertion("aws_requires_explicit_selection", deploy_cluster("aws-preview", "aws").requires_explicit_selection)
        .publish("Runtime", "uv")
        .publish("DeployCases", str(len(plans)))
    )
    for key, value in deploy_cluster("local-preview", "local").published().items():
        result.publish(f"Deploy{key}", value)
    return result


if __name__ == "__main__":
    main()
