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


def test_tg6_current_model_records_scaffold_and_lifecycle_actions() -> None:
    current_tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")

    assert "active_ticket: TG-6D" in manifest
    assert "jbang_environment_context_parity_landed" in manifest
    assert "environmentRepositoryContractJbang" in manifest
    assert "@command ScaffoldEnvironmentRepository" in current_tla
    assert "@command ScaffoldEnvironmentTemplate" in current_tla
    assert "@command ScaffoldLifecycleNodeTemplate" in current_tla
    assert "@command VerifyEnvironmentContextRuntime" in current_tla
    assert "@command SkipBranchEnvironmentReset" in current_tla
    assert "@command SkipBranchEnvironmentDestroy" in current_tla
    assert "@invariant EnvironmentTemplatesRequireRepositoryScaffold" in current_tla
    assert "@invariant LifecycleNodeTemplatesRequireRepositoryScaffold" in current_tla
    assert "@invariant RuntimeContextVerificationIsTyped" in current_tla
