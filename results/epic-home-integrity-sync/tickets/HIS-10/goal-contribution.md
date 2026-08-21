# HIS-10 — goal contribution

Issue #227 (also closes #206). Branch `feature/227-his-10`, based on epic tip
`32b437b`.

## What this delivers

**A clone records its descent, so every reader answers from one file instead of
guessing — and a clone keeps the toolchain it inherited instead of rebuilding
it.**

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

## Vacuity — every new assertion, run against the broken code

The epic's rule is that an assertion never run against the defect is not
coverage. Three fixes, three disables, each recorded.

| # | fix disabled | assertion | result with the fix removed |
| --- | --- | --- | --- |
| A | `HomeProvenance.recordDescent` in `HomeCloner.build` (clone writes no record) | `ClonedHomeDescentTest` | **4/4 FAIL** — "the clone recorded no descent at all"; "precondition: the clone verifies clean: expected <0> but was <1>" |
| B | the `HomeProvenance.sanctions` arm in `sanctionedParentShim` (record written, no reader consults it) | `ClonedHomeDescentTest` | **3/4 FAIL** — "`home verify --home <clone>` must reach the same verdict `home clone` just did, WITHOUT an operator supplying a source"; the laundering case correctly still passes |
| B | same | graph node `home.integrity.readers.agree.about.one.clone` | **node FAILED** — `readers disagreed: clone=0 verify=1 verify--against=0 shim-after-sync=replaced locally` and `removing home.provenance.json changed nothing — the readers agree for some other reason and this node is vacuous`. That log line is the epic-tip baseline, reproduced live. |
| C | `rootSpellings` returns only the caller's spelling (pre-#206 behaviour) | `HomeVerifyPathSpellingTest` | **2/4 FAIL** — "the same home reached through a symlink must reach the same verdict; real=[…/venvs/nope/bin/probe] link=[]" |

Raw logs: `probes/his-10/vacuity-{A,B,C}-unit.out`.

**The `/var` trap was avoided.** The epic agent's correction arrived before the
fixture was written: `/private/var/X` *contains* `/var/X`, so `indexOf` finds the
reference by accident and a fixture built on a macOS temp path passes **before
and after** the fix. `HomeVerifyPathSpellingTest`'s class comment records this in
a section headed "THE FIXTURE THIS DELIBERATELY DOES NOT USE, AND WHY", so the
next author does not reach for `/var` first. The fixture used is a real
directory, a sibling symlink at it, and the home addressed through the symlink.

**The graph node carries its own control, every run.** Rather than relying on a
one-off probe, `removing_the_record_makes_the_readers_disagree` deletes
`home.provenance.json` from the leaf home, re-runs `home verify`, restores the
file, and fails the node if the verdict did not change. Green run:
`without home.provenance.json: home verify exited 1`.

## Goals

**GOAL-one-home-one-answer (direct).**
- CLAUSE 1 — one verdict per scenario, no flag needed: **met for the cloned-home
  scenario**, measured 3 distinct verdicts → 1. Not claimed for a home this
  build did not clone; see "What was cut" (1).
- CLAUSE 2 — spelling-invariant, and the assertion fails when the resolution is
  removed: **met**, vacuity C above. One residual alias case, DEF-008.
- CLAUSE 3 — the lazy contract: **met**, 0 pruned / 2 installed, and the two
  installed are the two the clone declared cold.

The metric's harness is now in place: `home.integrity.readers.agree.about.one.clone`
drives all four readers over one cloned home and publishes
`readers.agreeing = 4`.

**GOAL-home-invariants (direct).** The behavioural statement this ticket
contributes, for HIS-5 to render as a TLA+ invariant: *a home's sanction set is a
function of its recorded descent, not of the command line.* No new invariant or
regression cfg is added here — HIS-5 carries the model work for the epic, and
this ticket's `validation.tlc` says so explicitly. The five pre-existing
regression cfgs are untouched.

## Validation

| lane | command | result |
| --- | --- | --- |
| repository_unit | `jbang RunTests.java` | **ALL PASSED** (`ClonedHomeDescentTest` 4/4, `HomeVerifyPathSpellingTest` 4/4, `ChildHomeShimIsolationTest` 9/9 unchanged) |
| spec_graph | `python skills/test_graph/scripts/run.py home-integrity` | **BUILD SUCCESSFUL in 5m 12s**, 14 nodes, `status: passed`, no missing/unexpected node ids |
| spec_unit | `uv run pytest specs/` | **38 passed in 1.82s** |
| manual | four-reader matrix, re-run after the fix | captured in `probes/his-10/after-*.out` |
| manual | bootstrap probe on a throwaway worktree | exit 0, five shims kept, root home unchanged |
| regression | `run.py project-child-home` | **BUILD SUCCESSFUL in 3m 55s** |
| regression | `run.py harness-smoke` | **BUILD SUCCESSFUL in 4m 36s** |

`project-child-home` and `harness-smoke` were run because HIS-7's own goal
contribution named them as the graphs most exposed to a change in this
mechanism, and because the spelling widening can only ever make the checker find
*more* — which is the direction that turns other graphs red.

**Not run:** `run.py --all` (~7 min plus the excluded graphs), and TLC. The
former is the epic agent's call at wave close; the latter is HIS-5's.

## Files

| file | what changed |
| --- | --- |
| `src/main/java/dev/skillmanager/store/HomeProvenance.java` | **new** — the record, its reader, and the derivation of a copy's parent stores from evidence the source already had |
| `src/main/java/dev/skillmanager/store/HomeCloner.java` | writes the record during `build`; `sanctionedParentShim` consults it; `destReferences`/`referencesIn`/`insideHomeText` scan every spelling of the root; the record is exempted from the source-naming leak rule by byte accounting |
| `src/main/java/dev/skillmanager/commands/HomeCommand.java` | `home verify` prints the descent it read, or says the home records none |
| `src/main/java/dev/skillmanager/cli/installer/CliShimPruner.java` | **unchanged** — it already asks the gate rather than deciding, so the record reaches it for free |
| `src/test/java/dev/skillmanager/store/ClonedHomeDescentTest.java` | **new** — four readers, one clone; the vacuity control; the laundering guard over a real clone; transitivity two tiers down |
| `src/test/java/dev/skillmanager/store/HomeVerifyPathSpellingTest.java` | **new** — #206, on a fixture the `/var` accident cannot rescue |
| `test_graph/sources/home-integrity/ReadersAgreeAboutOneClone.java` | **new** graph node, three-tier topology, real `sync`, own control |
| `results/epic-home-integrity-sync/deferred/backlog.yaml` | DEF-007 (blocking, escalated), DEF-008 |
