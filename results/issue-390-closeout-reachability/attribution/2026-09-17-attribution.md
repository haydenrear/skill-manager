# Bug attribution: #390 and #370, with their follow-ups

Written 2026-09-17. The standard is spec-double-compiler's
`references/bug_attribution.md`.

- **Rows:** [`2026-09-17-attribution.yaml`](2026-09-17-attribution.yaml)
  holds every CATCH row, plus the REACH and BLIND records.
- **Where the rows belong:** there is no `specs/deferred_findings.yaml` on
  main, so they live here (§4).

## 1. The record in numbers

29 rows, counted from the YAML by script.

| kind | rows |
|---|---:|
| product | 16 |
| instrument | 8 |
| process | 5 |

| class | rows |
|---|---:|
| hand | 19 |
| automated | 6 |
| reading | 4 |

- **Anchoring:** 9 rows anchor to `GitHistoryInternal` actions (`CloseOut` 4,
  `SyncApply` 3, `Fetch` 1, `PullTrunk` 1). The other 20 fall in 6
  `UNMODELED` bins.
- **Hand catches dominate the product rows.** 12 of 16 were found by an
  operator or by manual testing, not by a standing instrument.
  - The skill-manager test fixtures never had a git remote, so "published" and
    "reachable" never differed from "on HEAD". Every close-out defect here
    lived in that gap (BLIND record 1).
- **Manual testing mattered.** The first merge (#391) shipped with the
  record-route rewind still live (DEF-390-05). It surfaced only when close-out
  ran on two real clones of the project home.
  - On those clones, the released 0.28.0's own suggested `home sync` moved
    `spec-double-compiler` from `b7a7d203` to `dd2d5176`.

## 2. What was fixed, where

| row | defect | fix |
|---|---|---|
| DEF-390-01, DEF-370 | containment over every ref | skill-manager #391 |
| DEF-390-02 | remedy synced toward a newer destination | #391 |
| DEF-390-03 | "fast-forward" by reachability rewound the destination | #391 |
| DEF-390-04 | a fetch read as local work (record route) | this branch, 339504a7 |
| DEF-390-05 | an untouched record licensed replacing an ahead checkout | this branch, bafaebfb |
| DEF-390-06 | "uncommitted changes" invented for an ahead destination | this branch, 407b69ff |
| DEF-390-07 | `sweep --epic` scoped inverted | skt #50 |
| DEF-390-08 | `sweep --yes` with no target | skt #51 |
| DEF-390-09 | `skt check` advised syncing a pinned unit | skt #51 |
| DEF-390-10 | clean published checkout named "edited" | skt #51 |
| DEF-390-11 | `ticket new --path` ignored WT_DIRTY_OK | skt #51 |
| DEF-390-12 | stale git-issue-workflow copy ignored WT_DIRTY_OK, silently | project home synced; skt #51 hint |
| SF-010 | tla-spec-dev dead on Python < 3.12 | tla-spec-dev #346 |
| SF-011, SF-012 | promotion clobbers the manifest; history-entry collision | filed: tla-spec-dev #345, #347 |

## 3. The model

`specs/program_model/GitHistoryInternal.tla` was added by spec tickets SM-390
and SM-390B. Its transcripts are in the two closed snapshots under
`specs/.history/desired-ticket-workflow/`.

| config | expected | result |
|---|---|---|
| `GitHistoryInternal.cfg` | no error | 115,146 distinct states, no error |
| `_regression_allrefs` | fail `AnAncestorCopyWithNothingUnpublishedIsNeverBlocked` | fails it |
| `_regression_anysourceref` | fail `ASyncNeverMovesTheDestinationBackwards` | fails it |
| `_regression_syncremedy` | fail `NoRemedySyncsTowardANewerDestination` | fails it |
| `_regression_recorddigest` | fail `AFetchAloneNeverHoldsACopyBack` | fails it |
| `_regression_recordrewind` | fail `ASyncNeverMovesTheDestinationBackwards` | fails it |
| `_probe_reach` (`-continue`) | 4 violations | 4 |

Building the model surfaced one defect of its own (INS-390-05): the first
SM-390B draft let a plain sync's record license destroying the destination's
own branch. `rec.about` records whose reconcile wrote the record.

## 4. Validation behind the rows

- **Unit tests:** `jbang RunTests.java`, all passed on the final code.
  - Each new test fails on the code before its fix. HomeSyncGitUnitTest's tag
    control fails if the `refs/heads` restriction is removed.
- **Test graphs:**
  - Full sweep `20260917-162140`: 27 of 29 passed.
    - `home-clone` fails identically on unmodified main (#344).
    - `onboarding` failed on a stale node expectation (INS-390-08). It was
      fixed and passed on `20260917-170709`.
  - The home graphs were rerun on the final code (`20260917-171108`): 9 of 9 passed.
- **Manual testing:** two `home clone` copies of the project home, driven
  with the raw build. The incident shape (a source on `dd2d5176` with an
  inherited published branch, a destination on `b7a7d203`) gave:

  | build | close-out verdict | `home sync` |
  |---|---|---|
  | 0.28.0 | `would-update` | rewinds the destination |
  | this branch | clean | leaves the destination at `b7a7d203` |

  The same shape with the branch **unpublished** gives `conflicted`, the
  remedy `unit publish`, and the reason "the destination is ahead".
- **Evals:** `specs/evals/results/wide-round-6/summary.md`.

## 5. What is still open

- **REACH:** `gitTwinsDifferOnlyInBookkeeping`, `reportedDifferences` and
  `External.tla` still compare full ref sets.
  - The first two write nothing, so they can only fail to settle.
  - Whether other writers (install, remote sync) can replace an ahead checkout
    is UNDECIDED.
- **tla-spec-dev:** #345 and #347 are open; #348 lists 7 tests already failing
  on its main.
- **Cost:** `w-sm-closeout-ahead-is-publish-not-sync` stays over budget.
  Documenting the verdict (skt #52) did not change that.
