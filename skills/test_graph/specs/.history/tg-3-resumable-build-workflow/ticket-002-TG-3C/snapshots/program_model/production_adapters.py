"""Repository-local adapters for TestGraph program-model cases.

The first onboarding baseline records the semantic boundaries and keeps the
adapters non-executable. A later ticket can replace ``can_run=False`` with
real setup, invocation, observation, and refinement against the generated
state-graph cases.
"""

from __future__ import annotations


class _DocumentedBoundaryAdapter:
    """Shared conservative adapter shape for onboarding."""

    boundary = "unassigned"
    files: tuple[str, ...] = ()

    def can_run(self, case):
        del case
        return False, (
            f"{self.boundary} is documented in specs/program_model/spec_manifest.yaml; "
            "wire an executable refinement adapter in a future behavior ticket"
        )


class ScaffoldProjectAdapter(_DocumentedBoundaryAdapter):
    boundary = "scaffold_project"
    files = ("scripts/scaffold.py", "project_sdk_sources/")


class GraphPlanningAdapter(_DocumentedBoundaryAdapter):
    boundary = "graph_planning"
    files = (
        "scripts/discover.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/GraphAssembler.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/GraphModel.kt",
    )


class GraphRunAdapter(_DocumentedBoundaryAdapter):
    boundary = "graph_run"
    files = (
        "scripts/run.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
    )


class ReportAdapter(_DocumentedBoundaryAdapter):
    boundary = "reports"
    files = (
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/ValidationReportTask.kt",
    )
