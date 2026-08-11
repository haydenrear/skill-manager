# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Validate single-node replay for a uv node."""

from __future__ import annotations

import sys
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from rerun_harness import (
    local_http_fixture,
    output_of,
    run_project_graph,
    run_smoke_baseline,
    summarize,
    verify_fresh_replay,
)


SPEC = (
    NodeSpec("self.run.only.uv")
    .kind("assertion")
    .tags("self", "rerun", "run-only", "uv")
    .timeout("10m")
    .side_effects("process:gradle", "net:local")
    .output("smokeRunId", "string")
    .output("replayRunId", "string")
)


@node(SPEC)
def main(ctx):
    with local_http_fixture():
        baseline = run_smoke_baseline()
        run_only = run_project_graph(
            "smoke",
            [
                "--resume-from-build",
                str(baseline.build_dir),
                "--run-only-node",
                "user.seeded",
            ],
        )
        rerun_false = run_project_graph(
            "smoke",
            [
                "--resume-from-build",
                str(baseline.build_dir),
                "--run-only-node",
                "rerun.disabled.probe",
            ],
        )
    evidence = verify_fresh_replay(
        baseline,
        run_only,
        graph="smoke",
        mode="run-only-node",
        selected_node_id="user.seeded",
        expected_node_ids=("user.seeded",),
    )

    run_only_output = output_of(run_only)
    rerun_false_output = output_of(rerun_false)
    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("baseline_smoke_passed", baseline.completed.returncode == 0)
        .assertion("smoke_run_id_found", bool(baseline.run_id))
        .assertion("smoke_build_dir_exists", baseline.build_dir.is_dir())
        .assertion("run_only_uv_node_passed", run_only.returncode == 0)
        .assertion(
            "selected_node_was_uv", "  [1/1] user.seeded (uv)" in run_only_output
        )
        .assertion("did_not_continue_downstream", "  [2/" not in run_only_output)
        .assertion("run_only_rejects_rerun_false", rerun_false.returncode != 0)
        .assertion(
            "run_only_rerun_false_explained", "rerun=false" in rerun_false_output
        )
        .log(summarize("baseline_smoke", baseline.completed))
        .log(summarize("run_only_user_seeded", run_only))
        .log(summarize("run_only_rerun_false", rerun_false))
        .log(summarize("manual_validation_report", evidence.manual_report))
        .log(evidence.audit_log())
        .publish("smokeRunId", baseline.run_id)
        .publish("replayRunId", evidence.target_run_id)
    )
    for name, passed in evidence.checks:
        result.assertion(name, passed)
    if baseline.build_dir.is_dir():
        result.artifact("project-smoke-report", str(baseline.build_dir / "report.md"))
    if evidence.target_build_dir.is_dir():
        result.artifact(
            "project-replay-report", str(evidence.target_build_dir / "report.md")
        )
        result.artifact(
            "project-replay-summary", str(evidence.target_build_dir / "summary.json")
        )
        result.artifact(
            "project-replay-scope",
            str(evidence.target_build_dir / "execution-scope.json"),
        )
    return result


if __name__ == "__main__":
    main()
