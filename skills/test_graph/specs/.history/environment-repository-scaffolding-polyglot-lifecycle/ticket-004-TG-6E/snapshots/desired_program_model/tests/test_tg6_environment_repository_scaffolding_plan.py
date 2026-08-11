from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_tg6_ticket_plan_orders_contract_before_scaffolds_and_lifecycle() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")

    ordered_ids = ["TG-6A", "TG-6B", "TG-6C", "TG-6D", "TG-6E", "TG-6F"]
    positions = [plan.index(f"id: {ticket_id}") for ticket_id in ordered_ids]
    assert positions == sorted(positions)

    assert "id: TG-6G" not in plan
    assert "per_ticket_closeout_rule" in plan
    assert "functionality_graph_rule" in plan
    assert "exhaustive_environment_graph_rule" in plan
    assert "Close TG-6 spec workflow" not in plan
    assert "After every completed ticket" in plan
    assert "Document the environment repository contract and local k3d setup" in plan
    assert "scripts/scaffold-tf-env.py" in plan
    assert "scripts/scaffold-env.py" in plan
    assert "environmentRepositoryScaffoldLocal" in plan
    assert "environmentRepositoryScaffoldGithubAction" in plan
    assert "environmentRepositoryScaffoldAws" in plan
    assert "environmentRepositoryContractJbang" in plan
    assert "environmentRepositoryLocalLifecycle" in plan
    assert "environmentRepositoryGithubActionLifecycle" in plan
    assert "environmentRepositoryAwsLifecycle" in plan
    assert "deployment cases for missing cluster, existing cluster reuse, reset, explicit destroy, and skip-destroy" in plan
    assert "missing cluster deploy, existing cluster reuse without recreation, reset, explicit teardown, and skip teardown" in plan
    assert "temporary Git repository with git init/add/commit" in plan
    assert "normal CI cannot accidentally create" in plan
    assert "or delete cloud resources" in plan
    assert "scaffold output becomes the primary fixture for later lifecycle graphs" in plan


def test_tg6_desired_model_records_environment_repository_semantics() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")
    desired_state = (SPEC_ROOT / "desired_state.yaml").read_text(encoding="utf-8")
    mc_cfg = (SPEC_ROOT / "MC.cfg").read_text(encoding="utf-8")

    for command in [
        "ScaffoldEnvironmentRepository",
        "ScaffoldEnvironmentTemplate",
        "ScaffoldLifecycleNodeTemplate",
        "VerifyEnvironmentContextRuntime",
        "GuardAwsBranchEnvironment",
        "SkipBranchEnvironmentReset",
        "SkipBranchEnvironmentDestroy",
    ]:
        assert f"@command {command}" in tla
        assert command in manifest
        assert command in desired_state

    for invariant in [
        "EnvironmentTemplatesRequireRepositoryScaffold",
        "LifecycleNodeTemplatesRequireRepositoryScaffold",
        "AwsProvisioningRequiresExplicitGuard",
        "SkippedResetEnvironmentsRemainProvisioned",
        "SkippedDestroyEnvironmentsRemainActive",
        "RuntimeContextVerificationIsTyped",
    ]:
        assert f"@invariant {invariant}" in tla
        assert invariant in manifest
        assert invariant in desired_state
        assert invariant in mc_cfg

    assert "NodeRuntimes = {UvRuntime, JbangRuntime}" in mc_cfg
    assert "LifecycleCommands = {DeployClusterCommand, ResetClusterCommand, DeleteClusterCommand}" in mc_cfg
    assert "AwsTargets = {AwsPreview}" in mc_cfg
    assert "EnvironmentTargets = {AwsPreview}" in mc_cfg
    assert "AwsBackends = {}" in mc_cfg
    assert "environmentRepositoryScaffoldGithubAction" in manifest
    assert "environmentRepositoryGithubActionLifecycle" in manifest
    assert "exhaustive_graph_policy" in desired_state


def test_tg6_functionality_tickets_require_test_graph_coverage() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")

    for ticket_id in ["TG-6A", "TG-6B", "TG-6C", "TG-6D", "TG-6E", "TG-6F"]:
        start = plan.index(f"  - id: {ticket_id}")
        next_start = plan.find("\n  - id: ", start + 1)
        ticket = plan[start:] if next_start == -1 else plan[start:next_start]
        assert "graph_after_unit_pass:" in ticket
        assert "./scripts/run.py" in ticket
        assert "assertions:" in ticket


def test_tg6a_docs_ticket_does_not_claim_scaffolding_has_landed() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")
    start = plan.index("  - id: TG-6A")
    end = plan.index("\n  - id: TG-6B", start)
    tg6a = plan[start:end]

    assert "status: done" in tg6a
    assert "desired_actions: []" in tg6a
    assert "model_state: []" in tg6a
    assert "model_actions: []" in tg6a
    assert "ScaffoldEnvironmentRepository" not in tg6a
    assert "ScaffoldEnvironmentTemplate" not in tg6a
    assert "environmentRepositoryDocumentation" in tg6a


def test_tg6b_scaffolding_ticket_is_landed() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")
    start = plan.index("  - id: TG-6B")
    end = plan.index("\n  - id: TG-6C", start)
    tg6b = plan[start:end]

    assert "status: done" in tg6b
    assert "ScaffoldEnvironmentRepository" in tg6b
    assert "ScaffoldEnvironmentTemplate" in tg6b
    assert "environmentRepositoryScaffoldLocal" in tg6b
    assert "environmentRepositoryScaffoldGithubAction" in tg6b
    assert "environmentRepositoryScaffoldAws" in tg6b
    assert "scaffold output becomes the primary fixture for later lifecycle graphs" in tg6b


