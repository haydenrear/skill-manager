"""Subprocess helpers that mirror the Java SDK's :class:`Procs`.

Run a child process, capture its stdout+stderr to a per-(node, label)
log file under ``<reportDir>/node-logs/<nodeId>.<label>.log``, and
return a fully-populated :class:`ProcessRecord`. Attach it to the
result with :meth:`NodeResult.process` so the executor and report
renderer can show per-subprocess details without scraping stdout.

Never raises on spawn failure: if :func:`subprocess.Popen` blows up
(binary not found, OSError), we still return a record with
``pid=None``, ``exit_code=-1``, and ``error`` populated. The node
body's pass/fail logic stays uniform — it never has to decide
between "exception escaped" and "child exited non-zero".
"""
from __future__ import annotations

import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from .context import NodeContext
from .result import ProcessRecord


def log_file(ctx: NodeContext, label: str) -> Path:
    """Resolve (and create the parent dir of) the log path for one
    subprocess within the current node's execution.

    Mirrors the Java helper: callers pass a short label (e.g.
    ``"publish"``), the on-disk name is ``<nodeId>.<label>.log`` so a
    flat ``grep -r`` traces logs back to their origin node.
    """
    dir_ = ctx.report_dir / "node-logs"
    dir_.mkdir(parents=True, exist_ok=True)
    return dir_ / f"{ctx.node_id}.{label}.log"


def run(
    ctx: NodeContext,
    label: str,
    argv: list[str],
    *,
    cwd: str | os.PathLike[str] | None = None,
    env: dict[str, str] | None = None,
) -> ProcessRecord:
    """Spawn ``argv`` with stdout+stderr captured to :func:`log_file`,
    wait for it, return a fully-populated :class:`ProcessRecord`.

    ``env`` is the full environment for the child (use
    ``{**os.environ, "FOO": "bar"}`` to extend the inherited env).
    Missing ``env`` inherits the parent's environment, matching how
    the Java helper behaves with :class:`ProcessBuilder`.
    """
    try:
        log = log_file(ctx, label)
    except OSError as e:
        return ProcessRecord(
            label=label,
            command=list(argv),
            error=f"could not allocate log file: {e}",
        )
    log_str = _relative_to_report(ctx, log)

    started = datetime.now(timezone.utc)
    try:
        # Match Java's redirectErrorStream(true).redirectOutput(log) by
        # opening the log file once and pointing both streams at it.
        with open(log, "wb") as fh:
            proc = subprocess.Popen(
                argv,
                stdout=fh,
                stderr=subprocess.STDOUT,
                cwd=cwd,
                env=env,
            )
            pid = proc.pid
            try:
                exit_code = proc.wait()
            except KeyboardInterrupt:
                proc.terminate()
                return ProcessRecord(
                    label=label,
                    command=list(argv),
                    started_at=started,
                    ended_at=datetime.now(timezone.utc),
                    exit_code=-1,
                    pid=pid,
                    log_path=log_str,
                    error="interrupted",
                )
    except (OSError, FileNotFoundError) as e:
        return ProcessRecord(
            label=label,
            command=list(argv),
            started_at=started,
            ended_at=datetime.now(timezone.utc),
            exit_code=-1,
            pid=None,
            log_path=log_str,
            error=f"spawn failed: {e}",
        )

    return ProcessRecord(
        label=label,
        command=list(argv),
        started_at=started,
        ended_at=datetime.now(timezone.utc),
        exit_code=exit_code,
        pid=pid,
        log_path=log_str,
        error=None,
    )


def _relative_to_report(ctx: NodeContext, log_path: Path) -> str:
    """Render ``log_path`` relative to ``ctx.report_dir`` when possible,
    falling back to the absolute string. Keeps envelope JSON portable
    across machines without a manual rewrite in the executor.
    """
    try:
        return str(log_path.resolve().relative_to(ctx.report_dir.resolve()))
    except ValueError:
        return str(log_path)
