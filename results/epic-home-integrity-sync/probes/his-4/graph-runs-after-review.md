# Graph runs after the #231 review fixes

All three re-run on the branch tip, after the HIGH-1/2/3 and MED-4/7 changes.

| graph | runId | result |
| --- | --- | --- |
| sync-settles (RED, fix reverted) | 20260821-191159 | BUILD FAILED in 59s, node `failed` |
| sync-settles (GREEN, review fixes in) | 20260821-201708 | BUILD SUCCESSFUL in 1m 1s, node `passed` |
| project-child-home | 20260821-201827 | BUILD SUCCESSFUL in 3m 49s, 12/12 |

project-child-home is re-run here rather than relying on the reviewer's run,
because `SyncFromLocalDirHandler` changed after that run.
