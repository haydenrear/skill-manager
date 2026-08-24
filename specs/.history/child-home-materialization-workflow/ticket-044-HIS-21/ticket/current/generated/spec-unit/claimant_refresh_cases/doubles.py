from __future__ import annotations

from typing import Any

from .types import StateGraphCase, StateGraphInput


class ScriptedTransitionDouble:
    def __init__(self, case: StateGraphCase):
        self.case = case
        self._state = case.before
        self._called = False

    def snapshot(self):
        return self._state

    def input(self) -> StateGraphInput:
        return self.case.input

    def call(self, value: StateGraphInput) -> Any:
        if value != self.case.input:
            raise AssertionError(f"unexpected input for {self.case.name}: {value!r}")
        if self._called:
            raise AssertionError(f"case already consumed: {self.case.name}")
        self._called = True
        self._state = self.case.after
        return self.case.output
