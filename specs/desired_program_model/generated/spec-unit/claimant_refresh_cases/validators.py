from __future__ import annotations

from .types import StateGraphCase, StateGraphRejection


def assert_rejection_is_inert(case: StateGraphCase) -> None:
    """A negative case must assert refusal AND that nothing moved.

    Checking only the status would let a program that refuses the call and
    still mutates state pass, which is half a guard.
    """
    if not isinstance(case.output, StateGraphRejection):
        return
    assert case.after == case.before, (
        f"negative case {case.name} is not inert: a refused call changed state"
    )


def assert_case_replays(case: StateGraphCase) -> None:
    changed = {
        field: {"before": case.before.get(field), "after": case.after.get(field)}
        for field in sorted(set(case.before) | set(case.after))
        if case.before.get(field) != case.after.get(field)
    }
    if hasattr(case.output, "changed"):
        assert case.output.changed == changed
