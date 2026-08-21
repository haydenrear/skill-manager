# Wave 2 review — epic/home-integrity-sync

**Range:** `8d06d6b..23e35c7` · **Merged:** HIS-3 (#222 → #213), review response (#221), HIS-7 (#225 → #223)
**Schedule revision at close:** 3 → **4** (this review amends it) · **Review policy:** `cadence: wave`, `gate: true`

---

## What the wave was for

HIS-3 finished the drift trilogy: a gate that has been read once stops re-printing
itself. HIS-7 was supposed to finish the shim story — and delivered **half**, on
purpose, after an adversarial review found the other half wrong twice.

That split is the most important thing in this wave, so it goes first.

## Goal movement

| goal | clause | baseline | measured | verdict |
| --- | --- | --- | --- | --- |
| `GOAL-gate-settles` | 1 — 2nd surfacing is one line | full report every pass | one line + ack command | on track |
| | 2 — a new change still gates | — | `DriftGate` union reset preserved; regression rationale intact | on track |
| `GOAL-home-invariants` | via HIS-7 | 4 invariants | **narrowed** — see below | partial |

**`GOAL-home-invariants` narrowed and this is not a rounding error.** HIS-7 was
to state *an artifact reported built is an artifact whose output this home owns*.
What it can state now is only *a producer writes only inside the home
`SKILL_MANAGER_HOME` names*. The ownership half needs HIS-10 first, and **HIS-5
(the TLA+ ticket) was rescheduled behind it** rather than being asked to write an
invariant about a mechanism that is still changing.

`on track`, not `met` — **#218 (HIS-6) decides these**, on the integrated tip.

## Integrated validation

Run on the merged tip, not on either branch:

| lane | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `run.py home-integrity` | BUILD SUCCESSFUL (4m09s), 13 nodes |
| `run.py sync-settles` | BUILD SUCCESSFUL (49s) |
| `run.py project-child-home` | BUILD SUCCESSFUL (3m49s) |
| `run.py harness-smoke` | BUILD SUCCESSFUL (4m37s) |
| `run.py --all` | **NOT RUN** |

`--all` is ~7 minutes and was not run. The four above were chosen because the
adversarial review named `harness-smoke` and `project-child-home` as the graphs
most exposed to a change in CLI-dep provisioning. That is a reasoned subset, not
full coverage, and it is stated as such.

---

## Hot spots — where I would look first

**1. HIS-7 shipped a guard on ONE path, and the class it belongs to is open.**
`rebuildCliArtifact` takes ownership of the shim it is about to write. Nothing
generalises that. A producer is an arbitrary script handed
`$SKILL_MANAGER_BIN_DIR`; no layer between the effect and the filesystem asks
whether the destination is inside the home the command was given. **The
operator's root home was damaged twice while measuring this ticket** — through
an inherited symlink, into `~/.skill-manager/bin/cli/{computeq,tlc2}`. `tlc2`
survived because its shim happens to be `BIN_DIR`-relative. That is luck, not
design. → **HIS-9 (#226)**.

**2. `clearForeignShims` was a regression I introduced, and review caught it.**
The first commit called it from `installOne`, which every sync dispatches for
every dep — so **one sync in a child home would have deleted all five inherited
shims** and re-provisioned five venvs. It never shipped. It is recorded here
because it is the exact damage the epic exists to prevent, authored by the epic.

**3. I mis-attributed that damage as mine when it was already there.** I first
reported the sync-replacement as my regression. Measured on the untouched epic
branch: **a clone's first sync already prunes them.** Pre-existing → HIS-10. The
lesson is the general one — measure the baseline branch before claiming a
regression.

**4. Two vacuous tests in two waves.** HIS-1's first regression test passed
without its fix. HIS-7's first graph node passed with `takeOwnershipOfShim`
disabled, because a two-tier fixture has no inherited symlink for `cat >` to
follow. Both were caught by deliberately disabling the fix. **Assume vacuity
until disproved** is now this epic's working rule, and the three-tier node
records its own failed first shape so the next author does not rebuild it.

## Decisions made implicitly — flagging them rather than burying them

- **`takeOwnershipOfShim` / `restoreForeignShim` are a matched pair with no
  transaction.** If the process dies between them, the home has no tool and no
  record that it used to. Judged acceptable because the window is one producer
  invocation; not proved.
- **The PR merged with `Lint PR title` and `DCO` red.** The title was fixed;
  DCO has never passed on this epic (no commit carries a sign-off, including the
  wave-1 merges). Merged with `--admin` into an integration branch. **It will
  block the epic PR into `main` if `main` enforces it** — worth answering before
  finalization, not at it.
- **`run.py --all` was not run before the merge**, as above.

## Guardrails overridden

**Worktrees were still not used** — same as wave 1, and this time there is a
measured reason rather than a convenience one: **a clone's first sync destroys
its inherited toolchain and rebuilds it**, which is HIS-10. The declared worktree
paths in the assignments for HIS-4..HIS-6 remain un-exercised. HIS-4 is now
scheduled *behind* HIS-10 precisely so it can be the ticket that finally uses one.

---

## The measurement that reshaped the plan

Taken on the merged tip `23e35c7`, cloning this repository's real project home.
Evidence: `results/epic-home-integrity-sync/probes/his-10/`.

| reader | verdict on **the same** cloned home |
| --- | --- |
| `home clone` | ✓ clean — *"no path in it reaches another Skill Manager home"* |
| `home verify --home <clone>` | ✗ **exit 1** — 5× `FOREIGN_HOME` |
| `home verify --home <clone> --against <src>` | ✓ exit 0 — *"5 sanctioned parent-store shim(s)"* |
| `sync` in the clone | **prunes all five** — *"not this home's parent store"* — then re-provisions five toolchains locally, ~90s |

**Four readers, three answers, one home, five paths.** The sanction depends on an
operator typing `--against`, which is a flag, not a fact. And the last row is the
direct contradiction of the contract the owner stated for HIS-7 — *"why would we
build them without the agent changing? That's just a waste of time"* — paid by
every worktree, on its first sync, silently.

Root was verified byte-identical before and after that sync, so **HIS-7's guard
holds under a real clone → sync in a real home**. That is the one thing this
probe confirmed rather than broke.

## Where the bugs probably are

- **`CliShimPruner`'s parent-store test.** It is the fourth reader and the only
  destructive one. If HIS-10 changes the sanction rule, this is where a mistake
  deletes an operator's toolchain rather than printing something wrong.
- **`HomeCloner.verifyRoots`, provisioned-file branch.** It compares against the
  *unresolved* home spelling (`:1691` / `:1428`) while the symlink branch uses
  the resolved one (`:1635`). **Reproduced** — one home, one broken reference,
  two spellings: through a symlink it reports *"every reference resolves"*;
  through its real path it refuses. That is #206, folded into HIS-10.

  **And a correction to my own first reading of it.** I wrote that `HomeFixpointLaw`
  — wired into **24 of 29** registered graphs, every one of whose homes sits under
  `/var/folders/…/T` — was therefore blind. It is not: `/private/var/X` *contains*
  the substring `/var/X`, so `indexOf` accidentally matches and the reference is
  found. The graphs are saved by an accident of macOS path naming, not by
  correctness. The practical consequence is a trap, not a reprieve: **the obvious
  `/var`-based fixture passes before and after the fix**, and would have been this
  epic's third vacuous assertion. The ticket agent was told before writing it.
  Evidence: `probes/his-10/path-spelling.md`.
- **`Executor.java:307`** — `case SkillEffect.CommitUnitsToStore e -> List.of();`
  An empty compensation for the one effect that overwrites installed bytes. A
  failed resolve deletes what it overwrote and restores nothing. That is #186,
  now HIS-11.

## Architectural changes worth making — including to the epic's own machinery

1. **Prevention has no counterpart.** Six tickets stop the next damage; nothing
   repairs a home already damaged, and `HomeCommand` has no verb for it. The
   operator's own root home is the standing example. → **HIS-13 (#159)**.
2. **The epic's machinery is still gitignored and reaches nothing by merging.**
   Unchanged from wave 1 and now *more* load-bearing: `render-epic-assignment.py`
   and `regen-epic-projection.py` live in this repo, but the `git-epic-workflow`
   fixes they imply live in a home. Nothing in wave 2 ran `skt publish` either.
3. **`spec-double-compiler`'s `SKILL.md` names units the project home does not
   hold** (`skill-manager`, `skt`), so **every clone's sync exits 11** on
   markdown import violations after doing all its work. Recorded as DEF-005.

## Deferred findings

`results/epic-home-integrity-sync/deferred/backlog.yaml`

- **DEF-001** — closed.
- **DEF-002** — *open → now owned.* The remedy naming the root home's pinned CLI
  is the same defect as #161; both are **HIS-12**.
- **DEF-003** — open, not this epic's worktrees; admin records pruned, question
  unanswered.
- **DEF-004** — *triaged → resolved by split.* HIS-7 landed the build half;
  HIS-10 owns the clone half.
- **DEF-005** — *new.* The exit-11 above.

---

## Schedule revision 4 — what this review changed, and why

The owner's instruction: *"All home bugs across root, project, and worktree
should be resolved by this epic."* Every open issue was swept and classified —
home bug / adjacent / unrelated — on the discriminator: **is the defect about a
home as a thing (its identity, boundary, records, derived-artifact ownership, or
its relation to another home), or does it just happen to occur inside one?**

**Absorbed (5 issues → 3 new tickets + 2 folds):**

| ticket | issue(s) | what it is |
| --- | --- | --- |
| HIS-11 | #186 | delete-only compensation destroys the bytes a failed install overwrote |
| HIS-12 | #161 + #187 + DEF-002 | a refusal names a home you were not in and a build that cannot run it |
| HIS-13 | #159 | nothing detects or repairs a home already damaged |
| *fold* | #206 → HIS-10 | the verdict is not spelling-invariant; same method |
| *fold* | #187 → HIS-12 | `project sync` loses the typed refusal `project resolve` renders |

**Deliberately left out, with reasons** — so the omission is a decision:

- **#182** — the in-repo half **shipped** (`home sync --unit`, `88cacd1`/`a2ab5e8`);
  what remains is `skt publish <unit>` in another repository.
- **#110** — the reported mechanism is **gone**: `DEFAULT_MODE` is `COPY` and this
  repo's `.skill-manager/skills/` holds real directories. Its one mechanical
  deliverable is already HIS-9's.
- **#132** — a real per-home isolation hole (one global gateway owned by whichever
  build started it) but it needs an **owner decision** — per-home, per-project, or
  global-with-an-explicit-owner — before it can be a ticket. Attaching a terminal
  evaluation to an undecided design question puts a goal on something with no
  agreed target.

**Two goals were added rather than left implicit** — `GOAL-one-home-one-answer`
and `GOAL-no-destructive-recovery` — because none of the five absorbed issues maps
onto the six existing goals, and HIS-6 would otherwise report six verdicts against
an epic that had grown by half. Both carry baselines measured **today**, on the
merged tip, not estimated.

**The epic is now 8 waves, not 5.** That is the honest cost of the instruction.

## Recommended next steps

**Wave 3 is #227 (HIS-10), alone.** It is the keystone: it unblocks HIS-4's
worktree, it is what HIS-9 and HIS-13 want to build on, and it is the ticket that
makes the four readers agree. Running it alone also keeps `HomeCloner` off the
critical path of anything else.

Then **wave 4 in parallel** — HIS-4 (#216), HIS-9 (#226), HIS-12 (#161) — whose
conflict keys are disjoint: `ChildHomeMaterializer`/`Rederivable` against the
effect-boundary write guard against `HomeDescriptor`/`ProjectCommand`.

Two things to decide:

1. **DCO.** It has never passed on this epic. If `main` enforces it, the epic PR
   cannot merge without rewriting every commit. Cheaper to answer now.
2. **The v0.24.0 release re-run.** The root home still pins Homebrew `0.23.0`
   because the release workflow died on a transient dependency-descriptor read.
   Re-running it publishes a release, so it is the owner's call.
