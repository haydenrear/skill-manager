"""Checkout classification: repo kind, home tier, ticket/epic/spec context.

Marker files and git plumbing only — mirrors git-issue-workflow's
lib.sh semantics (integration.toml presence, never its contents).
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from .homes import root_home


def _git(*args: str, cwd: str | Path | None = None) -> str:
    proc = subprocess.run(
        ["git", *args], cwd=str(cwd) if cwd else None, capture_output=True, text=True
    )
    return proc.stdout.strip() if proc.returncode == 0 else ""


def checkout_root(start: str | Path = ".") -> Path:
    top = _git("rev-parse", "--show-toplevel", cwd=start)
    return Path(top) if top else Path(start).resolve()


def is_linked_worktree(root: Path) -> bool:
    git_dir = _git("rev-parse", "--git-dir", cwd=root)
    common = _git("rev-parse", "--git-common-dir", cwd=root)
    return bool(git_dir and common) and Path(git_dir).resolve() != Path(common).resolve()


def checkout_kind(root: Path) -> str:
    if (root / "integration.toml").is_file():
        return "integration"
    for parent in root.parents:
        if (parent / "integration.toml").is_file():
            return "constituent"
    return "standalone"


def classify_tier(home: Path, root: Path) -> str:
    try:
        if home.resolve() == root_home().resolve():
            return "root"
    except OSError:
        pass
    if is_linked_worktree(root) and _inside(home, root):
        return "worktree"
    return "project"


def _inside(path: Path, ancestor: Path) -> bool:
    try:
        path.resolve().relative_to(ancestor.resolve())
        return True
    except ValueError:
        return False


@dataclass(frozen=True)
class WorktreeSync:
    """Is this linked worktree's base the parent checkout's current commit?"""

    parent_head: str
    merge_base: str
    in_sync: bool
    ahead: int
    behind: int


def worktree_sync(root: Path) -> WorktreeSync | None:
    """None unless `root` is a linked worktree with a resolvable parent.

    in_sync means merge-base(worktree HEAD, parent HEAD) == parent HEAD —
    i.e. the parent repo has not moved past this worktree's base. `ahead`
    is the worktree's own commits; `behind` is how far the parent has
    moved past the base (the number that predicts promotion conflicts).
    """
    if not is_linked_worktree(root):
        return None
    listing = _git("worktree", "list", "--porcelain", cwd=root)
    if not listing:
        return None
    parent = Path(listing.splitlines()[0].split(" ", 1)[1])
    parent_head = _git("rev-parse", "HEAD", cwd=parent)
    head = _git("rev-parse", "HEAD", cwd=root)
    if not parent_head or not head:
        return None
    merge_base = _git("merge-base", head, parent_head, cwd=root)
    if not merge_base:
        return None
    ahead = _git("rev-list", "--count", f"{merge_base}..{head}", cwd=root)
    behind = _git("rev-list", "--count", f"{merge_base}..{parent_head}", cwd=root)
    return WorktreeSync(
        parent_head=parent_head,
        merge_base=merge_base,
        in_sync=(merge_base == parent_head),
        ahead=int(ahead or 0),
        behind=int(behind or 0),
    )


@dataclass(frozen=True)
class TicketContext:
    branch: str
    ticket: str | None
    epic: str | None
    kind: str
    tier: str
    on_epic_branch: bool = False
    spec_workflow: str | None = None
    spec_open_tickets: list[str] = field(default_factory=list)
    spec_ticket_in_plan: bool | None = None  # None: no plan or no ticket branch


_TICKET_RE = re.compile(r"^feature/(.+)$")
_EPIC_RE = re.compile(r"^epic/(.+)$")


def spec_workflow(root: Path) -> tuple[str | None, list[str], list[str]]:
    """(workflow name, open ticket ids, ALL ticket ids) from ticket_plan.yaml."""
    plan = root / "specs" / "desired_program_model" / "ticket_plan.yaml"
    if not plan.is_file():
        return None, [], []
    name = None
    open_tickets: list[str] = []
    all_tickets: list[str] = []
    current_id = None
    try:
        for line in plan.read_text().splitlines():
            if name is None and re.match(r"^name:\s*\S", line):
                name = line.split(":", 1)[1].strip().strip("'\"")
            m = re.match(r"^\s*-\s*id:\s*(\S+)", line)
            if m:
                current_id = m.group(1).strip("'\"")
                all_tickets.append(current_id)
            m = re.match(r"^\s*status:\s*(\S+)", line)
            if m and current_id and m.group(1).strip("'\"").lower() in ("open", "in_progress"):
                open_tickets.append(current_id)
                current_id = None
    except OSError:
        return None, [], []
    return name or "(unnamed)", open_tickets, all_tickets


def gather(start: str | Path, home: Path) -> TicketContext:
    root = checkout_root(start)
    branch = _git("branch", "--show-current", cwd=root) or "(detached)"
    ticket = None
    match = _TICKET_RE.match(branch)
    if match:
        ticket = match.group(1)
    epic = None
    on_epic_branch = False
    match = _EPIC_RE.match(branch)
    if match:
        epic = match.group(1)
        on_epic_branch = True
    if not epic:
        # Keep the field CLEAN (consumed as JSON by the SKT-6 hook): the
        # slug only, with a separate boolean for "a branch exists but we
        # are not on it". Check remote refs too — a fresh clone has no
        # local epic/* until someone checks it out.
        refs = _git(
            "for-each-ref", "--format=%(refname:short)",
            "refs/heads/epic/*", "refs/remotes/*/epic/*", cwd=root,
        )
        for ref in refs.splitlines():
            short = ref.split("epic/", 1)
            if len(short) == 2:
                epic = short[1]
                break
    name, open_tickets, all_tickets = spec_workflow(root)
    ticket_in_plan = None
    if name is not None and ticket is not None:
        ticket_in_plan = ticket in all_tickets
    return TicketContext(
        branch=branch,
        ticket=ticket,
        epic=epic,
        on_epic_branch=on_epic_branch,
        kind=checkout_kind(root),
        tier=classify_tier(home, root),
        spec_workflow=name,
        spec_open_tickets=open_tickets,
        spec_ticket_in_plan=ticket_in_plan,
    )
