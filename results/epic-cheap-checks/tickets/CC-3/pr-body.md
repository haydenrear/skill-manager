Refs #401

Epic branch `epic/cheap-checks`, workflow `cheap-checks`, ticket CC-3. Depends on CC-0 and CC-1, both merged and reachable from `origin/epic/cheap-checks` at 4dfe2b2e; promotion predecessor CC-2 is merged there too. This epic runs no TLA+ workflow, so the close is the epic agent's and no spec ticket was opened or closed. Ticket commit: 650c0c1e.

## What changed

- `ChildHomeMaterializer.reconcile`: a pair that is clean at one commit on the same refs (both sides checked by `GitOps.cleanCommit`, refs by `gitHistoryDigest`) is UNCHANGED before any bytes are read. A pair that differs in any way takes the existing comparison, unchanged.
- `HomeCloseOut.inspect` stopped asking `isLocallyModified` of every unit, twice. It now asks only checkout-record units up front, and asks a NEW declared unit where the DEF-101 exemption reads the answer.
- `ChildHomeMaterializer`: record reading no longer interns field names (the record's keys are file paths), and `fingerprintOf` reuses one read buffer per walk.

## Where the time and memory went

A JFR profile of the baseline on the two-clone fixture, 2,769 samples:

| where | share of samples |
| --- | --- |
| `locallyModifiedUnits` (called twice; reads the whole home) | 58.8% |
| `reconcile` (fingerprints plus `gitTwins`, `disposal`) | 37.4% |
| anywhere else | under 4% |

All of it was inside the per-unit comparison. Memory: an allocation profile taken after the digests were gone showed about 2.1 GB of allocation in one run, 1.7 GB of it Jackson's symbol table copying itself while interning the 26,588 path keys of the 5.8 MB materialization record, which is read several times per run. Stopping the digests alone left RSS at about 1 GB (937 MB to 1.02 GB, measured); the reader change took it to 240 MB. The buffer reuse in `fingerprintOf` was not measured separately. I took no allocation profile of the baseline, so the baseline's 1.5 GB is not broken down.

**The largest cost that remains is outside the comparison and outside this ticket's keys**: `home clone` leaves every checkout's git index stat-stale (new inode and ctime, preserved mtime), so each `git status` re-hashes the tree, about 5.8 s per side on tla-spec-dev, and the gate never writes the refreshed index. Filed as DEF-CC3-001.

## Goal contribution

| goal | contribution | expected effect | measured local signal | classification | decided by |
| --- | --- | --- | --- | --- | --- |
| GOAL-checking-costs-what-changed | direct | clause (b): from the CC-0 baseline to under 5 s and under 300 MB RSS | `/usr/bin/time -l` on the fixture, 3 runs each (`results/epic-cheap-checks/tickets/CC-3/local-signal.txt`): baseline 46.1/46.9/46.8 s, 1.51/1.51/1.51 GB; after, as cloned 16.8/16.9/17.6 s, 240-245 MB; after, indexes refreshed by hand 4.3/4.2/4.2 s, 234-241 MB. Node: median 15.97 s, 232.5 MB | moved less than expected: RSS target met, time target not met on the fixture as cloned. It is met once the indexes are fresh | CC-6 |

## Close-out verdict

`home close-out --home <worktree>/.skill-manager --into <project home> --json` (read-only, run with the project home's CLI): `safe: true`, exit 0, no blockers, three units unchanged (`results/epic-cheap-checks/tickets/CC-3/close-out-verdict.json`). That CLI is the project home's pin, so this verdict came from the build before this change. No unit edited in this home, nothing published.

## Deferred findings

- DEF-CC3-001 (medium): `home clone` leaves indexes stat-stale, so clause (b) time stays above 5 s on the fixture.
- DEF-CC3-002 (low): a worktree home behind a moved project home still reads both trees; the git-only "destination is ahead" answer sits after the fingerprints.

Backlog: `results/epic-cheap-checks/deferred/CC-3.yaml`.

## Skill changes proposed

| unit | what I hit | proposed change |
| --- | --- | --- |
| (test-graph, CheapChecksKit) | A bare `JAVA_TOOL_OPTIONS` with a JFR option breaks the jbang launcher (`exec: [0.259s]...: not found`), and a run of `jfr print` defaults to a depth-5 stack that hides every caller. Cost a wrong profile. | Note it in the profiling guidance: use `jbang --java-options=...` and `jfr print --stack-depth 64`. Nothing blocked. |

## Validation

| entry | result |
| --- | --- |
| `jbang RunTests.java` | exit 0, `ALL PASSED`, including new `HomeCloseOutCleanCommitTest` (11 cases) |
| `uv run --with pytest pytest specs/program_model/tests -q` | `11 passed` |
| `cheap-checks` | exit 1: `graphs_selected=1 graphs_executed=1 graphs_passed=0 graphs_failed=1`; node verdicts below |
| `home-verdicts` | `graphs_selected=1 graphs_executed=1 graphs_passed=1 graphs_failed=0 graphs_not_run=0` |
| `home-integrity` | `graphs_selected=1 graphs_executed=1 graphs_passed=1 graphs_failed=0 graphs_not_run=0` |
| `ticket-lifecycle` | `graphs_selected=1 graphs_executed=1 graphs_passed=1 graphs_failed=0 graphs_not_run=0` |

`cheap-checks` nodes (run 20261109-044235): `cheap.close-out` red on `shape_close_out_under_5s` only (DEF-CC3-001); RSS, every control and the new shape passed. `cheap.foreign-installers` and `cheap.edit-detection` green. `cheap.stale-time` red on `shape_clean_checkout_reads_no_file_contents` (DEF-CC1-001, known). `cheap.foreign-projection` and `cheap.install-exclusion` red on their shapes (other clauses of this epic).

Each blocker kind in `HomeCloseOutCleanCommitTest`, on a same-commit pair the shortcut would otherwise take: uncommitted edit, untracked file, unpushed commit, a side-branch commit with HEAD and the tree unmoved, a stash, a destination edit (safe, but not read as equality), two different commits, a unit only the worktree holds, a destination materialized as a checkout (HELD_BACK, publish remedy), and a worktree checkout with unaccounted history (the pair is settled UNCHANGED by git and the blocker still fires). The node adds an edit in a small unit of the large home: caught by name in 16.57 s, against a 15.97 s clean run. Evidence: `results/epic-cheap-checks/tickets/CC-3/matrix.txt`, `graph-*.log`, `local-signal.txt`.

No close-out verdict is meant to change: a pair the shortcut settles is one `settledWithoutARecord` already called UNCHANGED through its "destination is ahead" arm, so `specs/program_model/GitHistoryInternal.tla` `Verdict` needs no change. There is one narrow exception by reasoning, untested: item 1 under *Where I'd look for bugs*.

## Review input

**Hot spots**

- `src/main/java/dev/skillmanager/bindings/ChildHomeMaterializer.java` `cleanAtOneCommitOnTheSameRefs`: the new route to UNCHANGED, shared by `home sync` and close-out through `reconcile`.
- `src/main/java/dev/skillmanager/store/HomeCloseOut.java`: `dirtyCheckouts` and `selfObtainable` now ask per unit instead of reading one shared enumeration.
- `test_graph/sources/cheap-checks/CloseOutTime.java`: `closeout_edited` moved from `sm` to `timed` to report its time; its assertion is unchanged. Two new assertions added.

**Decisions and overrides**

- The fast path is in `reconcile`, not in close-out alone, so the gate and the sync cannot disagree.
- `home clone` was left alone (DEF-CC3-001): it is outside the conflict keys, and the epic reference says not to widen scope to chase the metric. The shape's 5 s target therefore stays red on the fixture. No target or assertion was changed.
- Loosening `core.checkStat` or `core.trustctime` was rejected: it loses a same-size, same-second edit.
- A differing unit still costs the full comparison: `plugin_edit_seconds` in the node is 45.07 s, about the baseline. The assignment says a differing unit takes the existing comparison; the speedup there is only memory.
- The canonical plan's CC-3 entry matches the assignment (schedule revision, dependencies, promotion order, conflict keys, validation, goal). No mismatches.
- `readRecordFile` and `fingerprintOf` are in `ChildHomeMaterializer`, next to CC-1's `bindings/plain-view-walk` key. I read them as `store/home-digest-compare`, since they are what the comparison costs.

**Where I'd look for bugs (suspicion, ranked)**

1. `cleanCommit` lists untracked files individually where `gitSourceIsBehind` collapses them to a directory, so an untracked directory holding only re-derivable output on one side is now UNCHANGED where it used to be possible for the bytes route to differ. The cheapest check: plant an untracked `foo/__pycache__/x.pyc` on one side and compare the two routes' answers.
   This is the one place I can reason the verdict moves: from a possible CONFLICTED to UNCHANGED, on a directory holding nothing anybody wrote.
2. An empty directory on one side is invisible to git, so a pair that differs only in one is UNCHANGED here (it already was, by the "destination is ahead" arm, whenever the source is clean).
3. `dirtyCheckouts` skips units with no record, as it always did through the CHECKOUT filter; only `readRecord` order changed.

**Machinery friction**

Two pieces of profiling setup cost me a wrong first read (see Skill changes proposed). Nothing was changed in this worktree's Skill Manager home, and no unit was published.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
