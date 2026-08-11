# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

from testgraphsdk import NodeResult, NodeSpec, node, reset_node


TARGETS = (
    ("local-preview", "local"),
    ("local-github-action", "github-action"),
    ("aws-preview", "aws"),
)

SPEC = (
    NodeSpec("tg6.lifecycle.python.reset-node")
    .kind("assertion")
    .tags("tg6", "environment", "lifecycle-template", "uv")
    .output("Runtime")
    .output("ResetCases")
)


@node(SPEC)
def main(ctx):
    run_plans = [reset_node(target, backend) for target, backend in TARGETS]
    just_provisioned = [reset_node(target, backend, just_provisioned=True) for target, backend in TARGETS]
    already_reset = [reset_node(target, backend, already_reset=True) for target, backend in TARGETS]

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("all_targets_covered", len(run_plans) == len(TARGETS))
        .assertion("normal_reset_runs", all(plan.should_run for plan in run_plans))
        .assertion("normal_reset_uses_reset_action", all(plan.environment_action == "reset" for plan in run_plans))
        .assertion("normal_reset_uses_apply_command", all(plan.tofu_command == ("tofu", "apply", "-auto-approve") for plan in run_plans))
        .assertion("just_provisioned_reset_skips", all(not plan.should_run for plan in just_provisioned))
        .assertion("just_provisioned_reason_recorded", all(plan.skip_reason == "just-provisioned" for plan in just_provisioned))
        .assertion("already_reset_skips", all(not plan.should_run for plan in already_reset))
        .assertion("already_reset_reason_recorded", all(plan.skip_reason == "already-reset" for plan in already_reset))
        .assertion("skip_keeps_environment_active", all(plan.expected_state == "kept-active" for plan in just_provisioned + already_reset))
        .publish("Runtime", "uv")
        .publish("ResetCases", str(len(run_plans) + len(just_provisioned) + len(already_reset)))
    )
    for key, value in reset_node("local-preview", "local", just_provisioned=True).published().items():
        result.publish(f"Reset{key}", value)
    return result


if __name__ == "__main__":
    main()
