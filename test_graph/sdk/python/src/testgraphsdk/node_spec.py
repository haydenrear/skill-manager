"""NodeSpec — self-declared node metadata."""
from __future__ import annotations

import json
from dataclasses import dataclass, field


VALID_KINDS = {"testbed", "fixture", "action", "assertion", "evidence", "report"}


@dataclass
class NodeSpec:
    """Metadata a Python node declares about itself.

    The plugin invokes the script with ``--describe-out=<path>``; the SDK
    writes this spec as JSON to that path so the graph can be discovered
    without running the body. Runtime is always ``"uv"`` for this SDK.
    """

    id: str
    _kind: str = "action"
    _depends_on: list[str] = field(default_factory=list)
    _tags: list[str] = field(default_factory=list)
    _timeout: str = "60s"
    _cacheable: bool = False
    _side_effects: list[str] = field(default_factory=list)
    _inputs: dict[str, str] = field(default_factory=dict)
    _outputs: dict[str, str] = field(default_factory=dict)
    _junit_xml: bool = False
    _cucumber: bool = False

    def kind(self, k: str) -> "NodeSpec":
        if k not in VALID_KINDS:
            raise ValueError(f"invalid kind '{k}'; expected one of {sorted(VALID_KINDS)}")
        self._kind = k
        return self

    def depends_on(self, *ids: str) -> "NodeSpec":
        self._depends_on.extend(ids)
        return self

    def tags(self, *t: str) -> "NodeSpec":
        self._tags.extend(t)
        return self

    def timeout(self, v: str) -> "NodeSpec":
        self._timeout = v
        return self

    def cacheable(self, b: bool = True) -> "NodeSpec":
        self._cacheable = b
        return self

    def side_effects(self, *s: str) -> "NodeSpec":
        self._side_effects.extend(s)
        return self

    def input(self, name: str, type_: str = "string") -> "NodeSpec":
        self._inputs[name] = type_
        return self

    def output(self, name: str, type_: str = "string") -> "NodeSpec":
        self._outputs[name] = type_
        return self

    def junit_xml(self) -> "NodeSpec":
        self._junit_xml = True
        return self

    def cucumber(self) -> "NodeSpec":
        self._cucumber = True
        return self

    def to_json(self) -> str:
        return json.dumps(
            {
                "id": self.id,
                "kind": self._kind,
                "runtime": "uv",
                "dependsOn": list(self._depends_on),
                "tags": list(self._tags),
                "timeout": self._timeout,
                "cacheable": self._cacheable,
                "sideEffects": list(self._side_effects),
                "inputs": dict(self._inputs),
                "outputs": dict(self._outputs),
                "reports": {
                    "structuredJson": True,
                    "junitXml": self._junit_xml,
                    "cucumber": self._cucumber,
                },
            },
            separators=(",", ":"),
        )
