# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

from testgraphsdk import NodeResult, NodeSpec, delete_cluster, node


TARGETS = (
    ("local-preview", "local"),
    ("local-github-action", "github-action"),
    ("aws-preview", "aws"),
)

SPEC = (
    NodeSpec("tg6.lifecycle.python.delete-cluster")
    .kind("assertion")
    .tags("tg6", "environment", "lifecycle-template", "uv")
    .output("Runtime")
    .output("DeleteCases")
)


@node(SPEC)
def main(ctx):
    skipped = [delete_cluster(target, backend, destroy_requested=False) for target, backend in TARGETS]
    destroy = [delete_cluster(target, backend, destroy_requested=True) for target, backend in TARGETS]

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("all_targets_covered", len(skipped) == len(TARGETS) and len(destroy) == len(TARGETS))
        .assertion("destroy_skips_without_intent", all(not plan.should_run for plan in skipped))
        .assertion("skip_reason_records_missing_destroy_intent", all(plan.skip_reason == "destroy-not-requested" for plan in skipped))
        .assertion("skip_destroy_keeps_environment_active", all(plan.expected_state == "kept-active" for plan in skipped))
        .assertion("destroy_runs_with_intent", all(plan.should_run for plan in destroy))
        .assertion("destroy_uses_destroy_action", all(plan.environment_action == "destroy" for plan in destroy))
        .assertion("destroy_uses_destroy_command", all(plan.tofu_command == ("tofu", "destroy", "-auto-approve") for plan in destroy))
        .assertion("destroy_expected_state_recorded", all(plan.expected_state == "destroyed" for plan in destroy))
        .assertion("aws_destroy_requires_explicit_selection", delete_cluster("aws-preview", "aws", destroy_requested=True).requires_explicit_selection)
        .publish("Runtime", "uv")
        .publish("DeleteCases", str(len(skipped) + len(destroy)))
    )
    for key, value in delete_cluster("local-preview", "local", destroy_requested=False).published().items():
        result.publish(f"Delete{key}", value)
    return result


if __name__ == "__main__":
    main()
