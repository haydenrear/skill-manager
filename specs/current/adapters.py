"""Test Graph (external view) adapters for the skill-manager program model.

Each generated external case carries one public action (a skill-manager CLI
command: install / sync / remove / bind / harness / publish / search /
registry / gateway / project), a projected ``before`` program state, the
expected command result, and the expected ``after`` program state. The
adapter batch:

- ``setup_all``: locates the skill-manager CLI (the ``skill-manager``
  wrapper at the repo root driving ``jbang SkillManager.java``), writes the
  fixture units (UnitA skill, DocRepoA doc-repo, HarnessA harness, ProjectA
  project manifest, LibA git lib) once, and warms the jbang build. When the
  Test Graph testbed published a kube context
  (``SKILL_MANAGER_SPEC_KUBECONTEXT``), it verifies the cluster is reachable
  and records ``mode=cluster-attached``; otherwise ``mode=command-surface``.
- ``setup``: creates one isolated ``SKILL_MANAGER_HOME`` per case (fresh
  temp home + permissive ``policy.toml`` mirroring
  test_graph/sources/common/EnvPrepared.java) and materializes the
  representable parts of ``case.before`` through real CLI commands
  (installs, registry/gateway config, project registration/resolution).
- ``run``: executes the real CLI command line mapped to the public action
  and records what actually happened (argv, exit code, output tail).
- projected-state assertion: reads the isolated home filesystem back into
  the TLA state shape and compares **only the fields the projector actually
  observes**; every unobserved modeled field is recorded in the per-case
  ``program-state.json`` evidence as a coverage gap — matches are never
  fabricated for unobserved fields.

Coverage note: gateway tool deploy/describe/invoke are MCP-protocol
operations with no direct CLI command; those actions probe the
``gateway status`` command surface and record the gateway_* fields as
coverage gaps (deep gateway behavior is covered by the smoke test graphs).
Registry publish/search run against an unroutable registry URL in
command-surface mode; the actual offline behavior is recorded as evidence.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

_SPEC_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SPEC_DIR.parents[1]

def _spec_double_compiler_root() -> Path:
    override = os.environ.get("SPEC_DOUBLE_COMPILER_HOME")
    if override:
        return Path(override)
    slot = Path.home() / ".skill-manager" / "skills" / "spec-double-compiler"
    # Content-addressed store: the working copy lives under latest/. Fall back
    # to the bare slot for a home installed before that migration.
    working_copy = slot / "latest"
    return working_copy if working_copy.is_dir() else slot


_SKILL_ROOT = _spec_double_compiler_root()
for _candidate in (_SKILL_ROOT, _SPEC_DIR):
    if _candidate.is_dir() and str(_candidate) not in sys.path:
        sys.path.insert(0, str(_candidate))

from spec_double_compiler.runtime import CaseRunResult

import tlc_projection

MODEL_UNITS = frozenset({"UnitA"})
MODEL_DOC_REPOS = frozenset({"DocRepoA"})
MODEL_HARNESS_TEMPLATES = frozenset({"HarnessA"})
# Units transitively installed when a harness template fixture installs.
HARNESS_TEMPLATE_UNITS = frozenset({"UnitA"})
MODEL_HARNESS_INSTANCES = frozenset({"InstanceA"})
MODEL_PROJECTS = frozenset({"ProjectA"})
MODEL_SHAS = frozenset({"ShaA", "ShaB", "ShaC"})

# Content-addressed store layout (SMVENV-001), mirroring SkillStore: a unit's
# slot is skills/<name>/, its working copy sits under latest/, and each stored
# sha is a sibling snapshot named by that sha.
LATEST_DIR = "latest"
LATEST_MARKER = ".store-latest"

# Fields the filesystem projector actually observes (the external view the
# TLC cases are generated and deduped under). Every other modeled field is
# reported as a coverage gap in the per-case evidence.
OBSERVED_FIELDS = (
    tlc_projection.EXTERNAL_SET_FIELDS + tlc_projection.EXTERNAL_BOOL_FIELDS
)
OBSERVED_PROJECT_MODEL_FIELDS = tlc_projection.EXTERNAL_PROJECT_MODEL_FIELDS

OFFLINE_REGISTRY_URL = "http://127.0.0.1:9"
CONFIGURED_REGISTRY_URL = "http://127.0.0.1:59991"
CONFIGURED_GATEWAY_URL = "http://127.0.0.1:59992"


def _external_mode() -> str:
    return "cluster-attached" if os.environ.get("SKILL_MANAGER_SPEC_KUBECONTEXT") else "command-surface"


def _plain(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): _plain(v) for k, v in sorted(value.items(), key=lambda kv: str(kv[0]))}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    if isinstance(value, (set, frozenset)):
        return [_plain(item) for item in sorted(value, key=repr)]
    return value


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


def _write_fixtures(root: Path) -> dict[str, str]:
    root.mkdir(parents=True, exist_ok=True)
    unit = root / "UnitA"
    unit.mkdir(exist_ok=True)
    (unit / "SKILL.md").write_text(
        "---\nname: UnitA\ndescription: spec external fixture\n---\nBody.\n"
    )
    (unit / "skill-manager.toml").write_text(
        '[skill]\nname = "UnitA"\nversion = "0.1.0"\ndescription = "spec external fixture"\n'
    )

    doc = root / "DocRepoA"
    doc.mkdir(exist_ok=True)
    (doc / "skill-manager.toml").write_text(
        '[doc-repo]\nname = "DocRepoA"\nversion = "0.1.0"\n\n'
        '[[sources]]\nid = "notes"\nfile = "notes.md"\nagents = ["claude"]\n'
    )
    (doc / "notes.md").write_text("spec external fixture notes\n")

    harness = root / "HarnessA"
    harness.mkdir(exist_ok=True)
    (harness / "harness.toml").write_text(
        f'[harness]\nname = "HarnessA"\nversion = "0.1.0"\nunits = ["{unit}"]\n'
    )

    lib = root / "LibA"
    if not (lib / ".git").is_dir():
        lib.mkdir(exist_ok=True)
        (lib / "README.md").write_text("# LibA spec fixture\n")
        subprocess.run(["git", "init", "-q", "-b", "main"], cwd=lib, check=True)
        subprocess.run(["git", "add", "README.md"], cwd=lib, check=True)
        subprocess.run(
            ["git", "-c", "user.email=spec@skill-manager.test", "-c", "user.name=spec",
             "commit", "-qm", "init"],
            cwd=lib,
            check=True,
        )

    project = root / "ProjectA"
    project.mkdir(exist_ok=True)
    (project / "skill-project.toml").write_text(
        f"""[project]
