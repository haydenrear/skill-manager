from pathlib import Path


TICKET_ROOT = Path(__file__).resolve().parents[1]


def test_ticket_workflow_scaffold_points_to_local_current_and_desired() -> None:
    current = TICKET_ROOT / "current/spec_manifest.yaml"
    desired = TICKET_ROOT / "desired/spec_manifest.yaml"
    ticket = TICKET_ROOT / "ticket.yaml"

    assert current.exists()
    assert desired.exists()
    assert ticket.exists()
    assert "SMVENV-001" in ticket.read_text(encoding="utf-8")
