# Bug attribution — epic kickoff baseline, 2026-09-13

Each defect is attributed to the component that PRODUCED it. My own instrument
errors are counted too.

## Product and plugin

| component | count | defects |
| --- | ---: | --- |
| **Shim writer: re-anchoring misses a line** | 1 | DEF-OHV-001. A skill-script shim's header says it resolves its own home, and its exec line names the root absolutely. `home repair` reports 0 of 78; the copied-home goal regressed from MET to NOT MET |
| **Verdicts that disagree about one fact** | 1 | DEF-OHV-002. `home verify` calls three dangling agent links resolved, and `artifacts list` marks the same three `disagrees` |
| **Ledger: retirement leaves rows** | 1 | DEF-OHV-003. 14 ledger-only rows for retired units. The #292 shape, reached through retirement instead of uninstall |
| **Record fields that are never refreshed** | 1 | DEF-OHV-004. `installed/*.json` `version` is stale while the hash is current, on 5 root units and 1 project unit |
| **Agent registration: another home's marketplace** | 1 | DEF-OHV-005. Root Claude settings enable plugins from commit-diff-context-parent's marketplace |
| **skt sweep gate** (adjacent) | 1 | DEF-OHV-006. Containment is skipped by default, and squash merges are unrecognised when it is checked |
| **Test hygiene** (adjacent) | 1 | DEF-OHV-008. A fixture gateway was left running for 6h46m |

## Instruments

| instrument | count | defects |
| --- | ---: | --- |
| **zsh does not word-split `$var` (mine)** | 2 | (1) a `for c in "home drift" …; skill-manager $c --help` loop passed one argument and printed the root help, which read like a CLI defect. (2) `set -- $mc` left `$1` holding "oid base", so `git merge-base --is-ancestor` failed and all 13 PRs read `in-main=NO`. That would have looked like unmerged work and blocked the sweep on a false premise. Both were caught by re-running under `bash -c`. It is the same trap the 0.27 migration attribution records for `$SKT` |
| **Goal ledger CLI** | 1 | DEF-OHV-007. `measure_goals.py --help` runs the whole ledger |
| **zsh glob NOMATCH (mine)** | 1 | `grep --include=*.java` unquoted: zsh tried to glob it, found no match, and aborted each grep. It printed "no matches found", which read like "the symbol does not exist" |
| **macOS `/bin/bash` is 3.2 (mine)** | 1 | `bash -c` with `declare -A` failed: 3.2 has no associative arrays. Every issue number came out empty, so the first pass at mirroring blocked-by edges ran `gh issue edit` with no issue and wrote nothing. Redone from Python and verified 9 of 9 against the plan |
| **`gh --json blockedBy` shape (mine)** | 1 | The field is `{nodes, totalCount}`, not a list. The state reader printed ERR for every issue, which looked like failed edits; the edits had succeeded |
| **A goal harness with a `<placeholder>` (mine)** | 1 | GOAL-ci-green-fresh-runner's harness said `gh run download <id>`. `validate_assignment.py` rejected 4 rendered bodies as unrendered templates before any reached GitHub. Rewritten as a runnable command |
| **stdout+stderr parsed as one (mine)** | 1 | `measure_fleet.py` joined the streams before `json.loads`, so 4 homes' `home repair` finding counts are null |
| **A read-only verb that looked like it wrote** | 0 | meta-orchestrator's `home repair --json` printed `AGENT_SYNC_FAILED … claude plugin install` on stderr, which read like a live sync. It was the recorded-errors banner. Checked on disk: no home file modified, no audit entry. Not a defect, but the banner's wording can't be told apart from a sync that just ran |

## Worktree sweep: merge evidence

The previous epic's 14 worktrees, checked before any removal:

| branch | PR | base | merge commit in base | reached main |
| --- | --- | --- | --- | --- |
| feature/hbr-2 | #286 | epic/home-boundary-resolution | yes | yes (commit) |
| feature/CI-OTEL | #322 | main | yes | yes |
| feature/FIX-311 | #314 | main | yes | yes |
| feature/OUN-10 | #323 | main | yes | yes |
| feature/OUN-12 | #324 | main | yes | yes |
| feature/OUN-9 | #318 | main | yes | yes |
| feature/OUN-0, 1, 2, 2B, 3, 4 | #308 #312 #319 #320 #313 #321 | epic/one-unit-one-name | yes | via #335 squash (`8eededc3`); epic tip vs merge tree diff = 0 files |
| fix/OUN-5-sync-path | #326 | epic/one-unit-one-name | yes | via #335 |
| feature/OUN-6 | none | head is an ancestor of epic/one-unit-one-name | — | via #335 |

- **Worktree state:** all 14 are clean with nothing unpushed. `wt-oun-6` has no upstream, and its head is in the epic branch.
- **Stashes:** the 10 in the list are repo-global, predate this epic, and none is on a ticket branch.
- **Agent registrations:** none name a worktree path.
- **The sweep was not run.** `skt ticket sweep --yes` was refused by the session's permission classifier; the decision goes back to the owner.
