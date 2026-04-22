"""ContextItem: one upstream node's published data."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class ContextItem:
    """One upstream node's contribution to the Context[] a downstream node sees.

    A node's ``data`` is the map it published via ``NodeResult.publish(k, v)``.
    Ordering in the parent list reflects plan execution order.
    """

    node_id: str
    data: dict[str, str] = field(default_factory=dict)

    def get(self, key: str) -> str | None:
        return self.data.get(key)


def dumps(items: list[ContextItem]) -> str:
    """Serialize a Context[] to the wire JSON shape."""
    return json.dumps(
        {"items": [{"nodeId": it.node_id, "data": dict(it.data)} for it in items]},
        separators=(",", ":"),
    )


def read(arg_value: str | None) -> list[ContextItem]:
    """Parse a --context CLI value (inline JSON or '@<path>') into a Context[]."""
    if not arg_value:
        return []
    raw = Path(arg_value[1:]).read_text() if arg_value.startswith("@") else arg_value
    payload = json.loads(raw)
    return [
        ContextItem(node_id=it["nodeId"], data=dict(it.get("data", {})))
        for it in payload.get("items", [])
    ]
