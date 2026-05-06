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
