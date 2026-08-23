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

## 0. What the review of PR #244 found, and what it cost

**Two blockers, both measured on stock output, both aimed at the goal this
ticket claims to decide outright. Both were real.** They are recorded here
first, above the claims, because the first version of this document put a false
positive in its headline.

### Blocker 1 — a fresh, untouched, healthy clone was reported as damaged

```
home clone …            exit 0   "no path in it reaches another Skill Manager home"
home verify --home C1   exit 0
home repair --home C1   exit 1   PRUNED_INHERITED_ENTRY bin/cli/tofu     <- WRONG
```

Reproduced verbatim on this branch before touching anything.
`prunedInheritedEntries` read raw ledger outputs and never asked
`home.policy.toml`'s `lazy_artifacts` nor `Artifact.Materialization`. My javadoc
argued that "declared AND absent AND the parent has it" was not a normal state.
**A lazy home satisfies that conjunction from birth.** `bin/cli/tofu` was never
in that clone and was never pruned.

Two things make it worse and both are the reviewer's:

1. **The verdict depended on the operator's machine.** Eight artifacts were
   `declared-only` in that clone and exactly one fired, because the root store
   happened to hold that one binary. On a machine where helm/docker/k3d had been
   built, the same untouched clone reports five.
2. **My PR presented this false positive as its proof of real-world value.**
   *"found a real finding on this worktree's own home unprompted… which
   falsified my own written doubt"* — the worktree home is itself a lazy clone
   whose recorded parent holds `tofu`. Same false positive, and 1 of the 5 in
   the headline `cp -a` measurement. **That claim is withdrawn; see §2 for what
   replaced it.**

And the reason my suite could not catch it is worse than the bug:
`makeStale()` planted `bin/cli/never-built`, a name **no parent store holds** —
the one flavour of staleness the check could never report. The oracle for the
clause I am graded on was the only case that cannot fail it. **Ledger row 13.**

**Fixed** by asking production's own rule (`HomeCloner.partitionDeclared`'s
"both conditions, and neither alone") read in the accusing direction, and by
replacing the oracle with a discriminating pair. Measurement in §2.

### Blocker 2 — `--fix` broke a working entry point and then reported the home clean

`rewrite`'s javadoc said *"textual and exact — the whole absolute path, not a
prefix"*. `String.replace` on a path string **is** a prefix replace. Reproduced:

```
before  wrapper → "I am F foo run"                      run_exit=0
detect  2 findings; the second DECLARED UNREPAIRABLE
--fix   "repaired 1 of 2"
after   exec "D1/…/skills/foo/run"   <- no finding named this line
        run_exit=126, No such file or directory
detect  ✓ nothing is damaged                             <- green over the damage
```

Four failures in one run, and the fourth is the one that matters: **detection
afterwards was green**, so the entire safety story — "the check that was red is
now green" — could not see it. Against the goal's own words, *never destroys
bytes it did not itself create*, it overwrote bytes in a file it did not create,
on a line no finding named.

**Fixed in three layers** (whole-path occurrence, a postcondition on every
rewrite, and a re-scan between repairs), each with its own probe. Measurement in
§2.

### What this ticket still does NOT deliver

1. **It does not change `home verify`.** Two commands inspect one home and
   reach different verdicts. DEF-070, **rewritten** after M9: the agent-axis
   half is defensible separation and my first entry described *that* half as
   the finding. The half that was real — three readers, three answers about
   `bin/cli/tofu` — is fixed by blocker 1, and what remains is smaller.
2. **`PRUNED_INHERITED_ENTRY` is now narrow, and silent where HIS-9's incident
   happened.** No record on disk distinguishes "was here and was pruned" from
   "was declared and never built". **DEF-073**, with the candidate fix.
3. **Four of five conditions are repairable** (§1), not three — MINOR, corrected.
4. **The `tla-spec-dev` lifecycle still did not run**, and my explanation of why
   was substantially wrong. **DEF-069 is marked `corrected`, three clauses
   withdrawn, superseded by the epic agent's DEF-072.** §7.

