# HP-11 evidence (#426)

Docker was not running for any of these runs (`docker info` failed before each).

| file | what |
| --- | --- |
| sweep1-* | `graph-run.sh smoke onboard plugin-smoke --continue`: onboard PASSED; smoke red at `mcp.tool.loads.bundled` (gateway killed by a concurrent worktree's `gateway.up` stale cleanup, see PR), plugin-smoke red at `plugin.synced` (exit 11 from sibling probes, previously masked) |
| sweep2-* | rerun of the two affected graphs after the fixes: smoke 43/43, plugin-smoke 21/21 PASSED |
| select-graph-set-*.txt | both scopes succeed; 31 registered, full 27, core 9 |
| unit-tests.txt | `jbang RunTests.java`: ALL PASSED |
| home-close-out.txt | `skill-manager home close-out`: holds nothing that removing it would destroy |
