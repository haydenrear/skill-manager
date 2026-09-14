# OHV-3 evidence — the census names nothing the disk does not hold, and a prune stays pruned

Ticket: #340 (carrying #292). Branch `feature/OHV-3`, rebased onto
`origin/epic/one-home-one-verdict` at `8bd883f3` (OHV-1 merged; OHV-5, the
promotion predecessor, not yet merged when this was written).

Each change landed as its own commit and was verified against BOTH graphs
before the next one was written (#292's failed attempt landed three together
and could not be bisected). Local graph report ids are under
`test_graph/build/validation-reports/` in the worktree.

| step | commit (rebased) | unit tests | plugin-smoke | artifact-dag |
| --- | --- | --- | --- | --- |
| (a) re-record fixpoint | `52c6db6c` | ArtifactPruneTest 22/22 (2 new, red first) | **passed** `20260913-233504` | red, only `uninstall.prunes.the.subgraph`: 5 surviving ids (was 6 — the tree row no longer comes back), byte-comparable false |
| (b) teardown reaps rows, only where absence is proven | `7474f5d5` | ArtifactPruneTest 28/28 (6 new) | **passed** `20260913-235305`, incl. `home.fixpoint.law` | `the_census_names_nothing_the_removed_unit_owned` **true**, `survivingIds=[]`; red ONLY on `the_home_is_byte_comparable_to_before_the_install` (`onlyAfter=[F artifacts.lock.toml]`) `20260914-000249` |
| (c) record version from checkout | `8f202a9f` | RecordVersionRefreshTest 3/3 (new); sync suites green | **passed** `20260914-000714` | unchanged from (b) `20260914-001142` |
| (d) uninstall removes the ledger it created | `48a0ab45` | ArtifactPruneTest 31/31, UninstallCliCleanupTest 7/7 (5 new, red first); RunTests ALL PASSED | **could not run locally**, see note; CI run 34798086104 is the evidence | **fully green** locally, 10/10 nodes `20260914-013614` |

**Local plugin-smoke on (d) could not run.** After the host disk filled, Docker
became unresponsive: `postgres.up` timed out after 90s (report `20260914-015014`,
no node touching uninstall or the ledger ran), and `docker info` returned no
server for more than 10 minutes. Docker was deliberately not restarted, because
that would kill other agents' runs. On the epic agent's direction, the Linux
plugin-smoke and artifact-dag jobs of CI run 34798086104 are (d)'s graph evidence.

