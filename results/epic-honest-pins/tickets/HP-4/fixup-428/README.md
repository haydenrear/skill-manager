# HP-4 fixup for #428 R6 / R7

Base: `origin/epic/honest-pins` 7b2f72b8. Branch `feature/HP-4-fixup`.

| finding | red-first test | before fix | after fix |
| --- | --- | --- | --- |
| R6: MCP binary reprovision deletes the working install first | `virtual-mcp-gateway/tests/test_binary_provisioning_swap.py` (6 cases; the legacy-`ok`-marker-offline case is the upgrade migration itself) | 4 failed, 2 passed (`r6-red.txt`): URLError / FileNotFoundError / init_script CalledProcessError with the old install already gone | 6 passed (`r6-green.txt`) |
| R7: partial `home sync` leaves stale records, no drift, no repair | `src/test/java/dev/skillmanager/store/HomeSyncPartialFailureTest.java` (2nd of 3 units fails; crash-without-bookkeeping repaired by next pass; no-op control) | 1 passed, 2 failed (`r7-red.txt`): unit a's record stayed 0.1.0 after its bytes moved to 0.2.0 | 3 passed (`r7-green.txt`) |

Validation:
- `jbang RunTests.java`: ALL PASSED, 1692 passed / 0 failed (log local; `*.log` gitignored).
- `virtual-mcp-gateway` pytest: 101 passed.
- `graph-run.sh honest-pins home-sync --continue`, sweep 20261108-032009 (`sweep.json`): honest-pins PASSED, home-sync PASSED.
