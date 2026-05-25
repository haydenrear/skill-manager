from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence


class SkillDevError(RuntimeError):
    pass


@dataclass(frozen=True)
class InstalledUnit:
    name: str
    kind: str
    unit_kind: str
    origin: str | None
    git_hash: str | None
    git_ref: str | None


@dataclass(frozen=True)
class UnitPaths:
    home: Path
    installed_record: Path
    installed_dir: Path
    project_root: Path
    worktree_root: Path
    worktree_dir: Path


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except SkillDevError as exc:
        print(f"skill-dev: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("skill-dev: interrupted", file=sys.stderr)
        return 130


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="skill-dev",
        description="Open installed skill-manager skills and plugins in project-local worktrees.",
    )
    parser.add_argument("--home", type=Path, help="Skill-manager home. Defaults to $SKILL_MANAGER_HOME or ~/.skill-manager.")
    parser.add_argument("--project", type=Path, help="Project root or any path inside it. Defaults to the current git repository.")
    sub = parser.add_subparsers(dest="command", required=True)

    open_p = sub.add_parser("open", help="Create or reuse skill-dev/<unit> for local development.")
    add_unit_args(open_p)
    open_p.add_argument("--branch", help="Development branch to create or check out.")
    open_p.add_argument("--ref", help="Start point for a new development branch. Defaults to the recorded install hash.")
    open_p.add_argument("--reuse", action="store_true", help="Reuse an existing worktree instead of refusing.")
    open_p.set_defaults(func=cmd_open)

    status_p = sub.add_parser("status", help="Show installed unit and worktree state.")
    add_unit_args(status_p)
    status_p.set_defaults(func=cmd_status)

    sync_p = sub.add_parser("sync", help="Apply the worktree back through skill-manager sync --from ... --merge.")
    add_unit_args(sync_p)
    sync_p.set_defaults(func=cmd_sync)

    git_p = sub.add_parser("git", help="Run git inside the unit worktree.")
    add_unit_args(git_p)
    git_p.add_argument("git_args", nargs=argparse.REMAINDER, help="Arguments passed to git after --.")
    git_p.set_defaults(func=cmd_git)

    close_p = sub.add_parser("close", help="Remove the project-local worktree when it is safe.")
    add_unit_args(close_p)
    close_p.add_argument("--merge", action="store_true", help="Sync with --merge before removing the worktree.")
    close_p.set_defaults(func=cmd_close)
    return parser


def add_unit_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("unit", help="Installed skill or plugin name.")


def cmd_open(args: argparse.Namespace) -> int:
    unit, paths = resolve(args)
    require_git_backed(unit, paths)
    require_clean_installed(paths.installed_dir)
    ensure_project_ignore(paths.project_root)

    if paths.worktree_dir.exists():
        if not args.reuse:
            raise SkillDevError(f"{paths.worktree_dir} already exists; pass --reuse to use it")
        require_git_repo(paths.worktree_dir, "existing worktree")
        print(f"reusing {paths.worktree_dir}")
        return 0

    branch = args.branch or default_branch(unit.name)
    start = args.ref or unit.git_hash or unit.git_ref or "HEAD"
    if not unit.git_ref:
        print("warning: installed metadata has no gitRef; starting from recorded hash or HEAD", file=sys.stderr)

    paths.worktree_root.mkdir(parents=True, exist_ok=True)
    if branch_exists(paths.installed_dir, branch):
        run_git(paths.installed_dir, ["worktree", "add", str(paths.worktree_dir), branch], check=True)
    else:
        run_git(paths.installed_dir, ["worktree", "add", "-b", branch, str(paths.worktree_dir), start], check=True)

    print(paths.worktree_dir)
    print(f"branch: {branch}")
    print(f"next: skill-dev git {unit.name} -- status")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    unit, paths = resolve(args)
    print(f"unit: {unit.name}")
    print(f"kind: {unit.unit_kind.lower()}")
    print(f"installed: {paths.installed_dir}")
    print(f"record: {paths.installed_record}")
    print(f"worktree: {paths.worktree_dir}")
    print(f"git-backed: {str(unit.kind == 'GIT').lower()}")
    print(f"recorded-ref: {unit.git_ref or '-'}")
    print(f"recorded-hash: {short(unit.git_hash)}")

    if not paths.worktree_dir.exists():
        print("worktree-status: missing")
        print(f"next: skill-dev open {unit.name}")
        return 0

    require_git_repo(paths.worktree_dir, "worktree")
    branch = git_stdout(paths.worktree_dir, ["branch", "--show-current"]) or "(detached)"
    head = git_stdout(paths.worktree_dir, ["rev-parse", "HEAD"])
    dirty = bool(git_stdout(paths.worktree_dir, ["status", "--porcelain"]))
    ahead_behind = divergence(paths.worktree_dir, unit.git_hash)
    print(f"branch: {branch}")
    print(f"head: {short(head)}")
    print(f"dirty: {str(dirty).lower()}")
    print(f"divergence-from-installed: {ahead_behind}")
    print(f"next: skill-dev sync {unit.name}")
    return 0


