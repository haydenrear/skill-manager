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
    copy_environment_repository_source,
    environment_repository_source_dir,
    git,
    init_environment_repository,
    local_preview_output_keys,
)

REQUIRED_OUTPUT_KEYS = {"EnvironmentId", "KUBECONFIG", "KUBECONTEXT"}

SPEC = (
    NodeSpec("tg5.environment.repository.fixture.generated")
    .kind("fixture")
    .tags("tg5", "environment", "repository-fixture")
    .output("environmentRepositoryPath")
    .output("environmentRepositoryFileUrl")
    .output("environmentRepositoryCommit")
    .output("environmentRepositoryTemplate")
    .output("environmentRepositoryOutputKeys")
)


@node(SPEC)
def main(ctx):
    source = environment_repository_source_dir()
    source_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(source.rglob("*"))
        if path.is_file()
    )
    generated = copy_environment_repository_source(ctx.report_dir)
    commit = init_environment_repository(generated)
    status = git(generated, "status", "--porcelain")
    output_keys = local_preview_output_keys(generated)
    template = "templates/local-preview"
    template_dir = generated / template

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("fixture_source_exists", source.is_dir())
        .assertion("fixture_source_is_not_nested_git_repo", not (source / ".git").exists())
        .assertion("generated_repository_has_git_dir", (generated / ".git").is_dir())
        .assertion("generated_repository_has_initial_commit", bool(commit))
        .assertion("generated_repository_is_clean", status == "")
        .assertion("local_preview_template_exists", (template_dir / "main.tf").is_file())
        .assertion("local_preview_outputs_required_keys", REQUIRED_OUTPUT_KEYS <= output_keys)
        .assertion("fixture_is_deploy_helm_neutral", "deploy-helm" not in source_text)
        .artifact("environment-repository-source", str(source))
        .artifact("generated-environment-repository", str(generated))
        .publish("environmentRepositoryPath", str(generated))
        .publish("environmentRepositoryFileUrl", generated.resolve().as_uri())
        .publish("environmentRepositoryCommit", commit)
        .publish("environmentRepositoryTemplate", template)
        .publish("environmentRepositoryOutputKeys", ",".join(sorted(output_keys)))
    )
    return result.log("Generated a report-local Git environment repository fixture from versioned source files.")


if __name__ == "__main__":
    main()
