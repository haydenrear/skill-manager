# HIS-19 — goal contribution

Issue **#246** · branch `feature/246-his-19` · base `64b7a2e` (rebased past HIS-5 `ce75019`) · wave **12** ·
promotion order **18** · predecessor **HIS-5** (in review at the time of writing).

Goals: **GOAL-no-destructive-recovery** (`direct`) and **GOAL-one-home-one-answer**
(`guard`).

Declared expected effect, verbatim from the plan:
*"a defect the product re-creates on every upgrade -> a pin that survives one"*,
and for the guard:
*"the pin is re-derived by the reader HIS-10 made canonical, not a fifth answer
to which build this home runs"*.

---

## 0. The measurement that decides the ticket

One sandbox, one script, two runs — this branch, and the pre-HIS-19 behaviour
restored by mutation. `probes/his-19/transcript.sh`; raw output in
`transcript.out` and `transcript-pre-his19.out`.

|  | pre-HIS-19 | this branch |
| --- | --- | --- |
| pin written for a Homebrew build | `…/Cellar/skill-manager/0.23.0/bin/skill-manager` | `…/prefix/bin/skill-manager` |
| **before** the upgrade: home runs | `BUILD 0.23.0` (rc 0) | `BUILD 0.23.0` (rc 0) |
| **after** `brew upgrade` 0.23.0→0.24.0: home runs | **rc 127**, "the CLI pinned … is missing" | `BUILD 0.24.0` (rc 0) |
| **after** the upgrade: `home verify` on the broken home | **exit 0** | **exit 1**, naming the pin and the repair |
| `home repair --fix` on a legacy home | re-pins, and the next upgrade breaks it again | re-pins **durably** |
| a SECOND upgrade 0.24.0→0.25.0 | breaks it again | `BUILD 0.25.0` (rc 0) |

The `exit 0` cell is the 0.24.0 incident reproduced in a sandbox, not quoted from
the issue. It is the only number in this document that establishes there was
anything to fix, which is why it was measured against the mutated tree rather
than asserted from the ticket text.

