# OHV-5 — the full graph set runs green on a fresh CI runner

Ticket for #346 in epic `one-home-one-verdict`. Branch `feature/OHV-5`, from
`origin/epic/one-home-one-verdict` at `de423138`.

Baseline on `main`, CI run 34786751392 (graph_set=full): 25 selected, 25
executed, 19 passed, 6 failed: artifact-dag, checkout-home, home-clone,
home-tripwire, onboarding, ticket-lifecycle. artifact-dag belongs to OHV-3 and
is not touched here.

Proof files are under `proof/`.

## #343 — `home verify` misses a `/var` reference when given `/private/var`

**Cause.** `HomeCloner.rootSpellings` returned `{given, real}`. Handed the
resolved path `/private/var/...`, both entries are the same, so the `/var/...`
spelling was never scanned. A literal scan for `/private/var/x` cannot find it
inside `/var/x/...`. `ShimHomeContract.rootSpellings` had the same shape.

**Fix.** New `src/main/java/dev/skillmanager/store/PathSpellings.java` returns
the given spelling, the real one, and the alias of each in both directions.
Aliases come from the symlinks directly under `/` (`/var -> private/var`,
`/tmp -> private/tmp` on macOS), read from the disk rather than typed in; on
Linux there are none. HomeCloner and ShimHomeContract both use it. The shim
rewrite in ShimHomeContract now replaces the longest spelling first, so
`/private/var/x` is never turned into `/private${SHIM_HOME}`.

**Proof.** Two new tests in `HomeVerifyPathSpellingTest`:
- a table test of the derivation, which runs the same on Linux and macOS;
- an end-to-end test: a home at `/private/var/...` with a shim naming `/var/...`.
  On Linux it prints a note and returns, since the alias cannot occur there.

