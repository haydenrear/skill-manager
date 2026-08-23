# HIS-13 probe artifacts

Two harnesses, because they redden two different things.

| file | what it is |
| --- | --- |
| `probes.py` | the UNIT vacuity harness. `python3 probes.py [V1 V2 …]`. Copies the production file aside, mutates, runs `jbang RunHis13.java`, restores by copying back. Asserts each mutation's pattern matched **exactly once** before applying it (mechanism C). |
| `V<n>.out` | one per probe: the mutation, the branch it moves, and every `[PASS]`/`[FAIL]` line observed. Truncated to 240 characters per line — see `V7.out`'s header for why. |
| `V6-VACUOUS.out` | **kept deliberately.** The first V6 reddened nothing. Mechanism D, in this ticket's own harness, one ticket after the ledger named it. |
| `graph-probes.sh` | the GRAPH vacuity harness. Same discipline, but it runs `home-integrity` and reads the node's envelope, because the node asserts one thing the unit suite cannot: that detection and repair are separate PROCESSES. |
| `G1.out`, `G2.out` | the node's assertions under each mutation, plus its metrics. They redden **disjoint** sets. |
| `*.graph.log` | the full graph run output for each graph probe. |

## Restoring after a mutation

`cp` from the saved copy. **Never `git checkout --`, `git restore`, `git stash`
or `git clean`** — DEF-035, which has now bitten four agents in this epic, two
of them after reading the entry about it. Both harnesses restore with `cp` and
delete the backup in a `finally`.

## The one that matters most

`V7.out`. It plants HIS-14's two-axis defect in the repairer and shows the
blast radius: 60 of the operator's REAL global agent links read and reported by
a command pointed at a temp directory. Nothing was written — verified
immediately, and the header records how — but the reason it was not written is
incidental, and `WriteConfinement` was not it. That is filed as **DEF-071**.
