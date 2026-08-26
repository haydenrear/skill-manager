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
| ~~F~~ | ~~`checkDelete` follows the leaf~~ | **WITHDRAWN — it reddened a DEAD predicate.** `checkDelete` had **zero** production callers; the delete rule the product actually runs is `requireInside`'s. Row **K** is the same disable against the live method, and it is the one that counts. `checkDelete` has been deleted rather than left as reachable-looking API. |
| **G** | `requireContainerInside` does **not** follow the leaf (the bug the first version had) | **4 FAIL** — all three prune tiers **and** the producer's `bin/cli`-is-a-link case |
| **H** | the shim's home-mismatch refusal removed | **1 FAIL** — *"it refuses with its own status, not the self-exec one and not 1: expected <79> but was <0>"* |
| **I** | the produced-artifact exemption removed | **2 FAIL** — *"a SANCTIONED mirror is not refused"* and *"an artifact linked OUTSIDE every home is left alone — brew's case"*, both *"refused: the stub-producer backend's artifact for tool would write a path outside the home it was given"* |
| **J** | the shim's `--home` escape removed | **1 FAIL** — *"naming a home on the command line is never refused: expected <0> but was <79>"* |
| **K** | `requireInside` follows the leaf | **2 FAIL** — *"takeOwnershipOfShim still takes ownership when the home is intact"* and the forced-ownership case, both *"refused: taking ownership of bin/cli/… would reach a path outside the home it was given"* |
| ~~L~~ | ~~the effect-boundary declaration removed~~ | **ALL PASSED — nothing reddened, and the declaration has since been DELETED.** Reporting a seam that gates nothing was the right disclosure; keeping it was not. See "What was cut", item 2. |
| **M** | `CliInstallRecorder.run` swallows the refusal instead of re-throwing (the shipped behaviour, before review) | **1 FAIL** — *"THE BULK PATH refuses too, and does not tally it as one dep failing: expected a write-confinement refusal, and the install completed — the producer was handed a destination in another home"* |
| **N** | `--home` removed from `SyncCommand` (the state this ticket shipped it in) | **1 FAIL** — *"the printed remedy RUNS -- both spellings, against the real CLI: expected false: the CLI accepts the flag the refusal printed -- this is the assertion that would have caught `sync` not declaring --home"* |

Rows **G** and **K** are the ones that discriminate: between them they cover
three *different* containment rules that all look plausible and of which only one
combination is correct. A–E and H–J prove each enforcement site is reached.

**Rows M and N are the two adversarial review found, and neither could have been
caught by the rows above** — that is the coverage lesson, not just two bugs.
Every case in `ProducerStaysInsideItsHomeTest` called `installOne` **directly**,
so no disable over those calls could ever see that the **bulk** path — the one
every `sync` takes — swallowed the refusal into a log line and exited 0. And
every assertion about the shim's remedy read its **text**, so none could see that
the flag it printed did not exist. Both are now driven end to end: M through
`CliInstallRecorder.run`, N by executing both printed spellings against the real
CLI.

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
| `python skills/test_graph/scripts/run.py home-integrity` | **BUILD SUCCESSFUL, 15/15 nodes passed** (run `20260821-200059`, 5m 12s, after the review fixes) |

### The graph result — run `20260821-200059`, BUILD SUCCESSFUL, 15/15

