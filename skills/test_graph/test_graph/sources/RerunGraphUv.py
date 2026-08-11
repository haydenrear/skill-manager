# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Validate graph resume starting at a uv node."""

from __future__ import annotations

import sys
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from rerun_harness import (
    SMOKE_NODE_IDS,
    local_http_fixture,
    output_of,
    run_project_graph,
    run_smoke_baseline,
    summarize,
    verify_fresh_replay,
)


SPEC = (
    NodeSpec("self.rerun.graph.uv")
    .kind("assertion")
    .tags("self", "rerun", "resume", "uv")
    .timeout("10m")
    .side_effects("process:gradle", "net:local")
    .output("smokeRunId", "string")
    .output("replayRunId", "string")
)


@node(SPEC)
def main(ctx):
    with local_http_fixture():
        baseline = run_smoke_baseline()
        resume = run_project_graph(
            "smoke",
            [
                "--resume-from-build",
                str(baseline.build_dir),
                "--resume-from-node",
                "user.seeded",
            ],
        )
        rerun_false = run_project_graph(
            "smoke",
            [
                "--resume-from-build",
                str(baseline.build_dir),
                "--resume-from-node",
                "rerun.disabled.probe",
            ],
        )
    evidence = verify_fresh_replay(
        baseline,
        resume,
        graph="smoke",
        mode="resume-from-node",
        selected_node_id="user.seeded",
        expected_node_ids=SMOKE_NODE_IDS[1:],
    )

    resume_output = output_of(resume)
    rerun_false_output = output_of(rerun_false)
    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("baseline_smoke_passed", baseline.completed.returncode == 0)
        .assertion("smoke_run_id_found", bool(baseline.run_id))
        .assertion("smoke_build_dir_exists", baseline.build_dir.is_dir())
        .assertion("resume_from_uv_node_passed", resume.returncode == 0)
        .assertion("selected_node_was_uv", "  [1/5] user.seeded (uv)" in resume_output)
        .assertion(
            "resume_continued_downstream",
            "  [5/5] context.snapshots.present (uv)" in resume_output,
        )
        .assertion("resume_rejects_rerun_false", rerun_false.returncode != 0)
        .assertion("resume_rerun_false_explained", "rerun=false" in rerun_false_output)
        .log(summarize("baseline_smoke", baseline.completed))
        .log(summarize("resume_user_seeded", resume))
        .log(summarize("resume_rerun_false", rerun_false))
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
