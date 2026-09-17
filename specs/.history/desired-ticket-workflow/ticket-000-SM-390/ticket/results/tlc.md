# SM-390 TLC evidence (2026-09-17T16:16Z, Version 2.19)

## GitHistoryInternal.cfg
```
Model checking completed. No error has been found.
105532 states generated, 13271 distinct states found, 0 states left on queue.
```
## GitHistoryInternal_regression_allrefs.cfg
```
Error: Invariant AnAncestorCopyWithNothingUnpublishedIsNeverBlocked is violated.
16 states generated, 8 distinct states found, 5 states left on queue.
```
## GitHistoryInternal_regression_anysourceref.cfg
```
Error: Invariant ASyncNeverMovesTheDestinationBackwards is violated.
1670 states generated, 467 distinct states found, 315 states left on queue.
```
## GitHistoryInternal_regression_syncremedy.cfg
```
Error: Invariant NoRemedySyncsTowardANewerDestination is violated.
126 states generated, 44 distinct states found, 32 states left on queue.
```
## GitHistoryInternal_probe_reach.cfg (-continue)
```
Invariant ProbeNeverBlockedOnUnpublishedWork is violated
Invariant ProbeNeverSyncedAtAll is violated
Invariant ProbeNeverTornDownWithSideWork is violated
```
