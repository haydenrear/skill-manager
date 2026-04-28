"""Decorator that wires a Python function into the validation graph.

Mirrors the Java :class:`Node` runner. The same script handles two modes:

- ``--describe-out=<path>`` : serialize the spec JSON to ``<path>`` and
  exit 0 (no body, no context).
- ``--result-out=<path>``  : parse :class:`NodeContext`, invoke the
  body, write the resulting :class:`NodeResult` JSON to ``<path>``, and
  exit 0.

The envelope under ``reportDir/envelope/`` is no longer this script's
responsibility — the build-logic ``PlanExecutor`` post-processes the
result-out file into the canonical envelope, stamping executor-
measured timing, recording the captured-stdout-log path, and
synthesizing a fallback envelope when this script never gets to write
its result. Same shape as the Java SDK, by design.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .context import NodeContext
from .node_spec import NodeSpec
from .result import NodeResult


def node(spec: NodeSpec) -> Callable[[Callable[[NodeContext], NodeResult]], Callable[[], None]]:
    def decorate(body: Callable[[NodeContext], NodeResult]) -> Callable[[], None]:
        def wrapper() -> None:
            describe_out = _find_arg(sys.argv[1:], "--describe-out=")
            if describe_out is not None:
                out = Path(describe_out)
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_text(spec.to_json())
                sys.exit(0)

            ctx = NodeContext.parse()
            if ctx.node_id != spec.id:
                raise RuntimeError(
                    f"spec/runtime id mismatch: spec={spec.id!r}, arg={ctx.node_id!r}"
                )

            started = datetime.now(timezone.utc)
            try:
                result = body(ctx)
            except BaseException as exc:
                result = NodeResult.error(spec.id, exc)
            ended = datetime.now(timezone.utc)
            result._stamp(started, ended)

            result_out = _find_arg(sys.argv[1:], "--result-out=")
            if result_out is not None:
                _write_result_out(result_out, result)
            # Exit 0 regardless of status: the executor decides pass/fail
            # from the parsed envelope's status field. Mirrors the Java
            # SDK's policy.
            sys.exit(0)

        wrapper.__wrapped__ = body  # type: ignore[attr-defined]
        return wrapper

    return decorate


def _write_result_out(path: str, result: NodeResult) -> None:
    try:
        out = Path(path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result.to_dict(), indent=2))
    except OSError as exc:
        # The executor detects a missing / empty result-out and
        # synthesizes an error envelope, so a write failure here
        # downgrades the run to a synthesized envelope — never loses it.
        sys.stderr.write(f"failed to write --result-out: {exc}\n")


def _find_arg(argv: list[str], prefix: str) -> str | None:
    for a in argv:
        if a.startswith(prefix):
            return a[len(prefix):]
    return None
