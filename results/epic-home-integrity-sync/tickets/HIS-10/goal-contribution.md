# HIS-10 — goal contribution

Issue #227 (also closes #206). Branch `feature/227-his-10`, based on epic tip
`32b437b`.

> **Corrected after adversarial review of #228.** The first version of this file
> made a claim it could not support, and the review measured it false. What
> follows is the revised, measured claim; the correction is written out below
> under "The claim I got wrong" rather than quietly edited away.
>
> The mechanism changed with it: the record no longer *stores* the sanction, it
> *points* at evidence that is re-derived live on every read.

## What this delivers

**A clone records the CHAIN it descends from, every reader re-derives that chain
live, and a clone keeps the toolchain it inherited instead of rebuilding it.**

The record is a pointer, not a grant. `HomeProvenance.sanctions` walks
`clonedFrom` hop by hop and asks `ChildHomeLink.isChildOf` at each one, against
the parent's own store, at the moment the question is asked. Nothing trusts the
`parentStores` set the record carries — that is a snapshot, kept for reporting
and for HIS-13's repair.

### The four-reader matrix, measured, before and after

Same probe both times: clone `/Users/hayde/IdeaProjects/skill-manager/.skill-manager`
(the project home — a *registered* child of the root store) into a scratch
directory, then run each reader. Baseline is the committed evidence at epic tip
`23e35c7`; "after" was re-run on this branch with the raw build.

| reader | before | after |
| --- | --- | --- |
| `home clone` | exit 0, "clean" | exit 0, clean |
| `home verify --home <clone>` (no flag) | **exit 1, 5× `FOREIGN_HOME`** | **exit 0**, "5 sanctioned parent-store shim(s)" |
| `home verify --home <clone> --against <src>` | exit 0 | exit 0 |
| `sync` in the clone | **5 shims pruned, 6 CLIs installed** (~90 s) | **0 pruned, 2 installed** |
| distinct verdicts over the matrix | **3** | **1** |

The two CLIs the after-run installed are `jinja2` and
`tracing-observability-install` — precisely the two the clone itself reported as
`declared: 2 entry point(s) name 'skill-manager build <id>'`, i.e. artifacts the
copy did **not** carry. Nothing already provided was re-provisioned. The clone
directory is **492 MB** after its first sync; the baseline run re-provisioned
five toolchains.

`home verify` now also prints the descent it read, in both spellings of the
command:

```
descent: this home was cloned from /Users/hayde/IdeaProjects/skill-manager/.skill-manager;
         1 recorded parent store(s), whose provisioned artifacts it shares rather than rebuilding
    /Users/hayde/.skill-manager
```

That line is the "…and **declares** them" half of the owner's contract. Evidence:
`probes/his-10/after-*.out`, `probes/his-10/after-home.provenance.json`.

### The bootstrap probe

A throwaway `git worktree add` bootstrapped with
`SKILL_MANAGER_CLI=<this build>/skill-manager`:

- exit 0, "verified: 4 skill(s) servable";
- **all five inherited shims still present** as links into
  `~/.skill-manager/bin/cli`;
- `home verify --home <new home>` with **no `--against`**: exit 0.

The operator's root home was **unchanged** — `root-after-his-10.txt` is
byte-identical to the committed `root-before.txt` for all five shims. Worktree
and its home deleted afterwards.

### The spelling half (#206)

