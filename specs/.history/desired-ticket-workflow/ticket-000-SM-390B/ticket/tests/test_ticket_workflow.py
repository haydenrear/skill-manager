from pathlib import Path


TICKET_ROOT = Path(__file__).resolve().parents[1]


def test_ticket_workflow_scaffold_points_to_local_desired() -> None:
    desired = TICKET_ROOT / "desired/spec_manifest.yaml"
    ticket = TICKET_ROOT / "ticket.yaml"

    assert desired.exists()
    assert ticket.exists()
    assert "SM-390B" in ticket.read_text(encoding="utf-8")
