#!/usr/bin/env python3
"""Decide which test graphs this CI run executes, and say what it left out.

Why this exists
---------------
``ci.yml`` used to carry a hand-written matrix of twelve graph names. The
repository declares twenty-seven. Nothing reconciled the two lists, so the
fifteen that were never in the matrix were not *excluded* — they were simply
absent, and absence leaves no record. When the whole matrix was then gated
behind ``vars.ENABLE_TEST_GRAPH`` (#113) the same failure mode ran to
completion: CI executed zero graphs and reported success.

So the graph set is computed, not typed:

* ``ALL``      — every ``testGraph("...")`` registered in
                 ``test_graph/build.gradle.kts``. Read from the build file, so
                 a graph added there cannot silently miss CI.
* ``CORE``     — the subset that runs on every push and pull request.
* ``EXCLUDED`` — graphs deliberately kept out of *every* automatic set, each
                 with a reason and an issue number, printed on every run.
* ``FULL``     — ``ALL - EXCLUDED``. Runs on ``schedule`` and on
                 ``workflow_dispatch``.

Every name in ``CORE`` and ``EXCLUDED`` must exist in ``ALL`` or this script
exits non-zero. A graph renamed in the build file breaks the selector loudly
instead of quietly shrinking the matrix.

Outputs (GITHUB_OUTPUT, all JSON-safe):
    scope         core | full
    graphs        JSON array of graph names for the matrix
    count         number of graphs in that array
    deferred      JSON array of graphs in ALL but not in the selected set
    gateway_graphs JSON array of selected graphs that boot virtual-mcp-gateway

Usage:
    select-graph-set.py [--scope core|full] [--repo-root DIR] [--print]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# CORE — runs on every push and pull request.
#
# Chosen for what the artifact-DAG epic (#100) actually changes: home
# provisioning, cloning, child homes, ticket worktree lifecycle, plugin
# registration. 8 graphs / 152 nodes, against 26 graphs / 326 nodes for the
# full set. The switch-off comment ("the graph matrix consumes too many
# hosted-runner resources") was true, and hosted minutes scale with the number
# of graphs, not with max-parallel — so the honest lever is the size of this
# list, and it is smaller than the twelve-graph matrix it replaces.
# ---------------------------------------------------------------------------
CORE: list[str] = [
    "smoke",               # 52 nodes — install/bind/sync/gateway spine
    "home-clone",          # 13 — home cloning, the epic's subject
    "home-sync",           # 18 — sync up a tier
    "checkout-home",       # 7  — per-checkout home bootstrap
    "project-child-home",  # 12 — child homes
    "ticket-lifecycle",    # 13 — wt/skt ticket new + close
    "plugin-smoke",        # 23 — plugin layout + harness registration
    "onboard",             # 14 — onboarding a unit end to end
    "home-integrity",      # 12 — what a healthy home IS (#124); no docker, no postgres
]

# ---------------------------------------------------------------------------
# EXCLUDED — kept out of core AND full, on purpose, with the reason visible.
#
# A quarantined test nobody can see is worse than a red one, so these are
# printed into the job summary of every run rather than living in a comment.
# ---------------------------------------------------------------------------
EXCLUDED: dict[str, str] = {
    "refresh-flow": (
        "skill-manager-integration-repository#53 — under-budgeted by design: a "
        "3s access-token TTL with CLOCK_SKEW_SECONDS=0 is verified by a live "
        "/auth/me round-trip that includes driver.quit(), which stalls 7.6-8.6s "
        "against a ~2.0s norm. Flakes ~1 in 4 on pre-epic code identically, so "
        "it is not an epic regression and not fixable from this ticket. "
        "Re-admit once the helper verifies the cached token instead of a live "
        "call inside the TTL."
    ),
    "hyper-experiments": (
        "skill-manager#143 — reaches github, npm and the live RunPod API; none "
        "of the three answer a question about skill-manager. Already opt-in at "
        "the build level (HYPER_EXPERIMENTS=1 / HYPER_LOCAL_DIR), so it is not "
        "in validationRunAll either. Run it deliberately, not on a push."
    ),
}

# ---------------------------------------------------------------------------
# BROWSER — Selenium/chromedriver graphs. Not excluded, but they run in their
# own job (test-graph-browser) on schedule and dispatch only, so they are
# subtracted from the matrix this script feeds.
# ---------------------------------------------------------------------------
BROWSER: list[str] = ["browser-auth", "password-reset", "refresh-flow"]

# Deliberately not line-anchored: `hyper-experiments` is registered as
# `} else testGraph("hyper-experiments") {`, and a graph that is conditionally
# registered still has to be visible to this script so its exclusion can be
# stated rather than inferred from its absence.
_TESTGRAPH_RE = re.compile(r'(?<![\w.])testGraph\("([^"]+)"\)')
_TESTGRAPH_OPEN_RE = re.compile(r'(?<![\w.])testGraph\("([^"]+)"\)\s*\{')
_NODE_RE = re.compile(r'node\("([^"]+)"\)')

# A graph "declares a gateway node" if any node source name mentions Gateway:
# `common/GatewayPythonVenvReady.java`, `smoke/GatewayUp.java`,
# `onboard/OnboardGatewayHealthy.java`. A substring rather than a fixed list, so
# a gateway node added later is picked up without a second hand-kept list going
# stale — the failure mode this whole script exists to remove.
#
# THIS IS REPORTING, NOT A CONDITION, and the difference cost a commit to learn.
# The venv resolves a dependency from a PRIVATE repository, so the obvious move
# was to skip building it for graphs declaring no gateway node. That is wrong:
# `InstallCommand` runs `EnsureGateway`, which shells out to its own `uv sync`,
# so a graph with no gateway node still needs the venv the moment it installs
# anything — measured on run 31900307288, where `checkout-home` (no gateway node
# among its seven) died in `EnsureGateway`. `ci.yml` therefore builds the venv
# unconditionally. This list only says which graphs boot a gateway *explicitly*.
_GATEWAY_MARKER = "Gateway"


def _graph_nodes(repo_root: Path) -> dict[str, list[str]]:
    """Map each registered graph to the node sources it declares.

    A brace-depth walk rather than a parser: the build file is Kotlin DSL and
    each `testGraph("name") { ... }` block is balanced, which is enough.
    """
    build_file = repo_root / "test_graph" / "build.gradle.kts"
    if not build_file.is_file():
        sys.exit(f"select-graph-set: no build file at {build_file}")
    out: dict[str, list[str]] = {}
    current: str | None = None
    depth = 0
    nodes: list[str] = []
    for line in build_file.read_text(encoding="utf-8").splitlines():
        if current is None:
            m = _TESTGRAPH_OPEN_RE.search(line)
            if m:
                current = m.group(1)
                depth = line.count("{") - line.count("}")
                nodes = []
            continue
        nodes.extend(_NODE_RE.findall(line))
        depth += line.count("{") - line.count("}")
        if depth <= 0:
            out.setdefault(current, nodes)
            current = None
    return out


def discover_all(repo_root: Path) -> list[str]:
    build_file = repo_root / "test_graph" / "build.gradle.kts"
    if not build_file.is_file():
        sys.exit(f"select-graph-set: no build file at {build_file}")
    names = _TESTGRAPH_RE.findall(build_file.read_text(encoding="utf-8"))
    if not names:
        sys.exit(f"select-graph-set: no testGraph(...) registrations in {build_file}")
    # Preserve declaration order, drop duplicates.
    seen: dict[str, None] = {}
    for n in names:
        seen.setdefault(n, None)
    return list(seen)


def gateway_graphs(repo_root: Path, selected: list[str]) -> list[str]:
    nodes = _graph_nodes(repo_root)
    return [
        g for g in selected
        if any(_GATEWAY_MARKER in n for n in nodes.get(g, []))
    ]


def select(scope: str, all_graphs: list[str]) -> tuple[list[str], list[str]]:
    known = set(all_graphs)

    unknown_core = [g for g in CORE if g not in known]
    if unknown_core:
        sys.exit(
            "select-graph-set: CORE names not registered in build.gradle.kts: "
            f"{unknown_core}. A graph was renamed or removed; fix this list "
            "rather than letting the matrix shrink silently."
        )
    unknown_excluded = [g for g in EXCLUDED if g not in known]
    if unknown_excluded:
        sys.exit(
            "select-graph-set: EXCLUDED names not registered in "
            f"build.gradle.kts: {unknown_excluded}. Drop the stale exclusion."
        )

    if scope == "core":
        selected = list(CORE)
    elif scope == "full":
        selected = [
            g for g in all_graphs
            if g not in EXCLUDED and g not in BROWSER
        ]
    else:
        sys.exit(f"select-graph-set: unknown scope {scope!r}")

    deferred = [g for g in all_graphs if g not in selected]
    return selected, deferred


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scope", default=os.environ.get("GRAPH_SCOPE", "core"))
    ap.add_argument("--repo-root", default=os.environ.get("GITHUB_WORKSPACE", "."))
    ap.add_argument("--print", action="store_true", help="human-readable dump only")
    args = ap.parse_args()

    repo_root = Path(args.repo_root).resolve()
    all_graphs = discover_all(repo_root)
    selected, deferred = select(args.scope, all_graphs)
    needs_gateway = gateway_graphs(repo_root, selected)

    lines = [
        f"scope: **{args.scope}**",
        "",
        f"- registered in `test_graph/build.gradle.kts`: **{len(all_graphs)}**",
        f"- selected for this run: **{len(selected)}** — "
        + ", ".join(f"`{g}`" for g in selected),
    ]
    if deferred:
        lines.append(
            f"- not in this run ({len(deferred)}): "
            + ", ".join(f"`{g}`" for g in deferred)
        )
    lines.append("")
    lines.append("Deliberate exclusions (every scope):")
    for name, reason in EXCLUDED.items():
        lines.append(f"- `{name}` — {reason}")
    lines.append("")
    lines.append(
        "Browser graphs (`" + "`, `".join(BROWSER) + "`) run in the "
        "`test-graph-browser` job on schedule and dispatch only."
    )
    lines.append("")
    lines.append(
        "**Every selected graph needs the virtual-mcp-gateway venv, and "
        "therefore the PRIVATE `haydenrear/tracing_skill` dependency** — "
        "`skill-manager install` runs `EnsureGateway`, which builds it. "
        + (
            "Booting a gateway explicitly: "
            + ", ".join(f"`{g}`" for g in needs_gateway)
            + f" ({len(needs_gateway)} of {len(selected)}); the rest reach it "
            "through `install`."
            if needs_gateway
            else "No selected graph boots a gateway explicitly; they reach it "
            "through `install`."
        )
    )
    summary = "\n".join(lines)

    print(summary)

    if args.print:
        return 0

    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write(f"scope={args.scope}\n")
            fh.write(f"graphs={json.dumps(selected)}\n")
            fh.write(f"count={len(selected)}\n")
            fh.write(f"deferred={json.dumps(deferred)}\n")
            fh.write(f"registered={len(all_graphs)}\n")
            fh.write(f"gateway_graphs={json.dumps(needs_gateway)}\n")

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write("## test-graph selection\n\n" + summary + "\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