def cmd_sync(args: argparse.Namespace) -> int:
    unit, paths = resolve(args)
    require_worktree(paths)
    cmd = ["skill-manager", "sync", unit.name, "--from", str(paths.worktree_dir), "--merge", "--yes"]
    env = os.environ.copy()
    env["SKILL_MANAGER_HOME"] = str(paths.home)
    try:
        return subprocess.run(cmd, env=env).returncode
    except FileNotFoundError as exc:
        raise SkillDevError("skill-manager is not on PATH; install skill-manager or add it to PATH") from exc


def cmd_git(args: argparse.Namespace) -> int:
    _, paths = resolve(args)
    require_worktree(paths)
    git_args = list(args.git_args)
    if git_args and git_args[0] == "--":
        git_args = git_args[1:]
    if not git_args:
        raise SkillDevError("missing git arguments after --")
    return subprocess.run(["git", *git_args], cwd=paths.worktree_dir).returncode


def cmd_close(args: argparse.Namespace) -> int:
    unit, paths = resolve(args)
    require_worktree(paths)
    if args.merge:
        rc = cmd_sync(args)
        if rc != 0:
            print("sync failed; leaving worktree in place", file=sys.stderr)
            return rc

    porcelain = git_stdout(paths.worktree_dir, ["status", "--porcelain"])
    if porcelain:
        raise SkillDevError(f"{paths.worktree_dir} has uncommitted changes; commit, discard, or use sync conflict resolution first")

    head = git_stdout(paths.worktree_dir, ["rev-parse", "HEAD"])
    installed_head = git_stdout(paths.installed_dir, ["rev-parse", "HEAD"]) if paths.installed_dir.exists() else None
    if head and installed_head and not is_ancestor(paths.worktree_dir, head, installed_head):
        raise SkillDevError("worktree has commits not present in the installed copy; run `skill-dev close "
                            f"{unit.name} --merge` or `skill-dev sync {unit.name}` first")

    run_git(paths.installed_dir, ["worktree", "remove", str(paths.worktree_dir)], check=True)
    print(f"removed {paths.worktree_dir}")
    return 0


def resolve(args: argparse.Namespace) -> tuple[InstalledUnit, UnitPaths]:
    home = (args.home or Path(os.environ.get("SKILL_MANAGER_HOME", "~/.skill-manager"))).expanduser().resolve()
    record = home / "installed" / f"{args.unit}.json"
    if not record.is_file():
        raise SkillDevError(f"installed metadata not found: {record}")

    try:
        raw = json.loads(record.read_text())
    except json.JSONDecodeError as exc:
        raise SkillDevError(f"could not parse {record}: {exc}") from exc

    unit = InstalledUnit(
        name=str(raw.get("name") or args.unit),
        kind=str(raw.get("kind") or "UNKNOWN"),
        unit_kind=str(raw.get("unitKind") or "SKILL"),
        origin=raw.get("origin"),
        git_hash=raw.get("gitHash"),
        git_ref=raw.get("gitRef"),
    )
    if unit.name != args.unit:
        raise SkillDevError(f"metadata name mismatch: requested {args.unit}, record contains {unit.name}")

    kind_dir = {
        "SKILL": "skills",
        "PLUGIN": "plugins",
        "DOC": "docs",
        "HARNESS": "harnesses",
    }.get(unit.unit_kind)
    if kind_dir is None:
        raise SkillDevError(f"unsupported unit kind in metadata: {unit.unit_kind}")
    installed_dir = home / kind_dir / unit.name
    project_root = project_root_for(args.project)
    worktree_root = project_root / "skill-dev"
    return unit, UnitPaths(
        home=home,
        installed_record=record,
        installed_dir=installed_dir,
        project_root=project_root,
        worktree_root=worktree_root,
        worktree_dir=worktree_root / unit.name,
    )


