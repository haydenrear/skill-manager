#!/usr/bin/env python3
"""Run skill-manager's test graphs — one command, and it names what it skipped.

WHY THIS EXISTS
---------------
`test_graph/build.gradle.kts` registers thirty graphs. The documented way to
run them all is the installed test-graph skill's `scripts/run.py --all`, which invokes Gradle's
`validationRunAll`; that task fans out over `ext.graphs`, i.e. every graph
registered with `testGraph(...)`. Three of those boot Selenium and chromedriver
(`browser-auth`, `password-reset`, `refresh-flow`), so `--all` on a laptop
starts a real browser, and `refresh-flow` is a known ~1-in-4 flake
(skill-manager-integration-repository#53). CI already knows all of this —
`.github/scripts/select-graph-set.py` carries the reasons — but nothing local
did, so a developer's choice was "run everything including a browser" or "type
a list of names from memory", and a name left out of a typed list leaves no
record that it was left out.

This script is the local counterpart of the CI selector, and it IMPORTS that
selector rather than restating it, so there is one list of exclusions and one
set of reasons. Every registered graph comes out classified:

    RUN      run by this command
    OPT-IN   deliberately not run here, with the reason and how to opt in
    DEAD     registered but known not to work

An unclassified graph is printed as UNCLASSIFIED. "Did not run" and "is not run
here" are different sentences and this command prints both of them.

VERDICTS COME FROM THE SWEEP LEDGER, NOT FROM AN EXIT CODE, and never from
`build/validation-reports/`, which keeps passing reports from earlier runs so a
graph that never executed still looks green there (DEF-OUN-023).

THIS SCRIPT NEVER REFUSES: it always exits 0. It reports; it does not gate.

Usage:
    python3 test_graph/run-graphs.py             # run the RUN set, then report
    python3 test_graph/run-graphs.py --list      # classification only; run nothing
    python3 test_graph/run-graphs.py --only smoke --only home-integrity
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import time
from pathlib import Path

TEST_GRAPH_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TEST_GRAPH_ROOT.parent
SELECTOR = REPO_ROOT / ".github" / "scripts" / "select-graph-set.py"


def _resolve_runner() -> Path:
    """`run.py` from the installed test-graph skill, on either rung.

    SI-18/#40: this repository no longer vendors `skills/test_graph/`. The
    runner comes from whatever `project resolve` installed into
    `.skill-manager`, which means the graphs now exercise the SAME test-graph
    the evals install instead of a private copy that had drifted 381 lines and
    three whole files ahead of it.

    Both rungs, in the order everything else in this repo uses them: a
    standalone `skills/test-graph/` first, then `plugins/*/skills/test-graph/`,
    because test-graph is a CONTAINED skill of the tla-spec-dev plugin and that
    is where its bytes actually land. The project home is preferred over the
    operator's root home so a checkout measures what it resolved, not what
    happens to be installed globally.
    """
    homes = [REPO_ROOT / ".skill-manager"]
    env_home = os.environ.get("SKILL_MANAGER_HOME")
    if env_home:
        homes.append(Path(env_home))
    homes.append(Path.home() / ".skill-manager")
    for home in homes:
        standalone = home / "skills" / "test-graph" / "scripts" / "run.py"
        if standalone.is_file():
            return standalone
        for contained in sorted(home.glob("plugins/*/skills/test-graph/scripts/run.py")):
            if contained.is_file():
                return contained
    # Return the preferred spelling so the caller has a path to name in an
    # error rather than None; run() reports the miss with the remedy.
    return homes[0] / "plugins" / "tla-spec-dev" / "skills" / "test-graph" / "scripts" / "run.py"


RUNNER = _resolve_runner()

# Graphs registered but known not to work: none today. Kept as an explicit
# empty table because "no dead graphs" is a claim somebody checked, and an
# absent table is indistinguishable from nobody having looked.
DEAD: dict[str, str] = {}

# Reasons for the browser graphs, which the CI selector subtracts from the
# matrix without a per-graph reason of its own (it explains them in ci.yml).
BROWSER_REASONS: dict[str, str] = {
    "browser-auth": "boots chromedriver and a real browser; run it deliberately",
    "password-reset": "boots chromedriver and a real browser; run it deliberately",
    "refresh-flow": "boots a browser AND is excluded outright (see the reason above)",
}


def load_selector():
    spec = importlib.util.spec_from_file_location("select_graph_set", SELECTOR)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def classify(selector) -> tuple[list[str], dict[str, str], list[str]]:
    all_graphs = selector.discover_all(REPO_ROOT)
    opt_in: dict[str, str] = {}
    for name, reason in selector.EXCLUDED.items():
        opt_in[name] = reason
    for name in selector.BROWSER:
        extra = BROWSER_REASONS.get(name, "Selenium graph")
        opt_in[name] = f"{opt_in[name]} ALSO: {extra}" if name in opt_in else extra
    run = [g for g in all_graphs if g not in opt_in and g not in DEAD]
    unclassified = [g for g in all_graphs if g not in run and g not in opt_in and g not in DEAD]
    return run, opt_in, unclassified


def newest_sweep(started_at: float) -> dict | None:
    sweeps = TEST_GRAPH_ROOT / "build" / "validation-sweeps"
    if not sweeps.is_dir():
        return None
    best: tuple[float, dict] | None = None
    for path in sweeps.glob("*/sweep.json"):
        if path.stat().st_mtime < started_at:
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        data["_path"] = str(path)
        stamp = path.stat().st_mtime
        if best is None or stamp > best[0]:
            best = (stamp, data)
    return best[1] if best else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--list", action="store_true", help="classification only; run nothing")
    parser.add_argument("--only", action="append", default=[], metavar="GRAPH",
                        help="run just this graph (repeatable)")
    args = parser.parse_args()

    selector = load_selector()
    if selector is None:
        print(f"UNDECIDED: cannot load {SELECTOR}; nothing classified, nothing run.")
        return 0

    run, opt_in, unclassified = classify(selector)
    total = len(run) + len(opt_in) + len(DEAD) + len(unclassified)

    print("== skill-manager test graphs ==")
    print(f"registered in test_graph/build.gradle.kts: {total}")
    if total == 0:
        print("  NOTHING REGISTERED — the classification below describes nothing. "
              "Treat every graph as UNDECIDED.")
    print(f"\nRUN by this command ({len(run)}):")
    for graph in run:
        print(f"  {graph}")
    print(f"\nOPT-IN, not run here ({len(opt_in)}) — 'did not run' is not 'is not run here':")
    for graph, reason in opt_in.items():
        print(f"  {graph}\n      {reason}")
        if graph == "hyper-experiments":
            print("      opt in: HYPER_EXPERIMENTS=1 ./gradlew hyper-experiments")
        elif graph in selector.BROWSER:
            print(f"      opt in: python3 {RUNNER} {graph}")
    print(f"\nDEAD ({len(DEAD)}):")
    for graph, reason in DEAD.items():
        print(f"  {graph}  {reason}")
    if not DEAD:
        print("  (none — every registered graph either runs here or is opt-in above)")
    if unclassified:
        print(f"\nUNCLASSIFIED ({len(unclassified)}) — nobody has said whether these "
              "should run; report them, do not assume:")
        for graph in unclassified:
            print(f"  {graph}")

    if args.list:
        return 0

    wanted = args.only or run
    if not RUNNER.is_file():
        print(f"\nUNDECIDED: no runner at {RUNNER}; nothing was run, no graph is green.")
        return 0

    started_at = time.time() - 1
    print(f"\n== running {len(wanted)} graph(s) ==", flush=True)
    proc = subprocess.run([sys.executable, str(RUNNER), *wanted, "--continue"],
                          cwd=str(REPO_ROOT))

    sweep = newest_sweep(started_at)
    print("\n== verdicts (from this invocation's sweep ledger, not from exit codes) ==")
    if sweep is None:
        print(f"  UNDECIDED: no sweep ledger written by this invocation "
              f"(runner exit {proc.returncode}). No graph here is green or red; "
              "it is unmeasured.")
    else:
        print(f"  ledger: {sweep.get('_path')}")
        print(f"  selected={sweep.get('graphs_selected')} executed={sweep.get('graphs_executed')} "
              f"passed={sweep.get('graphs_passed')} failed={sweep.get('graphs_failed')} "
              f"not_run={sweep.get('graphs_not_run')}")
        seen = set()
        for record in sweep.get("results", []):
            name = record.get("graph")
            seen.add(name)
            status = str(record.get("status", "?")).upper()
            reason = record.get("reason") or ""
            print(f"  {status:9} {name:28} {record.get('seconds')}s {reason}")
        for graph in wanted:
            if graph not in seen:
                print(f"  {'UNDECIDED':9} {graph:28} no ledger row — it did not execute")
    for graph, reason in opt_in.items():
        print(f"  {'NOT RUN':9} {graph:28} opt-in: {reason[:80]}")

    print("\nThis command always exits 0. A red or undecided graph is a report, "
          "not a refusal.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
