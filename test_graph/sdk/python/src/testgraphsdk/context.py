from __future__ import annotations

import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

from .context_item import ContextItem, read as _read_context


@dataclass(frozen=True)
class NodeContext:
    """Context passed by the Gradle plugin to a node invocation.

    CLI contract (matches the Java SDK):
      --nodeId=<id>         currently executing node
      --runId=<id>          overall graph run id
      --reportDir=<path>    where to write envelopes/artifacts
      --input.<k>=<v>       typed inputs (may repeat)
      --context=<value>     upstream Context[] — inline JSON or '@<path>'
    """

    node_id: str
    run_id: str
    report_dir: Path
    inputs: dict[str, str] = field(default_factory=dict)
    context: list[ContextItem] = field(default_factory=list)

    @classmethod
    def parse(cls, argv: list[str] | None = None) -> "NodeContext":
        argv = argv if argv is not None else sys.argv[1:]
        node_id: str | None = None
        run_id: str = "local"
        report_dir: Path | None = None
        inputs: dict[str, str] = {}
        context_arg: str | None = None
        for raw in argv:
            if not raw.startswith("--") or "=" not in raw:
                continue
            key, _, value = raw[2:].partition("=")
            if key == "nodeId":
                node_id = value
            elif key == "runId":
                run_id = value
            elif key == "reportDir":
                report_dir = Path(value)
            elif key == "context":
                context_arg = value
            elif key.startswith("input."):
                inputs[key[len("input."):]] = value
        if node_id is None or report_dir is None:
            raise RuntimeError(
                "node context missing required --nodeId / --reportDir"
            )
        return cls(
            node_id=node_id,
            run_id=run_id,
            report_dir=report_dir,
            inputs=inputs,
            context=_read_context(context_arg),
        )

    def input(self, key: str) -> str | None:
        return self.inputs.get(key)

    def get(self, upstream_node_id: str, key: str) -> str | None:
        """Look up a single value from an upstream node's published data."""
        for it in self.context:
            if it.node_id == upstream_node_id:
                return it.data.get(key)
        return None

    def item(self, upstream_node_id: str) -> ContextItem | None:
        """Look up an upstream node's whole ContextItem."""
        for it in self.context:
            if it.node_id == upstream_node_id:
                return it
        return None

    def upstream(self, node_id: str) -> dict | None:
        """Load a dependency's full envelope JSON if it exists."""
        path = self.report_dir / "envelope" / f"{node_id}.json"
        if not path.exists():
            return None
        return json.loads(path.read_text())
