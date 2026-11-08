# Bug attribution, epic honest-pins (#411)

Rows are in `2026-11-08-epic-attribution.yaml`. Counts below are computed from that file (`SUMMARY` block at the end of this page).

## What the record says

**Reviews found what the graphs could not, and four of the second review's findings were siblings of the first's fixes.** F1 is R1 on the other path, F5 is a defect in R4's fix, F6 is the unfinished half of R6, and F7 is R2's shape in `home sync`. A fix scoped to the file a finding named left the sibling. That is the recurring family of this epic. Where the second round closed it for good, it did so by making the guard the only entry (`GitOps.setAside`, `stashAll` private).

**The anchors point at a thin model around pins.** Most in-epic defects are `UNMODELED`: shared CLI rows, `requested_by`, stash failure, annotated tags, gateway provisioning. `CliPinInternal` scopes multi-claimant rows out. The bins with repeat findings are `cli-shared-row`, `cli-lock-requested-by`, `gateway-binary-provisioning` and `sync-failed-stash`. Nobody has classified them, so their disposition is `UNDECIDED`.

**Instrument cost was real.** The gateway cross-kill (DEF-HP11-001) and the graph lock I added (PROC-GRAPH-LOCK) both produced reds or waits against innocent code.

**Manual testing found no new defect in what the epic changed.** It ran after two review rounds and one full graph run, so it is a late check on a reduced population, and it was a quick pass by the epic agent rather than by a person.

## What this record does not know

- Roster bugs (`GH-*`) were filed before the epic. Their discovery channel is `UNDECIDED` because nobody recorded it.
- `origin` is `UNTRACED` for most pre-existing defects. No `git log -S` trace was run for this record.
- The changed tests were not checked for vacuity (BLIND row, last).
- The two reviews are one reviewer's reading each. No denominators exist; a concentration here may be a concentration of attention.

## Still open

Full text in `../deferred/`. The two to read first are `DEF-HP2-002` (a byte copy of a project home writes into the real home) and `RV2-MCP-ID-TRAVERSAL` (an unvalidated `..` in an `mcp_dependencies` name reaches a path; found by reading, not exercised).

## SUMMARY

53 rows. By kind: {'product': 41, 'instrument': 8, 'record': 1, 'process': 3}. By class: {'UNDECIDED': 10, 'automated': 8, 'reading': 21, 'hand': 14}. Anchored to a model action: 10; UNMODELED: 43. Largest bins: [('UNMODELED/gateway-binary-provisioning', 6), ('UNMODELED/sync-git-pin', 5), ('UNMODELED/test-graph-harness', 5), ('UNMODELED/console-rendering', 4), ('UNMODELED/unit-publish-content', 3), ('UNMODELED/epic-agent-process', 3)]. Recurrences of an earlier finding: ['F1', 'F5', 'F6', 'F7']. REACH rows: 3; BLIND rows: 5; PRICE rows: 0 (none was declared before this work, so none can be written now).
