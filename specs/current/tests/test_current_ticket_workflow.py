from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1].parent


def test_current_ticket_workflow_scaffold_points_to_desired_plan() -> None:
    manifest = SPEC_ROOT / "current/spec_manifest.yaml"
    plan = SPEC_ROOT / "desired_program_model/ticket_plan.yaml"

    assert manifest.exists()
    assert plan.exists()
    assert "ISSUE-75" in manifest.read_text(encoding="utf-8")
    assert "ISSUE-75" in plan.read_text(encoding="utf-8")


def test_current_manifest_records_child_home_harness_slice() -> None:
    manifest = SPEC_ROOT / "current/spec_manifest.yaml"
    text = manifest.read_text(encoding="utf-8")

    assert "ISSUE-75-5" in text
    assert "ISSUE-75-4" in text
    assert "ISSUE-75-3" in text
    assert "RegisterProjectManifest" in text
    assert "ResolveProjectDependencies" in text
    assert "MaterializeProjectEnv" in text
    assert "ResolveProjectLibs" in text
    assert "InstantiateChildHomeFromHarness" in text
    assert "ProjectManifestAdapter" in text
    assert "ProjectDependencyResolverAdapter" in text
    assert "ProjectEnvMaterializerAdapter" in text
    assert "ProjectLibResolverAdapter" in text
    assert "ChildHomeHarnessAdapter" in text
    assert "python skills/test_graph/scripts/run.py project-manifest" in text
    assert "python skills/test_graph/scripts/run.py project-resolve" in text
    assert "python skills/test_graph/scripts/run.py project-env" in text
    assert "python skills/test_graph/scripts/run.py project-libs" in text
    assert "python skills/test_graph/scripts/run.py harness-smoke" in text
