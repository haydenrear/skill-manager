import importlib.util
from pathlib import Path
from types import ModuleType


MODEL = Path(__file__).resolve().parents[1]


def _repo_root() -> Path:
    for candidate in MODEL.parents:
        if (candidate / "src/main/java/dev/skillmanager").is_dir():
            return candidate
    raise AssertionError("repository root not found from ticket model")


ROOT = _repo_root()


def _load_ci_graph_selector() -> ModuleType:
    """Import CI's graph-set selector as a module.

    The CI matrix is computed from ``test_graph/build.gradle.kts`` by
    ``.github/scripts/select-graph-set.py``, not typed into ``ci.yml``
    (ARTI-12, #113). "Is this graph wired into CI?" is therefore a question for
    the selector, and asking it there is a stronger check than the substring
    match on the workflow text this file used to do: ``"smoke" in ci`` was also
    satisfied by the word appearing inside ``plugin-smoke``, inside a comment,
    or inside a matrix that a job-level ``if:`` was skipping entirely — which
    is exactly what happened while ``vars.ENABLE_TEST_GRAPH`` was unset.
    """
    path = ROOT / ".github/scripts/select-graph-set.py"
    assert path.is_file(), f"CI graph selector missing at {path}"
    spec = importlib.util.spec_from_file_location("_ci_select_graph_set", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_program_model_preserves_project_child_home_and_force_script_boundaries() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")

    for needle in (
        "SyncClaimingProjectChildHomes",
        "@port SkillManagerCli.sync_claiming_project_child_homes",
        "u \\in ProjectChildHomePayload(pair[1])",
        "child_home_tool_shims = @ \\cup (homes \\X child_tools)",
        "InstallUnitForceScripts",
        "SyncUnitForceScripts",
        "OkForceScripts",
        "orphan_cli_deps == CliDepsFor({u}) \\ CliDepsFor(remaining)",
        "CliCliArtifactsAreClaimed",
        "CliCliLockRowsAreClaimed",
        "SkillScriptRunsAreClaimed",
    ):
        assert needle in tla


def test_program_model_accepts_bounded_cli_disclosure_case_surface() -> None:
    tla = (MODEL / "SkillManager.tla").read_text(encoding="utf-8")
    cfg = (MODEL / "MC.cfg").read_text(encoding="utf-8")
    manifest = (MODEL / "spec_manifest.yaml").read_text(encoding="utf-8")

    assert "SPECIFICATION CliDisclosureSpec" in cfg
    assert "CHECK_DEADLOCK FALSE" in cfg
    for needle in (
        "CliCommandCatalog ==",
        "CliWorkflowCatalog ==",
        "CliRootHelpStaysProgressive",
        "CliCommandHelpCoversCatalog",
        "CliSkillDocsCoverWorkflowCatalog",
        "CliAgentContextCoversWorkflowCatalog",
    ):
        assert needle in tla
        assert needle.replace(" ==", "") in manifest or needle in cfg


def test_program_model_validation_surfaces_remain_registered() -> None:
    graph = (ROOT / "test_graph/build.gradle.kts").read_text(encoding="utf-8")

    for graph_name in ("smoke", "doc-smoke", "plugin-smoke", "skill-dev-smoke"):
        assert f'testGraph("{graph_name}")' in graph

    selector = _load_ci_graph_selector()
    registered = selector.discover_all(ROOT)
    core, _ = selector.select("core", registered)
    full, _ = selector.select("full", registered)

    # On every push and pull request.
    for graph_name in ("smoke", "plugin-smoke"):
        assert graph_name in core, f"{graph_name} left CI's core set"

    # At minimum nightly. `doc-smoke` and `skill-dev-smoke` are budgeted to the
    # schedule, not dropped — the distinction is the whole point of #113.
    for graph_name in ("smoke", "doc-smoke", "plugin-smoke", "skill-dev-smoke"):
        assert graph_name in full, f"{graph_name} is in no CI graph set"


def test_ci_does_not_gate_the_graph_matrix_off_again() -> None:
    """The matrix ran zero graphs for two releases and every run was green.

    `vars.ENABLE_TEST_GRAPH` was a job-level `if:` that no repository variable
    satisfied, so `test-graph` and `test-graph-browser` were skipped on every
    push while the workflow still listed twelve graph names. Nothing failed,
    because a skipped job is not a red one. Re-introducing a repository-variable
    gate on the matrix is therefore a silent regression by construction, and
    this is the check that makes it loud.
    """
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    for job in ("test-graph:", "test-graph-browser:"):
        assert job in ci
    assert "if: ${{ vars.ENABLE_TEST_GRAPH == 'true' }}" not in ci
    assert "select-graph-set.py" in ci
    assert "graphs-executed" in ci
