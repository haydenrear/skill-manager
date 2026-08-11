from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def run_script(script: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(REPO_ROOT / "scripts" / script), *args],
        cwd=REPO_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def run_git(repo: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return completed.stdout.strip()


def test_scaffold_tf_env_creates_git_ready_environment_repository(tmp_path: Path) -> None:
    repo = tmp_path / "env-repo"

    run_script("scaffold-tf-env.py", str(repo), "--include-tofu-shim")

    template = repo / "templates" / "branch-preview"
    assert (repo / "README.md").is_file()
    assert (repo / ".gitignore").is_file()
    assert (template / "main.tf").is_file()
    assert (template / "variables.tf").is_file()
    assert (template / "outputs.tf").is_file()
    assert (template / "local.tf").is_file()
    assert os.access(repo / "bin" / "tofu", os.X_OK)

    outputs = (template / "outputs.tf").read_text(encoding="utf-8")
    for key in ["EnvironmentId", "KUBECONFIG", "KUBECONTEXT"]:
        assert f'output "{key}"' in outputs

    run_git(repo, "init")
    run_git(repo, "config", "user.email", "test-graph@example.invalid")
    run_git(repo, "config", "user.name", "Test Graph")
    run_git(repo, "add", ".")
    run_git(repo, "commit", "-m", "Initial scaffolded environment repository")
    assert run_git(repo, "status", "--porcelain") == ""


def test_scaffold_env_adds_targets_without_clobbering_existing_templates(tmp_path: Path) -> None:
    repo = tmp_path / "env-repo"
    run_script("scaffold-tf-env.py", str(repo))

    local_tf = repo / "templates" / "branch-preview" / "local.tf"
    readme = repo / "README.md"
    main_tf = repo / "templates" / "branch-preview" / "main.tf"
    before = local_tf.read_text(encoding="utf-8")
    readme_custom = readme.read_text(encoding="utf-8") + "\nCustom operator notes.\n"
    main_custom = main_tf.read_text(encoding="utf-8") + "\n# custom provider wiring\n"
    readme.write_text(readme_custom, encoding="utf-8")
    main_tf.write_text(main_custom, encoding="utf-8")

    run_script("scaffold-env.py", str(repo), "--target", "local-github-action")
    run_script("scaffold-env.py", str(repo), "--target", "aws-preview")

    assert local_tf.read_text(encoding="utf-8") == before
    assert readme.read_text(encoding="utf-8") == readme_custom
    assert main_tf.read_text(encoding="utf-8") == main_custom
    assert (repo / "templates" / "branch-preview" / "local-github-action.tf").is_file()
    assert (repo / "templates" / "branch-preview" / "aws.tf").is_file()

    local_tf.write_text(before + "\n# user customization\n", encoding="utf-8")
    duplicate = run_script("scaffold-env.py", str(repo), "--target", "local-preview", check=False)
    assert duplicate.returncode != 0
    assert "already exists with different content" in duplicate.stderr


def test_scaffold_env_can_add_lifecycle_node_templates(tmp_path: Path) -> None:
    repo = tmp_path / "env-repo"
    sources = tmp_path / "test_graph" / "sources"
    run_script("scaffold-tf-env.py", str(repo))

    run_script(
        "scaffold-env.py",
        str(repo),
        "--target",
        "aws-preview",
        "--lifecycle-nodes-dir",
        str(sources),
    )

    expected = {
        "deploy_cluster.py": "deploy_cluster(",
        "reset_node.py": "reset_node(",
        "delete_cluster.py": "delete_cluster(",
        "DeployCluster.java": "ClusterLifecycle.deployCluster",
        "ResetNode.java": "ClusterLifecycle.resetNode",
        "DeleteCluster.java": "ClusterLifecycle.deleteCluster",
    }
    for filename, needle in expected.items():
        text = (sources / filename).read_text(encoding="utf-8")
        assert needle in text
        assert "aws-preview" in text
        assert "aws" in text
