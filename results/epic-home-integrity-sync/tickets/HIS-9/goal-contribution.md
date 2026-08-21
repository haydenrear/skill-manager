# HIS-9 — goal contribution

Issue #226. Branch `feature/226-his-9`, based on epic tip `dc97f2a`.

## What this delivers

**A write-confinement guard: an operation declares the roots it may write
under, and a mutation resolving outside every one of them is REFUSED, naming
the path as spelled, where it actually resolves, and the home it escaped.**

Two of the four known instances were live and one of them was destroying bytes.
Both are closed, measured before and after, at the CLI and in the unit suite.

`dev.skillmanager.store.WriteConfinement` +
`dev.skillmanager.store.WriteOutsideHomeException`.

### The measured before/after, at the CLI

**DEF-007 — a `sync` in homeB deleting homeA's toolchain.** Two synthetic homes
under a scratch directory, one installed unit so `sync` reaches its CLI install
pass, `homeB/bin/cli` a symlink at `homeA/bin/cli`. Same script both arms; the
only difference is one line of production code.

| | homeA `bin/cli` before | after | sync exit | what it said |
| --- | --- | --- | --- | --- |
| guard removed | 2 entries | **0 entries** | **0** | `cli: pruned bin/cli/alpha … not this home's parent store` ×2 |
| guard in force | 2 entries | **2 entries** | 1 | `✗ × InstallCli: refused: sync's bin/cli prune would list and delete through a path outside the home it was given` |

The refusal prints all three facts:

```
  path:     …/homeB/bin/cli
  resolves: /private/…/homeA/bin/cli  <- outside the home, through a symlink
  home:     …/homeB
```

Evidence: `probes/his-9/def007-before-guard.out`,
`probes/his-9/def007-after-guard.out`.

**Instance (2) — the pinned shim rebinding the home.**
`SKILL_MANAGER_HOME=<x> <y>/bin/cli/skill-manager home describe`, two synthetic
homes:

| | exit | reported |
| --- | --- | --- |
| before | **0** | `SKILL_MANAGER_HOME <y>` — **the home the operator did not name** |
| after | **79** | refusal naming **x** ("you named") and **y** ("this shim would have edited") |

`--home <x>` in the same environment: exit 0, reports **x**. Evidence:
`probes/his-9/instance2-before-guard.out`, `instance2-after-guard.out`.

### Where the guard is enforced, and what each site closes

