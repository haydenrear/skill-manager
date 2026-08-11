"""`skt check` — new-version and sync-with-root notifications.

Pull-side (every tier): compare each change-managed unit's installed
gitHash against its remote tip (`git ls-remote`). Push-side (ROOT tier
only): a unit's store checkout that is dirty or ahead of its remote is
work nobody else can see — prompt the publish. Project homes are
updated from `wt`-created imports, so they get pull-side messages only.

`--cached` serves the last result from the state file while it is fresh
(no subprocess, no network) — that is the path SKT-6 wires into
tool-event hooks, so it must stay cheap.

Exit codes: 0 = nothing to report; 10 = notifications exist. Both are
success — 10 exists so hooks can decide to inject without parsing.
"""

from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path

from . import context as ctx_mod
from . import homes

SCHEMA_VERSION = 1
DEFAULT_TTL_SECONDS = 900
NOTIFY_EXIT = 10
REMOTE_TIMEOUT_SECONDS = 10
NETWORK_BUDGET_SECONDS = 15  # total, under the SessionStart hook's 30s / PostToolUse 20s


def _remote_tip(origin: str, ref: str | None) -> str | None:
    proc = subprocess.run(
        ["git", "ls-remote", origin, ref or "HEAD"],
        capture_output=True,
        text=True,
        timeout=REMOTE_TIMEOUT_SECONDS,
    )
    if proc.returncode != 0 or not proc.stdout.strip():
        return None
    return proc.stdout.split()[0]


def _remote_tip_safe(origin: str, ref: str | None) -> str | None:
    """Never raises: a hung or failing remote is 'unverifiable', not a crash.

    This is on the hook path (SKT-6 runs check on tool events) — an
    exception here would surface as a traceback inside an agent session.
    """
    try:
        return _remote_tip(origin, ref)
    except (subprocess.TimeoutExpired, OSError, ValueError):
        return None


def _store_dir(home: Path, unit: homes.Unit) -> Path | None:
    for kind_dir in ("skills", "plugins", "docs", "harnesses"):
        candidate = home / kind_dir / unit.name
        if candidate.is_dir():
            return candidate
    return None


def _local_state(unit_dir: Path) -> str:
    """'clean' | 'dirty' | 'ahead' for a store checkout with its own .git."""
    if not (unit_dir / ".git").exists():
        return "clean"
    proc = subprocess.run(
        ["git", "-C", str(unit_dir), "status", "--porcelain"],
        capture_output=True,
        text=True,
    )
    if proc.stdout.strip():
        return "dirty"
    proc = subprocess.run(
        ["git", "-C", str(unit_dir), "rev-list", "--count", "@{upstream}..HEAD"],
        capture_output=True,
        text=True,
    )
    if proc.returncode == 0 and proc.stdout.strip() and int(proc.stdout.strip()) > 0:
        return "ahead"
    return "clean"


def state_file(home: Path) -> Path:
    return home / "cache" / "skt-check.json"


