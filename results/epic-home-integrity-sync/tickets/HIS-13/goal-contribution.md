# HIS-13 — goal contribution

Issue **#159** · branch `feature/159-his-13` · base `6ee45b1` · wave **9** ·
promotion order **15** · predecessor **HIS-17** (merged, `d548f62`).

Goals: **GOAL-no-destructive-recovery** (`direct`, clause 2 outright, clause 3 as
anti-regression) and **GOAL-one-home-one-answer** (`guard`).

Declared expected effect, verbatim from the plan:
*"no detection and no repair -> one command names the damage and one repairs it,
idempotently"*
and, for the guard:
*"detection must use the same reader HIS-10 makes canonical, not a fourth one"*.

---

## 0. What this ticket does NOT deliver, up front

1. **It does not change `home verify`.** So this epic — whose second goal is
   that one home has one answer — now ships two commands that inspect one home
   and disagree: `home verify` exits 0 on a home `home repair` calls damaged.
   That is a real cost, it is deliberate, and it is filed as **DEF-070** rather
   than left for a reviewer to find. Folding four new checks into the command
   `HomeFixpointLaw` runs over every home in 24 graphs, in the last
   implementation wave, behind no flag, is how a guard becomes the hold-back
   `GOAL-no-spurious-holdback` forbids.

2. **It repairs three of the five conditions it detects.** A `bin/cli` that is
   itself a link out of the home, and a foreign path with no counterpart in this
   home, are **reported and not repaired** — see §3 and §4. Both say so on the
   line a person reads.

3. **The `tla-spec-dev` ticket open/close was not run**, because it cannot run:
   `open ticket HIS-13` aborts parsing `ticket_plan.yaml` before it does
   anything. Measured, root-caused, and filed as **DEF-069** — see §7. HIS-14
   recorded the same skip without the cause; this is the cause, and it has been
   blocking every ticket in the epic.

4. **The unit suite is where the coverage is.** The graph node adds one thing
   the unit test cannot express — five separate CLI processes — and covers two
   of the five conditions. Said in the node's own javadoc too.

---

## 1. What changed

