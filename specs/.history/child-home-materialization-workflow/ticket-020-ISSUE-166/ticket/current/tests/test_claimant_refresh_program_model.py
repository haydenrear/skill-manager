import json
from pathlib import Path

import yaml


MODEL_ROOT = Path(__file__).resolve().parents[1]


def _repo_root() -> Path:
    for candidate in MODEL_ROOT.parents:
        if (candidate / "src/main/java/dev/skillmanager").is_dir():
            return candidate
    raise AssertionError("repository root not found from ticket model")


REPO_ROOT = _repo_root()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_claimant_views_are_additive_to_the_accepted_monolith() -> None:
    accepted = REPO_ROOT / "specs/program_model"
    assert read(MODEL_ROOT / "SkillManager.tla") == read(accepted / "SkillManager.tla")
    assert read(MODEL_ROOT / "MC.cfg") == read(accepted / "MC.cfg")
    assert not (MODEL_ROOT / "Internal.tla").exists()
    assert not (MODEL_ROOT / "External.tla").exists()

    manifest = read(MODEL_ROOT / "spec_manifest.yaml")
    assert "module: SkillManager" in manifest
    assert "complexity_ledger_model:" in manifest
    assert "identity: SkillManager" in manifest
    assert "non_replacement: true" in manifest


def test_internal_view_separates_selection_from_claimant_reconciliation() -> None:
    internal = read(MODEL_ROOT / "ClaimantRefreshInternal.tla")
    desired_cfg = read(MODEL_ROOT / "ClaimantRefreshInternal.cfg")
    regression_cfg = read(MODEL_ROOT / "ClaimantRefreshInternal_regression.cfg")

    assert "SkillManager.SyncClaimingProjectChildHomes" in internal
    assert "ReconcileClaimingProjectFromSelectedParent" in internal
    assert "PullTrunkThenReconcileClaimingProject" in internal
    assert "AutomaticClaimantRefreshUsesSelectedParentRevision" in internal
    assert "AutomaticClaimantRefreshDoesNotPullTrunk" in internal
    assert "SPECIFICATION ClaimantRefreshSpec" in desired_cfg
    assert "SPECIFICATION ClaimantRefreshRegressionSpec" in regression_cfg
    assert regression_cfg.index("AutomaticClaimantRefreshDoesNotPullTrunk") \
        < regression_cfg.index("AutomaticClaimantRefreshUsesSelectedParentRevision")


def test_internal_corpus_covers_the_complete_claimant_action_catalog() -> None:
    coverage = json.loads(
        read(
            MODEL_ROOT
            / "generated/spec-unit/claimant_refresh_cases/case_coverage.json"
        )
    )
    action = "ReconcileClaimingProjectFromSelectedParent"
    assert coverage["module"] == "ClaimantRefreshInternal"
    assert coverage["view"] == "internal"
    assert coverage["cases"] == 1
    assert coverage["actions"] == {action: 1}
    assert coverage["declared_view_actions"] == [action]
    assert coverage["source"] == (
        "specs/tickets/ISSUE-166/desired/generated/spec-unit/claimant_refresh_cases"
    )
    metadata = read(MODEL_ROOT / "claimant_refresh_actions.yml")
    assert metadata.count("ReconcileClaimingProjectFromSelectedParent:") == 1
    assert "layer: internal" in metadata
    assert "controllability: unit_direct" in metadata
    assert "- spec_unit" in metadata


def test_external_view_requires_one_revision_across_public_surfaces() -> None:
    external = read(MODEL_ROOT / "ClaimantRefreshExternal.tla")
    desired_cfg = read(MODEL_ROOT / "ClaimantRefreshExternal.cfg")
    regression_cfg = read(MODEL_ROOT / "ClaimantRefreshExternal_regression.cfg")

    assert "SkillManager.SyncUnit" in external
    assert "SkillManager.SyncClaimingProjectChildHomes" in external
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


def test_automatic_refresh_uses_reconcile_only_while_explicit_sync_keeps_pull() -> None:
    interpreter = read(
        REPO_ROOT / "src/main/java/dev/skillmanager/effects/LiveInterpreter.java"
    )
    project_sync = read(
        REPO_ROOT / "src/main/java/dev/skillmanager/project/ProjectSyncUseCase.java"
    )

    assert "ProjectSyncUseCase.Options.reconcileOnly()" in interpreter
    assert "return sync(project, options, Options.defaults());" in project_sync
    assert "static Options reconcileOnly()" in project_sync
    assert "new Options(false, false, UnitTrunkPull.Options.defaults())" in project_sync


def test_public_real_git_case_is_non_vacuous_and_bound_to_both_graphs() -> None:
    bindings = read(MODEL_ROOT / "testgraph_bindings.yml")
    binding_contract = yaml.safe_load(bindings)
    graph = read(REPO_ROOT / "test_graph/build.gradle.kts")
    node = read(
        REPO_ROOT
        / "test_graph/sources/project/ProjectPinnedSyncRevisionCoherent.java"
    )

    assert "PublicNamedSyncPinnedRevision:" in bindings
    assert binding_contract["external"]["port_bindings"][
        "SkillManagerCli.sync_named_unit"
    ] == "real"
    assert "project.pinned.sync.revision.coherent" in bindings
    assert 'testGraph("project-smoke")' in graph
    assert 'testGraph("spec-conformance")' in graph
    assert 'node("sources/project/ProjectPinnedSyncRevisionCoherent.java")' in graph
    for assertion in (
        "fixture_unrelated_installed_record_present",
        "fixture_unrelated_parent_tree_nonempty",
        "fixture_unrelated_child_tree_nonempty",
        "fixture_unrelated_units_lock_row_present",
        "fixture_unrelated_cli_lock_row_present",
        "fixture_unrelated_parent_cli_shim_executable",
        "fixture_unrelated_child_cli_shim_executable",
        "unrelated_records_trees_locks_and_shims_byte_identical",
    ):
        assert assertion in bindings
        assert assertion in node


def test_claimant_slice_artifacts_do_not_embed_machine_specific_paths() -> None:
    results_root = MODEL_ROOT.parent / "results"
    paths = [
        *MODEL_ROOT.glob("ClaimantRefresh*"),
        MODEL_ROOT / "spec_manifest.yaml",
        MODEL_ROOT / "testgraph_bindings.yml",
        *results_root.glob("*"),
        *(
            MODEL_ROOT / "generated/spec-unit/claimant_refresh_cases"
        ).glob("*"),
    ]
    for path in paths:
        if path.is_file():
            text = read(path)
            assert "/private/tmp/" not in text, path
            assert "/Users/" not in text, path
