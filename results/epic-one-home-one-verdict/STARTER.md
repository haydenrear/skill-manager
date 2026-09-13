# Starter — epic `one-home-one-verdict` (#337)

You are picking this up cold. This file is the handoff; read it before the
issues, because the issues do not say what has already been tried.

**Nothing is scaffolded.** No branch, no spec workflow, no `ticket_plan.yaml`.
That is deliberate — `git-epic-workflow` plan-and-schedule is your first
action, and the goals below are a *proposal* to agree with the owner, not a
decision already taken.

## State of the world when this was written (2026-09-13)

- **Released: skill-manager `v0.27.2`, skt `0.8.2`.** Root home and this repo's
  project home are on both, `home verify` clean, `skt check` all current.
- **The skill-manager → skt migration now completes in real project homes.**
  0.27.0 rolled back on project claims, and 0.27.1 cloned the retired unit back in
  the same sync. Both are fixed (#347, #349). It was validated on 39 real project
  homes before release: 32 migrated, 1 correctly refused, 6 have no skt and are
  now told how to install it. The full record:
  `results/epic-one-home-one-verdict/attribution/2026-09-13-migration-0.27.md`.
- **The previous epic (`one-unit-one-name`) is merged and released.** Its 15
  ticket worktrees were NOT yet swept when this was written. See the sweep
  status in `STARTER-PROMPT.md` before creating any worktree of your own.

## Read in this order

1. **#337** — the epic, with the attribution that motivates it.
2. **#338, #339, #340, #341, #346, #352** — the tickets. #338 first; #339, #340,
   #341 and #352 depend on it. #346 (CI green on a fresh runner) depends on #343.
   #352 (OHV-6) was added 2026-09-13 — see *Marketplace identity* below.
3. `results/epic-one-unit-one-name/reviews/epic-close.md` — the previous
   epic's close review, including §9a "resolved since this review was written".
4. The three deferred backlogs. There are **154 findings** in them and they
   are the evidence base for this epic:
   - `results/epic-home-integrity-sync/deferred/backlog.yaml` (122)
   - `results/epic-one-unit-one-name/deferred/backlog.yaml` (24)
   - `results/epic-home-boundary-resolution/deferred/backlog.yaml` (6)

## The finding, in one line

**41% of 154 recorded findings are a guard whose rule is narrower than its
name.** Five commands answer five different questions about one home, each
correct about its own, none about *"is this home alright"* — and all five
exit 0 on a home that is not.

The 25% "two records disagreeing" and 19.5% "absolute path" clusters are
downstream of the same absence: a home has no single description of itself,
so every guard reimplements a partial one.

## What is already true, so you do not re-derive it

- `scripts/release_regression.py` implements the pattern the first goal needs:
  compare a guard's verdict against an **independent observation** of the same
  fact. Three checks. It is the seed of #339's harness, not a throwaway.
- `scripts/measure_goal_a_home_survives_being_copied.py` copies a home the way
  an image build does (`shutil.copytree`, never `home clone`) and reads three
  properties off the result. Same pattern, different subject.
- `HomeRepair.Kind` is generic over `values()` in `DamagedHomeIsRepairableTest`
  — **a new kind that nothing plants fails the suite.** Do not lose that in a
  refactor; it is the only thing standing between this epic and a narrower
  guard with a wider name.
- The attribution classification behind #337 was done **ad hoc**. #338 asks you
  to build it as a script, because this epic has to re-measure its own effect
  on those clusters and cannot without one.

## Traps, each of which cost a day

**#340 / #292 has a failed attempt on record.** The census phantom looks like
a three-line fix and is not:

- **The re-record is a fixpoint.** `ArtifactPrune.apply` rebuilds the ledger
  from `ArtifactIndex`, which merges the disk *with the ledger*, so every row a
  prune drops comes straight back. Measured: 59 before, 59 after. Nothing else
  can be observed to work until this is fixed. **Do it first.**
- Pruning a row whose ledger names no outputs **left a dangling agent symlink
  no cleanup could reach**, and `plugin-smoke`'s `home.fixpoint.law` went red.
  "The ledger names no output" is not "there is nothing on disk", and the index
  cannot fill the gap — it derives outputs from the same record.
- **Land the changes one at a time**, each verified against `plugin-smoke` as
  well as `artifact-dag`. Landing three interacting verdict changes together is
  what made the regression un-isolatable.

**`DEF-121` documents a remedy that is correct there and wrong here.** It
prescribes `rm -rf` of two paths for an `eval-skill` residue. That home had no
`installed/` record. Applied to a home where the unit *is* installed, the same
remedy manufactures the damaged shape `DEF-121` exists to clean up.

**A test that forbids the only correct current state is not a guard.** This
epic's predecessor hit that three times — a fixture writing the pre-fix shape
and calling it healthy, a damage function that had quietly become a no-op, and
a two-tables test encoding a rule the product cannot satisfy yet. Check what a
fixture *asserts is normal* before trusting a green.

**Measuring a mechanism is not running it.** Three of the previous epic's most
serious findings came from doing the thing for real: the copied-home goal was
declared and unmeasured; the epic branch was 7 commits behind `main`; and the
migration relinked the unit it had just retired, invisible to both guards, and
was only found by cloning a real home and running `sync skt` in it.

**Migrations are validated on REAL homes, not clones.** 0.27.0 passed on a
clone and failed on the root home, because a clone drops project and child-home
claims. 0.27.1 passed on the root home and failed in every project home, because
their git-epic-workflow still referenced the retired unit's old repository. The
only runs that found either defect were real homes. For anything that changes
what a sync installs or retires: back up `installed/`, run this repo's
`./skill-manager` against real project homes, and read the second sync.

**An eval can only measure a released build.** The eval sandbox passes only
`PATH`, and a home pinned to the repo's jbang build fails inside it ("Downloading
JDK 17", network denied). To measure an unreleased build, export the fatjar the
way `release.yml` does and point the branched homes' pins at it. Also, a run that
hits `max_turns` loses its Stop-hook graders (#353), so read the trace, not the score.

## Still untested by that standard

- **A fresh-machine onboard** — no home at all, from nothing.
- **Child-home fan-out** — the project home claims zero child homes, so that
  path has unit-test coverage only.

Neither blocks anything. Both are real gaps and belong in this epic's
validation plan.

## Marketplace identity — #352 (OHV-6), added 2026-09-13

Measured across all 53 homes on this machine, four shapes of one defect:
Claude still registered under the pre-per-home name (2 homes: tla-spec-dev,
meta-orchestrator), `ensureMarketplaceAdded` matching by substring, 13 cloned
homes carrying their source's `marketplace.json` identity, and 9 Codex configs
registering another home's marketplace (the root's `~/.codex` points into
commit-diff-context-parent). Every one is invisible to `home verify`; the only
trace is an `AGENT_SYNC_FAILED` line in a sync — which is why plugin updates,
including skt 0.8.2's migration notice, may never reach Claude in those homes.
Plant all four as `Kind`s rather than as one-off tests.

## Outside this epic, but you will trip over them

| issue | what | why it is not this epic |
| --- | --- | --- |
| #351 | Gemini consumes no plugins, so a migrated home has no `skill-manager` skill for Gemini | agent projection, not home verdicts |
| #353 | eval harness: a `max_turns` run loses every Stop-hook grader | eval harness (#328) |
| #327 | `remove`/`uninstall` delete a unit checkout with unpushed commits | owner decision on `remove` friction |
| #269 | (HBR-1) a child home's `bin/cli` shims point into another home, and child-home syncs rewrite the parent's shims | the open home-boundary-resolution epic |

## CI is red on `main` and that is in scope — #346

The nightly has failed every day since at least 2026-09-06, identically on
`main` and on the epic that preceded this one. #346 owns it:

- **#344** `home-clone`, `checkout-home` — `home.fixpoint.law` refuses a broken
  shim the fixture plants on purpose.
- **#345** `home-tripwire`, `ticket-lifecycle`, `onboarding` — cannot pass on a
  fresh runner. The last two pass locally **only through the operator's own
  `~/.skill-manager`**.
- **#343** — `home verify` on macOS misses `/var/…` references when handed
  `/private/var/…`. It is why local sweeps passed what CI failed, and #344
  cannot be verified locally until it is fixed.

**Do not trust a local sweep's count.** Gradle stops at the first failing
graph, so every graph after an early red is silently not run, and
`validation-reports/` still holds passing reports from earlier runs. That hid a
real regression until CI ran it (`DEF-OUN-023`). Use CI's per-graph jobs:

```bash
gh workflow run ci.yml -R haydenrear/skill-manager --ref <branch> -f graph_set=full
```

## How to validate anything here

```bash
python skills/test_graph/scripts/run.py --all          # ~35 min, 25 graphs
python skills/test_graph/scripts/run.py plugin-smoke   # ~4 min, the one that
                                                       # catches home-law breaks
jbang RunTests.java                                    # the unit suite
python3 scripts/measure_goals.py --epic <slug>         # the goal ledger
python3 scripts/release_regression.py                  # guard cross-checks
```

CI runs **no graphs** on push or PR, at the owner's instruction. A local sweep
is the only pre-merge graph signal — see `CLAUDE.md`.

Run graphs with `GRADLE_OPTS="-Dorg.gradle.daemon=false"`; a foreign session's
daemon causes flaky describe failures.

## The measurement to open with

Two verdicts on one home, one minute apart, which is the whole epic in four
lines:

```
~/.skill-manager judged by the CLI that home pins (0.26.0 when first measured)
    → "nothing is damaged in a way this command knows about (73 entries examined)"
the same home judged by a build carrying two more detectors
    → 8 findings
```

Reproduce it before you change anything, on `v0.27.2`. The root home has been
repaired and migrated since this was first measured, so expect the numbers to
have moved. Re-measure and record the new baseline rather than assuming.
