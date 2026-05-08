"""Tests for ``_is_broken_session_error`` — the predicate that decides
whether a downstream-client error is a transient transport-pipe death
(reopen + retry) vs. a permanent failure (propagate).

Regression: a stdio downstream subprocess closing its pipes mid-request
used to surface as ``MCPError("stdio downstream error for tools/list:
Connection closed")`` and propagate without triggering the worker's
reopen path. The MCP SDK wrapped the underlying ``ClosedResourceError``
in its own exception class whose type wasn't in the
``_BROKEN_SESSION_ERRORS`` tuple. Skill-manager then recorded the deploy
as ``MCP_REGISTRATION_FAILED`` and required a manual ``skill-manager
sync`` to recover.

The fix broadens detection in two ways:
  1. Walk the chained cause / context so a typed broken-session error
     wrapped by the SDK still triggers reopen.
  2. Fall back to a case-insensitive substring match over a list of
     message fragments downstream stdio servers + the SDK use when the
     pipe dies (``Connection closed``, ``Broken pipe``, ``EOF``, etc.).

This file pins both branches.
"""

from __future__ import annotations

import anyio
import pytest

from gateway.clients import _is_broken_session_error


class _SdkLikeWrapper(RuntimeError):
    """Stand-in for ``mcp.shared.exceptions.McpError`` — a wrapper class
    that doesn't subclass any ``_BROKEN_SESSION_ERRORS`` type but whose
    cause is one. This is the exact shape that bypassed detection
    before the fix.
    """


def test_returns_false_for_none() -> None:
    assert _is_broken_session_error(None) is False


def test_returns_false_for_unrelated_runtime_error() -> None:
    # A generic application-level error (e.g. a tool returning an MCP
    # error result) must NOT trigger reopen — protocol-level failures
    # should propagate without retry.
    assert _is_broken_session_error(RuntimeError("tool refused: bad arguments")) is False


@pytest.mark.parametrize(
    "exc",
    [
        anyio.ClosedResourceError("pipe closed"),
        anyio.BrokenResourceError("pipe broke"),
        anyio.EndOfStream(),
        BrokenPipeError("pipe"),
        ConnectionResetError("reset"),
        ProcessLookupError("no such process"),
        EOFError("eof"),
    ],
)
def test_typed_broken_session_errors_trigger_reopen(exc: BaseException) -> None:
    """Direct instances of any typed broken-session error are detected."""
    assert _is_broken_session_error(exc) is True


def test_sdk_wrapper_with_typed_cause_walks_chain() -> None:
    """The MCP SDK wraps a typed ``ClosedResourceError`` in its own
    exception class. The wrapper itself isn't in ``_BROKEN_SESSION_ERRORS``,
    but walking ``__cause__`` finds the real broken-session error.
    """
    underlying = anyio.ClosedResourceError("pipe closed")
    try:
        raise _SdkLikeWrapper("stdio downstream error") from underlying
    except _SdkLikeWrapper as wrapper:
        assert _is_broken_session_error(wrapper) is True


def test_implicitly_chained_context_also_walks() -> None:
    """Python's implicit ``__context__`` chain (``raise X`` inside an
    ``except Y`` without ``from``) is also walked so subprocess
    failures wrapped without an explicit ``raise from`` clause still
    trigger reopen.
    """
    try:
        try:
            raise BrokenPipeError("subprocess died")
        except BrokenPipeError:
            raise _SdkLikeWrapper("downstream failed")
    except _SdkLikeWrapper as wrapper:
        assert _is_broken_session_error(wrapper) is True


@pytest.mark.parametrize(
    "message",
    [
        # Exactly the user-reported pattern that surfaced as
        # MCP_REGISTRATION_FAILED before this fix.
        "stdio downstream error for tools/list: Connection closed",
        # Variations the SDK / underlying transports produce.
        "Connection closed",
        "Connection Closed",  # case-insensitive match
        "tools/call failed: Connection closed by peer",
        "Broken pipe",
        "[Errno 32] Broken pipe",
        "End of file",
        "EndOfStream raised",
        "stream is closed",
        "subprocess exited with code 1",
        "process terminated unexpectedly",
    ],
)
def test_message_fragment_match_triggers_reopen(message: str) -> None:
    """Untyped exceptions (no broken-session class in the cause chain)
    are detected by pattern-matching the message — the safety net for
    SDK wrappers that drop the underlying typed exception entirely.
    """
    assert _is_broken_session_error(RuntimeError(message)) is True


@pytest.mark.parametrize(
    "message",
    [
        "tool refused: bad arguments",
        "rate limit exceeded",
        "schema validation failed: missing required field 'foo'",
        "Authentication required",
        "permission denied",
        "tool not found: fixtures/echo",
    ],
)
def test_unrelated_messages_do_not_trigger_reopen(message: str) -> None:
    """Reopen is intentionally narrow: protocol-level / config-level
    errors must propagate so callers see the real cause. False
    positives here would mask legit failures behind a redundant retry.
    """
    assert _is_broken_session_error(RuntimeError(message)) is False


def test_does_not_recurse_on_self_referential_cause() -> None:
    """Pathological self-referential cause chains (rare, but possible
    in test doubles) must not infinite-loop — the helper tracks visited
    exceptions by id.
    """
    err = RuntimeError("benign")
    err.__cause__ = err  # forge a cycle
    # Should return False without hanging: the cycle exits via the
    # seen-id guard, then the message fallback runs and finds nothing.
    assert _is_broken_session_error(err) is False
