# Wave 4 review — epic/home-integrity-sync

**Range:** `22b947c..fb9205b` · **Merged:** HIS-4 (#231 → #216), HIS-9 (#230 → #226), HIS-12 (#229 → #161, #187)
**Schedule revision at close:** 4 → **5** · **117 files, +24,104 / −343**, 24 production files

---

## What the wave was for

The first wave worked in parallel, and it is the wave where **all three tickets
were told not to merge.** Every one shipped a defect that a review reproduced,
and two of them shipped defects *created by their own diff*. That is the headline,
not the deliverables.

| ticket | what it fixed | what review found in it |
| --- | --- | --- |
| HIS-4 | a scaffolded gitignored tree no longer strands the installed baseline | `sync --from` **destroyed** the materialization it used to refuse; every rolled-back conflict reported `0 file(s)`; the load-bearing guard had **zero** coverage |
| HIS-9 | a write or delete resolving outside the home is refused | the refusal printed a `--home` that did not exist; the refusal was **swallowed** and `sync` exited 0; three entry points were inert |
| HIS-12 | a remedy names the home you were in | the `env` prefix put a **CORE graph red**, was not runnable verbatim, and **neutralized** the assertion it claimed to defend |

## Goal movement

| goal | measured | verdict |
| --- | --- | --- |
| `GOAL-one-home-one-answer` | DEF-002 remedy now names the home operated on; the guard refuses a write **and a delete** outside the home | on track |
| `GOAL-no-destructive-recovery` | DEF-007: `homeA/bin/cli` 2→0 entries at exit 0 → **2→2, exit 1** | on track |
| `GOAL-symlink-merge-settles` | the project-tier case **reproduced** and fixed; `sync-settles` red→green | on track |
| `GOAL-sync-quiet` | **not advanced** — HIS-4's slice (1), the digest side, was not delivered | **regressed against plan** |

`GOAL-sync-quiet`'s row is the honest one. The ticket agent first reported it as a
plan defect ("the plan assumed the digest question and the git question are one");
review showed the plan states them separately and slice (1) simply was not done.
Its own correction is the better statement of it:

> *"'The plan was wrong' invites amending the plan; 'a slice was not delivered' invites finishing the work."*

The plan stands. **Nothing currently owns shrinking `DriftReport`'s input**, and
that is an open gap going into HIS-6.

## Integrated validation

On the merged tip, per the validation policy set at the wave-3 gate:

| lane | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `uv run pytest specs/` | 38 passed |
| `home-sync` (CORE) | **18/18** |
| `ticket-lifecycle` (CORE) | **13/13** |
| `home-integrity` | 14/14 |
| `sync-settles` | red → green |
| `project-child-home` | 12/12 |
| `run.py --all` | not run — **terminal, HIS-6 owns it** |

---

## The decision I got wrong, and what it cost

HIS-12's `/usr/bin/env SKILL_MANAGER_HOME=… <build>` binding had to be replaced.
**I proposed the replacement, put it up to be attacked, and it lost on
measurement.** Recording it because the plan records ticket-agent errors and
should record the epic agent's on the same terms.

My proposal: print the home's own entrypoint as the head token, and branch HIS-9's
guard on absolute-vs-bare invocation. Three measured failures:

1. **`$0` cannot carry "deliberate".** A `#!` script never sees the typed word —
   the shell PATH-resolves it and `execve`s the absolute path. Across nine
   invocation shapes, bare-via-PATH and absolute give **identical** `$0`. The only
   shape that differs is `./skill-manager` — a human at a keyboard — which my rule
   would have **refused**. Inverted on the one case it could discriminate.
2. **The damage case and a pasted remedy are the same byte string.** HIS-9's own
   javadoc names `SKILL_MANAGER_HOME=<x> <y>/bin/cli/skill-manager` as the damage;
   that is an *absolute* invocation, so my "deliberate" branch would have reopened
   exactly what HIS-9 exists to close.
3. **Not every home has `bin/cli/skill-manager`.** It is written by `home shims`
   and nothing else. `home-sync`'s own fixtures have an **empty `bin/cli`**, so my
   remedy was undefined precisely where remedies are needed.

**What shipped instead — bind per verb, not per CLI token.** Verbs that already
name their target bind nothing; verbs that take `--home` carry it; `sync` and
`project sync` were given it. The structural reason is the part I had missed:
**every consumer feels entitled to replace the head token** — `remedyArgs` and
`close-change.sh`'s `run_cli` both document doing exactly that — so a binding
carried there is silently deleted by all of them. `--home` lives in a token nobody
replaces.

**And the contract I chose is still only half right.** `--home` binds the *store*
axis and not the *agent* axis, which is **DEF-029 → HIS-14 (#232)**, scheduled
wave 5. It was found by HIS-12 pasting its own remedy into a shell and looking at
what it touched — and it had linked units into the operator's real `~/.claude`,
`~/.codex` and `~/.gemini` and registered a marketplace in three real config
files. Repaired 2026-08-21 with the owner's approval; the permission layer
correctly refused both the ticket agent and the epic agent, and neither worked
around it.

## Hot spots

**1. A guard caught what three reviews did not.** HIS-12's source scan fired on the
integrated tip against **HIS-4's** three unbound remedies — a cross-ticket drift
that survived two adversarial reviews and two green suites. Then its own vacuity
check found a **gap in the guard** (the pattern required a `+` before the CLI
token, so the assignment form walked past). *A guard whose vacuity check finds a
way around it is a guard with a gap, not a passing check.*

**2. Integration cost two node-level fixes, both from HIS-9's refusal meeting
probes written before it.** `ticket.lifecycle.provisioned` ran a home's pin without
`--home` and hit exit 79. The refusal was right and the probe was wrong. Recorded
as DEF-030 with attribution rather than silently fixed.

**3. The conflict keys I declared were wrong, in both directions.** They named
classes HIS-12 does not touch and omitted `LauncherShims.java`, `ConsoleProgramRenderer.java`
and `deferred/backlog.yaml` — the files that actually interacted. The code merged
clean; the cost landed as semantic collisions and a three-way DEF-id collision.

## Decisions made implicitly

- **HIS-9 deleted three inert public entry points** rather than wiring them up, and
  withdrew two vacuity rows by strikethrough. Acceptance item 2 is met instead by
  `HomeCloner.unsanctionedForeignHome` — the predicate `home verify` itself uses,
  which **cannot drift** from what the gate refuses. Better than the roots list.
- **HIS-12 fixed HIS-4's remedies** rather than deferring, because it lands last
  and a red guard cannot be handed to a later ticket that does not exist.
- **`--force-scripts` in a child home now rebuilds locally** — a deliberate partial
  narrowing of HIS-10, on a flag `AgentHomes.java:186` tells operators to use.

## Where the bugs probably are

- **`--home` on every verb that now accepts it**, until HIS-14 lands. Store bound,
  agent axis loose.
- **`UnitTrunkPull:187` and `UnitPublisher:169`** — a third and fourth sync path
  asking the raw dirty question, outside the package HIS-4's scan covers (DEF-016).
- **`ReportUseCase`'s remedy table** — three sites converged into one `syncRemedy`
  during the merge, which is the right shape and the least-reviewed code in the wave.

## Vacuity — the epic's running count

Nine assertions in this epic have now been caught passing against broken code.
Wave 4 added four, **three found by authors on themselves**: HIS-9's row M (a
test-only backend made `installOne` return SKIPPED before any check ran), HIS-4's
probe 7a (reddened a *precondition*, not the claim), HIS-12's V17 (deleted) and
V19 (found the guard gap). The discipline is working; the rate is not falling.

## Deferred findings

DEF-013 … DEF-030, with **DEF-029 promoted to HIS-14 (#232)**. Three-way id
collision resolved by allocation: HIS-4 013–016, HIS-9 017–020 + 024, HIS-12
025–030, epic 021.

## Recommended next steps

**Wave 5 is HIS-11 (#186) and HIS-14 (#232)**, conflict keys disjoint —
`Executor.preStateCompensations`/`HomeLock` against `homeEnvPrefix`/`AgentHomes`.

Then wave 6 (HIS-13), 7 (HIS-8 docs), 8 (HIS-5 TLA+), 9 (HIS-6 terminal).
