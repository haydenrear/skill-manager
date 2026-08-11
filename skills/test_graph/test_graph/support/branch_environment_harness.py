from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def provisioning_state_root(report_dir: Path) -> Path:
    return report_dir.parent.parent / "testgraph-provisioning-state"


def provisioned_marker(report_dir: Path, environment_id: str) -> Path:
    return provisioning_state_root(report_dir) / "provisioned" / f"{environment_id}.json"


def reset_markers(report_dir: Path, environment_id: str, run_id: str) -> list[Path]:
    reset_dir = provisioning_state_root(report_dir) / "reset"
    if not reset_dir.is_dir():
        return []
    return [
        path
        for path in sorted(reset_dir.glob(f"{environment_id}__*.json"))
        if read_json(path).get("runId") == run_id
    ]


def deployed_marker(report_dir: Path, environment_id: str) -> Path:
    return provisioning_state_root(report_dir) / "deployed" / f"{environment_id}.json"


def destroyed_marker(report_dir: Path, environment_id: str) -> Path:
    return provisioning_state_root(report_dir) / "destroyed" / f"{environment_id}.json"


def destroy_request_markers(report_dir: Path, environment_id: str, run_id: str) -> list[Path]:
    request_dir = provisioning_state_root(report_dir) / "destroy-requested"
    if not request_dir.is_dir():
        return []
    return [
        path
        for path in sorted(request_dir.glob(f"{environment_id}__*.json"))
        if read_json(path).get("runId") == run_id
    ]


def read_json(path: Path) -> dict:
    return json.loads(path.read_text()) if path.is_file() else {}


def envelope(report_dir: Path, node_id: str) -> dict:
    return read_json(report_dir / "envelope" / f"{node_id}.json")


def environment_repository_execution(report_dir: Path, node_id: str) -> dict:
    return envelope(report_dir, node_id).get("environmentRepositoryExecution", {})


def environment_repository_command_labels(report_dir: Path, node_id: str) -> set[str]:
    execution = environment_repository_execution(report_dir, node_id)
    return {command.get("label", "") for command in execution.get("commands", [])}


def ticket_plan_text() -> str:
    active = REPO_ROOT / "specs/desired_program_model/ticket_plan.yaml"
    if active.is_file():
        text = active.read_text(encoding="utf-8")
        if "id: TG-5" in text:
            return text

    closed = (
        REPO_ROOT
        / "specs/.history/branch-environment-repository-workflow/closed-snapshot/snapshots/desired_program_model/ticket_plan.yaml"
    )
    if closed.is_file():
        return closed.read_text(encoding="utf-8")

    latest_ticket = (
        REPO_ROOT
        / "specs/.history/branch-environment-repository-workflow/ticket-006-TG-5G/snapshots/desired_program_model/ticket_plan.yaml"
    )
    if latest_ticket.is_file():
        return latest_ticket.read_text(encoding="utf-8")

    raise FileNotFoundError("No TG-5 ticket plan found in active or closed spec workflow state.")


def deploy_cdc_issue_body_path() -> Path:
    return REPO_ROOT / "references" / "tickets" / "tg5-deploy-cdc-environment-repository-issue.md"


def environment_repository_source_dir() -> Path:
    return REPO_ROOT / "test_graph" / "environment-repository-source"


def generated_environment_repository_dir(report_dir: Path) -> Path:
    return report_dir / "generated-environment-repository"


def stable_environment_repository_dir() -> Path:
    return REPO_ROOT / "test_graph" / "build" / "tg5-environment-repository-source"


def scaffolded_environment_repository_dir(target: str = "local-preview") -> Path:
    safe = "".join(ch if ch.isalnum() or ch in "._-" else "-" for ch in target).strip(".-_") or "target"
    return REPO_ROOT / "test_graph" / "build" / f"tg6-environment-repository-{safe}"


def environment_repository_runtime_root() -> Path:
    return REPO_ROOT / "test_graph" / "build" / "testgraph-environment-repositories"


def reset_environment_repository_contract_state() -> None:
    state_root = REPO_ROOT / "test_graph" / "build" / "testgraph-provisioning-state"
    for path in (state_root, environment_repository_runtime_root()):
        if path.exists():
            shutil.rmtree(path)


def copy_environment_repository_source_to(destination: Path) -> Path:
    source = environment_repository_source_dir()
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)
    return destination


def copy_environment_repository_source(report_dir: Path) -> Path:
    return copy_environment_repository_source_to(generated_environment_repository_dir(report_dir))


def copy_stable_environment_repository_source() -> Path:
    return copy_environment_repository_source_to(stable_environment_repository_dir())


def scaffold_environment_repository(
    destination: Path,
    *,
    targets: list[str],
    include_tofu_shim: bool = False,
) -> Path:
    if destination.exists():
        shutil.rmtree(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    script = REPO_ROOT / "scripts" / "scaffold-tf-env.py"
    first, *rest = targets
    args = [str(script), str(destination), "--target", first]
    if include_tofu_shim:
        args.append("--include-tofu-shim")
    run(args, cwd=REPO_ROOT)
    add_script = REPO_ROOT / "scripts" / "scaffold-env.py"
    for target in rest:
        run([str(add_script), str(destination), "--target", target], cwd=REPO_ROOT)
    return destination


def run(args: list[str], *, cwd: Path) -> str:
    completed = subprocess.run(
        args,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"{' '.join(args)} failed with exit {completed.returncode}: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def git(repo: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} failed with exit {completed.returncode}: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def init_environment_repository(repo: Path) -> str:
    git(repo, "init")
    git(repo, "config", "user.email", "test-graph@example.invalid")
    git(repo, "config", "user.name", "Test Graph")
    git(repo, "add", ".")
    git(repo, "commit", "-m", "Initial local preview environment")
    return git(repo, "rev-parse", "HEAD")


def environment_output_keys(repo: Path, template: str = "templates/local-preview") -> set[str]:
    outputs = repo / template / "outputs.tf"
    text = outputs.read_text(encoding="utf-8")
    return {
        key
        for key in ("EnvironmentId", "KUBECONFIG", "KUBECONTEXT")
        if f'output "{key}"' in text
    }


def local_preview_output_keys(repo: Path) -> set[str]:
    return environment_output_keys(repo)
