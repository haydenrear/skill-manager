"""`skt check` — new-version and sync-with-root notifications.

Pull-side (every tier): compare each change-managed unit's installed
gitHash against its remote tip (`git ls-remote`). Push-side (ROOT tier
only): a unit's store checkout that is dirty or ahead of its remote is
work nobody else can see — prompt the publish. Project homes are
updated from `wt`-created imports, so they get pull-side messages only.

`--cached` is contract-cache-only: it reads the state file and NOTHING
else — no subprocess, no network, no fallback to a live check, whatever
state the cache is in. That is the path SKT-6 wires into tool-event
hooks, so a cold home must stay exactly as cheap as a warm one. The
report carries a typed `cache_state`:

  fresh    -> the cached result verbatim, plus `from_cache` metadata
  missing  -> no cache record exists; nothing was checked
  expired  -> a record exists but is past --ttl; its content is
              preserved under `stale` (text output labels each line
              [stale]) and is never presented as current

Exit codes: 0 = nothing to report; 10 = notifications exist. Both are
success — 10 exists so hooks can decide to inject without parsing.
`cache_state` missing/expired is the documented non-error outcome: exit
0 with empty top-level `notifications` (an absent result is "nothing to
report", and stale notifications must not re-fire as exit 10 on every
tool call). Refreshing is the live path's job — `skt check`, or the
SessionStart hook's bounded refresh — never the hook tool-event path's.

The live path owns one wall-clock deadline (NETWORK_BUDGET_SECONDS),
shared by the remote and root-local phases: every git child runs in its
own process group and the whole group is SIGKILLed and reaped when its
clamped timeout fires, queued executor work is cancelled at the
deadline instead of awaited, and units left unresolved are reported
`unverifiable`. A refresh that resolved nothing writes no cache record,
and the record it does write lands by atomic rename — `--cached` must
never serve a failure, or a partial write, as a fresh success.
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import time
from pathlib import Path

from . import context as ctx_mod
from . import homes

SCHEMA_VERSION = 1
DEFAULT_TTL_SECONDS = 900
NOTIFY_EXIT = 10
REMOTE_TIMEOUT_SECONDS = 10
NETWORK_BUDGET_SECONDS = 15  # total live wall budget (remote + root-local), under the SessionStart hook's 30s
LOCAL_TIMEOUT_SECONDS = 2  # per root-local git call — a hung `status` must not eat the whole budget

CACHE_FRESH = "fresh"
CACHE_MISSING = "missing"
CACHE_EXPIRED = "expired"


def _run_git(argv: list[str], timeout: float) -> subprocess.CompletedProcess | None:
    """Bounded git call; None on deadline. Kills the child's WHOLE group.

    `subprocess.run(timeout=)` kills only the direct child; git spawns
    helpers (ssh, credential fillers) that inherit the pipes and keep
    the caller open past its budget. Own session + killpg reaps the lot.
    """
    if timeout <= 0:
        return None  # budget already spent: do not spawn at all
    proc = subprocess.Popen(
        argv,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    try:
        out, err = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except OSError:  # group already gone or unkillable: fall back to the child
            proc.kill()
        try:
            proc.communicate(timeout=5)  # reap — SIGKILL cannot be blocked, so this returns
        except (subprocess.TimeoutExpired, OSError):
            pass
        return None
    return subprocess.CompletedProcess(argv, proc.returncode, out, err)


def _remote_tip(origin: str, ref: str | None, *, deadline: float | None = None) -> str | None:
    timeout = float(REMOTE_TIMEOUT_SECONDS)
    if deadline is not None:
        # Clamp to the shared deadline: a worker that starts late must not
        # extend the command past it, and one that starts after it spawns
        # nothing at all (_run_git refuses timeout <= 0).
        timeout = min(timeout, deadline - time.monotonic())
    proc = _run_git(["git", "ls-remote", origin, ref or "HEAD"], timeout)
    if proc is None or proc.returncode != 0 or not proc.stdout.strip():
        return None
    return proc.stdout.split()[0]


def _remote_tip_safe(origin: str, ref: str | None, *, deadline: float | None = None) -> str | None:
    """Never raises: a hung or failing remote is 'unverifiable', not a crash.

    This is on the hook path (SKT-6 runs check on tool events) — an
    exception here would surface as a traceback inside an agent session.
    """
    try:
        return _remote_tip(origin, ref, deadline=deadline)
    except (subprocess.TimeoutExpired, OSError, ValueError):
        return None


def _store_dir(home: Path, unit: homes.Unit) -> Path | None:
    for kind_dir in ("skills", "plugins", "docs", "harnesses"):
        candidate = home / kind_dir / unit.name
        if candidate.is_dir():
            return candidate
    return None


def _local_state(unit_dir: Path, *, deadline: float | None = None) -> str:
    """'clean' | 'dirty' | 'ahead' | 'unknown' for a store checkout.

    Both probes are bounded (per-call cap AND the shared deadline). A
    probe that timed out reports 'unknown', never 'clean': a publish
    prompt must not be fabricated from a check that did not finish, but
    a record that presents unpushed work as verified-clean for a whole
    TTL is the same lie in the other direction. The caller labels
    'unknown' as unverifiable, which also keeps an all-timed-out
    refresh out of the cache.
    """
    if not (unit_dir / ".git").exists():
        return "clean"

    def _timeout() -> float:
        if deadline is None:
            return float(LOCAL_TIMEOUT_SECONDS)
        return min(float(LOCAL_TIMEOUT_SECONDS), deadline - time.monotonic())

    proc = _run_git(["git", "-C", str(unit_dir), "status", "--porcelain"], _timeout())
    if proc is None:
        return "unknown"
    if proc.stdout.strip():
        return "dirty"
    proc = _run_git(
        ["git", "-C", str(unit_dir), "rev-list", "--count", "@{upstream}..HEAD"], _timeout()
    )
    if proc is None:
        return "unknown"
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
    # One deadline for the whole command: both git phases clamp every
    # subprocess to it, so the command's wall time is bounded by the
    # budget plus kill/reap slack — never by how many children hung.
    deadline = time.monotonic() + NETWORK_BUDGET_SECONDS
    if use_network and managed:
        # Parallel: a real root home has ~20 git-backed units, and serial
        # ls-remote at up to REMOTE_TIMEOUT_SECONDS each would block an
        # explicit refresh for minutes.
        from concurrent.futures import ThreadPoolExecutor

        pool = ThreadPoolExecutor(max_workers=8)
        try:
            futures = {
                u.name: pool.submit(_remote_tip_safe, u.origin, u.git_ref, deadline=deadline)
                for u in managed
            }
            for name, future in futures.items():
                remaining = deadline - time.monotonic()
                try:
                    tips[name] = future.result(timeout=max(0.05, remaining))
                except Exception:  # budget exhausted -> unverifiable, not late
                    tips[name] = None
        finally:
            # Deliberately NOT `with`: __exit__ waits for queued/running
            # calls, which is the ceil(units/8) * REMOTE_TIMEOUT overrun
            # this budget exists to prevent. Cancel what never started and
            # abandon the runners — their subprocess timeouts are clamped
            # to this same deadline, so nothing they hold outlives it.
            pool.shutdown(wait=False, cancel_futures=True)
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
                state = _local_state(unit_dir, deadline=deadline)
                if state == "unknown":
                    # The probe never finished: an evidence gap, not a
                    # verdict. Labeling it keeps the cached record honest
                    # and the refusal predicate counting it.
                    if unit.name not in unverifiable:
                        unverifiable.append(unit.name)
                elif state != "clean":
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
    checked = report.get("checked_units") or []
    unverifiable = report.get("unverifiable") or []
    if report.get("network") and checked and len(unverifiable) >= len(checked):
        # A refresh that resolved NOTHING is a failure, not a result:
        # caching it would let --cached serve "all current (fresh)" for a
        # TTL window in which no unit was actually verified.
        return
    path = state_file(Path(report["home"]))
    # Write-temp + rename in the same directory: os.replace is atomic, so
    # a reader (every PostToolUse) sees the old record or the new one,
    # never a truncated half — and a crash mid-write publishes nothing.
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp.write_text(json.dumps(report))
        os.replace(tmp, path)
    except OSError:
        try:
            tmp.unlink(missing_ok=True)
        except OSError:
            pass


def _load_cache(home: Path) -> dict | None:
    try:
        return json.loads(state_file(home).read_text())
    except (OSError, json.JSONDecodeError):
        return None


def cached_report(home: Path, ttl: int) -> dict:
    """The --cached result: state file in, report out, NO other I/O.

    Never calls collect()/_remote_tip/_local_state, never spawns a
    subprocess, never writes — a missing or expired cache is REPORTED,
    not repaired, or every cold home's PostToolUse would become the live
    check this function exists to avoid.
    """
    raw = _load_cache(home)
    if raw is None:
        return {
            "schema": SCHEMA_VERSION,
            "home": str(home),
            "cache_state": CACHE_MISSING,
            "from_cache": True,
            "checked_units": [],
            "unverifiable": [],
            "notifications": [],
        }
    if time.time() - raw.get("checked_at", 0) > ttl:
        # Stale content rides under `stale`, never at the top level: the
        # exit code stays 0 and hook injection cannot present it as
        # current — it is shown only where render_text labels it [stale].
        return {
            "schema": SCHEMA_VERSION,
            "home": str(home),
            "cache_state": CACHE_EXPIRED,
            "from_cache": True,
            "checked_units": [],
            "unverifiable": [],
            "notifications": [],
            "stale": raw,
        }
    raw["from_cache"] = True
    raw["cache_state"] = CACHE_FRESH
    return raw


def render_text(report: dict) -> str:
    if report.get("home") is None:
        return f"skt check: {report['error']}"
    state = report.get("cache_state")
    if state == CACHE_MISSING:
        return "skt check: no cached result (cache missing) — refresh with: skt check"
    if state == CACHE_EXPIRED:
        stale = report.get("stale") or {}
        age = int(time.time() - stale.get("checked_at", 0))
        lines = [f"skt check: cached result is {age}s old (expired) — refresh with: skt check"]
        lines += [f"  [stale] {n['message']}" for n in stale.get("notifications", [])]
        return "\n".join(lines)
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
        if cached:
            home = homes.find_home(start)
            if home is None:
                report = {"schema": SCHEMA_VERSION, "home": None, "error": "no skill-manager home found"}
            else:
                report = cached_report(home, ttl)
        else:
            report = collect(start)
            _write_cache(report)
    except Exception as exc:  # noqa: BLE001 — a hook traceback inside an agent session is worse
        print(f"skt check: internal error ({type(exc).__name__}: {exc})")
        return 1
    print(json.dumps(report, indent=2) if as_json else render_text(report))
    if not report.get("home"):
        return 1
    return NOTIFY_EXIT if report["notifications"] else 0