`jbang RunTests.java`: ALL PASSED at (c) before the rebase, and ALL PASSED again
on the rebased tree (`8f202a9f`). `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.

## (a) The re-record fixpoint

`ArtifactPrune.apply` re-recorded the ledger as
`ArtifactLedger.of(ArtifactIndex.of(store).artifacts())`. The index merges the
disk WITH the ledger, so every pruned row returned as `Origin.LEDGER`.

Change: `ArtifactPrune.rerecord(index, pruned)` leaves an id out only when the
pass pruned it AND it is proven gone (`provenGone`: origin `LEDGER`, and every
in-home output `MISSING`). A `DANGLING` link is still on disk and keeps its
row; so does an unprobed output.

Tests: `a prune stays pruned: the re-record does not restore what it dropped`
(red against the old rebuild: `cli-shim:pip/alpha-pkg` restored), `a pruned id
the home can still see keeps its row` (present dir, dangling link, missing —
only the missing one goes).

## (b) Uninstall and retirement reap their ledger rows

Retirement runs `RemoveUseCase.buildProgram(..., pruneCliOrphans=true)`, the same
program as `uninstall`, so both are covered by one change.

* **Evidence captured before removal.** `ArtifactPrune.outputsOf(store, unit)` —
  every output path (absolute; external ones included) of every artifact the
  home DERIVES for the unit — is computed at program build, while the unit is
  still installed, and carried in `SkillEffect.PruneOrphanArtifacts`. The ledger
  never records an external path, so this is the only proof that a projection's
  agent-side link is gone.
* **Row with no outputs** (projections): row-only PRUNE only if every captured
  path is absent now (`Files.exists(NOFOLLOW)` false). Otherwise REFUSED and
  kept: "this pass cannot prove its outputs are gone". No captured evidence (a
  whole-home prune over PAST removals) → REFUSED.
* **Row whose recorded outputs are all absent**: row-only PRUNE for kinds whose
  outputs are always in-home (`cli-shim`, `provisioned-tree`,
  `marketplace-entry`, `harness-instance`) or when the capture proves it;
  otherwise REFUSED (was a silent CLAIMED, which left the row forever).
* **`unit-store` / `unit-digest`**: never a path-deleting verdict. A LEDGER-only
  row whose owner is not installed is decided behind every gate and claim: PRUNE
  (row only) when none of its bytes exist (store path from the row plus capture)
  or `home.digest.json` is absent / readable and no longer names it; otherwise
  REFUSED by name. A row the home still derives stays out of the candidates.

The dangling-symlink trap from #292 is a test:
`a projection whose link is still on disk keeps its row` (link dangles after
the unit is removed → REFUSED, row and link both still there). Also:
`a projection whose captured link is gone is reaped, row only`,
`a projection row with no captured evidence is kept, by name`,
`a unit-store row goes, row only, once its owner and its bytes are gone`,
`a unit-store whose bytes are still on disk is never reaped` (DEF-121 shape),
`a unit-digest row stays while home.digest.json still names the unit`.
Changed: `a whole-home prune finds what past removals left behind` now accepts a
row-only step when it says "nothing on disk is touched".

### The residual needs an owner decision (not narrowed)

artifact-dag stays red on exactly one assertion,
`the_home_is_byte_comparable_to_before_the_install`: the uninstall CREATES
`artifacts.lock.toml` (its `RecordArtifactLedger` effect) in a home that had
none. The assertion was not touched. #292's three options:

1. **uninstall removes a ledger it created** when the home had none before —
   restores the pair, but reintroduces the no-ledger state in which prune refuses;
2. **install records the ledger** — symmetric, but costs `artifacts.enumerated`'s
   premise (the census answers without a recorded ledger);
3. **accept it** and narrow the assertion, on the grounds that a home which has
   learned to describe itself is not damaged.

### Owner decision (2026-09-13): option 1 → step (d)

The epic agent relayed the owner's decision: **uninstall removes a ledger it
created**, and the assertion stays exactly as strict as it is.

* **How "the home had no ledger" is decided.** `RemoveUseCase.buildProgram`
  checks whether `artifacts.lock.toml` is present when the removal's program is
  built. That is before its own `RecordArtifactLedger` effect can write one, and
  the product can observe it at uninstall time. No timestamp is involved. The
  answer rides in `SkillEffect.PruneOrphanArtifacts.discardLedgerIfCreated`.
  Retirement builds the same program, so it follows the same rule.
* **What is removed.** After the prune, `ArtifactPrune.discardCreatedLedger`
  deletes the file only if every artifact in the index is still derived from the
  home, i.e. no ledger-only row remains. A ledger holding a row the home can no
  longer derive is kept and named. That row is the only record of something, and
  deleting the file would re-open #292's dangling-link trap by another route.
* **What stays true.** A home that had a ledger keeps it. A no-ledger home is
  exactly the state `artifacts.enumerated` asserts the census answers in, and a
  prune there refuses without writing a ledger.

Tests, written first (the stub was red on exactly the two "removes it" cases):
`an uninstall in a home with no ledger leaves no ledger behind` and
`an uninstall in a home that HAD a ledger keeps it` (`UninstallCliCleanupTest`,
end to end through `Executor`); `a created ledger that only restates the home is
discarded`, `a created ledger holding a row the home cannot derive is kept`, and
`a prune in a no-ledger home refuses cleanly, writes no ledger, and the census
answers from disk` (`ArtifactPruneTest`).

## (c) Installed record version

`RecordVersionRefresh.refreshed(store, record)` restates `version` from the
unit's manifest (`SkillStore.loadUnit`) only when the record's `gitHash` is
the checkout's HEAD; anything else changes nothing. Applied in
`SyncGitHandler.refreshSourceRecord`, on the up-to-date-by-hash path (where
DEF-OHV-004's stale versions survive: nothing else writes the record there), and
after `SyncFromLocalDirHandler` copies a checkout in.

### Real-home proof: unit test only

**(c) is proven by `RecordVersionRefreshTest` only.** The one real-home attempt was
refused, and the epic agent directed that no further real-home sync be run.
The real-home proof is carried to the evaluation ticket (OHV-8).

The attempt, on tla-spec-dev-2, with a fresh backup of `installed/` taken first:
`SKILL_MANAGER_HOME=/Users/hayde/IdeaProjects/tla-spec-dev-2/.skill-manager
/Users/hayde/IdeaProjects/wt-ohv-3/skill-manager sync acp-cdc-ai-python --yes`.
That is the home's only stale record: 0.1.0 recorded, 0.2.0 in the manifest,
with the hash equal to the checkout's HEAD.

* **Exit 7, "extra local changes — `skill-manager sync <name> --merge`".** The
  checkout carries an uncommitted `scripts/sources/uv.lock` edit, so the sync
  refuses before any record write. That is correct behaviour. The record did not
  change (0.1.0, same hash).
* Inside `installed/`, 18 `*.projections.json` files were rewritten, and each
  differed only in `createdAt`. All 18 were restored from the backup, and
  `installed/` is byte-identical to its pre-sync hash list. `units.lock.toml` did
  not change.
* **Two side effects outside `installed/`**, which a file backup cannot undo:
  1. the gateway re-registered the `runpod` MCP server ("runpod spec changed —
     re-registering");
  2. the agent pass re-linked 18 units into claude, codex and gemini.

Evidence: `real-homes/sync-acp.txt`, `real-homes/sync-acp-record-before.json`,
`real-homes/sync-acp-record-after.json`.

## Real homes

Driven by THIS worktree's raw build with `SKILL_MANAGER_HOME` pinned
(`real-homes/census.py`; R1/R3 of `scripts/release_regression.py`, with outputs
stat'd independently of the census's own presence). Backups of `installed/`,
`artifacts.lock.toml` and `cli-lock.toml` — plus, for tla-spec-dev-2, the six
paths the prune planned to delete — are in the session scratchpad under
`ohv-3-backups/`. `~/.skill-manager` and commit-diff-context-parent were not
touched.

| home | declared | outputs missing | ledger-only rows | record versions stale | prune |
| --- | --- | --- | --- | --- | --- |
| project home, before | 54 | 7 | 0 | 1 (skt 0.8.1 vs 0.8.2) | dry-run: 0 planned, 3 refused |
| project home, after `artifacts prune` | 54 | 7 | 0 | 1 | applied: 0 pruned, 3 refused |
| tla-spec-dev-2, before | 180 | 32 | 6 | 1 (acp-cdc-ai-python 0.1.0 vs 0.2.0) | dry-run: 12 planned, 6 refused |
| tla-spec-dev-2, after `artifacts prune` | 168 | 26 | 6 | 1 | applied: 12 pruned, 6 refused |
| tla-spec-dev-2, second dry-run | — | — | — | — | **0 planned** — the prune stays pruned |

Project home matches the kickoff (54 declared, 7 missing, 0 ledger-only).
Its prune is a no-op: the 7 missing outputs are `cli-shim:brew/*` and
`pip/pytest` shims owned by INSTALLED units whose tools come from PATH (no shim
was ever written — DEF-OHV-130); the 3 refusals are skill-manager projections
still derived from a live `installed/skill-manager.projections.json` (the
orphaned projection record OHV-2 owns).

tla-spec-dev-2's 12 prunes: row-only `unit-store:skill-dev-skill`,
`unit-store:skill-manager` (new in (b)), `cli-shim:npm/{gemini-cli,google,google-gemini-cli}`
(lock rows), `provisioned-tree:cache/uv-tools` (all outputs absent, in-home kind);
path-deleting `cli-shim:skill-script/skill-dev` (`bin/cli/skill-dev`) and five
`harness-instance:learning-app-exp-*` (one file each, dated 2026-05-14, no `.git`),
all backed up first. The 6 kept rows are projections of the retired
skill-manager / skill-dev-skill with no recorded output — refused because nothing
proves their links absent (DEF-OHV-131). Checked by hand: no link at
`<project>/.{claude,codex,gemini}/skills/<unit>` or `~/.{claude,codex,gemini}/skills/<unit>`.

**Units intact on both homes**: `installed/` byte-identical (sha256 of every file
before vs after), unit directories (`skills/ plugins/ docs/ harnesses/` depth 1)
unchanged; project home's `artifacts.lock.toml` byte-identical.

### Against expected_effect

* "prune re-record 59->59 becomes a prune that stays pruned": **met** on the
  fixture (unit test) and on a real home (tla-spec-dev-2: 12 pruned, next dry-run 0).
* "root 15+14 and project 7+0 -> 0+0 after a prune": **not met by a prune**.
  Project stays 7+0: all 7 are DEF-OHV-130 (census models a PATH-satisfied shim as
  a missing home output), which no prune verdict should reach. Root was not
  touched per instructions; its 14 ledger-only rows are the DEF-OHV-131 shape
  (tla-spec-dev-2 reproduces it with 6). New removals no longer produce either.
* "versions 5/1 -> 0/0 after a sync": proven by `RecordVersionRefreshTest` only; the one real-home
  attempt was refused (exit 7, extra local changes), and the proof is carried to OHV-8.
* GOAL-ci-green-fresh-runner "artifact-dag red -> green, plugin-smoke stays
  green": after (d), artifact-dag is fully green locally (report `20260914-013614`,
  10/10 nodes, including `home.fixpoint.law`); plugin-smoke: see the (d) row.

## CI

`gh workflow run ci.yml --ref feature/OHV-3 -f graph_set=full` → run
34792035515 at `8f202a9f`. `graphs-executed.json`: selected 25, executed 25,
passed 19, failed 6. These are the same six graphs as main's baseline
(artifact-dag, checkout-home, home-clone, home-tripwire, onboarding,
ticket-lifecycle). No new failure. plugin-smoke, the unit tests and the
gateway pytest job pass. The artifact-dag job on Linux reports
`the_census_names_nothing_the_removed_unit_owned=true`, `survivingIds=[]`, with
only `the_home_is_byte_comparable_to_before_the_install=false` left (the owner
decision).

## Close-out

`skill-manager home close-out --home /Users/hayde/IdeaProjects/wt-ohv-3/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager`
→ `✓ … holds nothing that removing it would destroy` (rc 0). No unit changed in
the worktree home.

## Deferred

`deferred.yaml`: DEF-OHV-130, DEF-OHV-131 (2 of 5).
