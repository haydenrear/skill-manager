from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[4]
MODEL_ROOT = Path(__file__).resolve().parents[1]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_internal_model_separates_source_selection_from_claimant_reconciliation() -> None:
    internal = read(MODEL_ROOT / "Internal.tla")
    desired_cfg = read(MODEL_ROOT / "Internal_claimant_refresh.cfg")
    regression_cfg = read(MODEL_ROOT / "Internal_regression_claimant_refresh.cfg")

    assert "ReconcileClaimingProjectFromSelectedParent" in internal
    assert "PullTrunkThenReconcileClaimingProject" in internal
    assert "AutomaticClaimantRefreshUsesSelectedParentRevision" in internal
    assert "AutomaticClaimantRefreshDoesNotPullTrunk" in internal
    assert "SPECIFICATION ClaimantRefreshSpec" in desired_cfg
    assert "SPECIFICATION ClaimantRefreshRegressionSpec" in regression_cfg


def test_external_model_requires_one_revision_across_every_public_surface() -> None:
    external = read(MODEL_ROOT / "External.tla")
    desired_cfg = read(MODEL_ROOT / "External_named_sync.cfg")
    regression_cfg = read(MODEL_ROOT / "External_regression_named_sync.cfg")

    for field in (
        "named_checkout_revision",
        "named_installed_revision",
        "named_units_lock_revision",
        "named_registration_revision",
        "named_child_revision",
    ):
        assert field in external
    assert "SuccessfulNamedSyncHasOneSelectedRevision" in external
    assert "AutomaticClaimantRefreshPerformsNoSourceSelection" in external
    assert "SPECIFICATION NamedSyncSpec" in desired_cfg
    assert "SPECIFICATION NamedSyncRegressionSpec" in regression_cfg


def test_automatic_refresh_uses_reconcile_only_while_explicit_project_sync_keeps_pull() -> None:
    interpreter = read(REPO_ROOT / "src/main/java/dev/skillmanager/effects/LiveInterpreter.java")
    project_sync = read(REPO_ROOT / "src/main/java/dev/skillmanager/project/ProjectSyncUseCase.java")

    assert "ProjectSyncUseCase.Options.reconcileOnly()" in interpreter
    assert "return sync(project, options, Options.defaults());" in project_sync
    assert "static Options reconcileOnly()" in project_sync
    assert "new Options(false, false, UnitTrunkPull.Options.defaults())" in project_sync


def test_public_real_git_case_is_bound_to_both_required_graphs() -> None:
    bindings = read(MODEL_ROOT / "testgraph_bindings.yml")
    graph = read(REPO_ROOT / "test_graph/build.gradle.kts")
    node = REPO_ROOT / "test_graph/sources/project/ProjectPinnedSyncRevisionCoherent.java"

    assert "PublicNamedSyncPinnedRevision:" in bindings
    assert "project.pinned.sync.revision.coherent" in bindings
    assert 'testGraph("project-smoke")' in graph
    assert 'testGraph("spec-conformance")' in graph
    assert 'node("sources/project/ProjectPinnedSyncRevisionCoherent.java")' in graph
    assert node.is_file()
