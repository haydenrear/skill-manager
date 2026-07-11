# /// script
# requires-python = ">=3.11"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../../sdk/python", editable = true }
# ///
"""Aggregate projected-program-state evidence for the external spec cases.

Fails when any generated external trace is missing per-case evidence, when
any projected state mismatched, or when an external action family declared
by External.tla produced no executed case (exhaustiveness guard).
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node

SPEC = (
    NodeSpec("sm.spec.projected.evidence")
    .kind("evidence")
    .depends_on("sm.spec.external.cases")
    .tags("skill-manager", "spec", "external")
    .timeout("5m")
    .side_effects("fs:tmp")
)


@node(SPEC)
def run(ctx):
    work_dir = ctx.get("sm.spec.external.cases", "workDir")
    trace_manifest = ctx.get("sm.spec.external.cases", "traceManifest")
    model_dir = ctx.get("sm.spec.external.cases", "modelDir")
    if not work_dir or not trace_manifest or not model_dir:
        return NodeResult.fail(SPEC.id, "missing workDir/traceManifest/modelDir from sm.spec.external.cases")

    records = _load_records(Path(work_dir))
    expected_cases = _expected_trace_names(Path(trace_manifest))
    observed_cases = sorted(str(record.get("case")) for record in records)
    matched = [record for record in records if record.get("matched") is True]
    mismatched = [record for record in records if record.get("matched") is not True]

    aggregate = ctx.report_dir / "projected-program-states.json"
    aggregate.write_text(json.dumps(records, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    declared_actions = _declared_external_actions(Path(model_dir))
    executed_actions = Counter(str(record.get("action")) for record in records)
    families_missing = sorted(declared_actions - set(executed_actions))

    action_summary = ctx.report_dir / "external-action-coverage.json"
    action_summary.write_text(
        json.dumps(
            {
                "declared": sorted(declared_actions),
                "executed": dict(sorted(executed_actions.items())),
                "missing": families_missing,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )

    result = (
        NodeResult.pass_(SPEC.id)
        .artifact("json", str(aggregate))
        .artifact("json", str(action_summary))
        .metric("projectedAssertionFiles", len(records))
        .metric("matchedRecords", len(matched))
        .metric("mismatchedRecords", len(mismatched))
        .metric("declaredExternalActions", len(declared_actions))
        .metric("coveredExternalActions", len(executed_actions))
        .assertion("every generated trace produced evidence", observed_cases == expected_cases)
        .assertion("every projected program state matched", bool(records) and not mismatched)
        .assertion("every declared external action executed at least one case", not families_missing)
    )
    for action, count in sorted(executed_actions.items()):
        result = result.metric(f"cases.{action}", count)

    if observed_cases != expected_cases:
        missing = sorted(set(expected_cases) - set(observed_cases))[:10]
        return NodeResult.fail(
            SPEC.id, f"{len(observed_cases)}/{len(expected_cases)} traces have evidence; missing sample {missing}"
        ).artifact("json", str(aggregate)).artifact("json", str(action_summary))
    if mismatched:
        sample = [record.get("case") for record in mismatched[:10]]
        return NodeResult.fail(
            SPEC.id, f"{len(mismatched)} projected program states mismatched; sample {sample}"
        ).artifact("json", str(aggregate)).artifact("json", str(action_summary))
    if families_missing:
        return NodeResult.fail(
            SPEC.id, f"external actions with no executed cases: {families_missing}"
        ).artifact("json", str(aggregate)).artifact("json", str(action_summary))
    return result


def _load_records(work_dir: Path) -> list[dict]:
    return [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted((work_dir / "case-work").glob("*/program-state.json"))
    ]


def _expected_trace_names(manifest: Path) -> list[str]:
    if not manifest.is_file():
        return []
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    return sorted(Path(name).stem for name in payload["traces"])


def _declared_external_actions(model_dir: Path) -> set[str]:
    import re

    external = (model_dir / "External.tla").read_text(encoding="utf-8")
    declared = set(re.findall(r'MarkExternal\("([A-Za-z]+)"', external))
    declared.discard("HiddenInternalProgress")
    return declared


if __name__ == "__main__":
    run()
