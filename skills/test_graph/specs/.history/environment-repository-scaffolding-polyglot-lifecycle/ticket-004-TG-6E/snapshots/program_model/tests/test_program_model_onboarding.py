from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_program_model_baseline_can_have_active_ticket_workflow_siblings() -> None:
    assert (SPEC_ROOT / "TestGraph.tla").exists()
    assert (SPEC_ROOT / "spec_manifest.yaml").exists()

    current = SPEC_ROOT.parent / "current"
    desired = SPEC_ROOT.parent / "desired_program_model"
    if current.exists() or desired.exists():
        assert current.exists()
        assert desired.exists()
        assert (desired / "ticket_plan.yaml").exists()
        assert "../program_model/spec_manifest.yaml" in (current / "spec_manifest.yaml").read_text()


def test_program_model_points_at_existing_package_surfaces() -> None:
    expected = [
        "SKILL.md",
        "scripts/scaffold.py",
        "scripts/discover.py",
        "scripts/run.py",
        "templates/jbang-node.java.template",
        "templates/uv-node.py.template",
        "references/workflows.md",
        "references/reference.md",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/GraphAssembler.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/NodeSpec.java",
        "project_sdk_sources/sdk/python/src/testgraphsdk/node_spec.py",
        "project_sdk_sources/sources",
        "skill-manager.toml",
    ]
    missing = [path for path in expected if not (REPO_ROOT / path).exists()]
    assert missing == []


def test_tla_model_contains_core_workflow_actions() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text()
    for action in [
        "ScaffoldProject",
        "RegisterGraph",
        "DescribeNode",
        "ResolveNode",
        "PlanGraph",
        "StartRun",
        "RunNodePass",
        "RunNodeTerminal",
        "WriteInlineReport",
        "CleanBuild",
    ]:
        assert f"@command {action}" in tla
