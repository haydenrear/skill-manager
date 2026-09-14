"""Ticket T-4 desired-view adapters."""


class CreateAccountInternalAdapter:
    def apply(self, state, params):
        return state


class CheckoutInternalAdapter:
    def apply(self, state, params):
        return state


class RefundInternalAdapter:
    """New in T-4: refunds a completed order."""

    def apply(self, state, params):
        return state