With the old `rootSpellings` restored, the end-to-end test fails with exactly
the #343 symptom; with the fix, all six tests pass
(`proof/343-spelling-test-before-fix.txt`, `proof/343-spelling-test-after-fix.txt`).
Local home-clone on macOS: the law now sees the planted shim that was
invisible before (see #344).

This is the GOAL-one-verdict contribution: a `/var`-written reference in a home
given as `/private/var` is reported by `home verify`.

## #344 — the fixpoint law refuses damage the fixture plants on purpose

**Cause.** `HomeCloneFixtureBuilt` plants a dangling shim on purpose.
`home.fixpoint.law` runs `home verify`, gets exit 1, runs the printed remedy
(which cannot create an interpreter nobody declared), and fails. The law had no
way to be told the damage was deliberate. On CI it failed three homes: the
fixture, its clone, and the credential copy.

**Fix.** New `test_graph/sources/lib/IntentionalDamage.java`. A node publishes
under `intentionallyDamagedHomes` one line per home: the home, the exact
home-relative findings it planted, and a reason. The law:
- matches the home by its resolved path, never as a prefix of a directory;
- skips it only when `home verify` reports every declared finding and no other
  error line;
- judges any other finding as usual, and holds the re-verify to the same rule;
- FAILS if a declared finding is not reported, since then the declaration is
  stale or verify has gone blind (the #343 shape);
- counts skipped homes as `homesDamagedOnPurpose` and names each, with its
  findings and reason, in its log.

Declared by: `HomeCloneFixtureBuilt` (`venvs/hc-venv/bin/hc`),
`HomeClonedIntoProject` and `HomeCloneCarriesNoCredential`
(`bin/cli/hc-venv-tool`, inherited without `venvs/`).

**Proof.**
- The parser against the verify output from the failing main run
  (34786751392): each home's declared entry matches, and the other home's entry
  is reported as unexplained (`proof/344-declaration-parser-vs-ci-34786751392-output.txt`).
- Local macOS home-clone: 16/16 nodes pass; the law checked 3 homes, 3 damaged
  on purpose, each reporting exactly its declaration
  (`proof/local-macos-home-clone-summary.txt`, `proof/local-macos-home-clone-fixpoint-law.json`).
- CI: home-clone and checkout-home are green on runs 34790343243, 34791698649 and 34793308847. On CI the law checked 3 homes, `homesDamagedOnPurpose=3`, each reporting exactly its declaration (`proof/ci-34790343243-home-clone-fixpoint-law.json`).

## #297 — the source-home digest hashes a live gateway's log

**Cause.** `HomeCloneSupport.treeDigest` hashed the whole fixture home,
including `gateway.log`, which a live gateway appends OTLP export failures to
every few seconds. "Cloning does not write to the source home" then failed on a
log append. CI passed only because `OTEL_SDK_DISABLED` silenced the exporter.

**Fix.** New `HomeCloneSupport.homeDigest` skips the home's journals at its top
level: `gateway.log`, `gateway.pid`, `audit.log` (the files a clone itself does
not copy, `HomeCloner.SKIPPED_ROOT_FILES`), plus `logs` and `tmp` (the rest of
`ArtifactDagSupport.JOURNALS`). The four source-home digests use it; the digest
of one unit's authored file keeps `treeDigest`. The `ci.yml` comment that called
the exporter "not a cause" is corrected.

**Proof.** Local home-clone: `home.cloned.into.project`,
`home.clone.edit.stays.in.clone` and `home.clone.works.with.source.renamed` pass
with no `OTEL_*` variable set. CI: home-clone and checkout-home green on all three branch runs.

## #345 — three graphs cannot pass on a fresh runner

**Cause.**
- ticket-lifecycle and onboarding locate the worktree scripts through
  `$TICKET_LIFECYCLE_SCRIPTS`, an `integration.toml` above the checkout, or the
  skill installed in `~/.skill-manager`. A runner has none of the three;
  locally they passed only through the operator's own home.
- home-tripwire snapshots the real homes under `$HOME` and correctly refuses
  when none exist, which is every fresh runner.

**Fix: provisioning, no exclusion.** `EXCLUDED` in `select-graph-set.py` is
unchanged, and no triggers changed.
- ticket-lifecycle and onboarding: the graph job fetches
  `haydenrear/git-issue-workflow-skill` at pinned SHA
  `fae9e9ef8145a612e8ed734e6029ca912e566875` (public, no token), checks the
  three scripts exist, and sets `TICKET_LIFECYCLE_SCRIPTS`.
- home-tripwire, ticket-lifecycle, onboarding: new
  `.github/scripts/seed-agent-home.sh` seeds a real-shaped home under `$HOME`:
  16 units in the store (each a `SKILL.md`, a references page, a script and an
  installed record), each linked into `.claude/`, `.codex/` and `.gemini/`
  `skills/`, and an empty `~/.claude.json`. It refuses to run where any of the
  four homes already exists.
- onboarding: the Claude and Codex CLIs, which CI installed only for
  plugin-smoke, are now installed for onboarding too.

**Found one layer at a time.** Each fix let the graph run further and exposed
the next precondition a laptop satisfies and a runner does not:

| run | commit | ticket-lifecycle / onboarding stopped at |
| --- | --- | --- |
| 34786751392 (main) | — | first node: "could not locate git-integration-repo's scripts" |
| 34790343243 | 2e058bed | fixture: `the_leak_oracle_is_armed_over_the_operators_real_homes` (no home under `$HOME`) |
| 34791698649 | 153823d8 | `*.global.home.untouched`: `the_leak_baseline_watched_a_non_trivial_tree` (more than 100 entries required; one seeded unit gave 15). onboarding also: `the_claude_marketplace_entry_is_in_the_file_claude_reads` ("CLI claude not on PATH") |
| 34793308847 | 898f7834 | **green**, both graphs |

The 100-entry floor was not lowered. The seed was sized against it using
`TripwireSupport.collectAll` itself, which reproduces CI's 15 for the one-unit
seed and gives 169 for 16 units (`proof/345-seed-metadata-count.txt`).

**Proof.** home-tripwire green from run 34790343243; ticket-lifecycle and onboarding green on run 34793308847.

## Goal contribution

GOAL-ci-green-fresh-runner, clause (1). Expected: 6 failed -> 1 failed
(artifact-dag), executed == selected. Measured: run 34793308847: 25 selected, 25 executed, 24 passed, 1 failed (artifact-dag, the same `uninstall.prunes.the.subgraph` failure as on main). Target met.

GOAL-one-verdict. Expected: a `/var`-written reference in a home given as
`/private/var` is reported by `home verify`. Measured: yes. The
`HomeVerifyPathSpellingTest` end-to-end test fails before the fix and passes
after, and on macOS home-clone the law now sees the planted shim.

## Validation

- `jbang RunTests.java`: ALL PASSED locally (`proof/unit-tests-local.txt`); the
  CI unit-test job is green on the same commit.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- Graphs: see the CI table below.
- Close-out: `skill-manager home close-out --home <wt>/.skill-manager --into
  <project>/.skill-manager` exits 0, "holds nothing that removing it would
  destroy" (`proof/close-out.txt`).

## CI

| graph | main 34786751392 | 34790343243 (2e058bed) | 34791698649 (153823d8) | **34793308847 (898f7834)** |
| --- | --- | --- | --- | --- |
| home-clone | fail | pass | pass | **pass** |
| checkout-home | fail | pass | pass | **pass** |
| home-tripwire | fail | pass | pass | **pass** |
| ticket-lifecycle | fail | fail | fail | **pass** |
| onboarding | fail | fail | fail | **pass** |
| artifact-dag (OHV-3) | fail | fail | fail | **fail** (unchanged: `uninstall.prunes.the.subgraph`) |
| plugin-smoke | pass | pass | pass | **pass** |
| the other 18 | pass | pass | pass | **pass** |
| **executed / passed / failed** | 25 / 19 / 6 | 25 / 22 / 3 | 25 / 22 / 3 | **25 / 24 / 1** |

All 25 selected graphs executed in every run. Final run: https://github.com/haydenrear/skill-manager/actions/runs/34793308847 (`proof/ci-34793308847-graphs-executed.json`).

## Deferred findings

None (`deferred.yaml`).
