"""`skt ticket` — the worktree lifecycle, imported from git-issue-workflow.

skt does not reimplement or shell out to the `wt` path by hand: it
imports the typed Python surface that git-issue-workflow registers
(SKT-2) and adds skt's framing — orientation after `new`, guided
remedies on a refused `close`.
"""

from __future__ import annotations

import sys
from pathlib import Path

from . import homes


def _import_wrapper():
    """Import git_issue_workflow from the environment or the home's store copy."""
    try:
        import git_issue_workflow  # noqa: F401

        return sys.modules["git_issue_workflow"]
    except ImportError:
        pass
    home = homes.find_home(".")
    if home is not None:
        candidate = home / "skills" / "git-issue-workflow" / "src"
        if (candidate / "git_issue_workflow").is_dir():
            sys.path.insert(0, str(candidate))
            import git_issue_workflow

            return git_issue_workflow
    raise SystemExit(
        "skt ticket: the git-issue-workflow python surface is not importable.\n"
        "fix: skill-manager sync git-issue-workflow   # needs the SKT-2 version or later"
    )


def _print_contract(contract) -> None:
    print(f"worktree   {contract.worktree}")
    print(f"branch     {contract.branch}")
    if contract.launch:
        print(f"launch     {contract.launch}")
    if contract.if_exit_8:
        print(f"if-exit-8  {contract.if_exit_8}")
    print(f"close      skt ticket close — or: {contract.close}")
    if contract.propagate:
        print(f"propagate  {contract.propagate}")


def _bootstrap_script() -> Path | None:
    giw = homes.find_home(".")
    if giw is None:
        return None
    candidate = giw / "skills" / "git-issue-workflow" / "scripts" / "bootstrap-home.sh"
    return candidate if candidate.is_file() else None


def epic_new(ticket_id: str, base: str | None, path: str) -> int:
    """Create a DECLARED-path worktree the way an epic assignment requires.

    Epic assignments name the exact worktree path and base, which the
    conventional `wt new` cannot produce (it derives its own path) — the
    docs hand-roll `git worktree add` + `bootstrap-home.sh` for this one
    case. This subsumes that pair, with the index-base pinning
    conventions (clean tree; OIDs resolved once; create-only retention
    ref; branch from the pinned commit, never the moving ref) and the
    same roll-back-on-bootstrap-failure contract as new-change.sh.
    """
    import subprocess

    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(["git", *args], capture_output=True, text=True)

    dirty = git("status", "--porcelain")
    if dirty.stdout.strip():
        print("error: working tree is not clean — an epic worktree pins its base from a clean slate")
        print("fix:   commit or stash, then re-run")
        return 1
    base_ref = base or "HEAD"
    commit = git("rev-parse", base_ref)
    tree = git("rev-parse", f"{base_ref}^{{tree}}")
    if commit.returncode != 0 or tree.returncode != 0:
        print(f"error: cannot resolve base {base_ref!r}")
        print("fix:   git fetch origin, then pass --base <existing-ref>")
        return 1
    commit_oid, tree_oid = commit.stdout.strip(), tree.stdout.strip()
    toplevel = git("rev-parse", "--show-toplevel").stdout.strip()
    repo_id = Path(toplevel).name if toplevel else "repo"
    ref_name = f"refs/index-bases/{repo_id}/{tree_oid}"
    existing = git("rev-parse", "--verify", "--quiet", ref_name)
    if existing.returncode == 0 and existing.stdout.strip() != commit_oid:
        print(f"error: retention ref {ref_name} already points at {existing.stdout.strip()[:8]}, not {commit_oid[:8]}")
        print("fix:   the declared base disagrees with an earlier pin — reconcile with the epic owner")
        return 1
    if existing.returncode != 0:
        made = git("update-ref", ref_name, commit_oid, "")
        if made.returncode != 0:
            print(f"error: could not create retention ref {ref_name}: {made.stderr.strip()}")
            return 1
    branch = f"feature/{ticket_id}"
    added = git("worktree", "add", path, "-b", branch, commit_oid)
    if added.returncode != 0:
        print(f"error: git worktree add failed: {added.stderr.strip().splitlines()[-1] if added.stderr.strip() else added.returncode}")
        print(f"fix:   git worktree add {path} -b {branch} {commit_oid[:12]}   # then bootstrap-home.sh --root {path}")
        return 1
    bootstrap = _bootstrap_script()
    if bootstrap is None:
        git("worktree", "remove", "--force", path)
        git("branch", "-D", branch)
        print("error: bootstrap-home.sh not found in this home; worktree rolled back")
        print("fix:   skill-manager sync git-issue-workflow, then re-run")
        return 3
    proc = subprocess.run([str(bootstrap), "--root", path], capture_output=True, text=True)
    if proc.returncode != 0:
        git("worktree", "remove", "--force", path)
        git("branch", "-D", branch)
        tail = (proc.stdout + proc.stderr).strip().splitlines()
        print(f"error: home bootstrap failed (exit {proc.returncode}); worktree and branch rolled back")
        if tail:
            print("       " + tail[-1])
        print(f"fix:   {bootstrap} --root <repo-root>   # once per repository, then re-run")
        return 3
    print(f"created epic worktree {path}")
    print(f"branch     {branch} (pinned base {commit_oid[:12]}; retention ref {ref_name})")
    launch = Path(path) / ".skill-manager" / "bin" / "launch" / "claude"
    if launch.is_file():
        print(f"launch     {launch}")
    print(f"close      skt ticket close {ticket_id}   # resolves declared paths by search")
    print(
        "\nA skill edit inside that worktree's home is in no git diff; "
        "run `skt publish` there before closing, or the close gate will refuse."
    )
    return 0


