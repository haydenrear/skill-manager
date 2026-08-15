# Working in this repo

## Running test_graph

The integration tests live under `test_graph/` and run via:

```
python skills/test_graph/scripts/run.py --all      # every registered graph
python skills/test_graph/scripts/run.py <graph>    # one graph (smoke / plugin-smoke / sponsored / source-tracking / ...)
```

A full `--all` run is ~7 minutes. Each registered graph runs as a Gradle
task; the wrapper aggregates output but Gradle stops at the first
failing task so later graphs in the sweep don't run.

### When a graph fails — debugging workflow

1. **Tail 30+ lines** of the failing run's stdout. Tailing 5–10 lines
   misses the per-task failure marker (`> Task :<graph> FAILED`) and
   the upstream context. Use:

   ```
   python skills/test_graph/scripts/run.py --all 2>&1 | tail -40
   ```

2. **Run the failing graph in isolation** before re-running `--all`.
   Iterating against the full sweep wastes ~7 minutes per attempt:

   ```
   python skills/test_graph/scripts/run.py plugin-smoke 2>&1 | tail -40
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
| `push` on `main`; `pull_request` into `main` or `epic/**` | **core** — 8 graphs, 152 nodes |
| `schedule` (07:00 UTC) | **full** — every registered graph bar the named exclusions, plus the Selenium job |
| `workflow_dispatch` | `graph_set: core\|full`, `run_browser_graphs: true\|false` |

There is deliberately no `push: epic/**`: every ticket lands as a PR into
the epic branch, and promotion is serialized, so ticket N+1's PR rebases
onto N's merge and tests that integrated tree anyway. Adding it would run
the matrix twice per ticket for a signal that arrives one ticket later
regardless. **A direct push to an epic branch runs nothing** until the
next PR — accepted, because this epic promotes through PRs.

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