| site | instance | rule |
| --- | --- | --- |
| `CliShimPruner.prune` | (0) DEF-007, call site 1 | `bin/cli` must **resolve** inside this home before anything is listed or deleted |
| `InstallerRegistry.takeOwnershipOfShim` | (0) DEF-007, call site 2 — the one **HIS-10 made reachable** | the entry being deleted must belong to this home |
| `InstallerRegistry.installOne`, before the fork | (1) | the destination handed to a producer must not be an **unsanctioned** link into another home; under `force`, a sanctioned mirror is taken ownership of (HIS-7's mechanism, reused) |
| `InstallerRegistry.installOne`, after the fork | (1) | the artifact produced must not reach into another home |
| `LauncherShims.cliScript` | (2) | an explicitly-set, differing `SKILL_MANAGER_HOME` is refused, not overridden in silence |
| `LiveInterpreter.execute` | — | declares each effect's scope for the duration of that effect. **A seam, not a gate — see "What was cut", item 2.** |

### Writes and deletes are asked *different* questions, and that is load-bearing

A write follows the final path component; a delete does not. One rule would have
got one of them wrong, in the destructive direction:

- following the leaf for a **delete** refuses every sanctioned mirror shim in
  every child home — the link lives here and points there, and removing it
  removes something that is ours. Vacuity rows **F** and **K** are that mistake,
  made deliberately; between them they redden the pruner control, the forced-
  ownership case and the permitted-delete case.
- **not** following the leaf for a **write** misses the HIS-7 escape entirely.
- and a **container** about to be listed and deleted through needs the write
  rule even though the operation is a delete. That was a real bug in the first
  version of this guard: `requireInside(binDir, …)` used the entry rule, so
  `<home>/bin` resolved perfectly well inside the home and the check **refused
  nothing at all on the exact fixture it was written for**. Vacuity row **G** is
  that bug, kept executable.

### The sharing design is not broken, asserted from four directions

The acceptance item this ticket was most able to get wrong.

1. A child home's **sanctioned mirror** survives a prune — 0 pruned, link
   intact, parent still holds both entries (`PruneStaysInsideItsHomeTest`).
2. A routine install over a sanctioned mirror is **not refused**; the backend
   decides and returns `ALREADY_PRESENT` (`ProducerStaysInsideItsHomeTest`).
3. An artifact linked **outside every home** — brew's cellar — is not refused.
   The exemption is not a second rule: both the pre- and post-check ask
   `HomeCloner.unsanctionedForeignHome`, so this refuses exactly what
   `home verify` refuses. Removing that call (row **I**) reddens *both* the
   sanctioned-mirror case and the brew case.
4. A scope may **declare an extra root**, and only a declared one is permitted
   (`WriteConfinementTest`). That is the listing mechanism for a legitimate
   write into a shared root; an exception that is listed is reviewable, one that
   is implicit is the bug.

And the deliberately adversarial half: **being a genuine child does not license
deleting through a directory link into the parent.** A registered project child
and a clone carrying a live descent record are both refused, on the same shape,
in the same file.

### The root tier is covered

Tier 1 in `PruneStaysInsideItsHomeTest` is **root-shaped**: a store that is
nobody's child, with no claim anywhere and no descent record. It is
**synthetic** — built under a temp directory by the fixture. Nothing in this
ticket read or wrote `/Users/hayde/.skill-manager` or the project home, and both
CLI probes build their homes from scratch under `$TMPDIR` and delete them.

## Vacuity — every new assertion, run against the broken code

Twelve disables. Eleven redden, and they redden **different** cases; the
twelfth reddens nothing and is reported as such rather than dropped. Raw logs:
`probes/his-9/vacuity-*.out`.

| # | fix disabled | reddened |
| --- | --- | --- |
| **A** | `requireContainerInside` removed from `CliShimPruner.prune` | **3 FAIL** — the ROOT, PROJECT and WORKTREE tiers, all *"expected a write-confinement refusal, and nothing was refused — the prune ran to completion in the wrong home"* |
| **B** | `requireInside` removed from `takeOwnershipOfShim` | **1 FAIL** — *"takeOwnershipOfShim — DEF-007's SECOND call site — is refused too"* |
| **C** | `refuseAForeignDestination` removed from `installOne` | **1 FAIL** — *"not true: the producer never ran — no byte moved"* |
| **D** | `requireContainerInside` removed from `installOne` | **1 FAIL** — *"$SKILL_MANAGER_BIN_DIR itself pointed at the other home, and the producer was never handed it"* |
| **E** | the `force` ownership arm removed from `installOne` | **1 FAIL** — *"and the PARENT's artifact is byte-identical — this is the measured HIS-7 damage, not reproduced: expected <#!/usr/bin/env sh…"* |
| **F** | `checkDelete` follows the leaf | **1 FAIL** — *"a DELETE of that same link is permitted — it removes what lives here: refused: the pruner would delete a path outside the home it was given"* |
| **G** | `requireContainerInside` does **not** follow the leaf (the bug the first version had) | **4 FAIL** — all three prune tiers **and** the producer's `bin/cli`-is-a-link case |
| **H** | the shim's home-mismatch refusal removed | **1 FAIL** — *"it refuses with its own status, not the self-exec one and not 1: expected <79> but was <0>"* |
| **I** | the produced-artifact exemption removed | **2 FAIL** — *"a SANCTIONED mirror is not refused"* and *"an artifact linked OUTSIDE every home is left alone — brew's case"*, both *"refused: the stub-producer backend's artifact for tool would write a path outside the home it was given"* |
| **J** | the shim's `--home` escape removed | **1 FAIL** — *"naming a home on the command line is never refused: expected <0> but was <79>"* |
| **K** | `requireInside` follows the leaf | **2 FAIL** — *"takeOwnershipOfShim still takes ownership when the home is intact"* and the forced-ownership case, both *"refused: taking ownership of bin/cli/… would reach a path outside the home it was given"* |
| **L** | the **effect-boundary declaration** removed (`LiveInterpreter.execute` always declares unconfined) | **ALL PASSED** — nothing reddens. Reported, not hidden; see "What was cut", item 2. |

Rows **F**, **G** and **K** are the ones that matter. A–E and H–J prove each
enforcement site is reached; F, G and K discriminate between three *different*
containment rules that all look plausible and of which only one combination is
correct.

Two of the rows also carry the epic's other failure mode. Row **I** reddens the
two cases that keep this from being a blanket refusal; row **K** reddens the
`takeOwnershipOfShim` **control**, which exists because this repository has
already shipped a shim that refused *unconditionally* and satisfied a one-sided
"it refuses rather than succeeding silently" assertion for two releases.

## Validation

| harness | result |
| --- | --- |
| `jbang RunTests.java` | **ALL PASSED** (139 suites; 3 new classes, 19 new cases) |
| `uv run pytest specs/` | **38 passed** in 1.71s |
| `python skills/test_graph/scripts/run.py home-integrity` | **BUILD SUCCESSFUL, 15/15 nodes passed** (run `20260821-192222`, 5m 11s, on the rebased tree) |

### The graph result — run `20260821-192222`, BUILD SUCCESSFUL, 15/15

`home-integrity` ran green end to end in 5m 11s, on the tree rebased onto epic
tip `a4a95cb`. Both of this ticket's nodes executed. It was run twice: once at
`20260821-191337`, which surfaced the coverage hole below, and again at
`20260821-192222` after the fix. Verbatim envelopes are committed under
`probes/his-9/graph-*.json`.

**`home.integrity.sync.stays.inside.its.home` — new, `passed`**

```
[PASS] a_sync_in_one_home_removes_nothing_from_another
[PASS] it_refuses_rather_than_pruning_through_the_link
[PASS] the_refusal_names_the_home_it_was_given
[PASS] the_refusal_names_the_path_it_would_have_reached
[PASS] without_the_link_the_same_sync_does_not_refuse
metric victimEntriesBefore = 2
metric victimEntriesAfter  = 2
```

`2 → 2` is DEF-007's measurement inverted and now standing: the same fixture
that took another home from 2 entries to 0 leaves it at 2. The last assertion is
the control — the identical sync without the link does **not** refuse, so the
node discriminates on the shape rather than on "sync refuses".

**`home.integrity.bootstrap.projects.target` — re-aimed, `passed`**

```
[PASS] the_home_carries_its_own_cli_entrypoint
[PASS] the_shim_refuses_a_home_it_was_not_told_to_edit
[PASS] the_refusal_names_both_homes
[PASS] the_shim_never_runs_against_an_inherited_home
[PASS] naming_a_home_with_the_flag_beats_the_shims_own_binding
[PASS] the_flag_does_not_silently_fall_back_to_the_shims_home
metric shimExitOnHomeMismatch = 79
```

The process record shows `shim-with-inherited-env` exiting **79**, and what it
printed (`probes/his-9/graph-shim-refusal-verbatim.log`):

```
skill-manager: refusing to run against a home you did not name.
  you named:  …/home-integrity/scratch/bootstrap-target
  this shim would have edited: /private/…/home-integrity/home
  This entrypoint binds the home it lives in, so it cannot honour
  SKILL_MANAGER_HOME. Refusing rather than silently editing the
  other one.
  Use `…/home-integrity/home/bin/cli/skill-manager <cmd> --home …/bootstrap-target`,
  or run the CLI build directly instead of this shim.
```

The old assertion this replaced — `the_shim_overrides_an_inherited_skill_manager_home`
— would have gone red on this build. What it protected is intact and sharper:
the shim still never runs against the inherited home, and it no longer runs
against its own in silence.

### The law was checking 1 of 7 homes — DEF-017

Asked whether `home.fixpoint.law` still covers the graph's newest node, the
answer was **no, and not only for this node**. On the same run:

| store-shaped directory | production's `home verify` | law checked it |
| --- | --- | --- |
| `home-integrity/home` (fixture) | exit 0 | **yes** |
| `scratch/middle-home` (HIS-7 node) | exit 0 | no |
| `scratch/leaf-home` (HIS-7 node) | exit 0 | no |
| `scratch/his10/middle-home` (HIS-10 node) | exit 1 — negative fixture | no |
| `scratch/his10/leaf-home` (HIS-10 node) | exit 1 — negative fixture | no |
| `scratch/his9/victim-home` | exit 0 | no |
| `scratch/his9/syncing-home` | exit 0 | no |

`homesChecked = 1`. Seven directories, all of which **production itself** calls
homes (none exited 2, which is `NotAHomeException`, "skip me").

**The gate is `publish()`, not `dependsOn()`**, and that is the correction to the
obvious diagnosis. `HomeFixpointLaw.candidateHomes` walks
`item.data().values()` over upstream context items and offers each existing
directory to `home verify`; ordering was never wrong — the planner already put
the law at 15/15, after every assertion node. The fixture node is simply the only
one that ever published a home path.

That matters because of what the law is for. Its javadoc: a run that finds
**zero** homes fails, because "an instrument reporting success because it could
not look" is the failure it exists to close. A run that finds **one of seven** is
the same failure one notch quieter, and it passed.

**Fixed for this ticket's node only, and re-run to prove it.** It now declares
and publishes `victimHome` and `syncingHome`. Measured across the two runs:

| | run `…191337` | run `…192222` |
| --- | --- | --- |
| `homesChecked` | **1** | **3** |
| `homesRepaired` | 0 | **0** — both new homes verified; neither needed the remedy |
| law status | passed | passed |
| graph | 15/15 | 15/15 |

Both homes verify **exit 0**, so this extends the law's reach rather than
importing a known-bad fixture into a shared post-condition — which is why
`homesRepaired` stays at 0 rather than the law running `sync --force-scripts`
against something.

The other four homes belong to the HIS-7 and HIS-10 nodes and are **DEF-017**.
HIS-10's pair is a *decision*, not a one-liner: those two verify **exit 1 by
construction** — that node's subject is a home whose sanction was revoked — so
publishing them would point the law's repair arm at a deliberately unsanctioned
home. Either the law needs a way to mark a negative fixture, or they stay out
and the reason is written down.

**Graphs run, and why those.** `home-integrity` is this ticket's declared
`conflict_keys.test_graph` graph and the only one whose fixtures exercise the
sources edited here: it owns `ParentHomeSurvivesAChildBuild` (the HIS-7 producer
path, which `InstallerRegistry.installOne` is on),
`BootstrapProjectsTheTargetHome` (the pinned shim, which this ticket changes the
contract of) and the two nodes below. `run.py --all` was **not** run — it is
multi-hour and belongs to HIS-6, per the owner's instruction of 2026-08-21.

No other graph was run, and the reason is stated rather than assumed: the
production sources touched are `CliShimPruner`, `InstallerRegistry`,
`LauncherShims`, `SkillEffect`, `LiveInterpreter` and `SkillManagerCli`. Every
graph that installs a unit reaches `installOne`, so in principle the blast
radius is wide; what bounds it is that the guard's default is **unconfined** and
its enforcement fires only on a path that resolves into *another Skill Manager
home*, which no graph fixture builds except the ones in `home-integrity`. That
is an argument, not a measurement, and HIS-6's sweep is what decides it.

### Graph changes

- **New node `home.integrity.sync.stays.inside.its.home`.** Clones the fixture
  home twice, makes one clone's `bin/cli` a link at the other's, runs a real
  `sync`, and reads the victim's directory before and after. The unit test
  drives the pruner; this shows the whole command — exit 0, another home
  emptied, nothing in the output saying so. The victim is a **clone, never the
  shared fixture home**: if the guard regresses, a node pointed at the fixture
  would delete the fixture's toolchain and every later node, including
  `HomeFixpointLaw`, would fail for an unrelated reason.
  It carries its own control — `without_the_link_the_same_sync_does_not_refuse`
  — because "sync refuses" is a property a completely broken build satisfies.
- **`home.integrity.bootstrap.projects.target` re-aimed.** Its assertion
  `the_shim_overrides_an_inherited_skill_manager_home` asserted the behaviour
  this ticket changes, and would have gone red. It is now
  `the_shim_refuses_a_home_it_was_not_told_to_edit` +
  `the_refusal_names_both_homes` +
  `the_shim_never_runs_against_an_inherited_home`. **The property the old
  assertion protected is preserved and strengthened**: the shim still never
  operates on the inherited home (that is the decoy incident the override exists
  for), and it no longer operates on its own without saying so. The two `--home`
  assertions are untouched.

## Goal contribution

### GOAL-no-destructive-recovery — direct

Expected effect: *"the (a) half's mechanism generalised: no effect can write
outside its declared roots, so a producer cannot damage a home it was not
given."*

Delivered **for the paths where the damage was measured, and not more than
that**. The honest statement:

- a producer can no longer be handed a destination that resolves into another
  home, and an artifact that landed in one refuses the install;
- a delete path can no longer follow a link out of the home, at either of the
  two call sites;
- **an arbitrary `Files.writeString` elsewhere in the product is still
  unguarded** — 173 direct mutation sites, filed as **DEF-013**.

Against the goal's clause 1 (bytes surviving a failed resolve/install) this is a
**contribution, not a decision**: HIS-11 owns `Executor.preStateCompensations`.
What this adds to clause 1's account is a class of byte loss that was not a
walk-back at all — the successful path deleting another home's files and
reporting green.

### GOAL-home-invariants — direct

Expected effect: *"an invariant stating a producer writes only inside the home
`SKILL_MANAGER_HOME` names."*

**No TLA+ invariant was written, and that is the assignment, not an omission.**
The ticket's `validation.tlc` reads: *"N/A: this ticket states no new invariant;
HIS-5 carries the model work for the whole epic and re-runs every regression
cfg"*, and `conflict_keys.tla` is empty. Stating one here would collide with
HIS-5 on `HomeIntegrityInternal`.

The property is stated in prose so HIS-5 can encode it, at the width it actually
holds:

> **WriteConfinement.** For every effect *e* with a declared root set *R(e)*,
> and every filesystem mutation *m* performed under *e* that this program
> issues: `resolve(target(m)) ∈ R(e)`, where `resolve` follows every path
> component for a write and every component but the last for a delete. `R(e)` is
> `{home}` for the CLI install, rebuild and prune effects and is unconstrained
> otherwise. The quantifier is over mutations **this program issues**; a forked
> producer's writes are outside it, and what is stated for those is a
> precondition (the destination resolves inside the home) and a postcondition
> (the artifact resolves inside the home).

The two-clause quantifier is the part HIS-5 must not drop. An invariant that
said "no producer writes outside the home" would be false — we cannot observe a
subprocess — and an invariant that is false in the model is worse than none.

## What was cut, and what is not covered

**1. There is no universal in-process confinement, and there could not be one in
this ticket.** 173 direct `java.nio.file.Files` mutation call sites exist in
`src/main/java` and **none of them consults the guard**. `Fs` is not a choke
point — it carries no write, no move and no `createSymbolicLink` at all — and it
**cannot** be made one from here without breaking the server build:
`SkillManagerServer.java` compiles `shared/util/Fs.java` standalone out of a
package that imports nothing from `dev.skillmanager`. Filed as **DEF-013** with
two concrete options for the owner. Everything in this ticket is scoped to the
four enforcement sites named above.

An earlier draft of `WriteConfinement`'s own javadoc claimed enforcement at
`Fs.deleteRecursive`, which was never true — it was written while that was still
the plan and not corrected when the server-build constraint killed it. Caught on
re-read before the PR and fixed; recorded here rather than quietly edited,
because a false coverage claim in a guard's javadoc is exactly the kind of thing
a later reader trusts.

**2. The effect-boundary declaration is a SEAM, not a GATE, and vacuity row L
proves it.** `SkillEffect.writeConfinement` + the `LiveInterpreter.execute`
wrapper exist, are declared by the four CLI effects, and are what a future
effect would use to widen its roots in one reviewable place. But every
enforcement site also carries its own *unconditional* home check — deliberately,
so that closing DEF-007 does not depend on a caller upstream having remembered
to declare anything — and `installOne` self-declares this home when nothing is
in force. So **removing the declaration entirely reddens nothing**: measured,
`ALL PASSED`.

That is disclosed rather than dressed up. The alternative — making the
declaration load-bearing by deleting `installOne`'s fallback — would have
weakened real safety (a direct `installOne` call would be unguarded) to make a
vacuity row redder, and that is the wrong trade. The seam is kept because the
plan's slice and `conflict_keys` both name it and because widening is a real
future need; it is labelled as a seam in its own javadoc.

**3. The after-the-fork check cannot un-write bytes.** Nothing in this JVM can
see what a forked producer writes. What the boundary does is refuse to *hand* a
producer an escaping destination — which is prevention, and is what rows C, D
and E assert — and refuse the install if the artifact still resolved outside.
For the one path that reaches the producer over a *sanctioned* mirror, `force`,
the fix is prevention too (take ownership first). The residual case is a
producer that writes some path nobody declared; that is unobservable and is not
claimed.

**4. Instance (3) is a REPORT, per the plan, and it now fails LOUDLY where it
used to fail silently.** `bootstrap-home.sh` and `skt` resolve their CLI from
the root home; on a machine where PATH's `skill-manager` is a home shim, their
`env SKILL_MANAGER_HOME=<target> "$CLI" sync` now exits **79** with both homes
named instead of syncing the wrong home. It does not break a working path — that
path was already failing, and ARTI-14 measured 58 of 207 downstream assertions
falling over — but it is a behavioural change in another repository. Filed as
**DEF-014** for HIS-8, with the one-line remedy (`--home <target>`) already
asserted in both the unit suite and the graph node.

**5. `sync --force-scripts` in a child home now rebuilds locally instead of
writing through — a deliberate partial walk-back of HIS-10 (#227) for that flag
only.** HIS-10's measured win was a clone's first sync going from 5 pruned / 6
installed (~90s) to 0 pruned / 2 installed. That number is **unchanged**: the
ownership arm is gated on `force` and nothing else, so a routine sync over a
sanctioned mirror is untouched. What changes is that `--force-scripts` now
rebuilds the skill-script artifacts a child had been sharing. The alternative
for a forced run is the producer following the mirror into the parent store and
rewriting the parent's file, which is the HIS-7 damage. Recorded as a
**narrowing, not a regression** — **DEF-015**, which names #227 explicitly so
nobody discovers it later as one.

**6. The root tier is covered by a SYNTHETIC root-shaped fixture.** A store that
is nobody's child, built under a temp directory. The operator's real root home
was not read, written, or used as a fixture at any point in this ticket, and
that is a deliberate limit on what "the root tier is covered" claims: it covers
the *shape*, not that particular home.

**7. A confinement refusal has NO exit code of its own, and one was written
before it was cut.** Every sibling refusal here carries one —
`NotAHomeException` 2, `FrozenHomeException` 9, `GitFetcherException` 10,
`HomeSync` 12 — so this class got `EXIT_CODE = 13` and a dispatch branch in
`SkillManagerCli.handleExecutionException`. Then the callers were traced: every
production path that can raise it (`CliShimPruner.prune`,
`InstallerRegistry.installOne`, `takeOwnershipOfShim`) runs inside an effect,
and `LiveInterpreter.runEffects` traps `Exception` and turns it into a failed
receipt. **The branch was unreachable** — a contract with no caller, which is
the shape this epic keeps filing findings about. Both were removed and the
reason is written into the exception's javadoc where the constant used to be.
A sync that refuses exits 1 today; giving the refusal its own status means
mapping receipt kinds to exit codes, which is wider than this slice.

What is NOT cut is the exception being **unchecked**, and that is the part with
teeth: both DEF-007 call sites sit inside `catch (IOException)` handlers that
log a warning and carry on, and a checked exception would have been caught by
exactly those and turned back into a log line. Vacuity rows A and B are that
property.

**8. `home verify` and the pruner still disagree about one thing, and it is now
the safe direction.** `home verify` reports `FOREIGN_HOME bin/cli` and exits 1;
the prune refuses and the sync fails. Both refuse; neither repairs. **Repairing
a home already in this shape is HIS-13 (#159)**, explicitly out of scope here.

## THE BEHAVIOUR CHANGE AN OPERATOR WILL ACTUALLY MEET

**A home whose `bin/cli` is a symlink at another home's cannot be synced at all
any more. `sync` fails, every time, until a person repairs it by hand — and
nothing this ticket ships will repair it for them.**

That is availability traded for bytes. It is the right trade and it is the trade
the epic's goal statement asks for, but it is a real cost and it belongs in front
of a reader rather than in a footnote, so here is exactly what it looks like.

### Before

```
$ SKILL_MANAGER_HOME=<homeB> skill-manager sync
✓ resolve: 0 unit(s)
cli: pruned bin/cli/alpha — resolved into the home at …/homeA, which is not this home's parent store
cli: pruned bin/cli/beta  — resolved into the home at …/homeA, which is not this home's parent store
✓ synced 1 unit(s)
exit 0
```

Exit **0**. The sync succeeded. `homeA/bin/cli` went from 2 entries to 0 and
nothing in that output says so.

### After

```
$ SKILL_MANAGER_HOME=<homeB> skill-manager sync
✓ resolve: 0 unit(s)
✗ × InstallCli: refused: sync's bin/cli prune would list and delete through a path outside the home it was given
  path:     …/homeB/bin/cli
  resolves: /private/…/homeA/bin/cli  <- outside the home, through a symlink
  home:     …/homeB
! sync rolled back 2 effect(s) — store + gateway state restored
exit 1
```

Exit **1**, and it will exit 1 on every subsequent attempt too.

### What the operator is left holding

- **`sync` is unusable in that home** until the shape is gone. Not degraded —
  the `InstallCli` effect fails and the program rolls back, so the CLI install
  pass never runs and no unit's tools get provisioned.
- **`home verify` agrees** — exit 1, `FOREIGN_HOME bin/cli` — so at least the two
  readers now say the same thing, which they did not before.
- **Neither of them fixes it.** There is no `home repair`. The manual remedy is
  to remove the link and put a real directory back:
  `rm <home>/bin/cli && mkdir -p <home>/bin/cli`, then re-run `sync`, which will
  re-provision this home's own tools. **That is a person typing `rm` on a
  Skill Manager home**, which is precisely the operation this epic exists to
  stop being necessary.
- **A home in this shape is reachable by hand or by a restored backup**, not by
  the product: `ChildHomeMaterializer.mirrorExistingShim` mirrors ENTRIES, never
  the directory. That is why DEF-007 was filed rather than allowed to stop
  HIS-10, and it is why the availability cost is expected to be rare.

**The alternative was not "sync keeps working".** It was "sync keeps working and
keeps deleting another home's toolchain, reporting success". Between a command
that refuses until a person looks and a command that succeeds while destroying
bytes, this ticket takes the refusal. **Turning that refusal into a repair is
HIS-13 (#159)**, and this is the concrete case it should be measured against:
a home that `verify` refuses, `sync` refuses, and nothing can currently mend.

## One thing this file got wrong on the way, kept rather than edited away

The first version of the guard used the entry rule (resolve the parent, keep the
leaf) for `CliShimPruner`'s `bin/cli` check, on the reasoning that a prune is a
delete and deletes do not follow the last component. It is a good rule and it is
the wrong question for a **container**: `<home>/bin` resolves inside the home
whatever `bin/cli` points at, so the check passed and **nothing was refused on
the exact fixture it had just been written for**. Three tier cases failed with
"the prune ran to completion in the wrong home" — which is how it was caught,
and the only reason it was caught is that the fixture asserts a *refusal
happened* rather than asserting the victim's byte count alone.

That is now `requireContainerInside`, a separate method with the distinction in
its javadoc, and vacuity row **G** is the mistake preserved as an executable
control.
