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


def test_tg6_program_model_records_scaffold_and_lifecycle_actions() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")

    assert "workflow: closed" in manifest
    assert "environment-repository-scaffolding-polyglot-lifecycle" in manifest
    assert "tg6f_guarded_lifecycle_validation_landed" in manifest
    assert "environmentRepositoryLocalLifecycle" in manifest
    assert "environmentRepositoryGithubActionLifecycle" in manifest
    assert "environmentRepositoryAwsLifecycle" in manifest
    assert "@command ScaffoldEnvironmentRepository" in tla
    assert "@command ScaffoldEnvironmentTemplate" in tla
    assert "@command ScaffoldLifecycleNodeTemplate" in tla
    assert "@command VerifyEnvironmentContextRuntime" in tla
    assert "@command GuardAwsBranchEnvironment" in tla
    assert "@command SkipBranchEnvironmentReset" in tla
    assert "@command SkipBranchEnvironmentDestroy" in tla
    assert "@invariant EnvironmentTemplatesRequireRepositoryScaffold" in tla
    assert "@invariant LifecycleNodeTemplatesRequireRepositoryScaffold" in tla
    assert "@invariant AwsProvisioningRequiresExplicitGuard" in tla
    assert "@invariant RuntimeContextVerificationIsTyped" in tla
