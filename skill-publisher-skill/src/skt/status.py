"""`skt status` — the startup report. Local disk only; no network."""

from __future__ import annotations

import json
from pathlib import Path

from . import context as ctx_mod
from . import homes

SCHEMA_VERSION = 1
MAX_TEXT_UNITS = 15


def collect(start: str | Path = ".") -> dict:
    home = homes.find_home(start)
    if home is None:
        return {
            "schema": SCHEMA_VERSION,
            "home": None,
            "error": "no skill-manager home found (checked $SKILL_MANAGER_HOME, "
            "ancestor .skill-manager dirs, and the operator root)",
        }
    units = homes.read_units(home)
    tctx = ctx_mod.gather(start, home)
    return {
        "schema": SCHEMA_VERSION,
        "home": str(home),
        "tier": tctx.tier,
        "policy": homes.read_policy(home),
        "drift_pending": homes.drift_pending(home),
        "checkout": {
            "root": str(ctx_mod.checkout_root(start)),
            "kind": tctx.kind,
            "branch": tctx.branch,
            "ticket": tctx.ticket,
            "epic": tctx.epic,
            "on_epic_branch": tctx.on_epic_branch,
        },
        "spec_workflow": {
            "name": tctx.spec_workflow,
            "open_tickets": tctx.spec_open_tickets,
            "ticket_in_plan": tctx.spec_ticket_in_plan,
        },
        "cli_tools": homes.read_cli_tools(home),
        "worktree_sync": (
            {
                "parent_head": sync.parent_head[:8],
                "merge_base": sync.merge_base[:8],
                "in_sync": sync.in_sync,
                "ahead": sync.ahead,
                "behind": sync.behind,
            }
            if (sync := ctx_mod.worktree_sync(ctx_mod.checkout_root(start))) is not None
            else None
        ),
        "units": [
            {
                "name": u.name,
                "version": u.version,
                "kind": u.unit_kind,
                "loaded": u.loaded,
                "change_managed": u.change_managed,
                "git_hash": (u.git_hash or "")[:8] or None,
                "errors": u.errors,
            }
            for u in units
        ],
        "plugins": homes.read_plugins(home),
    }


def render_text(report: dict) -> str:
    if report.get("home") is None:
        return f"skt status: {report['error']}"
    lines: list[str] = []
    checkout = report["checkout"]
    lines.append(f"skt status — {checkout['root']}")
    place = f"checkout   {checkout['kind']} repo, branch {checkout['branch']}"
    if checkout["ticket"]:
        place += f" (ticket {checkout['ticket']})"
    if checkout["epic"]:
        suffix = "" if checkout.get("on_epic_branch") else " available"
        place += f" (epic {checkout['epic']}{suffix})"
    lines.append(place)
    home_line = f"home       {report['home']} — tier: {report['tier']}, policy: {report['policy']}"
    if report["drift_pending"]:
        home_line += ", DRIFT PENDING (launch will refuse; ack with: skill-manager home drift --ack)"
    lines.append(home_line)
    spec = report["spec_workflow"]
    if spec["name"]:
        open_part = (
            f"; open tickets: {', '.join(spec['open_tickets'])}"
            if spec["open_tickets"]
            else "; no open tickets"
        )
        match = spec.get("ticket_in_plan")
        if match is True:
            open_part += " — this branch's ticket IS in the plan"
        elif match is False:
            open_part += " — this branch's ticket is NOT in the plan"
        lines.append(f"spec       workflow '{spec['name']}' active{open_part}")
    sync = report.get("worktree_sync")
    if sync is not None:
        if sync["in_sync"]:
            lines.append(
                f"base       in sync with parent @{sync['parent_head']}"
                f" ({sync['ahead']} commit(s) ahead)"
            )
        else:
            lines.append(
                f"base       BASE STALE: parent @{sync['parent_head']}, worktree base "
                f"@{sync['merge_base']} (behind {sync['behind']}) — reconcile before promoting"
            )
    tools = report.get("cli_tools") or []
    if tools:
        shown = ", ".join(tools[:10]) + (f" +{len(tools)-10} more" if len(tools) > 10 else "")
        lines.append(f"cli        {shown}")
    units = report["units"]
    cm = sum(1 for u in units if u["change_managed"])
    bad = sum(1 for u in units if u["errors"])
    summary = f"units      {len(units)} installed ({cm} change-managed"
    summary += f", {bad} with errors)" if bad else ")"
    lines.append(summary)
    for unit in units[:MAX_TEXT_UNITS]:
        flags = "".join(
            [" [loaded]" if unit["loaded"] else "", " [cm]" if unit["change_managed"] else ""]
        )
        marker = " [ERRORS]" if unit["errors"] else ""
        lines.append(
            f"  {unit['name']} {unit['version']} {unit['kind'].lower()}"
            f"{flags}{marker} {unit['git_hash'] or ''}".rstrip()
        )
    if len(units) > MAX_TEXT_UNITS:
        lines.append(f"  … +{len(units) - MAX_TEXT_UNITS} more (skt status --json for all)")
    plugins = report["plugins"]
    lines.append(f"plugins    {', '.join(plugins) if plugins else 'none'}")
    lines.append("next       skt check — new-version and sync notifications")
    return "\n".join(lines)


def run(as_json: bool, start: str | Path = ".") -> int:
    report = collect(start)
    print(json.dumps(report, indent=2) if as_json else render_text(report))
    return 0 if report.get("home") else 1
