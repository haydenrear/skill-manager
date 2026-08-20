# HIS-1 — goal contribution

## GOAL-no-spurious-holdback (direct)

**Expected effect:** 16 of 18 held back → 0, with the divergent-fixture clause still refusing.

**Local signal:** `jbang RunTests.java` + `python skills/test_graph/scripts/run.py home-integrity`

| clause | result |
| --- | --- |
| 1 — a pristine unit whose record names a dead store is not held back | **met** — new test `a pristine unit whose record names a store that is gone is not held back` |
| 2 — a unit that genuinely differs is still held back | **met** — new test `a unit that genuinely differs is still held back, foreign record or not`; the agent's edit survives |

**The tests are non-vacuous, and this was verified rather than assumed.** With
the `copyUnit` call disabled, clause 1 fails `expected <0> but was <1>`; with it
enabled it passes. Clause 2 passes either way, which is correct — it is a guard
against the fix going too far, not a regression pin.

**A first version of this test passed without the fix.** It left both trees
pristine, and `describesSource` (`ChildHomeMaterializer.java:1408`) rescued the
unit: the record's `entryDigests` still equalled the live source's, so the named
path was never consulted. The operator's homes were not in that state — their
parent stores had moved on too. The fixture now moves BOTH homes to the same new
content and leaves the record describing the older bytes, which is what a ticket
worktree publishing its work upstream actually leaves behind.

## GOAL-sync-quiet (enabling)

**Expected effect:** none on its own. Removes 16 five-line paragraphs, which is
most of the non-diff noise HIS-2 then bounds.

**Measured:** not separately measured here. HIS-2 owns the number.

## Validation

| lane | command | result |
| --- | --- | --- |
| repository_unit | `jbang RunTests.java` | ALL PASSED |
| spec_graph | `python skills/test_graph/scripts/run.py home-integrity` | BUILD SUCCESSFUL |
| tlc | N/A — this ticket states no new invariant; HIS-5 carries the model work | — |
