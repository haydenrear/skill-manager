"""skt command-line entry point.

Deliberately stdlib-only: the skill-script installer runs this file with
the system python3 (no venv), so nothing here may import beyond the
standard library until the install path grows a venv.
"""

from __future__ import annotations

import argparse
import sys

__version__ = "0.3.0"

# Subcommand -> (implementing ticket, issue URL) for honest stubs.
# Empty since SKT-5; kept for future subcommands landing across tickets.
_PENDING: dict[str, tuple[str, str]] = {}

NOT_IMPLEMENTED_EXIT = 2


def _stub(name: str) -> int:
    ticket, issue = _PENDING[name]
    print(
        f"skt {name}: not implemented yet — lands with {ticket} ({issue})",
        file=sys.stderr,
    )
    return NOT_IMPLEMENTED_EXIT


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="skt",
        description=(
            "Skill-lifecycle CLI: startup disclosure of loaded skills/plugins, "
            "home tier and epic/ticket state; new-version and sync-with-root "
            "notifications; worktree change management."
        ),
    )
    parser.add_argument(
        "--version", action="version", version=f"skt {__version__}"
    )
    sub = parser.add_subparsers(dest="command")

    status = sub.add_parser(
        "status",
        help="startup report: units, plugins, home tier, epic/ticket context (SKT-3)",
    )
    status.add_argument("--json", action="store_true", help="machine-readable output")

    check = sub.add_parser(
        "check",
        help="new-version and sync-with-root notifications (SKT-4)",
    )
    check.add_argument("--cached", action="store_true", help="throttled, no-network path")
    check.add_argument("--ttl", type=int, default=900, help="cache freshness window in seconds")
    check.add_argument("--json", action="store_true")

    sync = sub.add_parser(
        "sync", help="pull a unit to its latest pushed source (SKT-4)"
    )
    sync.add_argument("unit", nargs="?")

    ticket = sub.add_parser(
        "ticket", help="worktree lifecycle: new/close/info via git-issue-workflow"
    )
    ticket.add_argument("verb", nargs="?", choices=["new", "close", "info"])
    ticket.add_argument("ticket_id", nargs="?")
    ticket.add_argument("--base", help="base branch for ticket new")
    ticket.add_argument(
        "--path",
        help="epic mode: create the worktree at this DECLARED path (assignments "
        "name it) with the index-base pinning conventions, instead of wt's "
        "derived path",
    )

    publish = sub.add_parser(
        "publish", help="guided home-sync + unit-publish for edited skills"
    )
    publish.add_argument("unit", nargs="?")
    publish.add_argument("--check", action="store_true", help="list edited units only; exit 10 if any")
    publish.add_argument("--ticket", help="ticket id for the publish branch (default: inferred from branch)")

    return parser


def _import_sibling(name: str):
    """Import a package sibling whether run as a package or a bare script.

    The skill-script installer execs this file directly with the system
    python3, so relative imports need a bootstrapped sys.path.
    """
    if __package__:
        import importlib

        return importlib.import_module(f".{name}", package=__package__)
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import importlib

    return importlib.import_module(f"skt.{name}")


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command is None:
        parser.print_help()
        return 0
    if args.command == "status":
        return _import_sibling("status").run(as_json=args.json)
    if args.command == "check":
        return _import_sibling("check").run(as_json=args.json, cached=args.cached, ttl=args.ttl)
    if args.command == "sync":
        return _import_sibling("sync").run(args.unit)
    if args.command == "ticket":
        return _import_sibling("ticket").run(args.verb, args.ticket_id, base=args.base, path=args.path)
    if args.command == "publish":
        return _import_sibling("publish").run(args.unit, check_only=args.check, ticket=args.ticket)
    return _stub(args.command)


if __name__ == "__main__":
    sys.exit(main())
