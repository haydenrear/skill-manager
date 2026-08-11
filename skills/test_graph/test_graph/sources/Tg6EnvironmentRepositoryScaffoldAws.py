# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import json
import sys
from pathlib import Path

from testgraphsdk import EnvironmentRepository, NodeResult, NodeSpec, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from branch_environment_harness import (
    environment_output_keys,
    git,
    init_environment_repository,
    reset_environment_repository_contract_state,
    scaffold_environment_repository,
    scaffolded_environment_repository_dir,
)


REPOSITORY_DIR = scaffolded_environment_repository_dir("aws-preview")
TEMPLATE = "templates/branch-preview"
REPOSITORY = EnvironmentRepository.of(
    "build/tg6-environment-repository-aws-preview",
    TEMPLATE,
).with_target("aws-preview").with_backend("aws")

SPEC = (
    NodeSpec("tg6.environment.repository.scaffold.aws")
    .kind("fixture")
    .tags("tg6", "environment", "repository-scaffold", "aws")
    .environment_repository(REPOSITORY)
    .output("environmentRepositoryPath")
    .output("environmentRepositoryCommit")
)


@node(SPEC)
def main(ctx):
    reset_environment_repository_contract_state()
    repo = scaffold_environment_repository(
        REPOSITORY_DIR,
        targets=["local-preview", "aws-preview"],
        include_tofu_shim=True,
    )
    commit = init_environment_repository(repo)
    status = git(repo, "status", "--porcelain")
    template_dir = repo / TEMPLATE
    described = json.loads(SPEC.to_json())
    repository = described["environmentRepository"]

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("aws_repository_has_git_dir", (repo / ".git").is_dir())
        .assertion("aws_repository_has_initial_commit", bool(commit))
        .assertion("aws_repository_is_clean", status == "")
        .assertion("shared_local_template_still_exists", (template_dir / "local.tf").is_file())
        .assertion("aws_template_exists", (template_dir / "aws.tf").is_file())
        .assertion("required_outputs_exist", {"EnvironmentId", "KUBECONFIG", "KUBECONTEXT"} <= environment_output_keys(repo, TEMPLATE))
        .assertion("metadata_targets_aws_preview", repository["target"] == "aws-preview")
        .assertion("metadata_backend_is_aws", repository["backend"] == "aws")
        .assertion("aws_scaffold_does_not_provision_in_normal_graph", "environment:provision" not in described.get("sideEffects", []))
        .artifact("scaffolded-aws-environment-repository", str(repo))
        .publish("environmentRepositoryPath", str(repo))
        .publish("environmentRepositoryCommit", commit)
    )


if __name__ == "__main__":
    main()
