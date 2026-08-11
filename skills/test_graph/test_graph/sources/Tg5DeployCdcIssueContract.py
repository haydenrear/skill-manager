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
from branch_environment_harness import deploy_cdc_issue_body_path


ISSUE_URL = "https://github.com/haydenrear/deploy-cdc/issues/6"
REQUIRED_SNIPPETS = {
    "title": "# Add deploy-helm environment repository templates for test graph branch environments",
    "local_template": "templates/local-preview",
    "aws_template": "templates/aws-preview",
    "source": "git@github.com:haydenrear/deploy-cdc.git",
    "tofu_init": "Run `tofu init`.",
    "tofu_apply": "Run `tofu apply -auto-approve` for first provision and reset.",
    "tofu_output": "Run `tofu output -json`.",
    "tofu_destroy": "Run `tofu destroy -auto-approve` only for merge-time destroy",
    "environment_id": "`EnvironmentId`: stable branch-scoped environment id or name.",
    "kubeconfig": "`KUBECONFIG`: absolute path to a kubeconfig",
    "kubecontext": "`KUBECONTEXT`: Kubernetes context name",
    "helm_deploy": "`helm-deploy`",
    "k3d": "k3d/k3s",
    "registry": "Docker registry",
    "kueue": "Kueue",
    "computeq": "CompuTeQ/OpenTofu",
    "aws_guard": "AWS preview validation must be explicitly selected and credential-gated",
    "sdk_boundary": "no `test_graph` SDK code imports deploy-cdc",
}

SPEC = (
    NodeSpec("tg5.deploy_cdc.issue.contract")
    .kind("assertion")
    .tags("tg5", "deploy-cdc", "environment-repository")
    .output("deployCdcIssueBodyPath")
    .output("deployCdcIssueUrl")
)


@node(SPEC)
def main(ctx):
    issue_body = deploy_cdc_issue_body_path()
    text = issue_body.read_text(encoding="utf-8") if issue_body.is_file() else ""

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("issue_body_is_durable_reference", issue_body.is_file() and "references/tickets" in issue_body.as_posix())
        .publish("deployCdcIssueBodyPath", str(issue_body))
        .publish("deployCdcIssueUrl", ISSUE_URL)
    )

    for name, snippet in REQUIRED_SNIPPETS.items():
        result.assertion(f"issue_body_contains_{name}", snippet in text)

    result.assertion("issue_body_rejects_nested_git_fixtures", "checked-in nested `.git` fixtures are outside the contract" in text)
    result.assertion("issue_body_rejects_tarball_fixtures", "tarballs, archives" in text)
    result.assertion("issue_body_requires_local_validation_graph", "Add a deploy-cdc test graph that validates the contract locally" in text)
    result.artifact("deploy-cdc-issue-body", str(issue_body))
    return result.log(f"Validated deploy-cdc issue contract artifact for {ISSUE_URL}.")


if __name__ == "__main__":
    main()