`destReferences` did a literal `indexOf` of ONE spelling of the home root while
the symlink branch used the resolved one. Both spellings are now scanned and
every recovered path is rewritten to the root the **caller** named, so callers
that relativize a finding (`ArtifactBuild`'s remedy join) still get a
home-relative path, and substring-compatible spellings collapse to one finding
rather than two.

**Resolving the root once — which is what the ticket text asked for — would have
swapped the blindness rather than removed it.** A clone re-anchors its generated
files to the destination spelling it was *given*, so a home created at
`<s>/link/home` holds that spelling in its wrappers and a scan for
`<s>/realdir/home` alone would miss every one of them. Both spellings are real.

## What was cut, and what is not covered

**1. `--against` still grants a sanction to a home this build did not make.**
The HIS-7 `srcRoot` inheritance arm in `HomeCloner.sanctionedParentShim` was
**kept and demoted**, not deleted. For a home cloned by this build the
provenance branch answers first, so `--against` decides nothing. For a home
produced some other way — `cp -a`, an rsync, an older skill-manager —
`--against` is still the only thing that can excuse its inherited shims.
Deleting the arm would turn every pre-HIS-10 clone red on a command that used to
pass; that is a migration, and repairing already-made homes is **HIS-13**. The
existing `ChildHomeShimIsolationTest` case that covers exactly that byte-copy
shape still passes, unchanged.

So the acceptance line "`--against` no longer *decides* the answer" is true **of
a home this build cloned**, and is stated that narrowly on purpose.

**2. An alias spelling the checker cannot derive is still unchecked.** A home
created through one symlinked alias and verified through a *different* one is
partly unscanned, because a symlink cannot be inverted. Pinned as an executable
assertion (`HomeVerifyPathSpellingTest`, "THE STATED LIMIT") and filed as
**DEF-008**, with a note in the test saying it should become an equality
assertion if the limit is ever closed.

**3. `CliArtifact.usableInHome` grew no ownership branch.** It is named in this
ticket's `conflict_keys`, and it is fixed only *transitively*: the spelling
defect it shared with `home verify` is gone because both call the same scanner,
which is now spelling-invariant. HIS-7 deliberately left `inspect`'s ownership
question alone and its reasoning still holds — with the sanction now durable,
an inherited shim reads as **provided**, which is what makes "provision nothing
already provided" true. Adding a branch that called a *sanctioned* foreign path
unprovided would re-break exactly the case this ticket exists to fix.

**4. `sync --all` in the probe exited 11.** Pre-existing, already filed as
**DEF-005**: `spec-double-compiler`'s `skill-imports` name two units the project
home does not hold. Unrelated to shims; the CLI half of that run reported
`cli: 11 already present, 2 installed`.

**5. THE NUMBERS ABOVE ARE TRUE OF THE RAW BUILD ONLY, AND THE FRONT DOOR DOES
NOT RUN IT.** Nothing in `skt` reads or writes `home.provenance.json` — that is
fine, it needs no client — but `skt sync` / `skt publish` / `skt check` and
`bootstrap-home.sh` all resolve their CLI from the ROOT home's pin, which
**DEF-006 measured as Homebrew 0.23.0**, a build predating this epic. So a
worktree created by the documented front door today is cloned by a build that
**writes no record**, is refused by `home verify` exactly as before, and its
first sync still prunes five shims and re-provisions five toolchains. The
measurements in this file used `SKILL_MANAGER_CLI` pointed at this branch's
build, which is a workaround, and DEF-006 records it as one.

This is the difference between *fixed* and *fixed, and unreachable until DEF-006
is closed*. It also means the goal metric HIS-6 will read has two values
depending on which build produced the home, and HIS-6 should be told which one
it is reading.

**6. `HomeCloner.verify(Path, boolean)`'s javadoc — an acceptance item — was
CORRECTED, not left.** Issue #227 item 4 said that javadoc ("two of the three
findings never needed a source at all") "is currently false and must end up true
again". It is now true *with a stated condition*: true for a home this program
cloned, because the copy records its descent and the chain is re-derived; still
false for a home this program did not clone, where `--against` is the only
excuse available. The condition is written into the javadoc rather than dropped,
so the sentence stops over-promising the way it did for two releases.

## MED-6 and MED-7, answered by measurement

The issue's acceptance asks for these two HIS-7 review findings to be *answered*.
Both were measured on this branch; output in
`probes/his-10/med-6-med-7-measured.out`.

- **MED-7 is not a defect.** "The scope is `bin/cli`, not `bin/`" describes the
  *sanction* list, not the scan. The isolation walk covers the whole home: the
  probe planted `bin/launch/claude` and `bin/mcp/srv` links into a foreign home
  and `home verify` reported `FOREIGN_HOME` on both. No change made; recorded in
  DEF-007's `detail` so it is not re-opened.
- **MED-6 is real, and worse than the finding said.** `home verify` *does* see a
  symlinked `bin/cli` directory (`FOREIGN_HOME bin/cli`). But
  `CliShimPruner.prune` follows it and deletes through it: in the probe, homeA's
  `bin/cli` went from **2 entries to 0** because a prune ran in homeB. That is
  write confinement — HIS-9 (#226)'s slice, not this one — so it is filed as
  **DEF-007**, severity blocking, `escalated: true`, and reported to the epic
  agent. Nothing the product does creates that shape today
  (`mirrorExistingShim` mirrors entries, never the directory), which is why it
  did not stop this ticket.

## The claim I got wrong

The first version of this file, of `HomeProvenance`'s class javadoc and of the
#228 PR body all said some form of **"a copy of an unsanctioned home is still
unsanctioned — cloning is not a laundering step."** The review measured that
false, and it was right to:

```
child is NOT a registered child of parent; child/bin/cli/tool -> parent/bin/cli/tool
  home verify --home child                 -> exit 1, 1x FOREIGN_HOME    [correct]
  echo '{"schemaVersion":1,"clonedFrom":"/nowhere",
         "parentStores":["<parent>"]}' > child/home.provenance.json
  home verify --home child                 -> exit 0, 0x FOREIGN_HOME    [gate off]
```

One file, written into the home being judged, naming a source that does not
exist, switched that home's isolation gate off — and the verdict then **printed
the forged descent as authoritative**. Worse in context: the line I added names
the filename, so an agent chasing a `FOREIGN_HOME` refusal was being told
exactly which file to write to make the refusal go away.

The same shape produced a second defect through **product operations only**:
revoke the parent's claim (what `ChildHomeRegistry.delete` does on teardown) and
`verify project` refused while `verify worktree` passed, on the same shim and
the same parent; the pruner deleted it in one home and kept it in the other; and
cloning the worktree again **re-minted** the deleted claim. That is
GOAL-one-home-one-answer's own failure mode, recreated on the tier axis by the
mechanism meant to close it.

**What I tested and what I should have tested.** `ClonedHomeDescentTest`'s
laundering case covered a source carrying *no record*. It never covered a source
carrying a *hand-written* one, and it never covered a claim being *revoked*.
Both are now assertions; see the vacuity table's row D, which is what proves
they are not decorative.

### What is true now, stated at the width it holds

- **A copy of a home with no live descent is unsanctioned** — the no-record case,
  the forged-record case, and the revoked-claim case, all asserted.
- **Cloning does not launder**, because a clone no longer carries anything
  forward: `parentStoresOf` re-derives at write time and `sanctions` re-derives
  again at read time, so a copy can only be sanctioned by evidence that is live
  when the question is asked.
- **It is still not forgery-proof.** A writer inside a home can name, as its
  `clonedFrom`, some *other* home that genuinely is a claimed child; the chain
  re-derives and the sanction holds. What is gone is the arbitrary grant: a
  record can no longer name a store and be believed. This adds **no new class**
  of in-home forgery — `ChildHomeLink` already accepts
  `<child>/.materialization/<kind>/<unit>.json`, a file inside the judged home,
  as positive evidence. That pre-existing trust is named in the class javadoc
  rather than widened.

### What re-derivation costs

The class javadoc used to justify this evidence over the alternatives because it
*"needs no second home to be present, readable, or even to still exist when the
question is asked."* **Re-derivation gives that up.** The sanction now depends on
the chain still being derivable, and when it cannot be, the class **sanctions
nothing** — fail closed. The worst case is a worktree whose project home was
deleted reverting to pre-HIS-10 behaviour: a bad day, not a breach. It is softer
than it sounds, because `ChildHomeLink` reads the *parent's* registry and that
record outlives the directory it names, so a moved or deleted ancestor can still
re-derive.

## Vacuity — every new assertion, run against the broken code

The epic's rule is that an assertion never run against the defect is not
coverage. Four fixes, four disables, all re-run after the re-derivation change.

| # | fix disabled | assertion | result with the fix removed |
| --- | --- | --- | --- |
| A | `HomeProvenance.recordDescent` in `HomeCloner.build` (clone writes no record) | `ClonedHomeDescentTest` | **6/6 FAIL** — *"the clone recorded no descent at all"*; *"precondition: the clone verifies clean: expected <0> but was <1>"* |
| B | the `HomeProvenance.sanctions` arm in `sanctionedParentShim` (record written, no reader consults it) | `ClonedHomeDescentTest` | **4/6 FAIL** — *"`home verify --home <clone>` must reach the same verdict `home clone` just did, WITHOUT an operator supplying a source"* |
| B | same | graph node `home.integrity.readers.agree.about.one.clone` | **node FAILED** — `readers disagreed: clone=0 verify=1 verify--against=0 shim-after-sync=replaced locally`, and `removing home.provenance.json changed nothing — the readers agree for some other reason and this node is vacuous`. Metrics: `readers.agreeing=2, readers.distinctVerdicts=2` — the epic-tip baseline, reproduced live |
| C | `rootSpellings` returns only the caller's spelling (pre-#206 behaviour) | `HomeVerifyPathSpellingTest` | **2/4 FAIL** — *"the same home reached through a symlink must reach the same verdict; real=[…/venvs/nope/bin/probe] link=[]"* |
| **D** | **`sanctions` TRUSTS the recorded `parentStores` instead of re-deriving** (the shape the review measured) | `ClonedHomeDescentTest` | **2/6 FAIL**, and they are the review's two findings verbatim: *"a record is a POINTER to evidence, not the evidence: /nowhere re-derives to nothing, so it sanctions nothing: expected <1> but was <0>"* and *"after the claim is revoked the two tiers must still AGREE: expected <1> but was <0>"* |

Raw logs: `probes/his-10/vacuity-{A,B,C,D}-unit.out`. The review's own CLI repro,
re-run against the fix, is `probes/his-10/after-forged-record-refused.out` —
exit 1, and the report now reads
`0 of 1 recorded parent store(s) still re-derive` /
`NOT re-derivable: no live claim links this home to it`.

**Row D is the one that matters.** Rows A–C were all green on the version the
review broke; only D discriminates between "the record decides" and "the record
points at something that is re-checked".

**The `/var` trap was avoided.** The epic agent's correction arrived before the
fixture was written: `/private/var/X` *contains* `/var/X`, so `indexOf` finds the
reference by accident and a fixture built on a macOS temp path passes **before
and after** the fix. `HomeVerifyPathSpellingTest`'s class comment records this in
a section headed "THE FIXTURE THIS DELIBERATELY DOES NOT USE, AND WHY", so the
next author does not reach for `/var` first. The fixture used is a real
directory, a sibling symlink at it, and the home addressed through the symlink.

**The graph node carries its own control, every run.**
`removing_the_record_makes_the_readers_disagree` deletes
`home.provenance.json` from the leaf home, re-runs `home verify`, restores the
file, and fails the node if the verdict did not change. Green run:
`without home.provenance.json: home verify exited 1`.

**One branch is still uncovered and it is named, not hidden.** The leak
*exemption* — the branch in `verifyRoots` that turns a hard `FOREIGN_HOME`-class
finding into no finding for the provenance record — has no direct assertion and
does not appear in the table above. That is exactly the rule this epic keeps
re-learning, applied to the one branch that downgrades. Filed as **DEF-009**
(MED-8) rather than claimed as covered.

## Goals

**GOAL-one-home-one-answer (direct).**
- CLAUSE 1 — one verdict per scenario, no flag needed: **met for the cloned-home
  scenario**, measured 3 distinct verdicts → 1. Not claimed for a home this
  build did not clone; see "What was cut" (1).
- CLAUSE 2 — spelling-invariant, and the assertion fails when the resolution is
  removed: **met**, vacuity C above. One residual alias case, DEF-008.
- CLAUSE 3 — the lazy contract: **met**, 0 pruned / 2 installed, and the two
  installed are the two the clone declared cold.

**And a caveat on all three: they are measured on homes cloned by THIS BUILD.**
The documented front door (`bootstrap-home.sh`, `skt ticket new`) resolves its
CLI from the root home's pin, which DEF-006 measured as Homebrew 0.23.0, so a
worktree made the normal way today still scores the baseline. See "What was
cut" (5). HIS-6 should be told which build produced the homes it measures.

The metric's harness is now in place: `home.integrity.readers.agree.about.one.clone`
drives all four readers over one cloned home and publishes
`readers.distinctVerdicts` — **1** with the fix, **2** with it removed, which is
the goal's own metric rather than a pass/fail restatement of it.

**GOAL-home-invariants (direct).** The behavioural statement this ticket
contributes, for HIS-5 to render as a TLA+ invariant, and the review changed its
wording: *a home's sanction set is a function of evidence that is live when the
question is asked* — not of the command line, and **not of what the home says
about itself**. The first version of this ticket would have modelled the weaker
property and the model would have been satisfied by the forged record. No new
invariant or regression cfg is added here — HIS-5 carries the model work for the epic, and
this ticket's `validation.tlc` says so explicitly. The five pre-existing
regression cfgs are untouched.

## Validation

| lane | command | result |
| --- | --- | --- |
| repository_unit | `jbang RunTests.java` | **ALL PASSED** (`ClonedHomeDescentTest` **6/6**, `HomeVerifyPathSpellingTest` 4/4, `ChildHomeShimIsolationTest` 9/9 unchanged) |
| spec_graph | `python skills/test_graph/scripts/run.py home-integrity` | **BUILD SUCCESSFUL in 4m 34s**, 14 nodes, `status: passed`, no missing/unexpected node ids; node metrics `readers.agreeing=4, readers.distinctVerdicts=1` |
| spec_unit | `uv run pytest specs/` | **38 passed in 1.82s** |
| manual | four-reader matrix, re-run after the fix | captured in `probes/his-10/after-*.out` |
| manual | bootstrap probe on a throwaway worktree | exit 0, five shims kept, root home unchanged |
| regression | `run.py project-child-home` | **BUILD SUCCESSFUL in 3m 51s** |
| regression | `run.py harness-smoke` | **BUILD SUCCESSFUL in 4m 39s** |

`project-child-home` and `harness-smoke` were run because HIS-7's own goal
contribution named them as the graphs most exposed to a change in this
mechanism, and because the spelling widening can only ever make the checker find
*more* — which is the direction that turns other graphs red.

**Every row was re-run after the re-derivation change.** `project-child-home`
and `harness-smoke` in particular, because re-derivation makes the sanction
predicate strictly NARROWER and a stale green there is the direction that could
hide a regression: both build child homes carrying CLI deps, which is the shape
this predicate decides. Both were green before the change and are green after,
at the timings above.

**Not run:** `run.py --all` (~7 min plus the excluded graphs), and TLC. The
former is the epic agent's call at wave close; the latter is HIS-5's.

## Files

| file | what changed |
| --- | --- |
| `src/main/java/dev/skillmanager/store/HomeProvenance.java` | **new** — the record; `sanctions` walks `clonedFrom` hop by hop and re-derives each one through `ChildHomeLink`, live; `parentStores` is a snapshot nothing trusts; a relative `clonedFrom` is refused rather than resolved against the CWD; cycle and depth guards |
| `src/main/java/dev/skillmanager/store/HomeCloner.java` | writes the record during `build`; `sanctionedParentShim` consults it; `destReferences`/`referencesIn`/`insideHomeText` scan every spelling of the root; the record is exempted from the source-naming leak rule by byte accounting |
| `src/main/java/dev/skillmanager/commands/HomeCommand.java` | `home verify` prints the descent as a CLAIM and marks each recorded store re-derived or `NOT re-derivable`, so a forged record reads as the dead claim it is; says so when the home records none |
| `src/main/java/dev/skillmanager/cli/installer/CliShimPruner.java` | **unchanged** — it already asks the gate rather than deciding, so the record reaches it for free |
| `src/test/java/dev/skillmanager/store/ClonedHomeDescentTest.java` | **new**, 6 cases — four readers over one clone; the vacuity control; the no-record case; **the hand-written-record case**; **the revoked-claim case**; transitivity two tiers down |
| `src/test/java/dev/skillmanager/store/HomeVerifyPathSpellingTest.java` | **new** — #206, on a fixture the `/var` accident cannot rescue |
| `test_graph/sources/home-integrity/ReadersAgreeAboutOneClone.java` | **new** graph node, three-tier topology, real `sync`, own control; removes the fixture claim it plants, on every exit path; publishes `readers.distinctVerdicts` so the goal's own metric can express the baseline (2) and not just pass/fail |
| `results/epic-home-integrity-sync/deferred/backlog.yaml` | DEF-007 (blocking, escalated; widened with MED-9), DEF-008, DEF-009, DEF-010 |