def collect(start: str | Path = ".", *, use_network: bool = True) -> dict:
    home = homes.find_home(start)
    if home is None:
        return {"schema": SCHEMA_VERSION, "home": None, "error": "no skill-manager home found"}
    tier = ctx_mod.classify_tier(home, ctx_mod.checkout_root(start))
    notifications: list[dict] = []
    checked: list[str] = []
    unverifiable: list[str] = []
    managed = [u for u in homes.read_units(home) if u.change_managed]
    tips: dict[str, str | None] = {}
    if use_network and managed:
        # Parallel: a real root home has ~20 git-backed units, and serial
        # ls-remote at up to REMOTE_TIMEOUT_SECONDS each would block an
        # agent session for minutes on the first post-TTL hook call.
        from concurrent.futures import ThreadPoolExecutor

        import time as _time

        deadline = _time.monotonic() + NETWORK_BUDGET_SECONDS
        with ThreadPoolExecutor(max_workers=8) as pool:
            futures = {
                u.name: pool.submit(_remote_tip_safe, u.origin, u.git_ref) for u in managed
            }
            for name, future in futures.items():
                remaining = deadline - _time.monotonic()
                try:
                    tips[name] = future.result(timeout=max(0.1, remaining))
                except Exception:  # budget exhausted -> unverifiable, not late
                    tips[name] = None
    for unit in managed:
        checked.append(unit.name)
        if use_network:
            tip = tips.get(unit.name)
            if tip is None:
                unverifiable.append(unit.name)
            elif tip != unit.git_hash:
                # Compared against the origin's default-branch tip: installed
                # records carry no ref pin, so a deliberately pinned unit can
                # appear here — the message states what was compared.
                notifications.append(
                    {
                        "kind": "new-version",
                        "unit": unit.name,
                        "installed": (unit.git_hash or "")[:8],
                        "remote": tip[:8],
                        "message": f"new version available for {unit.name} — pull with: skt sync {unit.name}",
                    }
                )
        if tier == "root":
            unit_dir = _store_dir(home, unit)
            if unit_dir:
                state = _local_state(unit_dir)
                if state != "clean":
                    notifications.append(
                        {
                            "kind": "sync-with-root",
                            "unit": unit.name,
                            "state": state,
                            "message": (
                                f"{unit.name} modified locally ({state}) — please sync with root "
                                f"to publish changes globally: skt publish {unit.name}"
                            ),
                        }
                    )
    report = {
        "schema": SCHEMA_VERSION,
        "home": str(home),
        "tier": tier,
        "checked_units": checked,
        "unverifiable": unverifiable,
        "network": use_network,
        "checked_at": time.time(),
        "notifications": notifications,
    }
    if len([n for n in notifications if n["kind"] == "new-version"]) > 1:
        report["hint"] = (
            "multiple units are stale — sync in skill-imports dependency order "
            "(a unit importing files another stale unit adds fails validation if synced first)"
        )
    return report


def _write_cache(report: dict) -> None:
    if not report.get("home"):
        return
    path = state_file(Path(report["home"]))
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report))
    except OSError:
        pass


def _read_cache(home: Path, ttl: int) -> dict | None:
    path = state_file(home)
    try:
        data = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return None
    if time.time() - data.get("checked_at", 0) > ttl:
        return None
    data["from_cache"] = True
    return data


def render_text(report: dict) -> str:
    if report.get("home") is None:
        return f"skt check: {report['error']}"
    notes = report["notifications"]
    unverifiable = report.get("unverifiable") or []
    if not notes:
        scope = f"{len(report['checked_units'])} change-managed unit(s)"
        line = f"skt check: all current ({scope}, tier {report['tier']})"
        if unverifiable:
            line += f"; unverifiable: {', '.join(unverifiable)}"
        return line
    lines = [f"skt check: {len(notes)} notification(s), tier {report['tier']}"]
    lines += [f"  {n['message']}" for n in notes]
    if unverifiable:
        lines.append(f"  unverifiable (remote unreachable): {', '.join(unverifiable)}")
    if report.get("hint"):
        lines.append(f"  hint: {report['hint']}")
    return "\n".join(lines)


def run(*, as_json: bool, cached: bool, ttl: int = DEFAULT_TTL_SECONDS, start: str | Path = ".") -> int:
    """Hook-safe: this function must never raise (SKT-6 wires it into sessions)."""
    try:
        report = None
        if cached:
            home = homes.find_home(start)
            if home is not None:
                report = _read_cache(home, ttl)
        if report is None:
            report = collect(start)
            _write_cache(report)
    except Exception as exc:  # noqa: BLE001 — a hook traceback inside an agent session is worse
        print(f"skt check: internal error ({type(exc).__name__}: {exc})")
        return 1
    print(json.dumps(report, indent=2) if as_json else render_text(report))
    if not report.get("home"):
        return 1
    return NOTIFY_EXIT if report["notifications"] else 0
