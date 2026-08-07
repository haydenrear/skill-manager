import json
from pathlib import Path

import yaml


MODEL_ROOT = Path(__file__).resolve().parents[1]


def _repo_root() -> Path:
    for candidate in MODEL_ROOT.parents:
        if (candidate / "src/main/java/dev/skillmanager").is_dir():
            return candidate
    raise AssertionError("repository root not found from desired model")


REPO_ROOT = _repo_root()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_claimant_refresh_slice_survives_future_chm_promotion() -> None:
    # The existing future CHM model and the additive ISSUE-166 views coexist.
    assert (MODEL_ROOT / "Internal.tla").is_file()
    assert (MODEL_ROOT / "External.tla").is_file()
    for filename in (
        "ClaimantRefreshInternal.tla",
        "ClaimantRefreshInternal.cfg",
        "ClaimantRefreshInternal_regression.cfg",
        "ClaimantRefreshExternal.tla",
        "ClaimantRefreshExternal.cfg",
        "ClaimantRefreshExternal_regression.cfg",
    ):
        assert (MODEL_ROOT / filename).is_file()

    manifest = read(MODEL_ROOT / "spec_manifest.yaml")
    assert "module: SkillManager" in manifest
    assert "tla: SkillManager.tla" in manifest
    assert "cfg: MC.cfg" in manifest
    assert "issue_166_claimant_refresh_internal:" in manifest
    assert "issue_166_claimant_refresh_external:" in manifest
    assert "non_replacement: true" in manifest
    assert "generation_status: generated" in manifest
    assert (MODEL_ROOT / "generated/spec-unit/claimant_refresh_cases/cases.py").is_file()
    assert (MODEL_ROOT / "generated/spec-unit/ClaimantRefreshInternal.dot").is_file()

    internal = read(MODEL_ROOT / "ClaimantRefreshInternal.tla")
    external = read(MODEL_ROOT / "ClaimantRefreshExternal.tla")
    assert "SkillManager.SyncClaimingProjectChildHomes" in internal
    assert "SkillManager.SyncUnit" in external
    assert "SkillManager.SyncClaimingProjectChildHomes" in external

    adapters = read(MODEL_ROOT / "case_adapters.toml")
    bindings = read(MODEL_ROOT / "testgraph_bindings.yml")
    binding_contract = yaml.safe_load(bindings)
    production_adapter = read(REPO_ROOT / "specs/claimant_refresh_adapter.py")
    assert "[adapters.ReconcileClaimingProjectFromSelectedParent]" in adapters
    assert "ClaimantRefreshPolicyAdapter" in production_adapter
    assert "PublicNamedSyncPinnedRevision:" in bindings
    assert binding_contract["external"]["port_bindings"][
        "SkillManagerCli.sync_named_unit"
    ] == "real"
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


def test_claimant_corpus_covers_the_complete_scoped_action_catalog() -> None:
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
        "specs/desired_program_model/generated/spec-unit/claimant_refresh_cases"
    )
    metadata = read(MODEL_ROOT / "claimant_refresh_actions.yml")
    assert metadata.count("ReconcileClaimingProjectFromSelectedParent:") == 1
    assert "layer: internal" in metadata
    assert "controllability: unit_direct" in metadata
    assert "- spec_unit" in metadata


def test_claimant_slice_artifacts_do_not_embed_machine_specific_paths() -> None:
    paths = [
        *MODEL_ROOT.glob("ClaimantRefresh*"),
        *(
            MODEL_ROOT / "generated/spec-unit/claimant_refresh_cases"
        ).glob("*"),
    ]
    for path in paths:
        if path.is_file():
            text = read(path)
            assert "/private/tmp/" not in text, path
            assert "/Users/" not in text, path
