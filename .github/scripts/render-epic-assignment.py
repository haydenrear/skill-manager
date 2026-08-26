#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["PyYAML>=6.0.2,<7"]
# ///
"""Render a git-epic-workflow assignment block for one HIS ticket.

WHY THIS EXISTS
---------------
The assignment block is the ticket agent's work order and the only
machine-readable link between a GitHub issue and the canonical plan. It has
~40 required fields across five sections, and `validate_assignment.py` refuses
the issue if any of them is missing, misspelled, or left as an unrendered
`<placeholder>`.

Hand-writing six of those is how a plan and its issues drift apart on field
number 37. This renders them from `specs/desired_program_model/ticket_plan.yaml`
instead, so the issue cannot disagree with the plan about a wave, a promotion
predecessor, a conflict key, or a goal's baseline -- and re-rendering after a
schedule amendment is one command rather than six careful edits.

Usage:
    .github/scripts/render-epic-assignment.py HIS-1 > /tmp/his1.md
    .github/scripts/render-epic-assignment.py HIS-1 --plan-commit <sha>
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
PLAN = REPO / "specs" / "desired_program_model" / "ticket_plan.yaml"

START = "<!-- git-epic-workflow:assignment:start -->"
END = "<!-- git-epic-workflow:assignment:end -->"

# `conflict_keys` is spelled `workflow` in an assignment and `workflow_data` in
# the plan. Deliberately not "fixed" in one of the two: the plan field predates
# the epic primitive and other tooling reads it.
CONFLICT_MAP = {
    "production": "production",
    "tla": "tla",
    "adapters": "adapters",
    "test_graph": "test_graph",
    "workflow_data": "workflow",
}


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(REPO), *args], capture_output=True, text=True, check=True
    ).stdout.strip()


def render(ticket_id: str, plan_commit: str | None) -> str:
    plan = yaml.safe_load(PLAN.read_text())
    epic = plan["epic"]
    goals_by_id = {g["id"]: g for g in plan["epic_goals"]}
    tickets = {t["id"]: t for t in plan["tickets"]}

    if ticket_id not in tickets:
        raise SystemExit(f"no ticket {ticket_id!r} in {PLAN}")
    t = tickets[ticket_id]
    role = t.get("role", "implementation")
    # references/epic-ticket.md fixes both names: the worktree is
    # ../wt-<issue-number>-<slug> and the branch is feature/<issue-number>-<slug>.
    # The ticket agent reads these two fields and creates exactly what they say,
    # so a convenient-looking shorter name here is a real divergence.
    issue = t.get("github_issue")
    if issue is None:
        raise SystemExit(
            f"{ticket_id} has no github_issue in the plan; the worktree and branch "
            "names are derived from it, so the issue must exist first"
        )
    slug = f"{issue}-{ticket_id.lower()}"

    conflicts = {
        assign: list(t.get("conflict_keys", {}).get(plan_key, []) or [])
        for plan_key, assign in CONFLICT_MAP.items()
    }

    goals = []
    for entry in t.get("goals", []):
        g = goals_by_id[entry["goal"]]
        goal_entry = {
                "goal": g["id"],
                "kind": g["kind"],
                "statement": " ".join(g["statement"].split()),
                "metric": " ".join(g["metric"].split()),
                "baseline": " ".join(str(g["baseline"]["value"]).split()),
                "target": " ".join(g["target"].split()),
                "expected_effect": " ".join(entry["expected_effect"].split()),
                "local_signal": entry["local_signal"],
                "decided_by": {
                    "ticket": g["evaluation_ticket"],
                    "harness": " ".join(g["harness"].split()),
                },
                "evidence_root": g["evidence_root"],
        }
        # An evaluation ticket DECIDES a goal; it does not contribute to one.
        # The validator warns if `contribution` is set on a role: evaluation
        # ticket, and it is right to -- "guard" there reads as though the
        # measurement itself were expected to move the number.
        if role != "evaluation":
            goal_entry["contribution"] = entry["contribution"]
        goals.append(goal_entry)

    assignment = {
        "version": 1,
        "epic": {
            "id": epic["slug"],
            "workflow": plan["name"],
            "branch": epic["branch"],
            "base_sha": epic["base"],
            "plan_commit": plan_commit or _git("rev-parse", "HEAD"),
            "default_branch": "main",
            "schedule_revision": plan["schedule_revision"],
        },
        "ticket": {
            "spec_id": ticket_id,
            "role": role,
            "feature_branch": f"feature/{slug}",
            "worktree": f"../wt-{slug}",
            "pr_base": epic["branch"],
            "wave": t["wave"],
            "promotion_order": t["promotion_order"],
            "promotion_predecessor": t.get("promotion_predecessor"),
            "depends_on": list(t.get("depends_on", []) or []),
            "blocks": list(t.get("blocks", []) or []),
            "conflict_keys": conflicts,
            **({"owns_goals": list(t["owns_goals"])} if role == "evaluation" else {}),
        },
        "validation": _validation(t, role),
        "goals": goals,
        "review": {
            "mode": "external",
            "ticket_agent_stops_after": "pr_open",
            "merged_by": "epic-owner",
            "cadence": plan["review_policy"]["cadence"],
            "artifact_root": plan["review_policy"]["artifact_root"],
        },
        "deferment": {
            "mode": plan["deferment_policy"]["mode"],
            "blocking": plan["deferment_policy"]["blocking"],
            "budget": plan["deferment_policy"]["budget"],
            "backlog": plan["deferment_policy"]["backlog"],
        },
    }

    body = yaml.safe_dump(assignment, sort_keys=False, width=88, allow_unicode=True)
    return f"{START}\n\n```yaml\n{body}```\n\n{END}"


def _validation(ticket: dict, role: str) -> dict:
    """The REQUIRED matrix. `N/A: <reason>` where a lane genuinely does not apply.

    A bare `N/A` is refused by the validator, and correctly so -- it hides why a
    REQUIRED-adjacent lane was waived.
    """
    ev = f"results/epic-home-integrity-sync/tickets/{ticket['id']}"
    touches_tla = bool(ticket.get("conflict_keys", {}).get("tla"))
    graphs = ticket.get("conflict_keys", {}).get("test_graph") or []

    tlc = (
        "specs/program_model/run_tlc.sh"
        if touches_tla
        else "N/A: this ticket states no new invariant; HIS-5 carries the model work "
        "for the whole epic and re-runs every regression cfg"
    )
    # `graphs` is the machine list the epic agent re-runs at integration;
    # `spec_graph` is the command the ticket agent runs itself. Both are
    # required, and they must not disagree about which graphs are affected.
    if graphs:
        names = sorted({g.split("/")[0] for g in graphs})
        graph = "python skills/test_graph/scripts/run.py " + " ".join(names)
    elif role == "evaluation":
        names = ["home-integrity", "artifact-dag"]
        graph = "python skills/test_graph/scripts/run.py --all"
    else:
        names = ["home-integrity"]
        graph = "N/A: this ticket adds no graph node, but the epic's graph still gates it"

    return {
        "tlc": tlc,
        "spec_unit": "uv run pytest specs/",
        "repository_unit": (
            "N/A: this ticket is measurement only and changes no production code"
            if role == "evaluation"
            else "jbang RunTests.java"
        ),
        "spec_graph": graph,
        "graphs": names,
        "graphs_not_required": (
            "python skills/test_graph/scripts/run.py --all -- it is multi-hour and it "
            "belongs to HIS-6, which owns the ONE terminal sweep run with the goal "
            "scorecard. Run the graphs above plus any graph whose fixtures exercise the "
            "sources you edited, and NAME that second set with its reason in your goal "
            "contribution. Owner's instruction, 2026-08-21."
        ),
        "toolchain_spec_workflow": (
            "tla-spec-dev --spec-root specs open ticket "
            f"{ticket['id']} ... close ticket {ticket['id']}"
        ),
        "evidence_root": ev,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ticket")
    ap.add_argument("--plan-commit", default=None)
    args = ap.parse_args()
    print(render(args.ticket, args.plan_commit))
    return 0


if __name__ == "__main__":
    sys.exit(main())
