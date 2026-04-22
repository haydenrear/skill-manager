from __future__ import annotations

from gateway.models import SessionState


def test_session_disclosure_allows_same_path_and_descendants() -> None:
    session = SessionState(session_id="test-session")
    session.disclose("fixtures/http", ttl_seconds=None)

    assert session.validate("fixtures/http")
    assert session.validate("fixtures/http/echo")
    assert not session.validate("fixtures/stdio/echo")
