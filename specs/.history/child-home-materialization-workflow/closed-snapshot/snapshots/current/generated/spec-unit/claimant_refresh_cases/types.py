from __future__ import annotations

from dataclasses import dataclass
from dataclasses import field
from typing import Any


class Unchecked:
    """An action argument this corpus could not recover from the state pair.

    MF-029. It is NOT None, "" or 0 -- those are values a model could
    legitimately produce, so an adapter comparing against one could pass by
    coincidence. UNCHECKED equals only itself, so any check expecting a
    concrete argument fails against it instead of passing vacuously.

    A case carrying UNCHECKED is still a real case and is never dropped: the
    sentinel marks the argument, it does not disqualify the transition.
    """

    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __repr__(self) -> str:
        return "UNCHECKED"

    def __bool__(self) -> bool:
        return False

    def __eq__(self, other: Any) -> bool:
        return other is self

    def __ne__(self, other: Any) -> bool:
        return other is not self

    def __hash__(self) -> int:
        return hash("tla-spec-dev.UNCHECKED")


UNCHECKED = Unchecked()


@dataclass(frozen=True)
class StateGraphInput:
    action: str
    source_node: str
    target_node: str
    params: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ActionMetadata:
    layer: str = "internal"
    controllability: str = "unit_direct"
    generates: frozenset[str] = frozenset(("spec_unit",))
    tags: frozenset[str] = frozenset()


@dataclass(frozen=True)
class StateGraphOutput:
    changed: dict[str, dict[str, Any]]


@dataclass(frozen=True)
class StateGraphRejection:
    """The expected outcome of a call the model does not enable (HP-03).

    Emitted by the negative corpus: at this before-state the action's own
    body evaluates to a definite FALSE, so the program must REFUSE the
    call. ``reason`` is the violated conjunct copied verbatim out of the
    module -- it is the model's words, not a classification invented here,
    so an adapter comparing rejection reasons is comparing against the
    specification rather than against this generator.

    ``changed`` is always empty and is spelled out rather than implied: a
    refused call changes no modeled variable, which is the second half of
    the assertion and the half a status-only oracle would miss.
    """

    action: str
    params: dict[str, Any]
    reason: str
    changed: dict[str, dict[str, Any]] = field(default_factory=dict)
    #: Variables that record the OUTCOME of a call rather than the state it
    #: left behind, derived from the model: the write set of every action
    #: this module uses to spell a refusal out. A real program's refusal
    #: does change these -- it reports that it refused -- so an adapter must
    #: report them unobservable, and `after == before` is asserted over
    #: everything else. When a model declares no refusal actions this tuple
    #: is empty and full inertness is asserted.
    outcome_fields: tuple[str, ...] = ()


@dataclass(frozen=True)
class StateGraphCase:
    name: str
    before: dict[str, Any]
    input: StateGraphInput
    output: Any
    after: dict[str, Any]
    labels: frozenset[str]
    schema_version: str = 'tla-testgraph.trace.v1'
    view: str = "internal"
    layer: str = "internal"
    controllability: str = "unit_direct"
    generates: frozenset[str] = frozenset(("spec_unit",))
    tags: frozenset[str] = frozenset()
    metadata: ActionMetadata = field(default_factory=ActionMetadata)
