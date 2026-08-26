#!/usr/bin/env python3
"""Drive every registered graph ONE AT A TIME, with per-graph resumption.

WHY THIS EXISTS -- DEF-011, and the owner's instruction of 2026-08-24.

`run.py --all` hands the whole set to Gradle as one task chain, and Gradle stops
at the first failing task. So a red graph at position 1 yields "stopped at 1",
which is indistinguishable from "only 1 graph exists". Measured 2026-08-21: one
lost teardown race in `smoke` meant 1 of 24 executed and 23 never ran.

`run.py` ALSO REFUSES to combine `--all` with resumption:

    if args.run_all and (args.resume_from_build or replay_node_count):
        parser.error("resume options apply to one graph; pass <graph> instead of --all")

Resumption is per-graph. So "sweep everything" and "resume from the failing
node" cannot both be asked of `--all`, and the owner's instruction -- never
restart from the beginning -- requires driving the graphs individually.

That is this script. One graph at a time; a red graph does NOT stop the sweep;
every graph's outcome is recorded whether it passed or failed; and a graph that
failed can be resumed from its own failing node without re-running the ones
before it.

WHAT IT DOES NOT DO: decide anything. It records. `graphs_executed` is a count
of graphs whose task RAN, red or green -- `graphs_passed` is the separate
question, and both are written to the report so neither can be inferred from
the other.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUN_PY = HERE / "run.py"


def registered_graphs(repo_root: Path, scope: str) -> list[str]:
    """Ask the CI selector, so the sweep and CI cannot disagree about the set."""
    sel = repo_root / ".github/scripts/select-graph-set.py"
    out = subprocess.run(
        ["uv", "run", "--with", "pyyaml", "python3", str(sel), "--scope", scope, "--print"],
        capture_output=True, text=True, cwd=repo_root,
    )
    if out.returncode != 0:
        raise SystemExit(f"selector failed: {out.stderr[-800:]}")
    for line in out.stdout.splitlines():
        if line.startswith("- selected for this run"):
            return [t.strip(" `") for t in line.split("—", 1)[1].split(",")]
    raise SystemExit("selector printed no selection line")


def run_one(repo_root: Path, graph: str, log_dir: Path,
            resume_build: str | None, resume_node: str | None) -> dict:
    cmd = [sys.executable, str(RUN_PY), graph]
    if resume_build and resume_node:
        cmd += ["--resume-from-build", resume_build, "--resume-from-node", resume_node]
    log = log_dir / f"{graph}.log"
    started = time.time()
    with log.open("w") as fh:
        rc = subprocess.call(cmd, cwd=repo_root, stdout=fh, stderr=subprocess.STDOUT)
    text = log.read_text(errors="replace")
    run_id = None
    for line in text.splitlines():
        if "validation-reports/" in line:
            frag = line.split("validation-reports/", 1)[1]
            run_id = frag.split()[0].split("/")[0].strip()
    return {
        "graph": graph, "exit_code": rc, "passed": rc == 0,
        "run_id": run_id, "seconds": round(time.time() - started, 1),
        "log": str(log.relative_to(repo_root)),
        "resumed_from": {"build": resume_build, "node": resume_node} if resume_node else None,
    }


def node_outcomes(repo_root: Path, run_id: str | None) -> tuple[list[str], list[str]]:
    """Split nodes that FAILED from nodes that never RAN.

    Caught by its own selftest on 2026-08-24: the first version returned every
    node whose status was not "passed", which lumps `skipped` in with `failed`.
    On `artifact-dag` that reported three failing nodes where there is ONE --
    `uninstall.prunes.the.subgraph` failed and `home.fixpoint.law` /
    `home.membership.law` are downstream, `spawnExitCode: -1`, zero assertions.
    Reporting a node that never executed as a node that failed would have told
    HIS-6 the cross-cutting laws were broken.

    It also matters for resumption: you resume from the node that FAILED. A
    skipped node has no saved input context to resume from.
    """
    if not run_id:
        return [], []
    env = repo_root / "test_graph/build/validation-reports" / run_id / "envelope"
    if not env.is_dir():
        return [], []
    failed, skipped = [], []
    for f in sorted(env.glob("*.json")):
        try:
            e = json.loads(f.read_text())
        except Exception:
            continue
        status = e.get("status")
        node = e.get("nodeId") or f.stem
        if status == "passed":
            continue
        (skipped if status == "skipped" else failed).append(node)
    return failed, skipped


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scope", default="full", choices=["core", "full"])
    ap.add_argument("--out", default="results/epic-home-integrity-sync/sweep")
    ap.add_argument("--only", nargs="*", help="run just these graphs")
    ap.add_argument("--start-at", help="skip graphs before this one")
    ap.add_argument("--retry-failed-from", help="a previous sweep.json; re-run only its failures, resuming each from its first failing node")
    args = ap.parse_args()

    repo_root = Path(subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"], text=True).strip())
    out_dir = repo_root / args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    log_dir = out_dir / "logs"
    log_dir.mkdir(exist_ok=True)

    resume_plan: dict[str, tuple[str, str]] = {}
    if args.retry_failed_from:
        prev = json.loads((repo_root / args.retry_failed_from).read_text())
        graphs = []
        for r in prev["results"]:
            if r["passed"]:
                continue
            graphs.append(r["graph"])
            nodes, _ = node_outcomes(repo_root, r.get("run_id"))
            if nodes and r.get("run_id"):
                build = f"test_graph/build/validation-reports/{r['run_id']}"
                resume_plan[r["graph"]] = (build, nodes[0])
        print(f"retrying {len(graphs)} failed graph(s); {len(resume_plan)} resumable from a node")
    else:
        graphs = args.only or registered_graphs(repo_root, args.scope)
        if args.start_at:
            graphs = graphs[graphs.index(args.start_at):]

    print(f"sweep: {len(graphs)} graph(s), one at a time, a red one does NOT stop the sweep")
    results = []
    for i, g in enumerate(graphs, 1):
        rb, rn = resume_plan.get(g, (None, None))
        note = f"  (resuming from {rn})" if rn else ""
        print(f"[{i}/{len(graphs)}] {g}{note} ...", flush=True)
        r = run_one(repo_root, g, log_dir, rb, rn)
        r["failing_nodes"], r["skipped_nodes"] = node_outcomes(repo_root, r.get("run_id"))
        results.append(r)
        mark = "PASS" if r["passed"] else f"FAIL rc={r['exit_code']}"
        nodes = f"  failed: {', '.join(r['failing_nodes'])}" if r["failing_nodes"] else ""
        skips = f"  (skipped downstream: {len(r['skipped_nodes'])})" if r["skipped_nodes"] else ""
        print(f"      {mark}  {r['seconds']}s  run={r['run_id']}{nodes}{skips}", flush=True)
        (out_dir / "sweep.json").write_text(json.dumps({
            "graphs_selected": len(graphs),
            "graphs_executed": len(results),
            "graphs_passed": sum(1 for x in results if x["passed"]),
            "results": results,
        }, indent=2) + "\n")

    ex, ps = len(results), sum(1 for r in results if r["passed"])
    print(f"\ngraphs_executed={ex}  graphs_passed={ps}  graphs_failed={ex - ps}")
    for r in results:
        if not r["passed"]:
            sk = f"  [+{len(r['skipped_nodes'])} skipped downstream]" if r["skipped_nodes"] else ""
            print(f"  FAILED {r['graph']}: {', '.join(r['failing_nodes']) or 'no node envelope'}{sk}")
    print(f"\nwrote {out_dir / 'sweep.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
