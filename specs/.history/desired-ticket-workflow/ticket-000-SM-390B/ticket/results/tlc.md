# SM-390B TLC evidence (2026-09-17T16:43Z, TLC 2.19)

## GitHistoryInternal.cfg
```
Model checking completed. No error has been found.
926296 states generated, 115146 distinct states found, 0 states left on queue.
```
## GitHistoryInternal_regression_allrefs.cfg
```
Error: Invariant AnAncestorCopyWithNothingUnpublishedIsNeverBlocked is violated.
31 states generated, 17 distinct states found, 13 states left on queue.
```
## GitHistoryInternal_regression_anysourceref.cfg
```
Error: Invariant ASyncNeverMovesTheDestinationBackwards is violated.
8067 states generated, 2257 distinct states found, 1530 states left on queue.
```
## GitHistoryInternal_regression_recorddigest.cfg
```
Error: Invariant AFetchAloneNeverHoldsACopyBack is violated.
220 states generated, 87 distinct states found, 67 states left on queue.
```
## GitHistoryInternal_regression_recordrewind.cfg
```
Error: Invariant ASyncNeverMovesTheDestinationBackwards is violated.
21287 states generated, 5405 distinct states found, 3447 states left on queue.
```
## GitHistoryInternal_regression_syncremedy.cfg
```
Error: Invariant NoRemedySyncsTowardANewerDestination is violated.
238 states generated, 94 distinct states found, 73 states left on queue.
```
## GitHistoryInternal_probe_reach.cfg (-continue)
```
Invariant ProbeNeverBlockedOnUnpublishedWork is violated
Invariant ProbeNeverSyncedAtAll is violated
Invariant ProbeNeverTornDownWithSideWork is violated
Invariant ProbeRecordNeverLicenses is violated
```
