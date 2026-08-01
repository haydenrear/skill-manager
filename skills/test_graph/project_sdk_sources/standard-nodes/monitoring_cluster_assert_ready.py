# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Independently assert strict readiness through the installed public CLI."""

from __future__ import annotations

from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node

from _support.monitoring import (
    ASSERT_READY_NODE_ID,
    ENSURE_NODE_ID,
    OUTPUT_NAMES,
    add_checks,
    invocation_failure,
    invoke_monitoring,
    monitoring_kubeconfig,
    monitoring_kubecontext,
    public_outputs,
    publish_outputs,
    report_checks,
    upstream_outputs,
)


SPEC = (
    NodeSpec(ASSERT_READY_NODE_ID)
    .kind("assertion")
    .depends_on(ENSURE_NODE_ID)
    .tags("monitoring", "observability", "shared-cluster", "standard")
    .timeout("30s")
)
for output_name in OUTPUT_NAMES:
    SPEC.output(output_name, "string")


@node(SPEC)
def main(ctx):
    outputs = upstream_outputs(ctx)
    published_kubeconfig = outputs["KUBECONFIG"]
    published_kubecontext = outputs["KUBECONTEXT"]
    kubeconfig = (
        Path(published_kubeconfig).expanduser().resolve(strict=False)
        if published_kubeconfig
        else monitoring_kubeconfig()
    )
    kubecontext = published_kubecontext or monitoring_kubecontext()
    invocation = invoke_monitoring(
        ctx,
        operation="status",
        arguments=["--json", "--require-ready"],
        timeout_seconds=18,
        kubeconfig=kubeconfig,
        kubecontext=kubecontext,
    )
    checks = report_checks(
        invocation.payload,
        expected_operation="status",
        expected_context=kubecontext,
        require_chart_changed=False,
    )
    reuse_valid = outputs["monitoringReused"] in {"true", "false"}
    expected_outputs = (
        public_outputs(
            invocation.payload,
            kubeconfig=kubeconfig,
            kubecontext=kubecontext,
            reused=outputs["monitoringReused"] == "true",
        )
        if invocation.payload is not None and reuse_valid
        else None
    )
    context_checks = {
        "ensure_published_all_monitoring_outputs": all(outputs.values()),
        "ensure_published_existing_kubeconfig": bool(published_kubeconfig)
        and kubeconfig.is_file(),
        "ensure_published_matching_kubecontext": published_kubecontext
        == outputs["monitoringKubeContext"],
        "ensure_published_compatible_reuse_flags": reuse_valid
        and outputs["EnvironmentRepositoryReused"] == outputs["monitoringReused"],
        "ensure_published_exact_monitoring_outputs": expected_outputs == outputs,
    }
    passed = (
        invocation.record.exit_code == 0
        and invocation.error is None
        and all(checks.values())
        and all(context_checks.values())
    )
    result = (
        NodeResult.pass_(ctx.node_id)
        if passed
        else NodeResult.fail(
            ctx.node_id, invocation_failure(invocation, checks, context_checks)
        )
    )
    result.process(invocation.record)
    if invocation.artifact is not None:
        result.artifact("monitoring-status-json", str(invocation.artifact))
    result.assertion("monitoring_status_require_ready_exit_zero", invocation.record.exit_code == 0)
    add_checks(result, checks)
    add_checks(result, context_checks)
    if passed:
        publish_outputs(result, outputs)
    result.log(f"strict monitoring status observed context={kubecontext}")
    return result


if __name__ == "__main__":
    main()
