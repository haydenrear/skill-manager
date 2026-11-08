# Bug attribution, epic honest-pins (#411)

Rows are in `2026-11-08-epic-attribution.yaml`. Counts below are computed from that file (`SUMMARY` block at the end of this page).

## What the record says

**Reviews found what the graphs could not, and three of the second review's findings were siblings of the first's fixes.** F1 is R1 on the other path, F5 is a defect in R4's fix, F6 is the unfinished half of R6, and F7 is R2's shape in `home sync`. A fix scoped to the file a finding named left the sibling. That is the recurring family of this epic. Where the second round closed it for good, it did so by making the guard the only entry (`GitOps.setAside`, `stashAll` private).

**The anchors point at a thin model around pins.** Most in-epic defects are `UNMODELED`: shared CLI rows, `requested_by`, stash failure, annotated tags, gateway provisioning. `CliPinInternal` scopes multi-claimant rows out. The bins with repeat findings are `cli-shared-row`, `cli-lock-requested-by`, `gateway-binary-provisioning` and `sync-failed-stash`, and each is `MODELABLE` except that nothing has decided to model it. Their disposition stays `UNDECIDED` until someone chooses.

**Instrument cost was real.** The gateway cross-kill (DEF-HP11-001) and the graph lock I added (PROC-GRAPH-LOCK) both produced reds or waits against innocent code.

**Manual testing found no new defect in what the epic changed.** It ran after two review rounds and one full graph run, so it is a late check on a reduced population, and it was a quick pass by the epic agent rather than by a person.

## What this record does not know

- Roster bugs (`GH-*`) were filed before the epic. Their discovery channel is `UNDECIDED` because nobody recorded it.
- `origin` is `UNTRACED` for most pre-existing defects. No `git log -S` trace was run for this record.
- The changed tests were not checked for vacuity (BLIND row, last).
- The two reviews are one reviewer's reading each. No denominators exist; a concentration here may be a concentration of attention.

## Still open

Full text in `../deferred/`. The two to read first are `DEF-HP2-002` (a byte copy of a project home writes into the real home) and `RV2-MCP-ID-TRAVERSAL` (an unvalidated `..` in an `mcp_dependencies` name reaches a path; found by reading, not exercised).