def run(verb: str | None, ticket_id: str | None, base: str | None = None, path: str | None = None) -> int:
    if not verb or not ticket_id:
        print("usage: skt ticket new|close|info <TICKET> [--base <branch>]", file=sys.stderr)
        return 1
    if verb == "new" and path:
        return epic_new(ticket_id, base, path)
    giw = _import_wrapper()
    try:
        if verb == "new":
            contract = giw.wt_new(ticket_id, base)
            print(f"created ticket worktree for {ticket_id}:")
            _print_contract(contract)
            print(
                "\nA skill edit inside that worktree's home is in no git diff; "
                "run `skt publish` there before closing, or the close gate will refuse."
            )
            return 0
        if verb == "info":
            info = giw.wt_info(ticket_id)
            _print_contract(info)
            from . import context as ctx_mod

            sync = ctx_mod.worktree_sync(Path(info.worktree))
            if sync is not None:
                if sync.in_sync:
                    print(f"base       in sync with parent @{sync.parent_head[:8]} ({sync.ahead} ahead)")
                else:
                    print(
                        f"base       BASE STALE: parent @{sync.parent_head[:8]}, base "
                        f"@{sync.merge_base[:8]} (behind {sync.behind}) — reconcile before promoting"
                    )
            return 0
        if verb == "close":
            from . import publish as publish_mod

            # The advisory must inspect the TARGET worktree's home, not
            # cwd's — `skt ticket close <T>` runs from anywhere, and the
            # edited skills at risk live in the home being torn down.
            target_home = None
            try:
                info = giw.wt_info(ticket_id)
                candidate = Path(info.worktree) / ".skill-manager"
                if candidate.is_dir():
                    target_home = candidate
            except giw.WtError:
                pass
            leftovers = publish_mod.edited_units(target_home or homes.find_home("."))
            if leftovers:
                names = ", ".join(u["unit"] for u in leftovers)
                print(f"note: edited unit(s) in this home before close: {names}")
                print("      (`skt publish <unit>` moves them out; the gate below enforces it)")
            result = giw.wt_close(ticket_id)
            print(f"closed {result.worktree}")
            if result.branch:
                print(f"branch {result.branch} kept — delete once the change has landed")
            if result.home_work:
                print(f"home-work: {result.home_work}")
            return 0
        print(f"skt ticket: unknown verb {verb!r} (expected new, close or info)", file=sys.stderr)
        return 1
    except giw.CloseRefused as err:
        print(f"error: close refused — {err.reason}")
        print(f"fix:   {err.fix or 'skt publish   # then re-run skt ticket close'}")
        if err.log:
            print(f"log:   {err.log}")
        return 4
    except giw.BootstrapFailed as err:
        print(f"error: {err.reason}")
        print(f"fix:   {err.fix}")
        if err.log:
            print(f"log:   {err.log}")
        return 3
    except giw.WtError as err:
        print(f"error: {err.reason}")
        if err.fix:
            print(f"fix:   {err.fix}")
        return err.exit_code or 1
