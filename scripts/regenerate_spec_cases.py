#!/usr/bin/env python3
"""Regenerate TLC state-graph case packages for the skill-manager program model.

TLA+ (Core/Internal/External under the selected spec directory) is the source
of truth. Generated packages are runtime IR: internal (spec-unit) packages go
to <out>/spec-unit/ and the external Test Graph package plus exported traces
go to <out>/testgraph/. Nothing generated here should be hand-edited or
checked in; the test graph regenerates the external package inside each
validation report.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


def _spec_double_compiler_root() -> Path:
    override = os.environ.get("SPEC_DOUBLE_COMPILER_HOME")
    if override:
        return Path(override)
    slot = Path.home() / ".skill-manager" / "skills" / "spec-double-compiler"
    # Content-addressed store: the working copy is under latest/. Fall back to
    # the slot itself for a home that predates the migration.
    working_copy = slot / "latest"
    return working_copy if working_copy.is_dir() else slot


SKILL_ROOT = _spec_double_compiler_root()

INTERNAL_SLICES = [
    ("InternalCliStoreCases.cfg", "skillmanager_cli_store_internal_cases"),
    ("InternalGatewayCases.cfg", "skillmanager_gateway_internal_cases"),
    ("InternalServerRegistryCases.cfg", "skillmanager_server_registry_internal_cases"),
    ("InternalProjectCases.cfg", "skillmanager_project_internal_cases"),
    ("InternalCliDisclosureCases.cfg", "skillmanager_cli_disclosure_internal_cases"),
]
EXTERNAL_SLICES = [
    ("External.cfg", "skillmanager_external_cases"),
]


def run(command: list[str], cwd: Path) -> None:
    printable = " ".join(str(part) for part in command)
    print(f"$ (cd {cwd}) {printable}", flush=True)
    subprocess.run([str(part) for part in command], cwd=cwd, check=True)


def generate(spec_dir: Path, out: Path, views: list[str], tlc2: str) -> None:
    generator = SKILL_ROOT / "scripts" / "generate_cases_from_tlc_dump.py"
    exporter = SKILL_ROOT / "scripts" / "export_testgraph_cases.py"
    if not generator.is_file():
        raise SystemExit(f"spec-double-compiler skill not found at {SKILL_ROOT}")

    def common(state_projector: str) -> list:
        return [
            "--actions-metadata", spec_dir / "actions.yml",
            "--state-projector", state_projector,
            "--output-projector", "tlc_projection:project_adapter_output",
            "--dedupe", "projected",
            "--tlc2", tlc2,
        ]

    if "internal" in views:
        for cfg, package in INTERNAL_SLICES:
            target = out / "spec-unit" / package
            if target.exists():
                shutil.rmtree(target)
            run(
                [sys.executable, generator, spec_dir / "Internal.tla", spec_dir / cfg,
                 "--out", out, "--package", package, "--view", "internal",
                 *common("tlc_projection:project_visible_state")],
                cwd=spec_dir,
            )

    if "external" in views:
        for cfg, package in EXTERNAL_SLICES:
            target = out / "testgraph" / package
            if target.exists():
                shutil.rmtree(target)
            run(
                [sys.executable, generator, spec_dir / "External.tla", spec_dir / cfg,
                 "--out", out, "--package", package, "--view", "external",
                 *common("tlc_projection:project_external_visible_state")],
                cwd=spec_dir,
            )
        traces = out / "testgraph" / "traces"
        if traces.exists():
            shutil.rmtree(traces)
        run(
            [sys.executable, exporter, out / "testgraph" / "skillmanager_external_cases",
             "--out", traces],
            cwd=spec_dir,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec-dir", type=Path, default=REPO_ROOT / "specs" / "program_model",
                        help="Model directory containing Internal.tla/External.tla and slice configs.")
    parser.add_argument("--out", type=Path, default=REPO_ROOT / "specs" / "generated",
                        help="Output root for spec-unit/ and testgraph/ packages.")
    parser.add_argument("--views", default="internal,external",
                        help="Comma-separated views to regenerate (internal, external).")
    parser.add_argument("--tlc2", default=os.environ.get("TLC2", "tlc2"))
    args = parser.parse_args()

    views = [view.strip() for view in args.views.split(",") if view.strip()]
    if "both" in views:
        views = ["internal", "external"]
    spec_dir = args.spec_dir.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    generate(spec_dir, out, views, args.tlc2)
    print(f"regenerated {views} case packages under {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
