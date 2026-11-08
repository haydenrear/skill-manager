# HP-3 (#403) evidence: home sync arms the drift gate

Local signal (`run.py honest-pins --continue`, sweep 20261107-181255, run 20261107-181516,
via /Volumes/dev/.honest-pins/graph-run.sh at feature/HP-3 on epic tip 433f1090):

| node | assertion | baseline (HP-0) | HP-3 |
| --- | --- | --- | --- |
| honest.record-a | control_noop_sync_records_nothing | green | green |
| honest.record-a | shape_changing_sync_leaves_pending_drift | RED | green |

Expected effect "record shape (a): red -> green, no-op arm stays green": met.
Every other honest-pins node is red on its own shape assertion, as at baseline
(owned by HP-1/2/4/5/6/10); their control arms stay green. See
`honest-pins-summary.json`, `record-a-envelope.json`, `sweep-honest-pins.json`.

Real-home probe (`real-home-probe.txt`): two `home clone`s of this repository's
project home (read-only source), one unit edited in A, `home sync A -> B`:
`1 updated` and B's `home drift` reports pending, naming the unit; the second sync is
a no-op and the record stays pending (neither cleared nor duplicated). A real no-op
`home sync --from <project home> --to <this worktree's home>`: 3 unchanged, no
pending record, installed/ byte-identical to its backup.

Unit tests: `jbang RunTests.java` ALL PASSED (two new HomeSyncTest cases).
Other graphs: see `others.md`.
