# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Ensure the one shared durable monitoring environment through its public CLI."""

from __future__ import annotations

from testgraphsdk import NodeResult, NodeSpec, SideEffect, node

from _support.monitoring import (
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
)


SPEC = (
    NodeSpec(ENSURE_NODE_ID)
    .kind("testbed")
    .tags("monitoring", "observability", "shared-cluster", "standard")
    .timeout("360s")
    .side_effects(SideEffect.environment("provision"))
)
for output_name in OUTPUT_NAMES:
    SPEC.output(output_name, "string")


@node(SPEC)
def main(ctx):
    kubeconfig = monitoring_kubeconfig()
    kubecontext = monitoring_kubecontext()
    invocation = invoke_monitoring(
        ctx,
        operation="up",
        arguments=["--json", "--budget-seconds", "335"],
        timeout_seconds=340,
        kubeconfig=kubeconfig,
        kubecontext=kubecontext,
    )
    payload = invocation.payload
    checks = report_checks(
        payload,
        expected_operation="up",
        expected_context=kubecontext,
        require_chart_changed=True,
    )
    checks["monitoring_kubeconfig_written"] = kubeconfig.is_file()
    passed = invocation.record.exit_code == 0 and invocation.error is None and all(checks.values())
    result = (
        NodeResult.pass_(ctx.node_id)
        if passed
        else NodeResult.fail(ctx.node_id, invocation_failure(invocation, checks))
    )
    result.process(invocation.record)
    if invocation.artifact is not None:
        result.artifact("monitoring-status-json", str(invocation.artifact))
    result.assertion("monitoring_up_exit_zero", invocation.record.exit_code == 0)
    add_checks(result, checks)

    if passed and payload is not None:
        reused = payload.get("chart_changed") is False
        outputs = public_outputs(
            payload,
            kubeconfig=kubeconfig,
            kubecontext=kubecontext,
            reused=reused,
        )
        publish_outputs(result, outputs)
        result.metric("monitoringReused", 1 if reused else 0)
        result.log(
            "installed monitoring up completed; "
            f"reused={outputs['monitoringReused']} context={outputs['KUBECONTEXT']}"
        )
    return result


if __name__ == "__main__":
    main()
