# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node


REPO_ROOT = Path(__file__).resolve().parents[2]
REFERENCE_PATH = REPO_ROOT / "references" / "environment-repositories.md"
SKILL_PATH = REPO_ROOT / "SKILL.md"
REFERENCE_SUMMARY_PATH = REPO_ROOT / "references" / "reference.md"
WORKFLOWS_PATH = REPO_ROOT / "references" / "workflows.md"
ACTIVE_TICKET_PLAN_PATH = REPO_ROOT / "specs" / "desired_program_model" / "ticket_plan.yaml"
HISTORY_TICKET_PLAN_PATH = (
    REPO_ROOT
    / "specs"
    / ".history"
    / "environment-repository-scaffolding-polyglot-lifecycle"
    / "closed-snapshot"
    / "snapshots"
    / "desired_program_model"
    / "ticket_plan.yaml"
)

SPEC = (
    NodeSpec("tg6.environment.repository.documentation")
    .kind("assertion")
    .tags("tg6", "environment-repository", "documentation")
    .output("environmentRepositoryReference")
)


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def _ticket_plan_text() -> str:
    if ACTIVE_TICKET_PLAN_PATH.is_file():
        return _text(ACTIVE_TICKET_PLAN_PATH)
    return _text(HISTORY_TICKET_PLAN_PATH)


@node(SPEC)
def main(ctx):
    reference = _text(REFERENCE_PATH)
    lower_reference = reference.lower()
    skill = _text(SKILL_PATH)
    reference_summary = _text(REFERENCE_SUMMARY_PATH)
    workflows = _text(WORKFLOWS_PATH)
    ticket_plan = _ticket_plan_text()

    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("environment_repositories_reference_exists", REFERENCE_PATH.is_file())
        .assertion("skill_routes_to_environment_repositories_reference", "references/environment-repositories.md" in skill)
        .assertion("api_reference_points_to_environment_repositories_reference", "environment-repositories.md" in reference_summary)
        .assertion("workflow_guide_points_to_environment_repositories_reference", "environment-repositories.md" in workflows)
        .publish("environmentRepositoryReference", str(REFERENCE_PATH))
        .artifact("environment-repositories-reference", str(REFERENCE_PATH))
    )

    for name, snippet in {
        "git_source_contract": "Ordinary Git URL or local Git repository path",
        "git_fixture_initialization": "git init",
        "git_fixture_add": "git add",
        "git_fixture_commit": "git commit",
        "nested_git_rejected": "nested `.git`",
        "tarball_rejected": "tarball",
        "local_preview_target": "local-preview",
        "github_action_target": "local-github-action",
        "aws_preview_target": "aws-preview",
        "environment_id_output": "EnvironmentId",
        "kubeconfig_output": "KUBECONFIG",
        "kubecontext_output": "KUBECONTEXT",
        "scaffold_tf_env_script": "scripts/scaffold-tf-env.py",
        "scaffold_env_script": "scripts/scaffold-env.py",
    }.items():
        result.assertion(f"reference_contains_{name}", snippet in reference)

    for name, phrase in {
        "local_k3d_setup": "local k3d setup",
        "missing_cluster_deploy": "missing cluster deploy",
        "existing_cluster_reuse": "existing cluster reuse",
        "reset": "reset",
        "explicit_teardown": "explicit teardown",
        "skip_teardown": "skip teardown",
        "external_evidence": "external evidence",
        "not_only_exit_code": "do not rely only on a\nnode process exit code",
    }.items():
        result.assertion(f"reference_requires_{name}", phrase in lower_reference)

    for target in ["local", "GitHub Actions", "AWS"]:
        result.assertion(f"ticket_plan_requires_{target.lower().replace(' ', '_')}_graph_surface", target in ticket_plan)

    result.assertion("ticket_plan_requires_functionality_graphs", "functionality_graph_rule" in ticket_plan)
    result.assertion("ticket_plan_requires_exhaustive_environment_graphs", "exhaustive_environment_graph_rule" in ticket_plan)
    return result.log("Validated TG-6A environment repository documentation routing and coverage requirements.")


if __name__ == "__main__":
    main()
