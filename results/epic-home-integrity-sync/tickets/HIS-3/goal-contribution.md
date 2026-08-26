# HIS-3 — goal contribution

## GOAL-gate-settles (direct)

**Expected effect:** full report every pass → full once, one line thereafter;
exit-8-on-new-change unchanged.

| clause | target | result |
| --- | --- | --- |
| 1 | 2nd+ surfacing of one unacknowledged gate is a single line | **met** — the reminder names the unit count and the ack command; the report is not re-printed |
| 2 | a genuinely new change still gates, and the safety at `DriftGate.java:39-49` still holds | **met** — a new change resets the count and re-opens the full report; the gate still exits `8` on every surfacing, collapsed or not |

**Both halves were checked for vacuity separately**, because they are two
different mechanisms and one can be right while the other is decoration:

- **Reset disabled** (`surfaced` always carried forward) → *"a genuinely new
  change resets the count"* fails. The reset is load-bearing.
- **Collapse disabled** (`if (true)` at the call site) → *"the second surfacing
  is the reminder"* fails. The collapse is load-bearing.

## GOAL-sync-quiet (guard)

**Expected effect:** must not reintroduce per-file output on any surface.

**Result: held.** The collapse only ever shortens. `--detail` is the one path
that still renders every path, and it is asserted to keep doing so however many
times the gate has been shown — the collapse is about what an agent gets when it
did *not* ask.

## The design decision, and why it is (a)

Recorded on #213 before any code, as the ticket required.

There was **no field** distinguishing a gate's first surfacing from its
re-discovery. Route **(a) a persisted marker** was chosen over **(b) a call-site
distinction** because the loop being fixed is `exec` refuses → operator runs
`home drift` → `exec` again: **three separate JVMs**. Nothing held in memory can
answer "has anybody seen this yet", so (b) would have collapsed nothing across
exactly the sequence the ticket exists to fix.

## Three decisions inside the implementation worth reviewing

1. **The reset is defined against the union, not the record's existence.**
   `record()` merges rather than replaces, so "a new change" and "the same gate
   again" are the same record differing only in content. Keyed on "is there a
   record" nothing would ever collapse; keyed on nothing at all a real new
   change would open as a one-line reminder.
2. **`acknowledge()` does not carry the count into its receipt.** A marker
   surviving an ack into a later re-pend would make the *first* sight of a
   change the agent had never seen a one-line reminder.
3. **An unreadable record surfaces at 0.** Collapsing the one report that says
   the gate itself is broken would be the worst possible place to save lines.

## Schema

`SCHEMA_VERSION` 1 → 2, additive, no migration. A v1 record has no field,
Jackson yields `0`, `0` means "not yet surfaced", and an old home behaves exactly
as it did before — full report once, then collapse. Asserted rather than assumed
by a test that strips the field and downgrades the version in a real record.

## Validation

| lane | command | result |
| --- | --- | --- |
| repository_unit | `jbang RunTests.java` | ALL PASSED |
| spec_graph | `python skills/test_graph/scripts/run.py home-integrity` | BUILD SUCCESSFUL (3m48s) |
| tlc | N/A — HIS-5 carries the model work; `AckIsNotStaleOnArrival` is the invariant this must not disturb | — |
