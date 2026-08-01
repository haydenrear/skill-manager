from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class LifecycleTarget:
    target: str
    backend: str
    dispatch_key: str
    requires_explicit_selection: bool = False


@dataclass(frozen=True)
class ClusterLifecyclePlan:
    command: str
    target: str
    backend: str
    dispatch_key: str
    environment_action: str | None
    tofu_command: tuple[str, ...]
    should_run: bool
    expected_state: str
    skip_reason: str = ""
    just_provisioned: bool = False
    already_reset: bool = False
    destroy_requested: bool = False
    requires_explicit_selection: bool = False

    def published(self) -> dict[str, str]:
        return {
            "LifecycleCommand": self.command,
            "LifecycleTarget": self.target,
            "LifecycleBackend": self.backend,
            "LifecycleDispatchKey": self.dispatch_key,
            "LifecycleEnvironmentAction": self.environment_action or "",
            "LifecycleTofuCommand": " ".join(self.tofu_command),
            "LifecycleShouldRun": str(self.should_run).lower(),
            "LifecycleExpectedState": self.expected_state,
            "LifecycleSkipReason": self.skip_reason,
            "LifecycleJustProvisioned": str(self.just_provisioned).lower(),
            "LifecycleAlreadyReset": str(self.already_reset).lower(),
            "LifecycleDestroyRequested": str(self.destroy_requested).lower(),
            "LifecycleRequiresExplicitSelection": str(self.requires_explicit_selection).lower(),
        }


_TARGETS = {
    "local-preview": LifecycleTarget("local-preview", "local", "local"),
    "local-github-action": LifecycleTarget("local-github-action", "github-action", "github-action"),
    "aws-preview": LifecycleTarget("aws-preview", "aws", "aws", requires_explicit_selection=True),
}


def lifecycle_targets() -> tuple[LifecycleTarget, ...]:
    return tuple(_TARGETS.values())


def resolve_lifecycle_target(target: str, backend: str | None = None) -> LifecycleTarget:
    try:
        selected = _TARGETS[target]
    except KeyError as exc:
        valid = ", ".join(sorted(_TARGETS))
        raise ValueError(f"unsupported lifecycle target {target!r}; expected one of: {valid}") from exc
    if backend is not None and backend != selected.backend:
        raise ValueError(
            f"lifecycle target {target!r} requires backend {selected.backend!r}, got {backend!r}"
        )
    return selected


def deploy_cluster(target: str, backend: str | None = None, *, environment_exists: bool = False) -> ClusterLifecyclePlan:
    selected = resolve_lifecycle_target(target, backend)
    return ClusterLifecyclePlan(
        command="deploy_cluster",
        target=selected.target,
        backend=selected.backend,
        dispatch_key=selected.dispatch_key,
        environment_action="provision",
        tofu_command=("tofu", "apply", "-auto-approve"),
        should_run=True,
        expected_state="reuse-existing" if environment_exists else "provision-missing",
        requires_explicit_selection=selected.requires_explicit_selection,
    )


def reset_node(
    target: str,
    backend: str | None = None,
    *,
    just_provisioned: bool = False,
    already_reset: bool = False,
) -> ClusterLifecyclePlan:
    selected = resolve_lifecycle_target(target, backend)
    skip_reason = ""
    if just_provisioned:
        skip_reason = "just-provisioned"
    elif already_reset:
        skip_reason = "already-reset"
    should_run = skip_reason == ""
    return ClusterLifecyclePlan(
        command="reset_node",
        target=selected.target,
        backend=selected.backend,
        dispatch_key=selected.dispatch_key,
        environment_action="reset" if should_run else None,
        tofu_command=("tofu", "apply", "-auto-approve") if should_run else (),
        should_run=should_run,
        expected_state="reset" if should_run else "kept-active",
        skip_reason=skip_reason,
        just_provisioned=just_provisioned,
        already_reset=already_reset,
        requires_explicit_selection=selected.requires_explicit_selection,
    )


def delete_cluster(
    target: str,
    backend: str | None = None,
    *,
    destroy_requested: bool = False,
) -> ClusterLifecyclePlan:
    selected = resolve_lifecycle_target(target, backend)
    return ClusterLifecyclePlan(
        command="delete_cluster",
        target=selected.target,
        backend=selected.backend,
        dispatch_key=selected.dispatch_key,
        environment_action="destroy" if destroy_requested else None,
        tofu_command=("tofu", "destroy", "-auto-approve") if destroy_requested else (),
        should_run=destroy_requested,
        expected_state="destroyed" if destroy_requested else "kept-active",
        skip_reason="" if destroy_requested else "destroy-not-requested",
        destroy_requested=destroy_requested,
        requires_explicit_selection=selected.requires_explicit_selection,
    )
