# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

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


REPOSITORY_DIR = scaffolded_environment_repository_dir("local-preview")
TEMPLATE = "templates/branch-preview"
REPOSITORY = EnvironmentRepository.of(
    "build/tg6-environment-repository-local-preview",
    TEMPLATE,
).with_target("local-preview").with_backend("local")

SPEC = (
    NodeSpec("tg6.environment.repository.scaffold.local")
    .kind("fixture")
    .tags("tg6", "environment", "repository-scaffold", "local")
    .environment_repository(REPOSITORY)
    .output("environmentRepositoryPath")
    .output("environmentRepositoryFileUrl")
    .output("environmentRepositoryCommit")
    .output("environmentRepositoryTemplate")
)


@node(SPEC)
def main(ctx):
    reset_environment_repository_contract_state()
    repo = scaffold_environment_repository(REPOSITORY_DIR, targets=["local-preview"], include_tofu_shim=True)
    commit = init_environment_repository(repo)
    status = git(repo, "status", "--porcelain")
    template_dir = repo / TEMPLATE
    output_keys = environment_output_keys(repo, TEMPLATE)

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("scaffolded_repository_has_git_dir", (repo / ".git").is_dir())
        .assertion("scaffolded_repository_has_initial_commit", bool(commit))
        .assertion("scaffolded_repository_is_clean", status == "")
        .assertion("template_directory_exists", template_dir.is_dir())
        .assertion("local_template_exists", (template_dir / "local.tf").is_file())
        .assertion("local_tofu_shim_is_executable", (repo / "bin" / "tofu").is_file() and (repo / "bin" / "tofu").stat().st_mode & 0o111 != 0)
        .assertion("required_outputs_exist", {"EnvironmentId", "KUBECONFIG", "KUBECONTEXT"} <= output_keys)
        .artifact("scaffolded-local-environment-repository", str(repo))
        .publish("environmentRepositoryPath", str(repo))
        .publish("environmentRepositoryFileUrl", repo.resolve().as_uri())
        .publish("environmentRepositoryCommit", commit)
        .publish("environmentRepositoryTemplate", TEMPLATE)
    )


if __name__ == "__main__":
    main()
