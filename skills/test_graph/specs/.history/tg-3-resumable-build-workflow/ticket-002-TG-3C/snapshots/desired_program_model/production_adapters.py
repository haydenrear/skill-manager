"""Repository-local adapters for TestGraph desired-model cases.

The TG-3 desired model records target semantic boundaries and keeps adapters
non-executable. As specs/current advances, replace these documented boundaries
with real setup, invocation, observation, and refinement against generated
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
            f"{self.boundary} is documented in specs/desired_program_model/spec_manifest.yaml; "
            "wire an executable refinement adapter when specs/current implements this boundary"
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


class InputContextSnapshotAdapter(_DocumentedBoundaryAdapter):
    boundary = "input_context_snapshots"
    files = (
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Context.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
    )


class RerunMetadataAdapter(_DocumentedBoundaryAdapter):
    boundary = "rerun_metadata"
    files = (
        "project_sdk_sources/sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/NodeSpec.java",
        "project_sdk_sources/sdk/python/src/testgraphsdk/node_spec.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/ValidationNodeSpec.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/NodeDescribeLoader.kt",
    )


class ResumeRunFromBuildAdapter(_DocumentedBoundaryAdapter):
    boundary = "resume_run_from_build"
    files = (
        "scripts/run.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
    )


class RunOnlyNodeFromBuildAdapter(_DocumentedBoundaryAdapter):
    boundary = "run_only_node_from_build"
    files = (
        "scripts/run.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Executors.kt",
    )


class RerunGuidanceAdapter(_DocumentedBoundaryAdapter):
    boundary = "rerun_guidance"
    files = (
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt",
        "references/workflows.md",
        "references/reference.md",
    )
