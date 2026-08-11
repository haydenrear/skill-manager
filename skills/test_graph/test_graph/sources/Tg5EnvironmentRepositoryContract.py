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
from branch_environment_harness import ticket_plan_text


ENV_REPOSITORY = EnvironmentRepository.of(
    "https://github.com/example/test-graph-environments.git",
    "templates/local-preview",
).with_output_keys("EnvironmentId", "KUBECONFIG", "KUBECONTEXT", "API_BASE_URL")

SPEC = (
    NodeSpec("tg5.environment.repository.contract")
    .kind("assertion")
    .depends_on("tg5.environment.markers.present")
    .tags("tg5", "environment", "repository-contract")
    .environment_repository(ENV_REPOSITORY)
    .output("environmentRepositorySource")
)


@node(SPEC)
def main(ctx):
    described = json.loads(SPEC.to_json())
    contract = described.get("environmentRepository", {})
    plan = ticket_plan_text()

    source = contract.get("source", "")
    template = contract.get("template", "")
    outputs = set(contract.get("outputKeys", []))

    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("contract_serializes_git_source", source.startswith("https://") and source.endswith(".git"))
        .assertion("contract_uses_repository_template_path", template == "templates/local-preview")
        .assertion("contract_names_local_target_backend", contract.get("target") == "local-preview" and contract.get("backend") == "local")
        .assertion("contract_declares_feature_branch_scope", contract.get("branch") == "feature")
        .assertion("contract_includes_kubernetes_outputs", {"EnvironmentId", "KUBECONFIG", "KUBECONTEXT"} <= outputs)
        .assertion("contract_rejects_tarball_primary_fixture_in_plan", "tarball-based fixture as the primary test approach" in plan)
        .assertion("contract_stays_deploy_helm_neutral", "deploy-helm should not be imported by SDK contract tests" in plan)
        .publish("environmentRepositorySource", source)
    )


if __name__ == "__main__":
    main()
