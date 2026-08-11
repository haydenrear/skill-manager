from __future__ import annotations

import sys
from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]
PYTHON_SDK = REPO_ROOT / "project_sdk_sources" / "sdk" / "python" / "src"
sys.path.insert(0, str(PYTHON_SDK))

from testgraphsdk import delete_cluster, deploy_cluster, reset_node  # noqa: E402


def test_lifecycle_helper_dispatches_all_targets() -> None:
    cases = [
        ("local-preview", "local", "local", False),
        ("local-github-action", "github-action", "github-action", False),
        ("aws-preview", "aws", "aws", True),
    ]

    for target, backend, dispatch, requires_explicit in cases:
        plan = deploy_cluster(target, backend)
        assert plan.dispatch_key == dispatch
        assert plan.backend == backend
        assert plan.environment_action == "provision"
        assert plan.tofu_command == ("tofu", "apply", "-auto-approve")
        assert plan.requires_explicit_selection is requires_explicit


def test_lifecycle_helper_records_reset_and_delete_skip_semantics() -> None:
    just_provisioned = reset_node("local-preview", "local", just_provisioned=True)
    already_reset = reset_node("local-preview", "local", already_reset=True)
    keep_alive = delete_cluster("local-preview", "local", destroy_requested=False)
    destroy = delete_cluster("local-preview", "local", destroy_requested=True)

    assert just_provisioned.should_run is False
    assert just_provisioned.skip_reason == "just-provisioned"
    assert already_reset.should_run is False
    assert already_reset.skip_reason == "already-reset"
    assert keep_alive.should_run is False
    assert keep_alive.skip_reason == "destroy-not-requested"
    assert keep_alive.expected_state == "kept-active"
    assert destroy.should_run is True
    assert destroy.environment_action == "destroy"
    assert destroy.tofu_command == ("tofu", "destroy", "-auto-approve")


def test_lifecycle_template_graph_contains_uv_and_jbang_nodes() -> None:
    graph = (REPO_ROOT / "test_graph" / "build.gradle.kts").read_text(encoding="utf-8")
    for source in [
        "sources/deploy_cluster.py",
        "sources/reset_node.py",
        "sources/delete_cluster.py",
        "sources/DeployCluster.java",
        "sources/ResetNode.java",
        "sources/DeleteCluster.java",
    ]:
        assert source in graph
