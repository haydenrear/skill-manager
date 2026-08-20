# HIS-2 — goal contribution

## GOAL-sync-quiet (direct)

**Expected effect:** ≤10 lines and ≥99% character reduction over the committed
fixture. (The plan's baseline was restated from an estimate of 889 lines /
87,208 chars to the rendered 896 lines / 88,308 chars — see
`baseline/README.md`. The target is a ratio, so the restatement does not move
it.)

**Measured**, on `results/epic-home-integrity-sync/baseline/root-home-drift.json`
— the operator's real pending record at the epic base commit:

| rendering | lines | chars | ~tokens |
| --- | ---: | ---: | ---: |
| before (`renderDetailed`, the old `render`) | 896 | 88,308 | ~22,077 |
| after (`render`) | **8** | **438** | **~109** |

**99.50% reduction, 201× smaller.**

| clause | target | result |
| --- | --- | --- |
| 1 | ≤10 lines and ≥99% char reduction | **met** — 8 lines, 99.50% |
| 2 | full list unchanged behind `--json` / detail | **met** — `renderDetailed()` is byte-identical to the old `render()`; `home drift --detail` reaches it; the JSON surface is untouched |

The rollup over that record, in full:

```
modified  skill:deploy-helm  no-digest  (20 files)
modified  skill:git-epic-workflow  no-digest  (14 files)
modified  skill:git-issue  no-digest  (5 files)
modified  skill:git-issue-workflow  no-digest  (2 files)
added     skill:plugin-repository  no-digest  (25 files)
modified  plugin:skt  no-digest  (47 files)
modified  skill:spec-double-compiler  no-digest  (776 files)
7 units, 889 files changed — `home drift --detail` for the paths, `home drift --json` for the data
```

`no-digest` because this record was written before the field existed. A record
written from now on carries the unit's short hash.

## Two defects found while building this, both caught by the new tests

1. **A format-precedence bug in the total line.** `.formatted()` binds tighter
   than `+`, so `"a %d" + "b".formatted(n)` applies the call to the second
   literal only and the first keeps its raw `%d unit%s` text. The rollup test
   caught it on the assertion for the total line.
2. **A missing digest read as a deleted unit.** `shortDigest()` returned `gone`
   for any absent digest, so all seven units of the committed baseline — every
   one of which predates the field — rendered as though they had been deleted.
   Now `gone` only for `REMOVED`, `no-digest` otherwise, with its own regression.

## Two existing tests changed, deliberately

Both encoded the old contract — that a changed **path** is named on the default
surface.

- `a launch is refused while the change is unread, and nothing runs` — now
  asserts the unit is named and the path is **not**, plus the file count.
- `` `home drift` shows the pending change and exits non-zero `` — now asserts
  the unit is named, the path is not, and that `--detail` still names it. That
  second half is clause 2 checked through the CLI rather than through the record.

## Validation

| lane | command | result |
| --- | --- | --- |
| repository_unit | `jbang RunTests.java` | ALL PASSED |
| spec_graph | `python skills/test_graph/scripts/run.py home-integrity` | BUILD SUCCESSFUL (3m42s) |
| tlc | N/A — states no new invariant; HIS-5 carries the model work | — |
