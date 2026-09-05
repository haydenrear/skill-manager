import re
from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1].parent


def _unquote(value: str) -> str:
    return value.strip().strip("\"").strip("'")


def test_current_ticket_workflow_scaffold_points_to_desired_plan() -> None:
    manifest = SPEC_ROOT / "current/spec_manifest.yaml"
    plan = SPEC_ROOT / "desired_program_model/ticket_plan.yaml"

    assert manifest.exists()
    assert plan.exists()

    manifest_text = manifest.read_text(encoding="utf-8")
    active_match = re.search(
        r"(?m)^  active_ticket:\s*(?P<ticket>[^#\n]+?)\s*$",
        manifest_text,
    )
    assert active_match is not None, "current manifest has no active_ticket"
    active_ticket = _unquote(active_match.group("ticket"))

    ticket_blocks = plan.read_text(encoding="utf-8").split("\n  - id: ")[1:]
    matching_blocks = [
        block
        for block in ticket_blocks
        if _unquote(block.splitlines()[0].strip()) == active_ticket
    ]
    assert len(matching_blocks) == 1, (
        f"expected one canonical plan block for {active_ticket}, "
        f"found {len(matching_blocks)}"
    )
    status_match = re.search(
        r"(?m)^    status:\s*(?P<status>[^#\n]+?)\s*$",
        matching_blocks[0],
    )
    assert status_match is not None, f"{active_ticket} has no plan status"
    assert _unquote(status_match.group("status")) in {"closed", "done"}
