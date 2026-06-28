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
        label = getattr(case, "label", "")
        if label.startswith(("Render", "Expose", "Emit")):
            return True, "covered by CliMetadata source adapter"
        return False, "not a CLI metadata case"


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
        label = getattr(case, "label", "")
        if label.startswith("Render"):
            return True, "covered by CliHelp source adapter"
        return False, "not a CLI help case"