`home-integrity` ran green end to end in 5m 12s on the reviewed tree. Both of
this ticket's nodes executed. It was run three times: `20260821-191337`, which
surfaced the coverage hole below; `20260821-192222`, which proved that fix; and
`20260821-200059` after the second review round changed the shim's refusal text,
which the `bootstrap.projects.target` node asserts on. The committed envelopes
are from the last of those. Verbatim envelopes are committed under
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
  Say which one you mean:
    --home /private/…/home-integrity/home   (this shim's home)
    --home …/home-integrity/scratch/bootstrap-target   (the home your environment names)
```

Both homes, neither guessed — and both spellings are now runnable on `sync`,
which is what row N executes.

The old assertion this replaced — `the_shim_overrides_an_inherited_skill_manager_home`
— would have gone red on this build. What it protected is intact and sharper:
the shim still never runs against the inherited home, and it no longer runs
against its own in silence.

### The law was checking 1 of 7 homes — DEF-020

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

Held at **3** on the third run (`20260821-200059`) as well.

Both homes verify **exit 0**, so this extends the law's reach rather than
importing a known-bad fixture into a shared post-condition — which is why
`homesRepaired` stays at 0 rather than the law running `sync --force-scripts`
against something.

The other four homes belong to the HIS-7 and HIS-10 nodes and are **DEF-020**.
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
  unguarded** — 173 direct mutation sites, filed as **DEF-017**.

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
package that imports nothing from `dev.skillmanager`. Filed as **DEF-017** with
two concrete options for the owner. Everything in this ticket is scoped to the
four enforcement sites named above.

An earlier draft of `WriteConfinement`'s own javadoc claimed enforcement at
`Fs.deleteRecursive`, which was never true — it was written while that was still
the plan and not corrected when the server-build constraint killed it. Caught on
re-read before the PR and fixed; recorded here rather than quietly edited,
because a false coverage claim in a guard's javadoc is exactly the kind of thing
a later reader trusts.

**2. The effect-boundary declaration is GONE, having been measured to gate
nothing.** `SkillEffect.writeConfinement`, its four overrides and the
`LiveInterpreter.execute` wrapper were the ticket's stated mechanism — "an
effect declares the roots it may write under". Row L measured that removing all
of it reddened nothing, because every enforcement site already re-derived the
home for itself. Adversarial review then found the same shape twice more:
`WriteConfinement.checkDelete` had **zero** production callers, and
`forHome`'s `alsoUnder` extra-roots parameter — the reviewable-exemption seam
acceptance item 2 asks for — had never been passed one.

With `alsoUnder` gone an override could not express anything the fallback did
not, so the whole declaration layer was **deleted** rather than kept as
enforcing-looking API. Three public entry points remain and all three have
production callers.

**Acceptance item 2 is still met, by a better mechanism than the one I built.**
The two exemptions that exist — a sanctioned mirror, and a path outside every
home — are decided by asking `HomeCloner.unsanctionedForeignHome`, the predicate
`home verify` itself uses. An exemption expressed as the gate's own predicate
cannot drift from what the gate refuses; a parallel list of roots is exactly the
second spelling this epic keeps paying for.

<details><summary>What the earlier draft of this section said</summary> `SkillEffect.writeConfinement` + the `LiveInterpreter.execute`
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

</details>

**The disclosure was right and the decision was wrong**, and the difference is
worth naming: reporting that a mechanism gates nothing is not a substitute for
removing it. "Widening is a real future need" was speculation; two reviews later
there was still no caller.

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
**DEF-018** for HIS-8. Its remedy is `--home <target>` — **which this ticket had
to add to `sync` and `project sync` to make true.** Those were the only two
verbs a home-mismatch refusal needs to be able to recommend and the only two
that lacked the flag, so the refusal printed advice the product answered with
`Unknown option: '--home'`. Now asserted by executing both printed spellings
against the real CLI (row N).

**5. `sync --force-scripts` in a child home now rebuilds locally instead of
writing through — a deliberate partial walk-back of HIS-10 (#227) for that flag
only.** HIS-10's measured win was a clone's first sync going from 5 pruned / 6
installed (~90s) to 0 pruned / 2 installed. That number is **unchanged**: the
ownership arm is gated on `force` and nothing else, so a routine sync over a
sanctioned mirror is untouched. What changes is that `--force-scripts` now
rebuilds the skill-script artifacts a child had been sharing. The alternative
for a forced run is the producer following the mirror into the parent store and
rewriting the parent's file, which is the HIS-7 damage. Recorded as a
**narrowing, not a regression** — **DEF-019**, which names #227 explicitly so
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

## The contract with HIS-12 (#161), stated so it can be checked

HIS-12 is changing printed remedies to bind per verb, and its
`CliSource.HOME_ENTRYPOINT` reasons that a home's own shim binds its home, so it
prints a **bare** `<home>/bin/cli/skill-manager <cmd>`. Against this ticket's
guard that spelling exits **79** in any shell exporting a different
`SKILL_MANAGER_HOME` — which is the normal state in every ticket worktree — at
ten call sites.

**What this ticket guarantees, and what HIS-12 must do to land on it:**

1. **The guard never refuses a command line carrying `--home`.** Both spellings
   are exempt — `--home <x>` and `--home=<x>` — checked by scanning `"$@"`
   before any home comparison. A remedy that carries `--home` is safe by
   construction, whatever the environment says.
2. **`--home` now exists on every verb a remedy needs.** It was declared on
   `home`, `exec` and `unit`; this ticket adds it to **`sync`** and
   **`project sync`**, which were the only two missing and the two that a
   home-mismatch refusal most has to recommend. There is still **no global
   `--home`**: a remedy naming any other verb must not assume one.
3. **The refusal names both homes and chooses neither.** It prints
   `--home <this shim's home>` and `--home <the environment's home>` and lets
   the operator pick. A shim cannot infer which was meant — a `#!` script never
   sees the word that was typed, and the damage case is itself an absolute
   invocation — so it does not try.

So HIS-12's `HOME_ENTRYPOINT` class needs its remedy to carry `--home <that
home>` rather than being bare. That is one flag on a spelling it already
computes, and it makes the remedy correct whatever `SKILL_MANAGER_HOME` the
reader's shell happens to export — which is a property the bare form never had,
independently of this guard.

## What adversarial review found that this file had not disclosed

Five things, all of them mine, none of them caught by my own vacuity rows.

**1. The bulk path swallowed the refusal and `sync` exited 0.** Fixed and
covered by row M. See the vacuity section for why no existing row could see it.

**2. The printed remedy named a flag that did not exist.** Fixed by adding
`--home` to `sync` and `project sync`, and covered by row N.

**3. Three of `WriteConfinement`'s five public entry points were inert.** Fixed
by deleting them; see "What was cut", item 2.

**4. `LAUNCHER_PIN` — issue slice item 4 — is NOT IMPLEMENTED, and was not
declared cut.** The issue asks for the `LAUNCHER_PIN` exemption `CliShimPruner`
carries to be mirrored "wherever the guard could reach `bin/cli/skill-manager`".
Nothing in this ticket does that. It is **declared cut here**, with the reason:
the guard's two delete sites cannot reach the pin — `CliShimPruner` skips it by
name before any confinement question is asked, and `takeOwnershipOfShim` only
ever addresses a declared dep's `on_path`, which is never `skill-manager`. So
there is no reachable case to exempt, and adding an exemption for one would be
untestable by construction. If a future writer *can* reach the pin, this needs
revisiting; it is not covered today and no assertion pretends it is.

**5. Acceptance (d) says a child may READ through a sanctioned mirror. Nothing
here reads.** What is asserted is that the mirror is **not deleted** (prune), not
refused before a producer (pre-check), and not refused after one (post-check).
Those are three write-side statements. No assertion resolves, opens or executes
through a mirror. The sharing contract is therefore covered *as far as this
guard can break it* — which is the honest width — and the read path is untouched
by this ticket because the guard never consults reads. Stated rather than
counted as met.

### Two more, about the assertions themselves

**`BootstrapProjectsTheTargetHome` half 2 does not exercise the mismatch
branch.** It sets the inherited home to the shim's *own* home, so the refusal
never fires and row J's disable leaves that half green. It still asserts
something real — `--home` addresses the named home — but it is not a second
witness for the refusal. The unit suite is (row N).

**That node also LOST an assertion, and I did not say so.** The re-aim replaced
`the_shim_binds_the_home_it_lives_in` — which was **deleted, not renamed**. The
property it held is now carried by
`the_shim_never_runs_against_an_inherited_home` plus the unit case *"the cli
entrypoint binds the home it lives in when none was named"*, but the node itself
no longer asserts the positive binding. Recorded here rather than left for a
reader to notice the count went from five to six with one of the five missing.

**The three prune "tiers" run byte-identical code.** `requireContainerInside`
throws before anything tier-sensitive is consulted — no claim is read, no
provenance record, no sanction — so ROOT, PROJECT and WORKTREE exercise one
path three times. Reporting "3 FAIL" for row A overstates it: it is one
behaviour, asserted on three fixtures, and the fixtures differ in ways the code
under test never looks at. They are worth keeping — they prove the refusal does
not accidentally depend on descent — but they are not three independent cases
and this file should not have implied they were.

**The vacuity `.out` files are curated.** Each holds the `[FAIL]` lines and the
`FAILURES:` count for its run, not the full suite output, so a reader can see
*which* cases failed but cannot confirm from the file alone that **only** those
failed. The counts are in the files and they are small; a reader wanting the
whole log has to re-run the disable. Said plainly rather than implied.

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
