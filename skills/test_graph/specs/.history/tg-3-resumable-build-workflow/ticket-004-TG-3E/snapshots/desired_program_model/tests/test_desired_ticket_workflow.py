from pathlib import Path


SPEC_DIR = Path(__file__).resolve().parents[1]


def test_desired_model_contains_issue_3_actions_and_invariants() -> None:
    tla = (SPEC_DIR / "TestGraph.tla").read_text(encoding="utf-8")

    for action in [
        "SetNodeRerunDisabled",
        "ResumeRunFromBuild",
        "RunOnlyNodeFromBuild",
    ]:
        assert f"@command {action}" in tla

    for invariant in [
        "EveryAttemptHasSavedInputContext",
        "RerunGuidanceOnlyForRerunnableFailures",
        "ResumptionsUseSavedInputContext",
        "BuildRerunsRespectDependencies",
    ]:
        assert f"@invariant {invariant}" in tla


def test_ticket_plan_tracks_all_issue_3_slices() -> None:
    plan = (SPEC_DIR / "ticket_plan.yaml").read_text(encoding="utf-8")

    for ticket_id in ["TG-3A", "TG-3B", "TG-3C", "TG-3D", "TG-3E"]:
        assert f"id: {ticket_id}" in plan

    for boundary in [
        "input_context_snapshots",
        "resume_from_build",
        "run_only_node_from_build",
        "rerun_metadata_and_guidance",
    ]:
        assert boundary in plan
