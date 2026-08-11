from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from testgraphsdk import EnvironmentRepository, NodeSpec, SideEffect


TEMPLATE = "templates/branch-preview"
TRUTHY = {"1", "true", "yes", "y"}
DESTROY_KEYS = ("TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT", "TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT")
AWS_SELECTION_KEYS = ("TEST_GRAPH_RUN_AWS_LIFECYCLE", "TESTGRAPH_RUN_AWS_LIFECYCLE")
AWS_CREDENTIAL_KEYS = ("AWS_PROFILE", "AWS_ACCESS_KEY_ID", "AWS_WEB_IDENTITY_TOKEN_FILE")


@dataclass(frozen=True)
class LifecycleProfile:
    name: str
    target: str
    backend: str
    source: str
    scaffold_node: str
    provision_node: str
    deploy_node: str
    reset_node: str
    marker_name: str

    @property
    def tags(self) -> tuple[str, ...]:
        return ("tg6", "environment", f"{self.name}-lifecycle")

    @property
    def repository(self) -> EnvironmentRepository:
        return EnvironmentRepository.of(self.source, TEMPLATE).with_target(self.target).with_backend(self.backend)


GITHUB_ACTION = LifecycleProfile(
    name="github-action",
    target="local-github-action",
    backend="github-action",
    source="build/tg6-environment-repository-local-github-action",
    scaffold_node="tg6.environment.repository.scaffold.github-action",
    provision_node="tg6.github-action.lifecycle.provision-missing",
    deploy_node="tg6.github-action.lifecycle.deploy-existing",
    reset_node="tg6.github-action.lifecycle.reset",
    marker_name="tg6-github-action-lifecycle-application.ready",
)


AWS = LifecycleProfile(
    name="aws",
    target="aws-preview",
    backend="aws",
    source="build/tg6-environment-repository-aws-preview",
    scaffold_node="tg6.environment.repository.scaffold.aws",
    provision_node="tg6.aws.lifecycle.provision-missing",
    deploy_node="tg6.aws.lifecycle.deploy-existing",
    reset_node="tg6.aws.lifecycle.reset",
    marker_name="tg6-aws-lifecycle-application.ready",
)


def truthy_env(keys: tuple[str, ...]) -> bool:
    return any(os.environ.get(key, "").strip().lower() in TRUTHY for key in keys)


def destroy_requested() -> bool:
    return truthy_env(DESTROY_KEYS)


def destroy_intent_absent_or_falsey() -> bool:
    return all(os.environ.get(key, "").strip().lower() not in TRUTHY for key in DESTROY_KEYS)


def aws_selected() -> bool:
    return truthy_env(AWS_SELECTION_KEYS)


def aws_credentials_present() -> bool:
    return any(os.environ.get(key, "").strip() for key in AWS_CREDENTIAL_KEYS)


def aws_lifecycle_enabled() -> bool:
    return aws_selected() and aws_credentials_present()


def aws_guard_reason() -> str:
    if not aws_selected():
        return "aws-lifecycle-not-selected"
    if not aws_credentials_present():
        return "aws-credentials-missing"
    return ""


def lifecycle_spec(
    node_id: str,
    kind: str,
    profile: LifecycleProfile,
    *,
    action: str | None = None,
    depends_on: tuple[str, ...] = (),
    outputs: tuple[str, ...] = (),
    extra_tags: tuple[str, ...] = (),
    enabled: bool = True,
) -> NodeSpec:
    spec = NodeSpec(node_id).kind(kind).depends_on(*depends_on).tags(*profile.tags, *extra_tags)
    if action is not None and enabled:
        spec = spec.side_effects(SideEffect.environment(action)).environment_repository(profile.repository)
    for output in outputs:
        spec = spec.output(output)
    return spec


def application_ready_path(kubeconfig: Path, profile: LifecycleProfile) -> Path:
    return kubeconfig.with_name(profile.marker_name)