name = "ProjectA"
version = "0.1.0"
description = "spec external fixture project"

[skills.unit-a]
source = "{unit}"

[docs.doc-repo-a]
source = "{doc}"

[harnesses.harness-a]
source = "{harness}"

[envs.EnvA]
python = "3.12"

[[libs]]
name = "LibA"
source = "git+file://{lib}"
ref = "main"

[profiles.ProfileA]
skills = ["unit-a"]
docs = ["doc-repo-a"]
harnesses = ["harness-a"]
envs = ["EnvA"]
libs = ["LibA"]
"""
    )
    return {
        "UnitA": str(unit),
        "DocRepoA": str(doc),
        "HarnessA": str(harness),
        "LibA": str(lib),
        "ProjectA": str(project),
    }


_POLICY_TOML = """# Spec external cases run unattended; relax every install gate.
require_confirmation = false
[install]
require_confirmation_for_hooks = false
require_confirmation_for_mcp = false
require_confirmation_for_cli_deps = false
require_confirmation_for_executable_commands = false
"""


class SkillManagerCliCommandAdapter:
    """Runs one external case against the real skill-manager CLI surface."""

    def __init__(self) -> None:
        self._shared: dict[str, Any] = {}

    def setup_all(self, context: Any) -> None:
        shared = context.shared
        self._shared = shared
        shared["mode"] = _external_mode()
        kube_context = os.environ.get("SKILL_MANAGER_SPEC_KUBECONTEXT")
        if kube_context:
            self._assert_cluster_ready(kube_context, shared)
        root = Path(tempfile.mkdtemp(prefix="sm-spec-external-"))
        shared["external_root"] = str(root)
        shared["fixtures"] = _write_fixtures(root / "fixtures")
        shared["cli"] = self._locate_cli()
        shared["case_records"] = {}
        # Warm the jbang build once so per-case invocations stay fast.
        self._run_cli(shared, Path(root), ["--version"], home=None, timeout=600)

    def teardown_all(self, context: Any) -> None:
        root = context.shared.get("external_root")
        if root and not os.environ.get("SKILL_MANAGER_SPEC_KEEP_HOMES"):
            shutil.rmtree(root, ignore_errors=True)

    def setup(self, context: Any) -> None:
        shared = context.shared
        self._shared = shared
        case = context.case
        root = Path(shared["external_root"])
        home = root / "homes" / case.name
        if home.exists():
            shutil.rmtree(home)
        agent_home = home / "agent-home"
        (agent_home / ".codex").mkdir(parents=True)
        (agent_home / ".gemini").mkdir(parents=True)
        (home / "policy.toml").write_text(_POLICY_TOML)
        project_dir = home / "project-work" / "ProjectA"
        project_dir.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(shared["fixtures"]["ProjectA"], project_dir)
        record: dict[str, Any] = {
            "home": str(home),
            "project_dir": str(project_dir),
            "commands": [],
            "setup_gaps": [],
        }
        shared["case_records"][case.name] = record
        self._materialize_before(shared, record, dict(case.before))

    def teardown(self, context: Any) -> None:
        pass

    def can_run(self, case: Any):
        if case.input.action in _EXTERNAL_DISPATCH:
            return True
        return (False, f"no external dispatch for action {case.input.action}")

    def validate(self, case: Any) -> None:
        if case.input.action not in _EXTERNAL_DISPATCH:
            raise ValueError(f"no external dispatch for action {case.input.action}")

    def run(self, case: Any, work_dir: Path | None = None) -> CaseRunResult:
        shared = self._shared
        record = shared["case_records"][case.name]
        commands = _EXTERNAL_DISPATCH[case.input.action](record, shared, dict(case.input.params))
        for label, argv, extra_env in commands:
            self._run_case_command(shared, record, label, argv, extra_env)
        # The projected-state assertion compares observed state; the raw CLI
        # results (argv, exit code, output) are recorded as evidence rather
        # than fabricated into the modeled accept/reject result shape.
        return CaseRunResult(output=None)

    # -- plumbing ---------------------------------------------------------

    def _locate_cli(self) -> list[str]:
        wrapper = _REPO_ROOT / "skill-manager"
        if wrapper.is_file() and os.access(wrapper, os.X_OK):
            return [str(wrapper)]
        return ["jbang", str(_REPO_ROOT / "SkillManager.java")]

    def _cli_env(self, home: Path | None, extra_env: dict[str, str] | None = None) -> dict[str, str]:
        env = os.environ.copy()
        env["SKILL_MANAGER_INSTALL_DIR"] = str(_REPO_ROOT)
        env.setdefault("JAVA_TOOL_OPTIONS", "-Djava.net.preferIPv6Addresses=true")
        if home is not None:
            env["SKILL_MANAGER_HOME"] = str(home)
            env["CLAUDE_HOME"] = str(home / "agent-home")
            env["CODEX_HOME"] = str(home / "agent-home" / ".codex")
            env["GEMINI_HOME"] = str(home / "agent-home" / ".gemini")
        if extra_env:
            env.update(extra_env)
        return env

    def _run_cli(
        self,
        shared: dict[str, Any],
        cwd: Path,
        args: list[str],
        home: Path | None,
        extra_env: dict[str, str] | None = None,
        timeout: int = 300,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [*shared["cli"], *args],
            cwd=cwd,
            env=self._cli_env(home, extra_env),
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    def _run_case_command(
        self,
        shared: dict[str, Any],
        record: dict[str, Any],
        label: str,
        args: list[str],
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        home = Path(record["home"])
        proc = self._run_cli(shared, home, args, home, extra_env)
        record["commands"].append(
            {
                "label": label,
                "argv": [*shared["cli"], *args],
                "exit_code": proc.returncode,
                "stdout_tail": proc.stdout[-2000:],
                "stderr_tail": proc.stderr[-2000:],
            }
        )
        return proc

    def _materialize_before(
        self, shared: dict[str, Any], record: dict[str, Any], before: dict[str, Any]
    ) -> None:
        fixtures = shared["fixtures"]
        home = Path(record["home"])
        if before.get("cli_registry_url_configured"):
            self._setup_command(shared, record, "setup:registry-set",
                                ["registry", "set", CONFIGURED_REGISTRY_URL])
        if before.get("cli_gateway_url_configured"):
            self._setup_command(shared, record, "setup:gateway-set",
                                ["gateway", "set", CONFIGURED_GATEWAY_URL])
        for unit in sorted(set(before.get("cli_store_units", [])) & MODEL_UNITS):
            self._setup_command(shared, record, f"setup:install-{unit}",
                                ["install", fixtures[unit], "--yes"])
        for doc in sorted(set(before.get("cli_doc_repos", [])) & MODEL_DOC_REPOS):
            self._setup_command(shared, record, f"setup:install-{doc}",
                                ["install", fixtures[doc], "--yes"])
        for template in sorted(set(before.get("cli_harness_templates", [])) & MODEL_HARNESS_TEMPLATES):
            self._setup_command(shared, record, f"setup:install-{template}",
                                ["install", fixtures[template], "--yes"])
        for instance in sorted(set(before.get("cli_harness_instances", [])) & MODEL_HARNESS_INSTANCES):
            harness_work = home / "harness-work"
            harness_work.mkdir(parents=True, exist_ok=True)
            self._setup_command(
                shared, record, f"setup:harness-instantiate-{instance}",
                ["harness", "instantiate", "HarnessA", "--id", instance,
                 "--project-dir", str(harness_work)])
        # Installing HarnessA transitively installs its referenced units
        # (HARNESS_TEMPLATE_UNITS); modeled before-states can carry the
        # template without those units (SyncHarness followed by RemoveUnit),
        # so prune the transitive installs the model's before-state lacks.
        if set(before.get("cli_harness_templates", [])) & MODEL_HARNESS_TEMPLATES:
            pulled = HARNESS_TEMPLATE_UNITS - set(before.get("cli_store_units", []))
            for unit in sorted(pulled):
                self._setup_command(shared, record, f"setup:prune-{unit}",
                                    ["remove", unit])
        project_model = dict(before.get("project_model", {}))
        manifests = {str(item) for item in project_model.get("manifests", [])} & MODEL_PROJECTS
        for project in sorted(manifests):
            self._setup_command(shared, record, f"setup:project-register-{project}",
                                ["project", "register", "--project-dir", record["project_dir"]])
        # Unrepresented modeled before-state (gateway deployments, effect
        # program state, registry-server contents, project locks/resolutions,
        # child homes) is recorded as an explicit setup gap.
        for field, empty in _SETUP_GAP_FIELDS:
            value = before.get(field)
            if value not in (None, empty):
                record["setup_gaps"].append(field)
        for field in ["locks", "resolved_units", "env_realizations", "child_homes",
                      "profile_locks", "lib_locks"]:
            if project_model.get(field):
                record["setup_gaps"].append(f"project_model.{field}")

    def _setup_command(
        self, shared: dict[str, Any], record: dict[str, Any], label: str, args: list[str]
    ) -> None:
        proc = self._run_case_command(shared, record, label, args)
        if proc.returncode != 0:
            raise RuntimeError(
                f"before-state materialization failed ({label}): "
                f"{proc.stdout[-500:]} {proc.stderr[-500:]}"
            )

    def _assert_cluster_ready(self, kube_context: str, shared: dict[str, Any]) -> None:
        kubeconfig = os.environ.get("SKILL_MANAGER_SPEC_KUBECONFIG")
        base = ["kubectl", "--context", kube_context]
        env = os.environ.copy()
        if kubeconfig:
            env["KUBECONFIG"] = kubeconfig
        probe = subprocess.run(
            [*base, "get", "--raw", "/readyz"], capture_output=True, text=True, timeout=60, env=env
        )
        if probe.returncode != 0:
            raise RuntimeError(
                f"cluster context {kube_context} not reachable for external cases: {probe.stderr.strip()}"
            )
        shared["kubeContext"] = kube_context


_SETUP_GAP_FIELDS = [
    ("server_registry_units", []),
    ("server_versions", []),
    ("server_packages", []),
    ("server_authenticated_users", []),
    ("gateway_catalog", []),
    ("gateway_dynamic_servers", []),
    ("gateway_global_deployments", []),
    ("gateway_session_deployments", []),
    ("gateway_tools", []),
    ("gateway_disclosures", []),
    ("gateway_errors", []),
    ("gateway_last_init", []),
    ("rollback_journal", []),
    ("cli_errors", []),
    ("program_halted", False),
    ("always_after_ran", False),
]


# ---------------------------------------------------------------------------
# External action -> concrete CLI command mapping. Each entry returns a list
# of (label, argv, extra_env) tuples executed in order against the isolated
# case home.
# ---------------------------------------------------------------------------


def _offline_registry_env() -> dict[str, str]:
    # Env precedence keeps publish/search off any real registry and prevents
    # URL persistence side effects on registry.properties.
    return {"SKILL_MANAGER_REGISTRY_URL": OFFLINE_REGISTRY_URL}


def _install(record, shared, params, *extra):
    return [("run", ["install", shared["fixtures"][str(params["unit"])], "--yes", *extra], None)]


def _sync_unit(record, shared, params, *extra):
    unit = str(params["unit"])
    return [("run", ["sync", unit, "--from", shared["fixtures"][unit], "--yes", *extra], None)]


def _sync_by_name(record, shared, params, key):
    return [("run", ["sync", str(params[key]), "--yes"], None)]


def _remove(record, shared, params):
    return [("run", ["remove", str(params["unit"])], None)]


def _bind_doc(record, shared, params):
    doc = str(params["doc"])
    home = Path(record["home"])
    if (home / "docs" / doc).is_dir():
        return [("run", ["sync", doc, "--from", shared["fixtures"][doc], "--yes"], None)]
    return [("run", ["install", shared["fixtures"][doc], "--yes"], None)]


def _sync_harness(record, shared, params):
    template = str(params["template"])
    instance = str(params["instance"])
    home = Path(record["home"])
    commands = []
    if not (home / "harnesses" / template).is_dir():
        commands.append(("run:install-template", ["install", shared["fixtures"][template], "--yes"], None))
    work = home / "harness-work"
    work.mkdir(parents=True, exist_ok=True)
    commands.append(
        ("run:instantiate",
         ["harness", "instantiate", template, "--id", instance, "--project-dir", str(work)],
         None)
    )
    return commands


def _publish(record, shared, params):
    return [("run", ["publish", shared["fixtures"][str(params["unit"])]], _offline_registry_env())]


def _search(record, shared, params):
    return [("run", ["search", "--no-ads", "--limit", "5"], _offline_registry_env())]


def _registry_set(record, shared, params):
    return [("run", ["registry", "set", CONFIGURED_REGISTRY_URL], None)]


def _gateway_set(record, shared, params):
    return [("run", ["gateway", "set", CONFIGURED_GATEWAY_URL], None)]


def _gateway_probe(record, shared, params):
    # No direct CLI command exists for gateway tool deploy/describe/invoke
    # (MCP-protocol operations); probe the gateway command surface and record
    # the gateway_* fields as coverage gaps.
    record["setup_gaps"].append("gateway_runtime_surface")
    return [("run:surface-probe", ["gateway", "status"], None)]


def _project_register(record, shared, params):
    return [("run", ["project", "register", "--project-dir", record["project_dir"]], None)]


def _project_resolve(record, shared, params, *extra):
    return [("run", ["project", "resolve", "--skip-gateway",
                     "--project-dir", record["project_dir"], *extra], None)]


def _project_env(record, shared, params):
    return [("run", ["env", "sync", str(params["env"]), "--skip-uv",
                     "--project-dir", record["project_dir"]], None)]


def _project_profile(record, shared, params):
    return [("run", ["project", "resolve", "--profile", str(params["profile"]),
                     "--skip-gateway", "--project-dir", record["project_dir"]], None)]



# skill-manager venv store surface (planned CLI; lands with SMVENV-001).
def _venv_store(record, shared, params):
    return [("run", ["store", "add", str(params["unit"]), "--sha", str(params["sha"]), "--yes"], None)]


_EXTERNAL_DISPATCH = {
    # CLI store surface.
    "SubmitInstallUnit": _install,
    "SubmitInstallUnitForceScripts": lambda r, s, p: _install(r, s, p, "--force-scripts"),
    "SubmitSyncUnit": _sync_unit,
    "SubmitSyncUnitForceScripts": lambda r, s, p: _sync_unit(r, s, p, "--force-scripts"),
    "SubmitSyncUninstalledUnit": lambda r, s, p: _sync_by_name(r, s, p, "unit"),
    "SubmitRemoveUnit": _remove,
    "SubmitRemoveUnknownUnit": _remove,
    "SubmitBindDocRepo": _bind_doc,
    "SubmitSyncDocRepo": lambda r, s, p: _sync_by_name(r, s, p, "doc"),
    "SubmitSyncUnknownDocRepo": lambda r, s, p: _sync_by_name(r, s, p, "doc"),
    "SubmitSyncHarness": _sync_harness,
    # Registry server surface.
    "SubmitPublishTarball": _publish,
    "SubmitPublishUnauthenticated": _publish,
    "SubmitSearchRegistry": _search,
    "SubmitConfigureRegistry": _registry_set,
    # Virtual MCP gateway surface.
    "SubmitEnsureGateway": _gateway_set,
    "SubmitDeployGatewayGlobal": _gateway_probe,
    "SubmitDeployGatewaySession": _gateway_probe,
    "SubmitDescribeGatewayTool": _gateway_probe,
    "SubmitInvokeGatewayTool": _gateway_probe,
    "SubmitInvokeUndisclosedTool": _gateway_probe,
    # Project lifecycle CLI.
    "RunProjectRegister": _project_register,
    "RunProjectResolve": _project_resolve,
    "RunProjectEnvMaterialize": _project_env,
    "RunProjectLibsResolve": lambda r, s, p: _project_resolve(r, s, p, "--resolve-libs"),
    "RunScaffoldProjectChildHome": _project_resolve,
    "RunProjectProfileResolve": _project_profile,
    # skill-manager venv store (planned surface, SMVENV-001).
    "RunStoreUnitVersion": _venv_store,
}


# ---------------------------------------------------------------------------
# Projection / assertion roles
# ---------------------------------------------------------------------------


class SkillManagerStateProjector:
    """Reads the isolated home filesystem back into the TLA state shape."""

    def observe(self, context: Any) -> dict[str, Any]:
        record = context.shared["case_records"][context.case.name]
        return observe_home(Path(record["home"]))


def observe_home(home: Path) -> dict[str, Any]:
    skills = _installed_skill_names(home / "skills") | _dir_names(home / "plugins")
    docs = _dir_names(home / "docs")
    harness_templates = _dir_names(home / "harnesses") - {"instances"}
    harness_instances = _dir_names(home / "harnesses" / "instances")
    installed = {path.stem for path in (home / "installed").glob("*.json")
                 if not path.stem.endswith(".projections")}
    lock_units: set[str] = set()
    lock_path = home / "units.lock.toml"
    if lock_path.is_file():
        current_name = None
        for line in lock_path.read_text().splitlines():
            line = line.strip()
            if line.startswith("name = "):
                current_name = line.split("=", 1)[1].strip().strip('"')
            elif line.startswith("kind = ") and current_name is not None:
                kind = line.split("=", 1)[1].strip().strip('"')
                if kind in {"skill", "plugin"}:
                    lock_units.add(current_name)
                current_name = None
    store_units = skills & MODEL_UNITS
    # The model pins cli_installed_records == cli_lock_units ==
    # cli_store_units (CliInstalledRecordsTrackStore / CliLockTracksStore);
    # a divergence in the real home is a production consistency bug, so the
    # projector fails loudly instead of silently picking one source.
    if (installed & MODEL_UNITS) != store_units or (lock_units & MODEL_UNITS) != store_units:
        raise AssertionError(
            "store/installed/lock divergence in isolated home "
            f"{home}: skills={sorted(store_units)} "
            f"installed={sorted(installed & MODEL_UNITS)} "
            f"lock={sorted(lock_units & MODEL_UNITS)}"
        )
    projects = _dir_names(home / "projects")
    observed_projects = sorted(projects & MODEL_PROJECTS)
    store_versions, store_latest = _observe_store(home / "skills")
    return {
        "cli_store_units": sorted(store_units),
        "cli_doc_repos": sorted(docs & MODEL_DOC_REPOS),
        "cli_harness_templates": sorted(harness_templates & MODEL_HARNESS_TEMPLATES),
        "cli_harness_instances": sorted(harness_instances & MODEL_HARNESS_INSTANCES),
        "cli_registry_url_configured": (home / "registry.properties").is_file(),
        "cli_gateway_url_configured": (home / "gateway.properties").is_file(),
        "project_model": {
            "manifests": observed_projects,
            "registrations": observed_projects,
            "store_versions": sorted(store_versions),
            "store_latest": sorted(store_latest),
        },
    }


def _dir_names(path: Path) -> set[str]:
    if not path.is_dir():
        return set()
    return {entry.name for entry in path.iterdir() if entry.is_dir()}


def _installed_skill_names(skills_dir: Path) -> set[str]:
    """Skill slots that hold a working copy.

    A slot outlives its working copy: ``remove`` deletes ``latest/`` but keeps
    the sha snapshots beside it, because the store is a cache and not part of
    the install lifecycle. Counting bare slots would report a removed-but-
    snapshotted unit as installed and trip the divergence check below.
    """
    return {slot for slot in _dir_names(skills_dir) if (skills_dir / slot / LATEST_DIR).is_dir()}


def _observe_store(skills_dir: Path) -> tuple[set[tuple[str, str]], set[tuple[str, str]]]:
    """The content-addressed store as ``(unit, sha)`` pairs.

    Mirrors ``project_model.store_versions`` / ``store_latest``: every
    ``skills/<unit>/<sha>/`` snapshot, and the sha named by the slot's
    ``.store-latest`` marker.
    """
    versions: set[tuple[str, str]] = set()
    latest: set[tuple[str, str]] = set()
    for slot in _dir_names(skills_dir) & MODEL_UNITS:
        for sha in _dir_names(skills_dir / slot) - {LATEST_DIR}:
            if sha in MODEL_SHAS:
                versions.add((slot, sha))
        marker = skills_dir / slot / LATEST_MARKER
        if marker.is_file():
            sha = marker.read_text(encoding="utf-8").strip()
            if sha in MODEL_SHAS:
                latest.add((slot, sha))
    return versions, latest


class ExpectedSkillManagerProjection:
    def expected_state(self, context: Any) -> dict[str, Any]:
        return tlc_projection.normalize_external_state(dict(context.case.after))


class ProjectedStateAssertion:
    """Diffs observed vs expected state on the observed fields only and
    writes per-case ``program-state.json`` evidence including the modeled
    service route, the raw CLI results, and the coverage gaps."""

    def assert_state(self, context: Any) -> None:
        expected_observed = _plain(context.expected)
        actual_observed = _plain(tlc_projection.normalize_external_state(dict(context.actual)))
        record = context.shared.get("case_records", {}).get(context.case.name, {})
        params = dict(context.case.input.params)
        matched = actual_observed == expected_observed
        gaps = sorted(
            set(tlc_projection.STATE_FIELDS) - set(OBSERVED_FIELDS) - {"project_model"}
        )
        project_gaps = sorted(
            set(tlc_projection.PROJECT_MODEL_FIELDS) - set(OBSERVED_PROJECT_MODEL_FIELDS)
        )
        artifact = Path(context.work_dir) / "program-state.json"
        payload = {
            "case": context.case.name,
            "action": context.case.input.action,
            "params": _plain(params),
            "route": _plain(params.get("route", [])),
            "mode": context.shared.get("mode", _external_mode()),
            "kubeContext": context.shared.get("kubeContext"),
            "expected_observed_program_state": expected_observed,
            "actual_projected_program_state": actual_observed,
            "observed_fields": OBSERVED_FIELDS + [
                f"project_model.{field}" for field in OBSERVED_PROJECT_MODEL_FIELDS
            ],
            "coverage_gaps": {
                "unobserved_state_fields": gaps,
                "unobserved_project_model_fields": project_gaps,
                "setup_gaps": sorted(set(record.get("setup_gaps", []))),
            },
            "modeled_result": _plain(dict(context.case.output))
            if isinstance(context.case.output, dict)
            else _plain(context.case.output),
            "cli_commands": _plain(record.get("commands", [])),
            "matched": matched,
        }
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        if not matched:
            raise AssertionError(
                f"projected program state mismatch for {context.case.name}; wrote {artifact}"
            )