## 1. What changed

| file | what |
| --- | --- |
| `src/main/java/dev/skillmanager/store/HomeRepair.java` | **new.** Detection and repair: **five conditions, four of them repairable.** |
| `src/main/java/dev/skillmanager/commands/HomeCommand.java` | **new subcommand** `home repair`. Reports by default; `--fix` is the only spelling that writes. |
| `src/main/java/dev/skillmanager/cli/CliMetadata.java` | the command catalog row (forced by `CliMetadataTest`, see §5). |
| `src/main/java/dev/skillmanager/cli/CommandHomeAccess.java` | `home repair` is **READ**, narrowed to WRITE only under `--fix` via the existing `INIT_GATED` mechanism. |
| `src/test/java/dev/skillmanager/store/DamagedHomeIsRepairableTest.java` | **new**, 19 cases, root-tier fixture. |
| `src/test/java/dev/skillmanager/cli/JsonContractTest.java` | the `--json` contract row (forced by HIS-15's guard, §5). |
| `src/test/java/dev/skillmanager/cli/LazyHomeScaffoldTest.java` | the read-only decoy probe (forced by HIS-14's guard, §5). |
| `test_graph/sources/home-integrity/DamagedHomeIsRepairable.java` | **new node**, five CLI processes. |
| `test_graph/build.gradle.kts` | one line, inside the `home-integrity` block. |
| `RunTests.java`, `RunHis13.java` | registration, and the one-suite runner the probes name. |
| `results/…/validation/vacuity-ledger.md` | **rows 12 and 13**, the counts, the mechanism-D section and three new work-list entries (M8). |

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

### The two blockers, measured after the fix

Same subjects, same commands, this branch.

**Blocker 1 — the same fresh clone `C1`, not one byte changed on disk:**

```
home verify --home C1   exit 0
home repair --home C1   exit 0   "nothing … is damaged in a way this command knows about"
```

Before the fix, `home repair` exited 1 on that home. The two instruments now
agree, which is the guard goal rather than a nicety.

And the regression is an assertion, in the sharpest form available — **one disk
state, judged twice**, with only the policy line differing:

```
lazy_artifacts = false   ->   PRUNED_INHERITED_ENTRY reported     (a home that deferred nothing)
lazy_artifacts = true    ->   not reported at all                 (the state every clone is in)
```

Three probes redden that assertion on its claim (V3, V5, V13).

**Blocker 2 — the reviewer's wrapper, rebuilt here:**

```
before  wrapper runs                                        run_exit=0
detect  2 findings: one repairable base, one UNREPAIRABLE longer path
--fix   "repaired 1 of 2"
after   wrapper still runs                                  run_exit=0     (was 126)
        exec line still names F/…/skills/foo/run                            (untouched)
detect  exit 1, still naming the path that is still wrong                   (was: green)
```

Every clause of the blocker is inverted: the unrepairable path is untouched, the
shim still runs, and detection afterwards is **red about what remains** instead
of green over it. Four probes redden that assertion on its claim (V2, V5, V14,
V19).

### The real-home measurement, corrected

The first version of this document led with *"`home repair` found a real finding
on this worktree's own home unprompted — `PRUNED_INHERITED_ENTRY bin/cli/tofu` —
which falsified my own written doubt about whether that shape occurs outside a
fixture."*

**That was the false positive.** The worktree home is a lazy clone whose
recorded parent holds `tofu`. **Withdrawn.**

What replaced it is better, and I did not go looking for it — it is what the
FIXED command reported on the same home:

```
MISANCHORED_AGENT_LINK .codex/skills/one-forty-five
  -> resolves into the store at /private/var/…/sm-145-<n>/operator/.skill-manager
MISANCHORED_AGENT_LINK .gemini/skills/one-forty-five   -> same
```

Real damage, in a real home, on the axis this epic is about — and the writer is
**`AgentProjectionFollowsHomeTest`, the #145 regression test**, whose subject is
that an operation on one home must not write another home's agent directories.
Reproduced deterministically with all five variables pinned at a scratch
directory: running that test alone leaves a dangling projection in whatever
`CODEX_HOME` and `GEMINI_HOME` name. Unset — the normal developer environment —
that is the operator's real `~/.codex/skills` and `~/.gemini/skills`. Filed as
**DEF-074**.

The operator's real agent directories were checked immediately and are clean
(mtime 2026-08-21, no link into any temp directory), because every run in this
session had those variables exported at the worktree. **That is luck of the
environment, not a guard**, and DEF-074 says so.

### The `cp -a` measurement, re-run

The headline measurement the reviewer reproduced exactly still holds, minus the
`tofu` line:

```
home verify --home <copy>     exit 0   "no path in it reaches any other home"
home repair --home <copy>     exit 1   4 agent projections still resolving into the SOURCE
home repair … --fix           exit 0   repaired 4 of 4
home repair … --fix (again)   exit 0   repaired 0 of 0
home repair --home <copy>     exit 0   a separate process, and it agrees
```

Four findings, not five. The fifth was the false positive.

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

## 5. Vacuity — 19 assertions, 19 probes, and a table that is now generated

**The first version of this section was the least trustworthy thing in the PR,
and the review was right about every clause of it.** It credited probes with
reddening claims when they had reddened preconditions (M4, on the keystone
DEF-067 assertion); it undercounted the precondition-reds by three assertions
and five events (M5); it counted 37 reds in V7 where 9 were this suite's (M6);
and its `.out` files could not have been produced by the committed harness (M7).

So the table below is **generated from the `.out` files**, not written by hand,
and the harness classifies each red rather than leaving that to my reading.

### Mechanism A, moved from convention to code

`probes.py` now labels every red **CLAIM** or **PRECONDITION** and writes both
counts into each probe's header. A precondition-red proves nothing about the
branch — the run failed on setup and the claim was never evaluated — and the
ledger has three instances of exactly that being counted as evidence. A probe
whose `claims` count is zero is printed with a warning and treated as a **failed
probe**, and `EXPECTED_GREEN` declares in advance the one probe that should
redden nothing.

**It found something on its first run.** `V11` had reddened before; after
blocker 1's fix it came back `claims=0`, because the new policy gate returns
before V11's line is reached. **Mechanism C** — the mutation stopped being
reachable, and a green run reads exactly like a passing check. It was re-aimed
and the assertion it should have had was written (`an EAGER home missing an
entry no parent holds…`). No careful transcript-reading was involved.

Two further countermeasures, both from review findings:

- an assertion line is matched against `labels.txt`, captured from a clean
  baseline run, so text matched *inside a failure message* is reported as
  `[NOTE] not an assertion of this suite` rather than counted. That is M6's 28
  phantom copies, structurally;
- the verdict is found by looking for it, so `BASELINE.out` and every `V<n>.out`
  carry a real one. That is M7.

### The matrix

**19 of 19 assertions have a claim-red.** The precondition column is recorded
and **not** counted as coverage.

| assertion | reddened on its CLAIM by | also reddened on a PRECONDITION by |
| --- | --- | --- |
| `BLOCKER 1: one disk state, two policies, and the verdict follows the policy` | V3, V5, V13 | — |
| `BLOCKER 2 postcondition: a rewrite that would break the shim is REFUSED` | V19 | — |
| `BLOCKER 2: a rewrite replaces a PATH, not a substring, and keeps the shim running` | V2, V5, V14, V19 | — |
| `DETECTION REPAIRS NOTHING — run it twice and the damage is still there` | V5 | — |
| `HIS-9's measurement target: a home whose bin/cli IS a link is NAMED` | V2, V12 | — |
| `M3: a broken CLI pin with no locatable build is reported, with the runnable remedy` | V4, V5, V17 | — |
| `M3: a projection this home cannot back is reported UNREPAIRABLE, not relinked` | V1, V7, V16 | — |
| `REPAIR IS IDEMPOTENT — the second run changes no bytes` | V6 | — |
| `REPAIR makes detection clean, and it is DETECTION that says so` | V1, V2, V3, V6, V7, V18 | V5 |
| ``home repair` reports and exits 1; `--fix` repairs and exits 0` | V1, V5, V6, V7, V18 | — |
| `a FORGED descent record buys no repair — the chain it names must re-derive` | V9 | V3, V5 |
| `a healthy ROOT-TIER home is reported clean, over a non-zero subject count` | V7, V10 | — |
| `a home damaged AFTER a repair goes red again — the verdict is not sticky` | V1, V5 | V6, V7 |
| `a merely STALE home is not reported as damaged` | V7, V10, V13 | — |
| `a repair cannot write outside the two axes of the home it was given` | V7, V8 | — |
| `all four damage shapes are reported, one line each, naming the repair` | V1, V2, V3, V4, V5 | — |
| `an EAGER home missing an entry no parent holds is not a repair this command can make` | V11 | — |
| `detection uses HIS-10's reader: a SANCTIONED inherited shim is not damage` | V2, V5 | V7, V10 |
| `the agent axis comes from the HOME and not from the ENVIRONMENT` | V1, V5, V7 | — |

### The probes

| probe | branch it moves |
| --- | --- |
| V1–V4 | one per damage shape: the agent-axis walk, the shim-text scan, the ledger×descent conjunction, DEF-012's pinned-build reader. Each still CALLS the branch it disables (`f(root, new ArrayList<>())`), so the mutated path is demonstrably reached |
| **V5** | **DEF-067's hazard, planted**: `detect` calls `repair` |
| V6 | repair's convergence — one repair per invocation instead of all |
| **V7** | the agent-axis derivation — HIS-14's defect planted in the repairer |
| V8 | the confinement roots — enclosing home root instead of the two axes |
| V9 | shape 3's sanction gate — the recorded snapshot trusted as a grant (#228) |
| V10 | `HomeCloner.unsanctionedForeignHome`'s sanction arm — the canonical reader |
| V11 | shape 3's "and the parent holds it" conjunction |
| V12 | the `bin/cli`-is-a-link guard (HIS-9's limitation) |
| **V13** | **blocker 1's fix**: the `lazy_artifacts` gate |
| **V14** | **blocker 2's fix, layer 1**: whole-path replacement, at the call site |
| V15 | **blocker 2's fix, layer 2 alone**: the postcondition. **Declared EXPECTED_GREEN** |
| V16 | M3: the branch deciding whether a mis-anchored projection is repairable |
| V17 | M3: `DANGLING_CLI_PIN`'s `live == null` arm |
| **V18** | **blocker 2's fix, layer 3**: the re-scan between repairs |
| V19 | `replaceWholePath`'s boundary predicate itself, inside the function |

### Three layers, and a probe that proves they are three

Blocker 2 is fixed in depth, and V14/V15/V18 show each layer separately:

- **V14** restores `String.replace` at the call site. The rewrite is then
  *refused by the postcondition* rather than shipping a broken shim — layer 2
  catching layer 1's failure, visibly.
- **V15** removes the postcondition alone and reddens **nothing**, because layer
  1 is correct and there is nothing for layer 2 to catch. Declared in advance as
  `EXPECTED_GREEN` rather than explained afterwards, which is the sentence a
  vacuous probe always gets written for it.
- **V18** removes the re-scan and reddens two claims: a repair loop acting on a
  list taken before the home changed.

### The V6 confession stands, and is now in the ledger where it belongs

The first V6 removed `rewrite`'s no-op guard and **reddened nothing** —
mechanism D, in this ticket's own harness, one ticket after the ledger named it.
Kept as `V6-VACUOUS.out`. **M8: it is now row 12 of
`validation/vacuity-ledger.md`**, with the counts, the self-caught ratio and the
mechanism-D section updated, rather than confessed only in this document. Row 13
is the reviewer's finding about `makeStale()`, and the two are written to be read
together: *a probe suite can be large, honest, and entirely blind, when every
probe is read against one oracle and that oracle cannot express the defect.* Six
probes reddened on the arm blocker 1 lived in. All six were reading the same
degenerate oracle.

### The byte-equality half is still unfalsified, and one thing is now covered

`REPAIR IS IDEMPOTENT` asserts the second run reports nothing to do (V6 reddens
it) *and* that the bytes did not move. The second half still has **no mutation
that reddens it alone**, because production performs no writes when the report
is clean. Unchanged from the first submission, and still stated rather than
counted.

What is no longer true is the claim that two assertions redden only on
preconditions. After M4's correction to `DETECTION REPAIRS NOTHING` — where the
byte-equality assertion now runs *before* the verdict assertion, so V5 reddens
the claim it was always credited with — every assertion has a claim-red.

### Three mechanised guards caught this change, and a fourth caught the fix

| guard | what it caught | owner |
| --- | --- | --- |
| `JsonContractTest.every_json_command_is_covered` | `home repair` declared `--json` with no contract row | HIS-15 |
| `CliMetadataTest.metadata_command_catalog_matches_picocli` | no catalog row for it | pre-epic |
| `LazyHomeScaffoldTest.every_read_only_command_has_a_probe` | a READ command with no decoy probe proving it scaffolds nothing | HIS-14 |
| `probes.py`'s zero-claims warning | **V11 going vacuous as a side effect of blocker 1's fix** | this ticket |

## 6. Validation

Every number below was produced on this branch after the rework, rebased onto
epic tip `576fa0b`.

### Declared

```
uv run pytest specs/                                      38 passed
jbang RunTests.java                                       ALL PASSED, 0 failures
                                                          (CLEAN ENVIRONMENT — see below)
python skills/test_graph/scripts/run.py home-integrity     18/18   run 20260823-222322
tla-spec-dev --spec-root specs open/close ticket HIS-13    not run — DEF-069 / DEF-072
```

The node `home.integrity.damaged.home.is.repairable`, in that run:
**12 of 12 assertions passed**, `findingsAtFirstDetect = 2`,
`findingsAfterRepair = 0`, `detectRunsBeforeRepair = 2`,
`detect=1,1 repair=0 detect-after=0 repair-again=0`.

TLC: **N/A** per the assignment — no new invariant; HIS-5 carries the model work.

### `RunTests.java` and `checkout-home`, which the review could not reproduce

The coordinator's note was right about the cause: the review protocol's
mandatory env pinning reddens unit cases that expect an unpinned environment,
which is not a property of this diff. Both were re-run here **with all five home
variables explicitly unset** (`env -u SKILL_MANAGER_HOME -u CLAUDE_CONFIG_DIR -u
CLAUDE_HOME -u CODEX_HOME -u GEMINI_HOME`):

```
jbang RunTests.java          ALL PASSED, 0 failures
run.py checkout-home         8/8      run 20260823-222706
```

So the number is mine and it is green. **And running it that way produced a
finding**: it is the run that let me establish DEF-074's real scope — with those
variables unset the #145 test writes nothing into the operator's agent
directories, which is the opposite of what my first draft of that entry claimed.
The env-pinning artefact is not just noise; it is the difference between two
behaviours, and only running both told me which.

### The second set, and why these graphs

`home-clone` and `checkout-home`. The production files this ticket edits are
`HomeCommand` (a new subcommand), `CliMetadata` and `CommandHomeAccess` (one row
each), plus the new `HomeRepair`. `CommandHomeAccess` is the one with reach: it
decides, for **every** CLI invocation in **every** graph, whether `HomeScaffold`
lays a home out before the command runs, and this ticket narrows its
`INIT_GATED` map. The two graphs that drive `home clone` / `home verify` /
`home close-out` end to end over real multi-tier homes are the ones whose
fixtures exercise it.

```
run.py home-clone            14/14    run 20260823-222529
run.py checkout-home          8/8     run 20260823-222706
```

**`--all` was NOT run** — owner's instruction; it belongs to HIS-6.

### The graph node's own two probes, re-run against the reworked source

| probe | branch it moves | node assertions reddened |
| --- | --- | --- |
| **G1** | the repair itself — `apply` suppressed, detection untouched | the four repair-outcome assertions. `findingsAfterRepair` **0 → 2**. `DETECTION_ALONE_REPAIRS_NOTHING` correctly stays green |
| **G2** | **DEF-067's hazard planted at the process boundary** — `detect` calls `repair` | `DETECTION_ALONE_REPAIRS_NOTHING` and the three "bare detect refuses / names" assertions. `findingsAtFirstDetect` **2 → 0**. The four repair-outcome assertions correctly stay green — *because the home did get repaired, by the observer* |

Still **disjoint**, which is the per-branch control discipline rather than one
control claimed for two. G1's mutation had to be re-aimed after the repair loop
was rewritten for blocker 2, and the harness's `count == 1` assertion is what
caught that it no longer matched — mechanism C, again, mechanically.

### Homes

Checked after every probe run, after the clean-environment suite, and before
commit:

```
find ~/.skill-manager ~/.claude ~/.codex ~/.gemini \
     /Users/hayde/IdeaProjects/skill-manager/.skill-manager \
     -maxdepth 3 -newermt "-N minutes"                     ->  no output
```

plus issue #159's own detection snippet (every link under the three agent
directories must resolve into `~/.skill-manager/skills`) — silent. `~/.codex/skills`
still carries its 2026-08-21 mtime. **No write to the ROOT home, the PROJECT
home, the operator's three real agent directories, or any sibling `wt-*`
worktree, at any point.**

`<wt>/probe/` was deleted after every use; disk went 22% → 23% → 23%. The two
leaked `one-forty-five` links in THIS worktree's home (DEF-074) were removed by
hand, which is what the finding's own remedy says to do, and `home repair` on
that home now exits 0.

## 7. Deferred

Budget 5. **Five used**, and one of the five is my own correction rather than a
new finding.

| id | severity | status | what |
| --- | --- | --- | --- |
| **DEF-069** | major | **corrected** | `tla-spec-dev` could not parse `ticket_plan.yaml`. The MECHANISM was real and is fixed on the epic branch. **Three clauses were false and are withdrawn**: it was not "all epic" (the parser broke 2026-08-22; waves 1–5 parsed), it is not why HIS-14 skipped (that document names a different cause), and `skt status` uses its own reader. Superseded by **DEF-072**. |
| **DEF-070** | major | rewritten | `home verify` vs `home repair`. **M9 was right that my entry described the wrong half.** The agent-axis case is defensible separation; the real conflict was three readers giving three answers about `bin/cli/tofu`, and blocker 1's fix removes it. What remains is smaller and is stated as such. |
| **DEF-071** | major | open | A confinement whose roots come from the same function as its targets protects nothing. From probe V7. Unchanged. |
| **DEF-073** | major | open | **New.** Nothing on disk distinguishes "this entry point was pruned" from "it was declared and never built", so shape 3 is now correctly narrow and silent exactly where HIS-9's incident happened. Carries the candidate fix and a warning to whoever later "improves" the check. |
| **DEF-074** | major | open | **New.** `AgentProjectionFollowsHomeTest` leaves a dangling projection in an explicitly-set `CODEX_HOME`/`GEMINI_HOME` — the configuration this epic's own protocol mandates. Found by `home repair` on its own worktree home. |

### On DEF-069, since the coordinator asked me to argue where I disagree

**I do not disagree. The reviewer is right and the finding is worse than a
mistake — it is this epic's signature failure, committed by the agent shipping
the detector for it.** I hit a parse error on the current tip, found HIS-14's
document saying "the workflow was not run", and inferred one cause for both. I
never ran the parser against an earlier commit (a two-minute check) and never
opened HIS-14's document to see it names a different cause. Then I filed it at
`blocking` and escalated it.

My own ticket's rule is *"an assertion whose red you have not personally seen
does not count as delivered."* A backlog entry is an assertion. I did not apply
the rule to my own prose. That reflex is recorded in the entry itself, under
`how_i_got_it_wrong`, because the count of these is the thing HIS-6 reports.

**And it recurred while fixing the review.** My first draft of DEF-074 claimed
the test writes the operator's real `~/.codex` in a normal environment. I
measured instead of asserting: with all five variables unset, it writes nothing
there. The clause was withdrawn before the entry shipped. Same reflex, ninety
seconds of measurement, caught this time.

## 8. What I am unsure about, and where I disagree

### Where I disagree with the review — one place, and it is small

**MINOR: "PR/doc say base `6ee45b1`; `git merge-base` says `0f1a9a4`."** Both
were true when written and neither is now: the branch has been rebased twice
since, and the base is `576fa0b`. I have made the document name the tip it was
last rebased onto rather than a base sha at all, because on a serialized
promotion branch that number is stale the moment the predecessor merges. If the
epic wants a fixed base recorded, it should come from the assignment block,
which says `76434c7`.

Everything else in the review I accept, including both blockers, M2's withdrawal
of three of my own clauses, and M9's finding that my DEF-070 described the wrong
half of its own disagreement.

### What I am still unsure about

1. **`PRUNED_INHERITED_ENTRY` is now nearly dead, and I cannot prove it is
   useful.** It fires only on a home with `lazy_artifacts = false` — the
   operator root by default, which has no recorded parent store, so in practice
   only a home whose operator explicitly turned laziness off. I have a passing
   assertion for it and no field sighting. The honest options were "narrow it"
   or "delete it"; I narrowed it and filed DEF-073 rather than delete a shape
   the ticket named. **A reviewer may reasonably say delete it.**

2. **`absolutePathTokens` is still a scanner I wrote.** The verdict is
   production's in every case, and the rewrite now has a postcondition behind
   it, but the extraction is mine and its stop set is copied from
   `HomeCloner.scanFor` rather than shared with it. A shim quoting a path in a
   way neither anticipates is a silent false negative. I still cannot think of a
   completeness assertion that is not itself a second scanner.

3. **The graph node covers two conditions of five.** Disclosed in its javadoc.
   Unchanged, and it is the DEF-046 shape in my own node.

4. **`replaceWholePath`'s boundary class is a judgement call.** Letters, digits,
   `.`, `_`, `-`, `/`. A path containing a space, or a `+`, or a unicode
   letter — `Character.isLetterOrDigit` handles the last — sits outside what I
   reasoned about. V19 reddens the predicate; nothing establishes that the
   predicate is *right*, only that it is load-bearing.

5. **Blocker 1 is the second time this ticket's shape-3 arm has been wrong.**
   First the sanction disjunction (found by my own test), now the policy gate
   (found by a reviewer, on stock output, after six of my probes reddened
   against it). I do not have a reason to believe the third version is right
   beyond "I now consult both records production consults". That arm has the
   worst track record in the change and it is the one I would look at first.

6. **What the `cp -a` repair actually leaves behind.** It re-points four agent
   projections at this home's own store, and detection then agrees. I have not
   verified that an agent session in that home *loads* those skills — only that
   the links resolve where they should. "The link is right" and "the harness
   works" are different claims, and I am only making the first.
