from __future__ import annotations

import traceback
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum

from .context_item import ContextItem


class NodeStatus(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    ERRORED = "errored"
    SKIPPED = "skipped"


@dataclass
class _Assertion:
    name: str
    status: NodeStatus


@dataclass
class _Artifact:
    type: str
    path: str


@dataclass
class NodeResult:
    """Per-node reporting envelope.

    Reporting fields (status, assertions, artifacts, metrics, logs) feed the
    aggregator. The ``published`` map is this node's contribution to the
    downstream Context[] — readable via ``ctx.get(node_id, key)``.
    """

    node_id: str
    status: NodeStatus = NodeStatus.PASSED
    failure_message: str | None = None
    error_stack: str | None = None
    started_at: datetime | None = None
    ended_at: datetime | None = None
    assertions: list[_Assertion] = field(default_factory=list)
    artifacts: list[_Artifact] = field(default_factory=list)
    metrics: dict[str, float | int] = field(default_factory=dict)
    logs: list[str] = field(default_factory=list)
    published: dict[str, str] = field(default_factory=dict)

    @classmethod
    def pass_(cls, node_id: str) -> "NodeResult":
        return cls(node_id=node_id, status=NodeStatus.PASSED)

    @classmethod
    def fail(cls, node_id: str, message: str) -> "NodeResult":
        return cls(node_id=node_id, status=NodeStatus.FAILED, failure_message=message)

    @classmethod
    def error(cls, node_id: str, exc: BaseException) -> "NodeResult":
        return cls(
            node_id=node_id,
            status=NodeStatus.ERRORED,
            failure_message=str(exc),
            error_stack="".join(traceback.format_exception(exc)),
        )

    def assertion(self, name: str, ok: bool) -> "NodeResult":
        self.assertions.append(
            _Assertion(name=name, status=NodeStatus.PASSED if ok else NodeStatus.FAILED)
        )
        if not ok and self.status == NodeStatus.PASSED:
            self.status = NodeStatus.FAILED
        return self

    def artifact(self, kind: str, path: str) -> "NodeResult":
        self.artifacts.append(_Artifact(type=kind, path=path))
        return self

    def metric(self, name: str, value: float | int) -> "NodeResult":
        self.metrics[name] = value
        return self

    def log(self, line: str) -> "NodeResult":
        self.logs.append(line)
        return self

    def publish(self, key: str, value: str) -> "NodeResult":
        """Publish a value so downstream nodes can read it via ctx.get(...)."""
        self.published[key] = value
        return self

    def to_context_item(self) -> ContextItem:
        return ContextItem(node_id=self.node_id, data=dict(self.published))

    def _stamp(self, started: datetime, ended: datetime) -> "NodeResult":
        self.started_at = started
        self.ended_at = ended
        self.metrics.setdefault(
            "durationMs", int((ended - started).total_seconds() * 1000)
        )
        return self

    def to_dict(self) -> dict:
        def iso(d: datetime | None) -> str | None:
            return d.astimezone(timezone.utc).isoformat().replace("+00:00", "Z") if d else None

        payload: dict = {
            "nodeId": self.node_id,
            "status": self.status.value,
            "startedAt": iso(self.started_at),
            "endedAt": iso(self.ended_at),
            "assertions": [
                {"name": a.name, "status": a.status.value} for a in self.assertions
            ],
            "artifacts": [{"type": a.type, "path": a.path} for a in self.artifacts],
            "metrics": dict(self.metrics),
            "logs": list(self.logs),
            "published": dict(self.published),
        }
        if self.failure_message is not None:
            payload["failureMessage"] = self.failure_message
        if self.error_stack is not None:
            payload["errorStack"] = self.error_stack
        return {k: v for k, v in payload.items() if v is not None}