**The treadmill row was cited to a file that showed the opposite, and the file
is now correct (review of #250, m7).** The first `transcript-pre-his19.out`
reverted three call sites — the writer, the verify detector and the verify
verdict — and left `HomeRepair.danglingCliPin`'s `DurableCliPin.forPin(live)` in
place, so the *repair* in that run still produced a durable pin and the file
showed the legacy home surviving to 0.25.0. The claim was true of real
pre-HIS-19 code and the evidence did not support it: HIS-8's fabricated-SHA
family, evidence that looks authoritative and is attached to the wrong line.
Re-run with **five** call sites reverted, the file now shows what the table
says:

```
MIGRATION -- through HIS-13's repairer
  legacy pin : …/Cellar/skill-manager/0.24.0/bin/skill-manager     <- versioned again
  legacy rc=0  BUILD 0.24.0
AND AGAIN -- 0.24.0 -> 0.25.0
  legacy rc=127  the CLI pinned for the home … is missing           <- the treadmill
  legacy home verify rc=0
```

**On the operator's real machine** (`probes/his-19/real-machine.out`), against
`/opt/homebrew` as it stands today:

```
located : /opt/homebrew/Cellar/skill-manager/0.24.0/libexec/bin/skill-manager   <- what the ROOT HOME pins right now
  names a version : true
  PIN WRITTEN     : /opt/homebrew/bin/skill-manager
  pin versioned   : false
  same real file  : true
located : /Users/hayde/IdeaProjects/wt-246-his-19/skill-manager                 <- a source checkout
  names a version : false
  PIN WRITTEN     : /Users/hayde/IdeaProjects/wt-246-his-19/skill-manager        <- untouched
```

Nothing was written to the root home; the check is a pure function over a path.
Its pin file's mtime is unchanged and its pin line still reads the versioned
Cellar path — this ticket repairs no home it was not pointed at.

---

## 1. What was built

### 1.1 `DurableCliPin` — the cause (`src/main/java/dev/skillmanager/launch/DurableCliPin.java`, new)

Given the build `RunningCli` located, return the most **durable spelling of that
same file**. Five branches, one enum value each, so a probe can name one:

| branch | rule | when it fires |
| --- | --- | --- |
| `NO_VERSION_TO_LOSE` | the located path carries no version segment → return it | source checkouts, CI builds, every test fixture. Checked **first**, on the located path alone, so nothing touches the disk. |
| `HOMEBREW_LINKED` | `<prefix>/Cellar/<f>/<v>/…` → `<prefix>/bin/<name>` | a linked Homebrew formula. Preferred because it survives the formula *moving* its binary — 0.23.0 shipped `libexec/bin/skill-manager` and 0.24.0 also publishes `bin/skill-manager`. |
| `HOMEBREW_KEG` | → `<prefix>/opt/<f>/<rest>` | keg-only formulae, or a binary not linked into the prefix. |
| `NO_DURABLE_ALIAS` | nothing equivalent survives → return the located path | **the pre-HIS-19 behaviour, unchanged**, with HIS-13's `DANGLING_CLI_PIN` still the net under it. |

A candidate is refused by three gates: it exists and is executable
(**verdict-neutral** — see below); **it carries no version segment**; and
**`toRealPath()` makes it the identical file**. The last one is what makes the
whole thing safe to reason about — see §2.

**A fourth arm and a fourth gate were removed at the review of #250.**

- **`PATH_ALIAS` is gone (MAJOR 2).** It probed `PATH` for a versionless entry of
  the same basename, as a generic arm for layouts this class does not know by
  name. §2 has the measurement and the decision.
- **The identity gate is gone (m4).** It refused a candidate equal to the located
  path. With every candidate derived from a keg decomposition, no candidate can
  contain the `Cellar` segment the located path must contain, so it was
  unreachable — the reviewer found it deletable with the suite fully green.
  Deleting a dead branch is the answer to that; a control for one would have
  been a control for nothing.
- **The `real == null` short-circuit stays and is declared verdict-neutral.** It
  too was deletable green, because with `real` null every candidate fails the
  same-file gate anyway. What it decides is the *recorded reason* — "the build
  you pointed me at is not there" versus "no alias resolves to it" — so the case
  named after it now asserts that, and probe **V13** reddens it. Ledger row 18.

### 1.2 `LauncherShims.write` — one line, and its report

`Path pin = pinnedCli.toAbsolutePath().normalize()` becomes
`DurableCliPin.choose(pinnedCli, System::getenv).pin()`, and a substituted pin is
logged at detail level with the reason.

`LauncherShims.Result` gained a `pin` component and `home shims` now reports
**what it wrote** rather than what it located. That was a real bug this fix
created and I caught it in a hand transcript, not in a test: the command printed
`pinned CLI: …/Cellar/…/0.23.0/…` while the file on disk said
`…/prefix/bin/skill-manager`. A reader disagreeing with the writer about one
artifact is the exact class this epic exists to remove, freshly manufactured by
its own fix.

**And it stayed caught only by hand until the review of #250 (MAJOR 1).** The
reviewer reverted `HomeCommand`'s `pin = result.pin();` and ran the full suite:

```
EXIT=0 ; [FAIL] count = 0 ; [PASS] count = 1402
```

Nothing reddened. A class of defect this epic exists to remove was being
defended by my memory of having noticed it once — on the command an operator
runs to *repair* a broken pin, and on the `--json` `"cli"` field a script reads.
Two cases now cover it, and `ShimsCmd` gained an injected-pin seam (the one
`RepairCmd` already carries) so the **command** can be driven rather than only
the writer. Measured red, probe **V4**:

```
[FAIL] MAJOR 1: `home shims` PRINTS the pin it wrote, byte for byte
[FAIL] MAJOR 1: the --json `cli` field is the pin that was written too
```

`Choice.considered`'s javadoc also claimed its rejection reasons were "written
into the `home shims` detail log" while `grep -rn "\.considered()" src/main`
returned no hits (m5). `Choice.auditLines()` exists now and `home shims` prints
it — bounded to at most three lines, because every candidate is derived.

### 1.3 `HomeCloner.verifyRoots` + `home verify` — the exit-0 half

`Verification` gained `danglingCliPins`, populated from
`LauncherShims.danglingPinIn` — **HIS-13's reader, not a second one**. `home
verify` prints the finding and adds `|| !deadPins.isEmpty()` to its verdict.

This is a separate question rather than a wider walk, and the reason is
structural: `missingReferencesIn` only considers paths **inside** the home and
under a provisionable root, and a dead pin names a path outside the home
entirely. `home verify` was not lying about what it had checked. It had checked
everything it could see.

### 1.4 `HomeRepair.danglingCliPin` — migration, through HIS-13

The finding's `target` and printed remedy go through the same
`DurableCliPin.forPin`. `apply` already repairs by calling
`LauncherShims.write`, so **existing homes migrate through the command that
already repairs them** and there is no second migration path. The stabilisation
is pure and idempotent, which is what lets the writer and the repairer agree
without either knowing about the other — asserted by the `ONE ANSWER` and
`IDEMPOTENT` cases.

---

## 2. The alternatives, and why they lost

DEF-027 stated the tradeoff precisely and asked that it be *"decided against that
argument, not around it"*. The argument is `LauncherShims`' class javadoc
(issue #61): a pin exists so that **"this home runs the build it was provisioned
with"**, not *"whatever is installed under this name now"*.

### Rejected: a PATH fallback in the shim, or re-resolving at launch

This is issue #61 exactly, and #61's measurement stands: on a machine whose PATH
`skill-manager` was the released 0.19.2 — no `exec` subcommand — every launcher
died at its last line, and both builds answer `--version` identically, so nothing
said which CLI had answered. It also cannot be bounded: PATH can name an older
build, a foreign home's entrypoint (DEF-002's measured shape), or a different
product. **Not attempted.**

### Rejected: record intent and re-derive in bash

The HIS-10 shape taken literally — store "Homebrew formula skill-manager" and
resolve it at exec time. It puts a resolver in a generated bash file, which is
the second untested copy of security-relevant logic that `LauncherShims`'
"One implementation of the launch rules" section refuses; and every spelling of
"re-derive" available to bash is a PATH search wearing a hat. **Rejected on the
class's own stated grounds.**

### Rejected: let the resolver answer, i.e. write no pin at all

`HomeDescriptor.locateCli` already steps past a dead pin (HIS-12) and falls
through to the running build. Deleting the pin would make that the only answer —
but the resolver's step 3 is *"the build running right now"*, which for a home
being launched from a shim is the shim itself, and step 4 is PATH. It converts a
loud 127 into the silent downgrade, and it deletes the record HIS-12's caveat is
built on. **Rejected.**

### Rejected: a versionless path with no verification

"Just pin `<prefix>/bin/skill-manager`." Same result on a Homebrew machine and
strictly worse everywhere else: it names a file that may be a different build,
may not exist, and may not be the one that ran `home shims`. This is the
alternative that *looks* identical to what I built and is not, and the
difference is one line — `candidateReal.equals(real)`. **V5 is that line's
probe**, and it reddens the assertion that a versionless decoy of the right name
is never taken.

### Rejected AT REVIEW, having first been built: the generic `PATH` arm

**This is the one thing in the ticket that a measurement overturned, and the
measurement overturned my own written claim as well as the arm.**

The first version shipped a third arm: probe `PATH` for a versionless entry of
the same basename, so a layout this class does not know by name
(`/usr/local/skill-manager/1.4.0/bin` fronted by `/usr/local/bin`) could still
be stabilised. The review of #250 challenged it against this class's own
sentence — *"it is also not a silent downgrade: an upgrade only moves forward,
and issue #61's failure was an OLDER build answering, which this cannot
produce."*

I reproduced it, and then asked the same question of the arms I had defended
(`probes/his-19/downgrade.out`):

| case | arm | after the alias moves backwards |
| --- | --- | --- |
| A | `PATH_ALIAS`, an arbitrary versionless PATH entry re-pointed | pin runs **BUILD 0.19.2** |
| B | `HOMEBREW_LINKED`, `brew link` an older keg | pin runs **BUILD 0.19.2** |
| C | no substitution, the keg deleted | **absent** — exit 127, `home verify` exit 1, `home repair --fix` repairs |

**Case B is the result I did not expect and it falsifies the sentence for the
derivable arms too**, which is more than the review claimed. So the sentence is
rewritten, not narrowed. What this design actually guarantees is that issue
#61's failure — an *unrelated* build the operator never chose, selected by PATH
ordering at launch — cannot occur, because nothing is resolved at launch. It
does **not** guarantee that the build never moves backwards: a package-manager
alias follows the operator's own installation, including a deliberate downgrade.
That limit is now asserted by a test (`HONEST LIMIT: a package-manager alias
FOLLOWS a deliberate downgrade`) rather than denied in prose.

**Given that, the `PATH` arm loses on four counts and I dropped it:**

1. **Bounded versus unbounded mover.** `brew` moves `<prefix>/bin/skill-manager`
   and nothing else has a reason to. An arbitrary PATH directory can be moved by
   any version manager, any shell profile, or another package shipping a binary
   of that name — and **nix generations roll backwards by design**.
2. **Ambient state in a written record.** A `PATH` arm makes the same command,
   on the same machine, against the same home, write a different pin from cron
   than from a login shell. That is a second answer to "which build does this
   home run" produced by the mechanism added to remove one —
   `GOAL-one-home-one-answer`, violated by its own guard.
3. **The fallback is loud and already handled.** With no derivable alias the
   versioned path is written; if an upgrade deletes it the home exits 127,
   `home verify` exits 1, `home repair --fix` repairs — all built by this epic.
   A silent downgrade has none of that. **Prefer no substitution over an unsafe
   one.**
4. **No measured user.** DEF-093 already recorded that the only layouts seen are
   Homebrew's and *a synthetic fixture written for this arm*. An arm serving a
   hypothetical does not earn an unbounded failure mode.

The class now reads **no environment at all**: `choose(Path)`, a function of the
located path and the filesystem. A layout it cannot derive is a layout it leaves
alone, and the fix for one is a new arm keyed on that packaging's own marker —
never a search. Probe **V14** plants a candidate that is *not* derived from the
located path and shows what depends on the invariant.

### Taken: the record is a POINTER, verified to be the same file at write time

What actually changes, stated as narrowly as I can make it:

* **Today's behaviour is byte-identical.** Every candidate must be the same file
  by real-path comparison. The home runs exactly the binary it would have run
  before, and the generated entrypoint still holds one absolute path with no
  PATH branch and no search at launch.
* **Tomorrow's differs, and only where the alternative is a deleted file.** The
  property narrows from *"the build it was provisioned with"* to *"that build,
  or its successor under the same installation"*. It cannot narrow for a build
  whose path carries no version — a checkout, a CI artifact, a hand-built binary
  — because those short-circuit before anything is probed.
* **It cannot reproduce issue #61 — but it CAN follow a downgrade, and saying
  otherwise was wrong.** Nothing is resolved at launch, so an unrelated build
  cannot be selected by PATH ordering. A package-manager alias does move
  backwards when the operator moves their own installation backwards
  (`brew link` an older keg — measured, case B above), which is a deliberate
  act with its own command, about this product, and indistinguishable from what
  they asked for. Asserted, not denied.
* **It never invents.** With no versionless alias, the versioned path is written
  exactly as before and HIS-13's detector remains the net.

**The honest cost:** an operator who deliberately pins an old keg via
`SKILL_MANAGER_CLI`, on a machine where `<prefix>/bin` happens to point at that
same keg, will have the home follow the next upgrade. Today the two are the same
build, so nothing is lost at write time; tomorrow the home moves where the
operator may have meant it to stay. Filed as **DEF-091** rather than guessed at.

---

## 3. Goal contribution

### GOAL-no-destructive-recovery — `direct`

> *CLAUSE 2: one command names what is damaged in a home and what would repair
> it, and the repair is idempotent — **and the product stops RE-CREATING the
> damage on every upgrade**, which is what makes the repair a fix rather than a
> treadmill.*

The clause has two halves and HIS-13 delivered the first. This ticket delivers
the second, and the treadmill row of §0's table is the measurement: pre-HIS-19,
`home repair --fix` restores a home and the *next* upgrade breaks it again; on
this branch the repaired home survives 0.24.0→0.25.0 untouched.

The baseline sentence — *"`home verify` returned EXIT 0 on the broken home"* — is
now false of this build, measured both ways over one disk state.

### GOAL-one-home-one-answer — `guard`

The pin must not become a fifth answer to "which build does this home run".
Three assertions hold the guard, each with its own probe:

| reader pair | assertion | probe |
| --- | --- | --- |
| writer ↔ repairer | `HomeRepair`'s finding target **is** the path `LauncherShims.write` would write | V12 |
| library ↔ command | `HomeCloner.verify(...).danglingCliPins()` and `home verify`'s exit code agree | V10, V11 |
| writer ↔ its own report | `home shims` prints the pin it wrote | caught by hand; §1.2 |

There is one detector (`LauncherShims.danglingPinIn`, HIS-13's) and one chooser
(`DurableCliPin`, pure and idempotent). `home verify` reports and names
`home repair --fix`; it repairs nothing, because an observer that repairs is
DEF-067.

---

## 4. Validation

All commands run with the five home variables **unset** (`env -u SKILL_MANAGER_HOME
-u CLAUDE_CONFIG_DIR -u CLAUDE_HOME -u CODEX_HOME -u GEMINI_HOME`), per DEF-074.
Graphs additionally with `GRADLE_OPTS=-Dorg.gradle.daemon=false` and
`COMPOSE_PROJECT_NAME=skill-manager` (worktree collision).

Re-run in full **after the rebase past HIS-5 (`ce75019`) and after the review
changes**, not carried over.

| command | result |
| --- | --- |
| `uv run pytest specs/` | **88 passed** — was 38 before the rebase; HIS-5's model work is the difference |
| `jbang RunTests.java` | **ALL PASSED** — 147 suites, **1404 cases**, 0 failures |
| `jbang RunHis19.java` | **ALL PASSED** — **20 cases**, the new suite alone |
| `run.py home-integrity` | **BUILD SUCCESSFUL**, 18/18 nodes |
| `run.py home-clone` | **BUILD SUCCESSFUL** |
| `run.py checkout-home` | **BUILD SUCCESSFUL** |

The reviewer additionally ran `ticket-lifecycle` and `smoke`, both green. Not
repeated here — a second run of someone else's green is not evidence I produced,
and citing it as mine would be the reverse of the SHA lesson.

**Why those two extra graphs, named.** `home-clone` exercises
`HomeCloner.verifyRoots` — the method that gained the new question and the record
that gained a component — on real cloned homes, and it is the graph that would
notice if the new finding fired on a healthy clone. `checkout-home` provisions
homes through `home shims`, so it is the graph that runs the **writer** end to
end and would notice a pin the launch surface cannot use; it is also the graph
DEF-074 records as going red under a pinned environment, which is why the
unpinned configuration is stated for it specifically.

`run.py --all` was **not** run: multi-hour, and HIS-6 owns the single terminal
sweep (owner's instruction, recorded in the assignment).

TLC: **N/A** for this ticket — HIS-5 carries the model work and re-runs every
regression cfg. This change is below the TLA+ surface: no state variable, no
action, no adapter.

## 5. Vacuity

Fourteen probes, one per branch, in `probes/his-19/vacuity-checks.md`. Thirteen
reddened a claim; **V8 was declared expected-green before it ran** and the
argument is in the file. **V4 is MAJOR 1's measured red** and did not exist
before the review — the branch it moves was covered by nothing, in 1402 cases.

Three vacuity instances are recorded there and added to the ledger as rows
16-18: a mechanism-D probe aimed at the identity gate instead of the version
gate (mine), a mechanism-C/row-14 instrument failure in `transcript.sh` that
produced a confident, meaningless "both exit 0" (mine), and **two
verdict-neutral branches documented as gates with controls** (the reviewer's).
A fourth instrument failure — a MAJOR 2 reproduction harness that read the real
`PATH` instead of the fixture's, and so reported the reviewer wrong — is
recorded in the same file; it is the most expensive shape of row 14 available
here, because acting on it would have meant rejecting a correct review.

## 6. Migration on the operator's own machine — a named step, not a footnote

**Nothing in this PR migrates the root home, and it will break at 0.25.0.**
Measured, read-only, at the time of writing:

```
/Users/hayde/.skill-manager/bin/cli/skill-manager
  cli="${SKILL_MANAGER_CLI:-/opt/homebrew/Cellar/skill-manager/0.24.0/libexec/bin/skill-manager}"
```

That path is **alive today** — it was repaired by hand after the 0.24.0 incident
— and **dead the moment 0.25.0 lands**, for exactly the reason this ticket
exists. This change is in the *writer*; it does not walk existing homes.

**The step, after the next release ships:**

```
skill-manager home repair --home ~/.skill-manager --fix     # or: home shims --home ~/.skill-manager
skill-manager home verify --home ~/.skill-manager           # exit 0 confirms it
```

Either command re-pins through the new writer, so the result is
`/opt/homebrew/bin/skill-manager` and the home stops needing this step ever
again. Until then the root home is fine and `home verify` correctly says so.

The **project home** (`<repo>/.skill-manager`) already pins a versionless
checkout path, is untouched by design (`NO_VERSION_TO_LOSE`), and needs nothing.

## 7. Deferred

| id | what |
| --- | --- |
| **DEF-092** | `home clone` still does not report a dead pin; only `home verify` does. Two commands, one home, two verdicts. |
| **DEF-093** | the version predicate is a validated heuristic, not a swept corpus. |
| **DEF-094** | an explicit `SKILL_MANAGER_CLI` is stabilised too, so a deliberate downgrade-pin would drift forward. |
| **DEF-095** | `HomeFixpointLaw` cannot auto-repair a dead pin — **ruled correct as-is**, recorded so nobody teaches the law a second remedy string. |

**Renumbered 089/090/091 → 092/093/094 at the review of #250.** HIS-5 merged
carrying its own DEF-089 and keeps it; the epic owner adjudicated these three to
move. A renumbering aid is in the backlog beside them: *any citation of "DEF-089
(HIS-19, home clone / dead pin)" written before 2026-08-24 means DEF-092.* The
same collision happened one level up — this ticket's skill-feedback entry was
filed as SF-004, which HIS-5 had also taken, and is now **SF-005**. Both were
caught by the rebase putting the two versions of a file side by side, and by
nothing else. That is HIS-5's own DEF-089.

## 8. What I am not confident about

* **The version predicate is a heuristic, and my stated cost of a false positive
  was wrong (m3).** I wrote that it "costs a pointless probe". While the `PATH`
  arm existed it could also *substitute* a pin that never needed moving
  (`backup-2024.06.01/skill-manager`). The reviewer's corpus found it errs
  **narrow** on the shapes I named (`v2-experiments`, `3d-tools`, `python3.11`,
  `temurin-21.jdk` — all correctly not-a-version) and **wide** on dates and
  dotted numerics (`2024.1`, `192.168.1.1`, `release-1.0`, `gradle-8.10.2`,
  `commons-compress-1.27.1.jar`). With the `PATH` arm removed the cost genuinely
  does collapse — a path with no `Cellar` segment produces no candidates at all —
  but the predicate is still unswept. **DEF-093.**
* **`home clone` still does not report a dead pin**; only `home verify` does.
  A clone of a broken home is a broken clone, reported clean by the command that
  made it. **DEF-092.**
* **Windows / `.exe`.** `RunningCli` strips `.exe` when matching a basename;
  `DurableCliPin` compares basenames literally and has no Windows layout arm.
  No fixture, no measurement, and this repo's graphs do not run there.
* **I did not measure a real `brew upgrade`.** The upgrade is simulated —
  installing a keg, deleting the old one and re-pointing two symlinks, which is
  what `brew` does and what was observed on 2026-08-21.
* **If a future formula stops linking into `<prefix>/bin` *and* stops maintaining
  `opt/`, both arms miss.** The PR's first wording said the fallback is then
  "today's behaviour"; while the `PATH` arm existed that was inaccurate, because
  the arm still ran (m6). With the arm removed it is now exactly true: no
  candidates, `NO_DURABLE_ALIAS`, the versioned path written as before, and the
  recorded reason says so in as many words.
* **The downgrade limit is real and now asserted rather than denied.** A
  package-manager alias follows the operator's installation backwards. I believe
  that is correct behaviour — it is their installation, moved by their command —
  but it is a judgement, not a measurement, and a reviewer could reasonably want
  `home shims --exact` for it. That is DEF-094's shape.