def test_tg6c_lifecycle_template_ticket_is_landed() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")
    start = plan.index("  - id: TG-6C")
    end = plan.index("\n  - id: TG-6D", start)
    tg6c = plan[start:end]

    assert "status: done" in tg6c
    assert "ScaffoldLifecycleNodeTemplate" in tg6c
    assert "SkipBranchEnvironmentReset" in tg6c
    assert "SkipBranchEnvironmentDestroy" in tg6c
    assert "environmentLifecycleNodeTemplates" in tg6c
    assert "deploy_cluster.py" in tg6c
    assert "DeployCluster.java" in tg6c
    assert "117199 distinct states" in tg6c


def test_tg6d_jbang_context_ticket_is_landed() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")
    start = plan.index("  - id: TG-6D")
    end = plan.index("\n  - id: TG-6E", start)
    tg6d = plan[start:end]

    assert "status: done" in tg6d
    assert "VerifyEnvironmentContextRuntime" in tg6d
    assert "environmentRepositoryContractJbang" in tg6d
    assert "Tg6EnvironmentRepositoryProvisionJbang.java" in tg6d
    assert "Tg6EnvironmentContextEnvKeyJbang.java" in tg6d
    assert "Tg6EnvironmentContextEnvAllJbang.java" in tg6d
    assert "468796 distinct states" in tg6d


def test_tg6e_local_lifecycle_ticket_is_landed_and_tg6f_is_next() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")
    start = plan.index("  - id: TG-6E")
    end = plan.index("\n  - id: TG-6F", start)
    tg6e = plan[start:end]
    desired_state = (SPEC_ROOT / "desired_state.yaml").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")

    assert "active_ticket: TG-6F" in plan
    assert "status: done" in tg6e
    assert "environmentRepositoryLocalLifecycle" in tg6e
    assert "environmentRepositoryLocalLifecycleDestroy" in tg6e
    assert "Tg6LocalLifecycleProvisionMissing.py" in tg6e
    assert "Tg6LocalLifecycleDeployExisting.py" in tg6e
    assert "Tg6LocalLifecycleReset.py" in tg6e
    assert "Tg6LocalLifecycleDestroy.py" in tg6e
    assert "TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true" in tg6e
    assert "tg6e_local_lifecycle_validation_landed" in desired_state
    assert "tg6e_local_lifecycle_validation_landed" in manifest


def test_tg5_graph_helpers_ignore_active_tg6_ticket_plan() -> None:
    harness = (REPO_ROOT / "test_graph/support/branch_environment_harness.py").read_text(encoding="utf-8")
    workflow_record = (REPO_ROOT / "test_graph/sources/Tg5DeployCdcIssueWorkflowRecord.py").read_text(encoding="utf-8")

    assert 'if "id: TG-5" in text:' in harness
    assert "active_tg5_ticket_plan" in workflow_record
    assert 'if "id: TG-5G" in plan.read_text' in workflow_record


def test_tg6_current_model_records_tg6e_local_lifecycle_status() -> None:
    current_tla = (REPO_ROOT / "specs/current/TestGraph.tla").read_text(encoding="utf-8")
    current_manifest = (REPO_ROOT / "specs/current/spec_manifest.yaml").read_text(encoding="utf-8")

    assert "local_lifecycle_validation_landed" in current_manifest
    assert "environmentRepositoryLocalLifecycle" in current_manifest
    assert "@command ScaffoldEnvironmentRepository" in current_tla
    assert "@command ScaffoldEnvironmentTemplate" in current_tla
    assert "@command ScaffoldLifecycleNodeTemplate" in current_tla
    assert "@command VerifyEnvironmentContextRuntime" in current_tla
    assert "@command SkipBranchEnvironmentReset" in current_tla
    assert "@command SkipBranchEnvironmentDestroy" in current_tla
    assert "@invariant EnvironmentTemplatesRequireRepositoryScaffold" in current_tla
    assert "@invariant LifecycleNodeTemplatesRequireRepositoryScaffold" in current_tla
    assert "@invariant RuntimeContextVerificationIsTyped" in current_tla
    assert "@invariant SkippedResetEnvironmentsRemainProvisioned" in current_tla
    assert "@invariant SkippedDestroyEnvironmentsRemainActive" in current_tla
    assert "GuardAwsBranchEnvironment" not in current_tla


def test_tg6a_docs_are_authoritative_and_graph_validated() -> None:
    skill = (REPO_ROOT / "SKILL.md").read_text(encoding="utf-8")
    reference = (REPO_ROOT / "references/environment-repositories.md").read_text(encoding="utf-8")
    api_reference = (REPO_ROOT / "references/reference.md").read_text(encoding="utf-8")
    workflows = (REPO_ROOT / "references/workflows.md").read_text(encoding="utf-8")
    graph = (REPO_ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    assert "references/environment-repositories.md" in skill
    assert "environment-repositories.md" in api_reference
    assert "environment-repositories.md" in workflows
    assert 'testGraph("environmentRepositoryDocumentation")' in graph
    assert 'testGraph("environmentRepositoryScaffoldLocal")' in graph
    assert 'testGraph("environmentRepositoryScaffoldGithubAction")' in graph
    assert 'testGraph("environmentRepositoryScaffoldAws")' in graph

    for phrase in [
        "Local k3d",
        "git init",
        "git add",
        "git commit",
        "local-preview",
        "local-github-action",
        "aws-preview",
        "EnvironmentId",
        "KUBECONFIG",
        "KUBECONTEXT",
        "scripts/scaffold-tf-env.py",
        "scripts/scaffold-env.py",
        "external evidence",
    ]:
        assert phrase in reference
