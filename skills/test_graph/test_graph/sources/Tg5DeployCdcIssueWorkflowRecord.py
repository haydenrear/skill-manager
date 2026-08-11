# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import json
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node


REPO_ROOT = Path(__file__).resolve().parents[2]
ISSUE_URL = "https://github.com/haydenrear/deploy-cdc/issues/6"
REFERENCE_PATH = "references/tickets/tg5-deploy-cdc-environment-repository-issue.md"
GRAPH_NAME = "deployCdcIssueContract"

SPEC = (
    NodeSpec("tg5.deploy_cdc.issue.workflow-record")
    .kind("assertion")
    .depends_on("tg5.deploy_cdc.sdk.uncoupled")
    .tags("tg5", "deploy-cdc", "spec-workflow")
)


def active_tg5_ticket_plan() -> Path | None:
    plan = REPO_ROOT / "specs" / "desired_program_model" / "ticket_plan.yaml"
    if not plan.is_file():
        return None
    if "id: TG-5G" in plan.read_text(encoding="utf-8"):
        return plan
    return None


def closed_snapshot_manifest() -> Path:
    return REPO_ROOT / "specs" / ".history" / "branch-environment-repository-workflow" / "closed-snapshot" / "manifest.json"


def latest_ticket_snapshot_manifest() -> Path:
    return REPO_ROOT / "specs" / ".history" / "branch-environment-repository-workflow" / "ticket-006-TG-5G" / "manifest.json"


@node(SPEC)
def main(ctx):
    plan = active_tg5_ticket_plan()
    closed = closed_snapshot_manifest()
    ticket_snapshot = latest_ticket_snapshot_manifest()

    if plan is not None:
        text = plan.read_text(encoding="utf-8")
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("tg5g_ticket_plan_exists", True)
            .assertion("tg5g_ticket_is_done", "id: TG-5G" in text and "status: done" in text)
            .assertion("tg5g_records_deploy_cdc_issue_url", ISSUE_URL in text)
            .assertion("tg5g_records_durable_reference_issue_body", REFERENCE_PATH in text)
            .assertion("tg5g_records_deploy_cdc_issue_graph", GRAPH_NAME in text)
            .artifact("ticket-plan", str(plan))
            .log("Validated active TG-5G ticket-plan evidence.")
        )

    if closed.is_file():
        manifest = json.loads(closed.read_text(encoding="utf-8"))
        rendered = json.dumps(manifest, sort_keys=True)
        tickets = manifest.get("tickets", [])
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("closed_workflow_snapshot_exists", True)
            .assertion("closed_workflow_contains_all_tickets", all(f"TG-5{suffix}" in rendered for suffix in "ABCDEFG"))
            .assertion("closed_workflow_records_deploy_cdc_issue_url", ISSUE_URL in rendered)
            .assertion("closed_workflow_records_durable_reference_issue_body", REFERENCE_PATH in rendered)
            .assertion("closed_workflow_records_deploy_cdc_issue_graph", GRAPH_NAME in rendered)
            .assertion("closed_workflow_tickets_are_done", all(str(ticket.get("status", "")).lower() == "done" for ticket in tickets))
            .artifact("closed-workflow-manifest", str(closed))
            .log("Validated closed TG-5 workflow snapshot evidence.")
        )

    if ticket_snapshot.is_file():
        manifest = json.loads(ticket_snapshot.read_text(encoding="utf-8"))
        rendered = json.dumps(manifest, sort_keys=True)
        return (
            NodeResult.pass_(ctx.node_id)
            .assertion("tg5g_ticket_snapshot_exists", True)
            .assertion("tg5g_ticket_snapshot_records_issue_url", ISSUE_URL in rendered)
            .assertion("tg5g_ticket_snapshot_records_durable_reference_issue_body", REFERENCE_PATH in rendered)
            .artifact("tg5g-ticket-snapshot", str(ticket_snapshot))
            .log("Validated TG-5G ticket snapshot evidence.")
        )

    return (
        NodeResult.fail(ctx.node_id, "No TG-5G workflow record exists.")
        .assertion("tg5g_workflow_record_exists", False)
        .log("No active ticket plan, closed workflow snapshot, or TG-5G ticket snapshot exists.")
    )


if __name__ == "__main__":
    main()
