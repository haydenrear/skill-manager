# /// script
# requires-python = ">=3.11"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../../sdk/python", editable = true }
# ///
"""Regenerate and execute the spec-double-compiler external cases.

Regenerates the External.tla TLC case package into this run's report
directory (the package is runtime IR; TLA+ is the source of truth), then
executes every exported external Test Graph case through the bindings in
specs/program_model/testgraph_bindings.yml with projected-state assertions
against the real skill-manager CLI in isolated SKILL_MANAGER_HOMEs.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, ProcessRecord, node

SPEC = (
    NodeSpec("sm.spec.external.cases")
    .kind("action")
    .depends_on("sm.cluster.ready")
    .tags("skill-manager", "spec", "external")
    .timeout("90m")
    .rerun(False)
    .side_effects("net:local", "fs:tmp")
    .output("workDir")
    .output("generatedRoot")
    .output("traceManifest")
    .output("modelDir")
)

REPO_ROOT = Path(__file__).resolve().parents[3]


def _skill_root() -> Path:
    return Path(
        os.environ.get(
            "SPEC_DOUBLE_COMPILER_HOME",
            Path.home() / ".skill-manager" / "skills" / "spec-double-compiler",
        )
    )


def _model_dir() -> Path:
    override = os.environ.get("SKILL_MANAGER_SPEC_MODEL_DIR")
    if override:
        return Path(override).resolve()
    return REPO_ROOT / "specs" / "program_model"


@node(SPEC)
def run(ctx):
    model_dir = _model_dir()
    generated_root = ctx.report_dir / "generated"
    work_dir = ctx.report_dir / "external-case-work"
    trace_manifest = generated_root / "testgraph" / "traces" / "manifest.json"
    log_path = ctx.report_dir / "spec-external-cases.log"

    regenerate = [
        sys.executable,
        str(REPO_ROOT / "scripts" / "regenerate_spec_cases.py"),
        "--spec-dir", str(model_dir),
        "--out", str(generated_root),
        "--views", "external",
    ]
    execute = [
        sys.executable,
        str(_skill_root() / "scripts" / "run_generated_case_adapters.py"),
        str(generated_root / "testgraph" / "skillmanager_external_cases"),
        "--mapping", str(model_dir / "testgraph_bindings.yml"),
        "--spec-dir", str(model_dir),
        "--view", "external",
        "--batch",
        "--work-dir", str(work_dir),
        "--import-root", str(model_dir),
        "--import-root", str(REPO_ROOT),
    ]

    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    skill_cli_bin = Path.home() / ".skill-manager" / "bin" / "cli"
    if skill_cli_bin.is_dir():
        env["PATH"] = f"{skill_cli_bin}{os.pathsep}{env.get('PATH', '')}"
    kube_context = ctx.get("sm.cluster.ready", "kubeContext")
    if kube_context:
        env["SKILL_MANAGER_SPEC_KUBECONTEXT"] = kube_context
    kube_config = ctx.get("sm.cluster.ready", "kubeConfig")
    if kube_config:
        env["SKILL_MANAGER_SPEC_KUBECONFIG"] = kube_config

    records = []
    with log_path.open("w", encoding="utf-8") as log:
        log.write("$ " + " ".join(regenerate) + "\n")
        log.flush()
        regen = subprocess.run(regenerate, cwd=REPO_ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        records.append(ProcessRecord(
            label="regenerate external TLC case package",
            command=regenerate,
            exit_code=regen.returncode,
            log_path=str(log_path),
        ))
        if regen.returncode == 0:
            log.write("$ " + " ".join(execute) + "\n")
            log.flush()
            batch = subprocess.run(execute, cwd=REPO_ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            records.append(ProcessRecord(
                label="external adapter batch",
                command=execute,
                exit_code=batch.returncode,
                log_path=str(log_path),
            ))
        else:
            batch = None

    if regen.returncode != 0:
        result = NodeResult.fail(SPEC.id, f"external case regeneration failed with exit {regen.returncode}")
        for record in records:
            result = result.process(record)
        return result.artifact("log", str(log_path))

    expected = _expected_case_names(trace_manifest)
    executed = _executed_case_names(work_dir)
    result = NodeResult.pass_(SPEC.id) if batch is not None and batch.returncode == 0 else NodeResult.fail(
        SPEC.id, f"external cases failed with exit {batch.returncode if batch else 'n/a'}")
    for record in records:
        result = result.process(record)
    result = (
        result
        .artifact("log", str(log_path))
        .artifact("traceManifest", str(trace_manifest))
        .publish("workDir", str(work_dir))
        .publish("generatedRoot", str(generated_root))
        .publish("traceManifest", str(trace_manifest))
        .publish("modelDir", str(model_dir))
        .metric("expectedCaseCount", len(expected))
        .metric("executedCaseCount", len(executed))
        .assertion("external adapter batch passed", batch is not None and batch.returncode == 0)
        .assertion("every generated external case wrote program-state evidence", executed == expected)
    )
    if executed != expected and batch is not None and batch.returncode == 0:
        missing = sorted(set(expected) - set(executed))[:10]
        return NodeResult.fail(
            SPEC.id,
            f"external case evidence mismatch: {len(executed)}/{len(expected)} cases wrote evidence; missing sample {missing}",
        ).process(records[-1]).artifact("log", str(log_path)).publish("workDir", str(work_dir)).publish(
            "generatedRoot", str(generated_root)).publish("traceManifest", str(trace_manifest)).publish(
            "modelDir", str(model_dir))
    return result


def _expected_case_names(manifest: Path) -> list[str]:
    if not manifest.is_file():
        return []
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    return sorted(Path(name).stem for name in payload["traces"])


def _executed_case_names(work_dir: Path) -> list[str]:
    return sorted(path.parent.name for path in (work_dir / "case-work").glob("*/program-state.json"))


if __name__ == "__main__":
    run()
