"""Production adapters for whole-program model cases.

Each adapter materializes a generated case pre-state, calls the production
boundary, observes production state, and refines the observation back to the
generated case shape.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re


class ScaffoldedProgramModelAdapter:
    """Placeholder documenting the expected adapter shape."""

    def can_run(self, case):
        return False, "replace with a repository-specific program-model adapter"


@dataclass(frozen=True)
class CliMetadataSnapshot:
    command_paths: frozenset[str]
    aliases: dict[str, frozenset[str]]
    workflow_links: dict[str, str]

    @property
    def workflow_ids(self) -> frozenset[str]:
        return frozenset(self.workflow_links.keys())


def load_cli_metadata_source(repo_root: str | Path) -> CliMetadataSnapshot:
    """Extract the static CLI metadata catalog from production source.

    The ticket adapter is intentionally source-level for now: CLI-PROGDISC-002
    adds the metadata source of truth, while later tickets wire rendering and
    runtime context ports. The unit suite compiles and exercises the same source
    against picocli.
    """

    root = Path(repo_root)
    source = root / "src/main/java/dev/skillmanager/cli/CliMetadata.java"
    text = source.read_text(encoding="utf-8")

    command_paths: set[str] = set()
    aliases: dict[str, frozenset[str]] = {}
    for match in re.finditer(r'command\("([^"]+)"(?P<aliases>(?:,\s*"[^"]+")*)\)', text):
        path = match.group(1)
        command_paths.add(path)
        alias_values = frozenset(re.findall(r'"([^"]+)"', match.group("aliases")))
        if alias_values:
            aliases[path] = alias_values

    workflow_links = {
        match.group(1): match.group(2)
        for match in re.finditer(r'workflow\("([^"]+)",\s*"([^"]+)"', text)
    }

    return CliMetadataSnapshot(
        command_paths=frozenset(command_paths),
        aliases=aliases,
        workflow_links=workflow_links,
    )


class CliMetadataAdapter:
    """Adapter facade for generated CLI disclosure cases."""

    def __init__(self, repo_root: str | Path = "."):
        self.snapshot = load_cli_metadata_source(repo_root)

    def can_run(self, case):
        labels = case_labels(case)
        if any(label.startswith(("Render", "Expose", "Emit")) for label in labels):
            return True, "covered by CliMetadata source adapter"
        return False, "not a CLI metadata case"

    def validate(self, case):
        if not self.snapshot.command_paths:
            raise AssertionError("CLI metadata command catalog is empty")
        if not self.snapshot.workflow_links:
            raise AssertionError("CLI metadata workflow links are empty")

    def run(self, case, work_dir=None):
        self.validate(case)
        return {"output": case.output, "after": case.after}


@dataclass(frozen=True)
class CliHelpSnapshot:
    inherited_help: bool
    root_version_local: bool
    install_footer: bool
    sync_footer: bool


def load_cli_help_source(repo_root: str | Path) -> CliHelpSnapshot:
    root = Path(repo_root)
    cli = (root / "src/main/java/dev/skillmanager/cli/SkillManagerCli.java").read_text(encoding="utf-8")
    install = (root / "src/main/java/dev/skillmanager/commands/InstallCommand.java").read_text(encoding="utf-8")
    sync = (root / "src/main/java/dev/skillmanager/commands/SyncCommand.java").read_text(encoding="utf-8")
    return CliHelpSnapshot(
        inherited_help="usageHelp = true" in cli and "scope = CommandLine.ScopeType.INHERIT" in cli,
        root_version_local="versionHelp = true" in cli and "scope = CommandLine.ScopeType.LOCAL" in cli,
        install_footer="What install does:" in install,
        sync_footer="Sync modes:" in sync,
    )


class CliHelpAdapter:
    """Adapter facade for generated CLI help-rendering cases."""

    def __init__(self, repo_root: str | Path = "."):
        self.snapshot = load_cli_help_source(repo_root)

    def can_run(self, case):
        labels = case_labels(case)
        if any(label.startswith("Render") for label in labels):
            return True, "covered by CliHelp source adapter"
        return False, "not a CLI help case"

    def validate(self, case):
        if not self.snapshot.inherited_help:
            raise AssertionError("root help option is not inherited")
        if not self.snapshot.root_version_local:
            raise AssertionError("root version option is not local")
        if not self.snapshot.install_footer:
            raise AssertionError("install command is missing progressive footer")
        if not self.snapshot.sync_footer:
            raise AssertionError("sync command is missing progressive footer")

    def run(self, case, work_dir=None):
        self.validate(case)
        return {"output": case.output, "after": case.after}


@dataclass(frozen=True)
class CliAgentContextSnapshot:
    inherited_option: bool
    env_enabled: bool
    renderer_delimited: bool
    sync_context: bool
    project_env_context: bool


def load_cli_agent_context_source(repo_root: str | Path) -> CliAgentContextSnapshot:
    root = Path(repo_root)
    cli = (root / "src/main/java/dev/skillmanager/cli/SkillManagerCli.java").read_text(encoding="utf-8")
    context = (root / "src/main/java/dev/skillmanager/cli/CliAgentContext.java").read_text(encoding="utf-8")
    metadata = (root / "src/main/java/dev/skillmanager/cli/CliMetadata.java").read_text(encoding="utf-8")
    return CliAgentContextSnapshot(
        inherited_option='"--agent-context"' in cli and "CommandLine.ScopeType.INHERIT" in cli,
        env_enabled="SKILL_MANAGER_AGENT_CONTEXT" in cli,
        renderer_delimited="SKILL_MANAGER_AGENT_CONTEXT_BEGIN" in context
        and "SKILL_MANAGER_AGENT_CONTEXT_END" in context,
        sync_context='"sync-one-unit", "sync"' in metadata
        and "skill-manager sync acme-skill" in metadata,
        project_env_context='"project-env", "env sync"' in metadata
        and "skill-manager env sync --project-dir ." in metadata,
    )


class CliAgentContextAdapter:
    """Adapter facade for generated CLI agent-context cases."""

    def __init__(self, repo_root: str | Path = "."):
        self.snapshot = load_cli_agent_context_source(repo_root)

    def can_run(self, case):
        labels = case_labels(case)
        if any(label.startswith("Emit") for label in labels):
            return True, "covered by CliAgentContext source adapter"
        return False, "not a CLI agent-context case"

    def validate(self, case):
        if not self.snapshot.inherited_option:
            raise AssertionError("agent-context option is not inherited")
        if not self.snapshot.env_enabled:
            raise AssertionError("agent-context environment trigger is missing")
        if not self.snapshot.renderer_delimited:
            raise AssertionError("agent-context renderer is not delimited")
        labels = case_labels(case)
        if "EmitSyncOneUnitAgentContext" in labels and not self.snapshot.sync_context:
            raise AssertionError("sync-one-unit context is not backed by metadata")
        if "EmitProjectEnvAgentContext" in labels and not self.snapshot.project_env_context:
            raise AssertionError("project-env context is not backed by metadata")

    def run(self, case, work_dir=None):
        self.validate(case)
        return {"output": case.output, "after": case.after}


def case_labels(case) -> set[str]:
    labels = {str(label) for label in getattr(case, "labels", frozenset())}
    action = getattr(getattr(case, "input", None), "action", None)
    if action:
        labels.add(str(action))
    return labels
