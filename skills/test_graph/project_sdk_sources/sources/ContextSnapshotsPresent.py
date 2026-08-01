# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Assert that one attempt contains exactly its scoped context evidence."""
from __future__ import annotations

import json
import os
import re
import stat
from pathlib import Path
from typing import Any

from testgraphsdk import NodeResult, NodeSpec, node


UPSTREAM_NODE_IDS = [
    "app.running",
    "user.seeded",
    "network.pingable",
    "login.smoke",
    "rerun.disabled.probe",
]
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_SCOPE_NODES = 10_000
NODE_ID_RE = re.compile(r"^[a-z0-9._-]{1,128}$")

SPEC = (
    NodeSpec("context.snapshots.present")
    .kind("assertion")
    .depends_on("login.smoke", "rerun.disabled.probe")
    .tags("smoke", "resume")
    .timeout("30s")
    .output("snapshotCount", "integer")
)


def _snapshot_name(node_id: str) -> str:
    if NODE_ID_RE.fullmatch(node_id) is None:
        raise ValueError(f"invalid node id in execution scope: {node_id!r}")
    return f"{node_id}.input.json"


def _bounded_json_object(path: Path, label: str) -> dict[str, Any]:
    try:
        info = path.lstat()
    except OSError as exc:
        raise ValueError(f"missing {label}: {path}") from exc
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f"{label} must be a regular non-symlink file: {path}")
    if info.st_size > MAX_JSON_BYTES:
        raise ValueError(f"{label} exceeds {MAX_JSON_BYTES} bytes")
    with path.open("rb") as handle:
        raw = handle.read(MAX_JSON_BYTES + 1)
    if len(raw) > MAX_JSON_BYTES:
        raise ValueError(f"{label} exceeds {MAX_JSON_BYTES} bytes")
    payload = json.loads(raw.decode("utf-8", errors="strict"))
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must contain one JSON object")
    return payload


def _execution_scope(report_dir: Path) -> tuple[str, ...]:
    scope = _bounded_json_object(
        report_dir / "execution-scope.json",
        "execution scope",
    )
    if scope.get("version") != 3:
        raise ValueError("execution scope must use version 3")
    raw_ids = scope.get("expectedNodeIds")
    if (
        not isinstance(raw_ids, list)
        or not 1 <= len(raw_ids) <= MAX_SCOPE_NODES
        or any(not isinstance(node_id, str) for node_id in raw_ids)
    ):
        raise ValueError("execution scope must contain 1..10,000 string node ids")
    node_ids = tuple(raw_ids)
    if len(set(node_ids)) != len(node_ids):
        raise ValueError("execution scope contains duplicate node ids")
    for node_id in node_ids:
        _snapshot_name(node_id)
    return node_ids


def _canonical_ids(directory: Path, suffix: str) -> set[str]:
    observed: set[str] = set()
    count = 0
    with os.scandir(directory) as entries:
        for entry in entries:
            count += 1
            if count > MAX_SCOPE_NODES:
                raise ValueError(
                    f"{directory.name} exceeds {MAX_SCOPE_NODES} canonical entries"
                )
            if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
                raise ValueError(f"non-regular evidence entry: {entry.path}")
            if not entry.name.endswith(suffix):
                raise ValueError(f"unexpected evidence entry: {entry.path}")
            node_id = entry.name.removesuffix(suffix)
            if NODE_ID_RE.fullmatch(node_id) is None or node_id in observed:
                raise ValueError(f"invalid or duplicate evidence identity: {entry.path}")
            observed.add(node_id)
    return observed


@node(SPEC)
def main(ctx):
    context_dir = ctx.report_dir / "context"
    node_ids = _execution_scope(ctx.report_dir)
    if ctx.node_id not in node_ids:
        raise ValueError("current node is absent from its immutable execution scope")
    result = NodeResult.pass_(ctx.node_id)

    observed_context_ids = _canonical_ids(context_dir, ".input.json")
    result.assertion(
        "context_snapshot_identities_match_execution_scope",
        observed_context_ids == set(node_ids),
    )

    for node_id in node_ids:
        path = context_dir / _snapshot_name(node_id)
        try:
            payload = _bounded_json_object(path, f"input context for {node_id}")
            exists = True
        except (OSError, UnicodeError, ValueError):
            payload = {}
            exists = False
        result.assertion(f"{node_id}.input_context_snapshot_exists", exists)
        if exists:
            result.assertion(
                f"{node_id}.input_context_snapshot_has_items_array",
                set(payload) == {"items"} and isinstance(payload.get("items"), list),
            )

    self_snapshot = context_dir / _snapshot_name(ctx.node_id)
    try:
        payload = _bounded_json_object(self_snapshot, "current-node input context")
        raw_items = payload.get("items")
        items = raw_items if isinstance(raw_items, list) else []
        seen = {
            item.get("nodeId")
            for item in items
            if isinstance(item, dict)
        }
        result.assertion(
            "self_snapshot_contains_upstream_context",
            isinstance(raw_items, list) and set(UPSTREAM_NODE_IDS).issubset(seen),
        )
    except (OSError, UnicodeError, ValueError):
        result.assertion("self_snapshot_contains_upstream_context", False)

    envelope_dir = ctx.report_dir / "envelope"
    current_index = node_ids.index(ctx.node_id)
    prior_attempt_node_ids = node_ids[:current_index]
    observed_envelope_ids = _canonical_ids(envelope_dir, ".json")
    result.assertion(
        "predecessor_envelope_identities_match_execution_scope",
        observed_envelope_ids == set(prior_attempt_node_ids),
    )
    for node_id in prior_attempt_node_ids:
        try:
            envelope = _bounded_json_object(
                envelope_dir / f"{node_id}.json",
                f"envelope for {node_id}",
            )
        except (OSError, UnicodeError, ValueError):
            envelope = {}
        rel = envelope.get("inputContextFile")
        expected_rel = f"context/{_snapshot_name(node_id)}"
        result.assertion(
            f"{node_id}.envelope_has_scoped_input_context_file",
            rel == expected_rel,
        )
        if rel:
            result.assertion(
                f"{node_id}.envelope_input_context_file_exists",
                (ctx.report_dir / rel).is_file()
                and not (ctx.report_dir / rel).is_symlink(),
            )

    return (
        result
        .metric("snapshotCount", len(node_ids))
        .artifact("input-context-dir", str(context_dir))
        .publish("snapshotCount", str(len(node_ids)))
    )


if __name__ == "__main__":
    main()
