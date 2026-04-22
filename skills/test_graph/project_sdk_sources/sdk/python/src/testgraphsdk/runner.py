from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .context import NodeContext
from .node_spec import NodeSpec
from .result import NodeResult, NodeStatus


def node(spec: NodeSpec) -> Callable[[Callable[[NodeContext], NodeResult]], Callable[[], None]]:
    """Decorator that wires a Python function into the validation graph.

    The decorated function must accept a NodeContext and return a NodeResult.
    The same script handles two modes:

    - ``--describe-out=<path>`` : serialize the spec JSON to <path> and exit
      (no context, no body execution).
    - full context args         : parse NodeContext, invoke body, write envelope.
    """

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

            envelope_dir = ctx.report_dir / "envelope"
            envelope_dir.mkdir(parents=True, exist_ok=True)
            (envelope_dir / f"{spec.id}.json").write_text(
                json.dumps(result.to_dict(), indent=2)
            )

            sys.exit(0 if result.status == NodeStatus.PASSED else 1)

        wrapper.__wrapped__ = body  # type: ignore[attr-defined]
        return wrapper

    return decorate


def _find_arg(argv: list[str], prefix: str) -> str | None:
    for a in argv:
        if a.startswith(prefix):
            return a[len(prefix):]
    return None