| file | what |
| --- | --- |
| `src/main/java/dev/skillmanager/store/HomeRepair.java` | **new.** Detection and repair. 5 conditions, 4 repairable kinds. |
| `src/main/java/dev/skillmanager/commands/HomeCommand.java` | **new subcommand** `home repair`. Reports by default; `--fix` is the only spelling that writes. |
| `src/main/java/dev/skillmanager/cli/CliMetadata.java` | the command catalog row (forced by `CliMetadataTest`, see §5). |
| `src/main/java/dev/skillmanager/cli/CommandHomeAccess.java` | `home repair` is **READ**, narrowed to WRITE only under `--fix` via the existing `INIT_GATED` mechanism. |
| `src/test/java/dev/skillmanager/store/DamagedHomeIsRepairableTest.java` | **new**, 13 cases, root-tier fixture. |
| `src/test/java/dev/skillmanager/cli/JsonContractTest.java` | the `--json` contract row (forced by HIS-15's guard, §5). |
| `src/test/java/dev/skillmanager/cli/LazyHomeScaffoldTest.java` | the read-only decoy probe (forced by HIS-14's guard, §5). |
| `test_graph/sources/home-integrity/DamagedHomeIsRepairable.java` | **new node**, five CLI processes. |
| `test_graph/build.gradle.kts` | one line, inside the `home-integrity` block. |
| `RunTests.java`, `RunHis13.java` | registration, and the one-suite runner the probes name. |

`HomeCloner` was **not** modified. My declared production conflict keys were
`HomeCommand.repair` and `HomeCloner.verifyRoots`; I used the first and did not
need the second — detection reaches `verifyRoots`' logic through the public
predicate `HomeCloner.unsanctionedForeignHome`, which is the point of the guard
goal. (Probe V10 mutates `HomeCloner` and restores it; `git status` is clean of
it.)

### The three shapes the ticket named, and the two it did not

| kind | shape on disk | what `home verify` says | repair |
| --- | --- | --- | --- |
| `MISANCHORED_AGENT_LINK` | a **symlink** under `<home>/.claude\|.codex\|.gemini/{skills,plugins}` resolving into another home's store | **nothing** — the walk covers the STORE; the agent axis is outside it | re-point at this home's own unit |
| `FOREIGN_PATH_IN_SHIM` | a **regular file** under `bin/cli`/`bin/mcp` whose TEXT names a live path in another home | **nothing** — it resolves fine, it is simply wrong | rewrite that path to this home's counterpart |
| `PRUNED_INHERITED_ENTRY` | a **missing** file the artifact ledger declares and the recorded parent store still holds | **nothing** — nothing compared the live tree to `home.provenance.json` | re-link at the parent's own entry |
| `DANGLING_CLI_PIN` | a **regular file** — the home's own front door — pinning a build that is gone | **exit 0** (DEF-012, measured on the operator's root home) | re-pin at the running build |
| `FOREIGN_PATH_IN_SHIM` on `bin/cli` itself | the **directory** is a link into another home | reports the entries seen *through* it as several broken shims | **none — reported only**, §6 |

`DANGLING_CLI_PIN` is a fourth kind because **the ticket's own table is wrong
about DEF-012**. It calls DEF-012 an instance of "a shim rewritten with another
home's absolute paths". Measured, DEF-012 is a pin at
`/opt/homebrew/Cellar/skill-manager/0.23.0/...` that `brew upgrade` deleted:
that path is in no home and does not exist. DEF-012's own disposition says what
it needs — *"HIS-13's detection must cover references that LEAVE the home, not
only those inside it"* — and that is a different branch, so it gets a different
kind and its own fixture (a two-build fixture; see §5, where the one-build
version was measured repairing nothing).

### The design decision a reviewer should push on

**`home repair` with no flag is an observer, and that is enforced in three
places rather than promised in one.** DEF-067 is that `HomeFixpointLaw` parses
a remedy out of a refusal and *runs* it, so it can repair the condition it was
checking and report PASS. This ticket ships the repairer, so:

- `HomeRepair.detect` opens nothing for writing, and `repair` is the only entry
  point that mutates;
- `CommandHomeAccess` classifies `home repair` **READ** — narrowed to WRITE only
  when `--fix` matched at the leaf — so `tryReconcile` does not scaffold the
  home a detection run was merely asked about;
- the graph node runs detection **twice** against a damaged home before the
  repair and asserts the home is byte-identical afterwards.

Push on this: the third one is the only assertion that could catch a future
change, and it lives in a graph node rather than in the command.

---

## 2. Contribution to `GOAL-no-destructive-recovery`, clause 2

Metric (b): *whether a damaged home is detectable by a command rather than by an
operator noticing.*

| | baseline (`23e35c7`, per the plan) | after (`feature/159-his-13`) |
| --- | --- | --- |
| commands that name damage | **0** | 1 (`home repair`) |
| commands that repair | **0** | 1 (`home repair --fix`) |
| damage shapes detected | 0 of 3 named + 0 of 2 found | **5 of 5** |
| damage shapes repaired | 0 | **3 of 5**, idempotently |
| detection is separable from repair | n/a | **asserted**, 3 places |
| repair confined to the home given | n/a | store + its three agent dirs, and nothing else |

### The measurement that is not a fixture

Everything above could be true of a detector that only works on homes I built.
So it was pointed at a real one. Full transcript:
`probes/his-13/real-home-measurement.md`.

**This worktree's own home, read-only:** one finding, on the first try —
`PRUNED_INHERITED_ENTRY bin/cli/tofu`. Verified genuine before being believed:
`artifacts.lock.toml` declares `outputs = ["bin/cli/tofu"]` for `deploy-helm`,
the home does not hold it, and `~/.skill-manager/bin/cli/tofu` (the recorded
parent store, which still re-derives) does. **That answers §8's own "unsure #3"**
— the shape fires in the wild, on a home nobody constructed to make it.

**A `cp -a` copy of that home** — literally the case
`sanctionedParentShim`'s javadoc names as *"repairing such a home … is
HIS-13's"*:

```
home verify --home <copy>    exit 0   "every reference resolves, and no path in
                                       it reaches any other Skill Manager home"
home repair --home <copy>    exit 1   5 findings, 4 of them agent projections
                                       still resolving into the SOURCE home
home repair … --fix          exit 0   repaired 5 of 5
home repair … --fix (again)  exit 0   repaired 0 of 0, nothing changed
home repair --home <copy>    exit 0   a separate process, and it agrees
```

Exit 0 and exit 1 about one home a minute apart. That is clause 2 decided on a
real subject, and it is **DEF-070 measured live** rather than argued.

The source home was re-checked after every repair and is unchanged.

Clause 3, the anti-regression: `a merely STALE home is not reported as damaged`
is an assertion, over a fixture carrying an **unacknowledged drift record**, a
**unit whose bytes moved after it was recorded**, and a **declared-but-unbuilt
entry point**. All three are normal states and none is reported. Three separate
mutations redden it (V7, V10, V11), so it is not passing by being blind.

### Contribution to `GOAL-one-home-one-answer`, as a guard

"Which home is this path in, and may this home own it" is asked of
**`HomeCloner.unsanctionedForeignHome`** in every one of the five checks — the
same predicate `home verify` refuses on and `InstallerRegistry` exempts on.
Nothing in `HomeRepair` re-derives "is that another home", and nothing in it
decides a sanction: `HomeProvenance.sanctions` / `ChildHomeLink.isChildOf` do,
live.

Asserted in both directions, and on the **right branch**:

- `detection uses HIS-10's reader: a SANCTIONED inherited shim is not damage`
  builds a **grandchild** (a real `HomeCloner.cloneHome` of a claimed child) and
  asserts its inherited wrapper is clean; then deletes `home.provenance.json`
  and asserts **the same bytes** become a finding.
- `a FORGED descent record buys no repair` revokes the parent's claim, leaves
  the record naming the store, and asserts no `PRUNED_INHERITED_ENTRY` is
  produced and **no link into that store is created**. That is the #228 review's
  finding on the repair side, where it is worse: there a forged record switched
  a gate off, here it would have created paths.

**Mechanism D compliance, stated explicitly**, because this is the trap HIS-17
was caught by and damaged-home fixtures are exactly the shape that hides it. The
sanctioned-shim control is a **regular-file wrapper**, not a symlink decoy. A
symlink decoy would exercise a branch `verifyRoots` decides earlier, in a
different walk, and that `HomeRepair` does not decide at all. The branch under
test — `sanctionedParentShim`'s three conditions — is reached from the
regular-file side, which is the side this ticket moved.

---

## 3. The five inherited findings, each answered

The ticket deferred five things to me. I was required to state, for each,
whether my detection finds that shape.

| finding | does detection find it? |
| --- | --- |
| **DEF-014** — a `sync --merge` that SUCCEEDED reverted a dereferenced tree to a symlink into the parent store | **PARTLY, and only in the worst case.** If the restored link is ABSOLUTE and lands in the parent store, it is under `skills/<unit>/...`, not under `bin/cli` or an agent dir, so **no**: my walk does not cover unit content. If the tree is DELETED (that entry's MODE=B) it is a unit missing from a home whose `installed/` record names it — which is `HomeMembershipLaw`'s LOST direction, not mine. **Honest answer: no.** Covering it means walking unit content for foreign links, which is `home verify`'s territory and DEF-070's question. |
| **DEF-015** — the `MERGE_CONFLICT` probe clears an error whose damage remains | **NO.** That is a claim about what an `InstalledRecord` error KIND asserts, across nine kinds in an exhaustive switch. Nothing in it is a path. DEF-015's own text asks HIS-13 to treat it and DEF-012 as one class with two surfaces; I took **the DEF-012 surface** (`DANGLING_CLI_PIN`) and left the record surface, because the two share a lesson and not a mechanism. |
| **DEF-047** — the `project` family re-realized a worktree home, removing one unit and installing two | **NO, and it should not.** The residue is a home holding units its manifest does not name. That is a **membership** question, `HomeMembershipLaw`'s, and HIS-16 shipped it. Adding a second reader of it here is the exact defect HIS-17 exists to remove. |
| **DEF-054** — `HomeMembershipLaw`'s one detection mode reaching a real home depends on a separate bug (an orphaned `.projections.json`) | **NOT FIXED, deliberately, and the warning is taken.** DEF-054 points HIS-13 at that orphan. Fixing it would narrow a live instrument — HIS-16's law would stop firing for its one measured cause — in exchange for tidiness, in the last implementation wave, with no replacement coverage. The right move is the one DEF-054 itself names: *a decision about what replaces the coverage*, which is HIS-6's. What I did instead is make sure **no assertion I added rests on an accident**: every one of the thirteen has a named mutation and an observed red (§5). |
| **HIS-9's disclosed limitation** — *"a home whose `bin/cli` is a symlink at another home's can no longer be synced at all until a person repairs it by hand"* | **MEASURED. See §4.** |

---

## 4. HIS-9's sentence, re-measured

> *"A home whose `bin/cli` is a symlink at another home's cannot be synced at all
> any more. `sync` fails, every time, until a person repairs it by hand — and
> nothing this ticket ships will repair it for them."*

**After HIS-13 the first half is still true and the second half is half true.**

- **Still true:** nothing repairs it. `home repair --fix` will not, and that is a
  decision, not a gap. The only mechanical repair is to delete the link and put
  an empty directory in its place, which discards every entry point the home can
  currently reach. That is destructive recovery — the thing this ticket's own
  goal forbids — so the finding carries `repairable = false` and a remedy naming
  the two exits a person can actually take.
- **No longer true:** it is invisible. `home repair` names it, as **one finding
  about the directory**, in one line, saying what it is. Before, the condition
  presented as several `FOREIGN_HOME` findings on entries, or as a confinement
  message about a path.
- **And a hazard my own first draft had:** `Files.isDirectory` follows the link,
  so an unguarded walk descends into the *other* home, reports its entries under
  this home's `bin/cli/<name>` spelling, and offers to rewrite them. The write
  gate would have refused (`checkWrite` resolves the leaf), so it would have been
  noise rather than bytes — but a detector that reads another home's files in
  order to describe this one is the container-versus-entry distinction
  `WriteConfinement` records as *"getting it wrong is silent"*. It is silent
  here too. The guard is asserted, and probe **V12** reddens it.

Assertion: `HIS-9's measurement target: a home whose bin/cli IS a link is NAMED`.
It also asserts the other home is byte-identical after a repair run.

---

## 5. Vacuity — thirteen assertions, fourteen probes, one confession

Every probe: copy the production file aside, mutate, run `jbang RunHis13.java`,
restore **by copying the saved file back** (never `git checkout --`; DEF-035 has
now bitten four agents in this epic). The harness is
`results/epic-home-integrity-sync/probes/his-13/probes.py`; each probe's observed
output is `V<n>.out` beside it.

**Mechanism C is mechanised here:** the harness asserts each mutation's pattern
matched **exactly once** before applying it. It caught itself on its first run —
the four suppression probes were first written as `0 * f(...)`, which does not
compile (the methods return `int`), and jbang's failure reads exactly like a
suite of reds. They are now `f(root, new ArrayList<>())`, so the mutated path is
demonstrably **reached** and only its findings are dropped.

| probe | branch it moves | reddened |
| --- | --- | --- |
| **V1** | shape 1 — the agent-axis walk (the pre-HIS-13 world for that surface) | 5 of 13, incl. `all four damage shapes`, `REPAIR makes detection clean`, `the agent axis comes from the HOME`, the CLI test — **all claims** |
| **V2** | shape 2 — the regular-file shim TEXT scan | 3, incl. `detection uses HIS-10's reader` **on its claim** (*"with the record gone the SAME bytes are damage"*) |
| **V3** | shape 3 — ledger × descent record | 3; the forgery test reddens on a **precondition** here (mechanism A — counted as such, not as a claim) |
| **V4** | shape 4 — DEF-012's pinned-build reader | 1, `all four damage shapes` — the claim, and the only assertion that names this kind |
| **V5** | **DEF-067's hazard, planted**: `detect` calls `repair` | 8 of 13, incl. `DETECTION REPAIRS NOTHING` **on its claim** |
| **V6** | repair's convergence — one finding per pass instead of all of them | 4, incl. `REPAIR IS IDEMPOTENT` **on its claim** |
| **V7** | the agent-axis derivation — HIS-14's defect planted in the repairer | 37 (see below) incl. `the agent axis comes from the HOME`, `a repair cannot write outside the two axes` |
| **V8** | the confinement roots — enclosing home root instead of the two axes | 1, `a repair cannot write outside the two axes` — the claim |
| **V9** | shape 3's sanction gate — the recorded snapshot trusted as a grant (#228) | 1, `a FORGED descent record buys no repair` — **the claim** |
| **V10** | `HomeCloner.unsanctionedForeignHome`'s sanction arm — the canonical reader | 3; `detection uses HIS-10's reader` reddens on a **precondition** here (mechanism A) |
| **V11** | shape 3's conjunction — "declared and missing" without "the parent holds it" | 1, `a merely STALE home is not reported as damaged` — the claim |
| **V12** | the `bin/cli`-is-a-link guard | `HIS-9's measurement target` — **the claim** (*"ONE finding about the directory, not one per entry seen through it"*; without the guard the walk descends and reports the other home's entries) |
| ~~**V6 (first version)**~~ | ~~`rewrite`'s no-op guard~~ | **VACUOUS — 12 passed, 0 failed.** Kept as `V6-VACUOUS.out`. |

### The graph node's own two probes — and they redden DISJOINT sets

The unit probes redden the unit suite. These redden the NODE, and they are
separate because the node asserts one thing the unit suite cannot: that
detection and repair are separate PROCESSES. Harness:
`probes/his-13/graph-probes.sh`; outputs `G1.out`, `G2.out`.

| probe | branch it moves | node assertions reddened |
| --- | --- | --- |
| **G1** | the repair itself — `apply` suppressed, detection untouched | `a_separate_detection_run_after_the_repair_is_clean`, `the_projection_points_at_this_homes_own_store`, `the_wrapper_runs_this_homes_own_tree`, `running_the_repair_twice_changes_nothing_the_second_time`. `findingsAfterRepair` **0 → 2**. `DETECTION_ALONE_REPAIRS_NOTHING` correctly stays **green**. |
| **G2** | **DEF-067's hazard planted at the process boundary** — `detect` calls `repair` | `DETECTION_ALONE_REPAIRS_NOTHING`, `bare_home_repair_refuses_a_damaged_home`, `bare_home_repair_names_each_damage_shape`, `every_finding_names_the_repair_for_it`. `findingsAtFirstDetect` **2 → 0**. The four repair-outcome assertions correctly stay **green** — *because the home did get repaired, by the observer.* |

**G2 is the whole ticket in one table.** With the hazard planted, the home ends
up healthy and every outcome assertion passes; what fails is that the
*detection* run reported nothing, having quietly fixed what it was asked to
look at. That is DEF-067's third consequence — *"it can green a real defect"* —
reproduced deliberately, and the node catches it.

The two sets are **disjoint**. That is the per-branch control discipline
(mechanism D) satisfied rather than claimed: each probe reddens the assertions
downstream of the branch it moved and none of the others.

### The V6 confession, because it is mechanism D on my own harness

The first V6 removed `rewrite`'s "the path is no longer in this file" guard and
made it append a byte. **It reddened nothing.** `rewrite` only runs for findings,
and on the second repair the report is clean, so the branch is never reached.
Idempotence in this design is not held by a no-op guard at all — it is held by
detection coming back clean — so the mutation moved a branch the assertion is not
downstream of. That is precisely the mechanism HIS-17 was blocked on, one ticket
later, in the harness written by the agent who had just read the ledger entry
about it.

The re-aimed V6 moves **convergence** (one finding per pass) and reddens the
idempotence claim.

**And the byte-equality half is still unfalsified.** `REPAIR IS IDEMPOTENT`
asserts two things: the second run reports nothing to do, *and* the bytes did not
move. The first has V6. **The second has no mutation that reddens it alone**,
because production performs no writes when the report is clean, so there is no
branch to break. It is a guard against a *future* repairer that writes
unconditionally, and it is worth exactly that and no more. Said here rather than
counted as covered.

### Two assertions whose claim never reddened

- `a home damaged AFTER a repair goes red again` reddens under four probes, and
  **every one of them reddens its precondition** (`precondition: repaired`), not
  its claim. There is no mutation for the claim, because production keeps no
  verdict to be sticky. Mechanism A, self-declared: it is a guard against a
  future cached verdict.
- `every finding names its repair` (inside `all four damage shapes`) is a
  structural assertion over the record; no mutation targets it specifically.

### The probes that were not mine — three mechanised guards caught this ticket

Worth recording, because the ledger's honest read is that eleven of twelve
countermeasures are enforced by care rather than by code. Three that *are* code
fired on this change, unprompted:

| guard | what it caught | owner |
| --- | --- | --- |
| `JsonContractTest.every_json_command_is_covered` | `home repair` declared `--json` with no contract row | HIS-15 |
| `CliMetadataTest.metadata_command_catalog_matches_picocli` | the command catalog had no row for it | pre-epic |
| `LazyHomeScaffoldTest.every_read_only_command_has_a_probe` | a READ command with no decoy probe proving it scaffolds nothing | HIS-14 (#241 H1) |

The third is the sharpest: it forced an assertion that bare `home repair`
creates nothing in the home it is pointed at, which is the DEF-067 contract
restated at the CLI boundary. I would not have written it.

---

## 6. Validation

### Declared

```
uv run pytest specs/                                     38 passed in 1.92s
jbang RunTests.java                                      ALL PASSED (0 failures)
python skills/test_graph/scripts/run.py home-integrity    BUILD SUCCESSFUL, 18/18 nodes
                                                          run 20260823-211025
tla-spec-dev --spec-root specs open/close ticket HIS-13   CANNOT RUN — DEF-069
```

The new node, `home.integrity.damaged.home.is.repairable`, in that run:
**11 of 11 assertions passed**, `findingsAtFirstDetect = 2`,
`findingsAfterRepair = 0`, `detectRunsBeforeRepair = 2`.

TLC: **N/A** per the assignment — this ticket states no new invariant, and HIS-5
carries the model work for the epic.

`jbang RunTests.java` was **red twice before it was green**, and both reds were
mechanised guards catching this change rather than flakes:

```
run 1   FAILURES: 2   JsonContractTest  — `home repair` declares --json, no contract row
                      CliMetadataTest   — no catalog row for `home repair`
run 2   FAILURES: 1   LazyHomeScaffoldTest — a READ command with no decoy probe
run 3   ALL PASSED
```

### The second set, and why these graphs

`home-clone` and `checkout-home`.

**Why these two and not others.** The production files this ticket edits are
`HomeCommand` (a new subcommand), `CliMetadata` and `CommandHomeAccess` (one row
each), plus the new `HomeRepair`. `CommandHomeAccess` is the one with reach
beyond the new command: it decides, for **every** CLI invocation in **every**
graph, whether `HomeScaffold` lays a home out before the command runs. Adding a
row cannot change another command's row, but the `INIT_GATED` map it narrows is
consulted on every parse, so the two graphs that drive `home clone` /
`home verify` / `home close-out` end-to-end over real multi-tier homes are the
ones whose fixtures exercise what I touched.

```
python skills/test_graph/scripts/run.py home-clone       BUILD SUCCESSFUL, 14/14
                                                          run 20260823-211248
python skills/test_graph/scripts/run.py checkout-home    BUILD SUCCESSFUL, 8/8
                                                          run 20260823-211434
```

### `--all` was NOT run

Owner's instruction: it is multi-hour and belongs to HIS-6, which owns the one
terminal sweep with the goal scorecard.

### Homes

Checked immediately after the V7 probe, which is the run that could have done
damage (see DEF-071), and again before commit:

```
find ~/.skill-manager ~/.claude ~/.codex ~/.gemini \
     /Users/hayde/IdeaProjects/skill-manager/.skill-manager \
     -maxdepth 3 -newermt "-60 minutes"          ->  no output
```

and issue #159's own detection snippet — every link under `~/.claude/skills`,
`~/.codex/skills` and `~/.gemini/skills` must resolve into
`~/.skill-manager/skills` — printed nothing. `~/.claude/skills` still carries its
Aug 21 mtime. **No write to the ROOT home, the PROJECT home, the operator's three
real agent directories, or any sibling `wt-*` worktree, at any point in this
ticket.**

Every experiment ran in a temp directory under `$TMPDIR` (unit fixtures) or under
the graph's own sandbox (node). `specs/desired_program_model/ticket_plan.yaml` was
edited once, to test DEF-069's cause, and restored from a copy taken first;
`git status` shows it unmodified.

## 7. Deferred

| id | severity | what | owner |
| --- | --- | --- | --- |
| **DEF-069** | **blocking** | `tla-spec-dev open/close ticket` cannot parse `ticket_plan.yaml` — wrapped flow sequences. **No ticket in this epic can run its declared spec lifecycle**, and `skt status` has been reporting every HIS ticket as not-open for the whole epic as a consequence. | epic owner / spec-double-compiler |
| **DEF-070** | major | `home verify` and `home repair` now disagree about the same home. The cut is deliberate; the question is a goal question. | HIS-6 |
| **DEF-071** | major | A confinement whose roots come from the same function as its targets protects nothing. Measured from probe V7. | HIS-6 / HIS-9 |

Budget was 5; **three used**. DEF-069 is `blocking`, and the deferment policy for
this epic is `escalate` — it is escalated here in §0 and in the report to the
epic owner, not merely filed.

---

## 8. What I am unsure about

1. **`DANGLING_CLI_PIN`'s repair is the widest thing this command does.**
   `LauncherShims.write` rewrites all three agent launchers as well as the CLI
   entrypoint. It is confined to the store and it is exactly what `home shims`
   does, but it is the one repair that touches files the finding did not name.
   A reviewer may reasonably want it split.

2. **`absolutePathTokens` is a scanner I wrote.** The *verdict* is production's
   in every case, but the extraction is new, and its stop set is copied from
   `HomeCloner.scanFor` rather than shared with it. A shim that quotes a path in
   a way neither anticipates is a false negative, silently. I could not think of
   a way to assert its completeness that was not itself a second scanner.

3. ~~**Whether `PRUNED_INHERITED_ENTRY` will fire in the wild.**~~
   **ANSWERED, and it falsified the doubt.** It fired on the first real home it
   was pointed at — this worktree's own, `bin/cli/tofu`, declared by
   `deploy-helm` and absent — and the `cp -a` copy produced four
   `MISANCHORED_AGENT_LINK` findings that `home verify` called clean. See §2.
   The residual doubt is smaller and different: **a false-positive rate I cannot
   bound.** One real home, five real findings, all five verifiable by hand. That
   is one home.

4. **The graph node covers two shapes of four.** Deliberate and disclosed, but if
   the epic wants `home-integrity` to be the instrument that decides clause 2,
   the node is narrower than the claim — which is the DEF-046 shape, in my own
   node, and I would rather a reviewer decided that than me.

5. **DEF-069 means this ticket's spec lifecycle is unclosed.** I did not edit the
   plan file to unblock myself. If the epic wants it closed, the plan file needs
   normalising first, and that is the epic agent's file.
