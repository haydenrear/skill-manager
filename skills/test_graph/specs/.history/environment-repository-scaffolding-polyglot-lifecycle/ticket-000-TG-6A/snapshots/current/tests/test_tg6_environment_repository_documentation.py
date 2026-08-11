from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_tg6a_environment_repository_reference_is_routed_from_skill_docs() -> None:
    skill = (REPO_ROOT / "SKILL.md").read_text(encoding="utf-8")
    reference = (REPO_ROOT / "references/environment-repositories.md")
    api_reference = (REPO_ROOT / "references/reference.md").read_text(encoding="utf-8")
    workflows = (REPO_ROOT / "references/workflows.md").read_text(encoding="utf-8")

    assert reference.exists()
    assert "references/environment-repositories.md" in skill
    assert "environment-repositories.md" in api_reference
    assert "environment-repositories.md" in workflows


def test_tg6a_environment_repository_reference_records_contract_and_graph_matrix() -> None:
    reference = (REPO_ROOT / "references/environment-repositories.md").read_text(encoding="utf-8")
    graph = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    for phrase in [
        "git init",
        "git add",
        "git commit",
        "local-preview",
        "local-github-action",
        "aws-preview",
        "EnvironmentId",
        "KUBECONFIG",
        "KUBECONTEXT",
        "Local k3d Setup",
        "Missing cluster deploy",
        "existing cluster reuse",
        "explicit teardown",
        "skip teardown",
        "external evidence",
        "scripts/scaffold-tf-env.py",
        "scripts/scaffold-env.py",
    ]:
        assert phrase in reference

    assert 'testGraph("environmentRepositoryDocumentation")' in graph


def test_tg6a_current_model_records_docs_slice_without_semantic_actions() -> None:
    current_tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")
    program_tla = (REPO_ROOT / "specs/program_model/TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")

    assert current_tla == program_tla
    assert "active_ticket: TG-6A" in manifest
    assert "documentation_slice_landed" in manifest
    assert "environmentRepositoryDocumentation" in manifest
    assert "ScaffoldEnvironmentRepository" not in current_tla
