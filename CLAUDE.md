# Working in this repo

## Running test_graph

The integration tests live under `test_graph/`. **The front door is one
command**, which runs the graphs meant to run here and prints the ones it did
not run, each with its reason:

```
python3 test_graph/run-graphs.py           # the 26 graphs meant to run here
python3 test_graph/run-graphs.py --list    # classification only; runs nothing
```

Thirty graphs are registered. Twenty-six run; four are OPT-IN and excluded by
default — `browser-auth` and `password-reset` boot chromedriver and a real
browser, `refresh-flow` is a known ~1-in-4 flake
(skill-manager-integration-repository#53), and `hyper-experiments` reaches
github, npm and the live RunPod API (#143). `run-graphs.py` names all four and
prints each one's opt-in command, so "did not run" can be told apart from "is
not run here". It reads its verdicts from the sweep ledger, and it always exits
0: it reports, it does not gate.

## Before any of this: resolve the project home

**SI-18/#40 stopped vendoring the test-graph skill and its SDK.** `test_graph/sdk`,
`test_graph/build-logic` and `test_graph/standard-nodes` are now tracked
SYMLINKS into `.skill-manager/plugins/tla-spec-dev/skills/test-graph/`, and
`.skill-manager` is gitignored. **A fresh clone cannot run graphs until the home
exists:**

```
mkdir -p .skill-manager/skills
SKILL_MANAGER_HOME="$PWD/.skill-manager" ./skill-manager project resolve \
    --project-dir "$PWD" --skip-gateway --repair-vendored
```

That installs the `tla-spec-dev` plugin, whose contained `test-graph` skill
supplies both the SDK and the runner. The point is that the graphs and the evals
now exercise the SAME copy: the vendored one had drifted 381 lines and three
whole files ahead of the skill it copied, and nothing could see it.

The underlying runner is the installed skill's, for a single graph or an
arbitrary set. `run-graphs.py` resolves it (standalone rung first, then
`plugins/*/skills/test-graph`), so prefer the front door; the direct form is:

```
R=.skill-manager/plugins/tla-spec-dev/skills/test-graph/scripts/run.py
python3 $R <graph>                             # one graph (smoke / plugin-smoke / ...)
python3 $R doc-smoke artifact-dag sync-settles # several graphs
python3 $R --all                               # EVERY registered graph, INCLUDING the browser ones
```

A full `--all` run is ~7 minutes. Each registered graph runs as a Gradle
task, always in `build.gradle.kts` declaration order (not command-line
order). `--all` and a multi-graph invocation are a **sweep**: they run
Gradle with `--continue`, so a red graph does not stop the graphs after
it (`--fail-fast` restores stopping at the first red). A sweep ends with

```
== test graph sweep <sweepId> ==
graphs_selected=N  graphs_executed=N  graphs_passed=N  graphs_failed=N  graphs_not_run=N
  PASSED / FAILED / NOT RUN  <graph>  <seconds>  build/validation-reports/<runId>
```

and exits non-zero if any selected graph failed or did not run. Those
counts come from a ledger the runner's Gradle init script writes during
that invocation (`test_graph/build/validation-sweeps/<sweepId>/sweep.json`).
**Never count coverage from `build/validation-reports/`** — it keeps
passing reports from earlier runs, so a graph that never executed still
looks green there (DEF-OUN-023). A single `run.py <graph>` prints no
summary; add `--continue` if you want one.

### When a graph fails — debugging workflow

1. **Tail 30+ lines** of the failing run's stdout. Tailing 5–10 lines
   misses the per-task failure marker (`> Task :<graph> FAILED`) and
   the upstream context. Use:

   ```
   python3 test_graph/run-graphs.py 2>&1 | tail -40
   ```

2. **Run the failing graph in isolation** before re-running `--all`.
   Iterating against the full sweep wastes ~7 minutes per attempt:

   ```
   python3 test_graph/run-graphs.py --only plugin-smoke 2>&1 | tail -40
   ```

   Faster iteration loop and the failing node's logs land in the same
   place either way.

3. **Inspect node-level logs** under
   `test_graph/build/validation-reports/<runId>/`:
   - `envelope/<nodeId>.json` — assertion-by-assertion result + the
     command line and exit code.
   - `node-logs/<nodeId>.<label>.log` — captured stdout/stderr from
     the actual subprocess (where the failure usually surfaces).

   The most recent run is `ls -t .../validation-reports/ | head -1`.

4. **Once the failing graph passes in isolation**, re-run `--all` to
   confirm no other graph regressed.

### Common failure modes

- **Policy gate prompts hang in non-interactive contexts**: tests pass
  `--yes`; the test home's `policy.toml` (written by `EnvPrepared`)
  also turns off every install-confirmation gate.
- **Docker pulls fail with 403/404**: the image isn't published, or the
  registry rejects anonymous pulls. Swap to a known-public image
  (`mcp/sequentialthinking:latest` for stdio examples).
- **`hello.installed` resolves to a contained-skill name**: the
  resolver / fetcher's locate-root logic descended into
  `skills/<contained>/SKILL.md` instead of detecting the plugin
  layout. Check `Resolver.resolveAll` and `Fetcher.locateSkillRoot`
  for plugin-aware probes (`PluginParser.looksLikePlugin`).

### What CI runs, and how to know

Do not read the matrix out of `ci.yml` — it is computed, not typed.
`.github/scripts/select-graph-set.py` is the single source:

| event | graph set |
| --- | --- |
| `push` on `main`; `pull_request` into `main` or `epic/**` | **none** — suspended, see below |
| `schedule` (07:00 UTC) | **full** — every registered graph bar the named exclusions, plus the Selenium job |
| `workflow_dispatch` | `graph_set: core\|full`, `run_browser_graphs: true\|false` |

**The graphs do not run on a push or a PR right now**, at the owner's
instruction ("disable the graphs and just run them locally for now"). The
`graph-set` job carries `if: github.event_name == 'schedule' ||
github.event_name == 'workflow_dispatch'`, and every graph job `needs:
graph-set`, so push and PR skip the lot — including `graph-count`, so those
runs produce no `graphs-executed` artifact at all. `unit-tests` and the
`virtual-mcp-gateway` pytest job are unaffected and still run on both.

**So run them locally** — that is now the only pre-merge graph signal:

```
python3 test_graph/run-graphs.py --only <graph>
python3 .github/scripts/select-graph-set.py --scope core --print   # what the core set is
```

Or on a runner, on demand, without waiting for 07:00 UTC:

```
gh workflow run ci.yml -R haydenrear/skill-manager --ref <branch> -f graph_set=core
```

**This costs GOAL-validation-floor metric (a)** — graphs executed per CI
run, target ≥ 8 — on the push/PR half: **8 nightly, 0 per push and per PR.**
It is a suspension and not a removal, which is why the `schedule` and
`workflow_dispatch` triggers were kept; the terminal evaluation ticket
decides which number the goal is read against.

There remains deliberately no `push: epic/**` trigger: every ticket lands as
a PR into the epic branch, and promotion is serialized, so ticket N+1's PR
rebases onto N's merge and tests that integrated tree anyway. **A direct push
to an epic branch runs nothing** until the next PR — accepted, because this
epic promotes through PRs.

Print either set without a runner:

```
python3 .github/scripts/select-graph-set.py --scope core --print
python3 .github/scripts/select-graph-set.py --scope full --print
```

The selector fails if a name in `CORE` or `EXCLUDED` no longer exists in
`test_graph/build.gradle.kts`, so renaming a graph breaks the selector
loudly instead of shrinking the matrix silently. **Two graphs are excluded
from every automatic set** — `refresh-flow` (integration-repo #53, ~1-in-4
flake by construction) and `hyper-experiments` (#143, three third-party
services) — and the reason is printed into the job summary of every run.

**How many graphs did a run actually execute?** Read it, do not infer it:

```
gh run download <run-id> -R haydenrear/skill-manager -n graphs-executed
jq .graphs_executed graphs-executed.json
```

`graphs_executed` counts graphs whose task ran, red or green;
`graphs_passed` is the separate question. The whole matrix used to sit
behind `vars.ENABLE_TEST_GRAPH`, which was never set — twelve graphs
declared, zero executed, every run green.
