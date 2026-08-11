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

from testgraphsdk import NodeResult, NodeSpec, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from branch_environment_harness import (
    copy_stable_environment_repository_source,
    git,
    init_environment_repository,
    local_preview_output_keys,
    reset_environment_repository_contract_state,
)


SPEC = (
    NodeSpec("tg5.environment.repository.fixture.stable")
    .kind("fixture")
    .tags("tg5", "environment", "repository-fixture")
    .output("environmentRepositoryPath")
    .output("environmentRepositoryFileUrl")
    .output("environmentRepositoryCommit")
)


@node(SPEC)
def main(ctx):
    reset_environment_repository_contract_state()
    generated = copy_stable_environment_repository_source()
    commit = init_environment_repository(generated)
    status = git(generated, "status", "--porcelain")
    tofu = generated / "bin" / "tofu"
    output_keys = local_preview_output_keys(generated)

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("stable_repository_has_git_dir", (generated / ".git").is_dir())
        .assertion("stable_repository_has_initial_commit", bool(commit))
        .assertion("stable_repository_is_clean", status == "")
        .assertion("fixture_local_tofu_is_executable", tofu.is_file() and tofu.stat().st_mode & 0o111 != 0)
        .assertion("local_preview_outputs_required_keys", {"EnvironmentId", "KUBECONFIG", "KUBECONTEXT"} <= output_keys)
        .artifact("stable-environment-repository", str(generated))
        .publish("environmentRepositoryPath", str(generated))
        .publish("environmentRepositoryFileUrl", generated.resolve().as_uri())
        .publish("environmentRepositoryCommit", commit)
    )


if __name__ == "__main__":
    main()