def project_root_for(project: Path | None) -> Path:
    start = (project or Path.cwd()).expanduser().resolve()
    if project and not start.exists():
        raise SkillDevError(f"project path does not exist: {start}")
    result = run(["git", "rev-parse", "--show-toplevel"], cwd=start if start.is_dir() else start.parent)
    if result.returncode != 0:
        raise SkillDevError("current directory is not in a git repository; pass --project with a git-backed project")
    return Path(result.stdout.strip()).resolve()


def require_git_backed(unit: InstalledUnit, paths: UnitPaths) -> None:
    if unit.kind != "GIT":
        raise SkillDevError(f"{unit.name} is not git-backed (kind={unit.kind}); reinstall from a git source")
    require_git_repo(paths.installed_dir, "installed unit")


def require_git_repo(path: Path, label: str) -> None:
    if not path.exists():
        raise SkillDevError(f"{label} path does not exist: {path}")
    result = run(["git", "rev-parse", "--git-dir"], cwd=path)
    if result.returncode != 0:
        raise SkillDevError(f"{label} is not a git repository: {path}")


def require_clean_installed(installed_dir: Path) -> None:
    status = git_stdout(installed_dir, ["status", "--porcelain"])
    if status:
        raise SkillDevError(f"installed unit has local changes: {installed_dir}")


def require_worktree(paths: UnitPaths) -> None:
    if not paths.worktree_dir.exists():
        raise SkillDevError(f"worktree does not exist: {paths.worktree_dir}; run `skill-dev open` first")
    require_git_repo(paths.worktree_dir, "worktree")


def ensure_project_ignore(project_root: Path) -> None:
    ignore = project_root / ".gitignore"
    entry = "skill-dev/"
    if ignore.exists():
        lines = ignore.read_text().splitlines()
        if entry in {line.strip() for line in lines}:
            return
        prefix = "" if not lines or lines[-1] == "" else "\n"
        with ignore.open("a") as fh:
            fh.write(f"{prefix}{entry}\n")
    else:
        ignore.write_text(f"{entry}\n")


def default_branch(unit: str) -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    safe_unit = unit.replace("/", "-")
    return f"skill-dev/{safe_unit}/{stamp}"


def branch_exists(cwd: Path, branch: str) -> bool:
    return run_git(cwd, ["rev-parse", "--verify", "--quiet", f"refs/heads/{branch}"]).returncode == 0


def divergence(cwd: Path, base: str | None) -> str:
    if not base:
        return "unknown"
    result = run_git(cwd, ["rev-list", "--left-right", "--count", f"{base}...HEAD"])
    if result.returncode != 0:
        return "unknown"
    parts = result.stdout.split()
    if len(parts) != 2:
        return "unknown"
    behind, ahead = parts
    return f"ahead {ahead}, behind {behind}"


def is_ancestor(cwd: Path, ancestor: str, descendant: str) -> bool:
    return run_git(cwd, ["merge-base", "--is-ancestor", ancestor, descendant]).returncode == 0


def git_stdout(cwd: Path, args: Sequence[str]) -> str:
    result = run_git(cwd, args)
    return result.stdout.strip() if result.returncode == 0 else ""


def run_git(cwd: Path, args: Sequence[str], *, check: bool = False) -> subprocess.CompletedProcess[str]:
    result = run(["git", *args], cwd=cwd)
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise SkillDevError(f"git {' '.join(args)} failed in {cwd}: {detail}")
    return result


def run(args: Sequence[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, cwd=cwd, text=True, capture_output=True)


def short(value: str | None) -> str:
    if not value:
        return "-"
    return value[:12]


if __name__ == "__main__":
    raise SystemExit(main())
