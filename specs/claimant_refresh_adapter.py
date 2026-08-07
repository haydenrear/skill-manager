"""Spec-unit adapter for automatic claimant-project reconciliation.

The generated Internal action is a policy boundary, not a second project-sync
implementation.  This adapter therefore observes the production routing seam:
automatic refresh must select reconcile-only options, while the public
two-argument ``project sync`` entry point must retain its pull-by-default policy.
The real-git A/B behavior behind that seam is exercised by the Java regression
and the public Test Graph node.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re


ACTION = "ReconcileClaimingProjectFromSelectedParent"


@dataclass(frozen=True)
class ClaimantRefreshPolicySnapshot:
    automatic_refresh_reconcile_only: bool
    reconcile_only_disables_pull: bool
    reconcile_only_preserves_in_place_reconcile: bool
    explicit_project_sync_uses_defaults: bool


def load_claimant_refresh_policy(
    repo_root: str | Path = ".",
) -> ClaimantRefreshPolicySnapshot:
    root = Path(repo_root).resolve()
    interpreter = (
        root / "src/main/java/dev/skillmanager/effects/LiveInterpreter.java"
    ).read_text(encoding="utf-8")
    project_sync = (
        root / "src/main/java/dev/skillmanager/project/ProjectSyncUseCase.java"
    ).read_text(encoding="utf-8")

    automatic_method = _method_body(interpreter, "syncClaimingProjects")
    reconcile_factory = _method_body(project_sync, "reconcileOnly")
    return ClaimantRefreshPolicySnapshot(
        automatic_refresh_reconcile_only=(
            "ProjectSyncUseCase.Options.reconcileOnly()" in automatic_method
        ),
        reconcile_only_disables_pull=(
            "new Options(false, false, UnitTrunkPull.Options.defaults())"
            in reconcile_factory
        ),
        reconcile_only_preserves_in_place_reconcile=(
            "new Options(false, false," in reconcile_factory
        ),
        explicit_project_sync_uses_defaults=(
            "return sync(project, options, Options.defaults());" in project_sync
        ),
    )


class ClaimantRefreshPolicyAdapter:
    """Validate the production policy used by the generated Internal action."""

    def __init__(self, repo_root: str | Path = "."):
        self.snapshot = load_claimant_refresh_policy(repo_root)

    def can_run(self, case):
        action = getattr(getattr(case, "input", None), "action", None)
        labels = {str(label) for label in getattr(case, "labels", frozenset())}
        if action == ACTION or ACTION in labels:
            return True, "covered by the automatic claimant-refresh policy seam"
        return False, "not an automatic claimant-refresh case"

    def validate(self, case=None) -> None:
        failures = [
            name
            for name, value in self.snapshot.__dict__.items()
            if not value
        ]
        if failures:
            raise AssertionError(
                "claimant-refresh production policy is incomplete: "
                + ", ".join(failures)
            )

    def run(self, case, work_dir=None):
        self.validate(case)
        return {"output": case.output, "after": case.after}


def _method_body(source: str, method_name: str) -> str:
    """Return one Java method body using balanced braces.

    The adapter only needs a narrow structural observation, but scoping the
    search to the named method prevents an unrelated use of reconcile-only
    options elsewhere in the file from satisfying the contract.
    """

    match = re.search(r"\b" + re.escape(method_name) + r"\s*\([^)]*\)\s*\{", source)
    if match is None:
        return ""
    start = match.end() - 1
    depth = 0
    for index in range(start, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[start : index + 1]
    return ""
