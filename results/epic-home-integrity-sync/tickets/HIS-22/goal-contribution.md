# HIS-22 — goal contribution

Issue **#254** · branch `feature/254-his-22` · base `9c55c07` · wave **15** ·
promotion order **21** · predecessor **HIS-21** (filing concurrently).

Goals: **GOAL-one-home-one-answer** (`direct`) and **GOAL-no-spurious-holdback**
(`guard`).

Declared expected effect, verbatim from the plan:
*"the promotion direction the owner asked to enforce becomes usable, and a
project that claims nothing stops speaking for units it does not claim"*, and
for the guard: *"scoping a refusal must not become a new hold-back: a project
that DOES claim the unit still refuses"*.

---

## 0. THE HEADLINE: this ticket's own measurement falsifies its issue

Two claims in #254 are false, and both were checked before a line was written.

### (a) `meta-harness` claims all four of the units that failed

> *"One — `meta-harness` — had three vendored symlinks spelled absolutely. That
> one project's condition refused all four units, **none of which names it or is
> named by it**."*

Read from the operator's root home the same day (read-only; `command grep` over
`~/.skill-manager/projects/*/project-lock.toml`):

```
commit-diff-context-parent claims git-issue-workflow, git-epic-workflow, skill-manager, skt
meta-harness              claims git-issue-workflow, git-epic-workflow, skill-manager, skt
skill-manager             claims skill-manager
```

`meta-harness` claims **all four**. It is not unrelated to any of them.

### (b) The fix the issue prescribes had already shipped, with a test

> *"Either scope the refusal to the units the failing project actually **claims**…"*

`LiveInterpreter.syncClaimingProjects` has done exactly that since **#144**:
failures are stamped only on `projectClaimers.get(projectName)`. It is guarded by
`ProjectDependencyResolverTest`'s *"project sync failures are recorded only on
units claiming the failed project"*.

**Measured, not argued.** The prescribed acceptance node is
`ProjectToRootPromotionTest` CASE 3 — *"a non-durable project that does not claim
the unit was never in the way"*. Probe **V1** reverts the entire DEF-103 fix, and
CASE 3 stays **green**:

```
V1 (fix reverted):
  [FAIL] a unit sync into the root home survives a non-durable claiming project
  [PASS] the project that owns the non-durable paths is still refused its own resolve
  [PASS] a non-durable project that does not claim the unit was never in the way   <-- the prescription
  [FAIL] a direct root update still works while a claiming project is non-durable
```

A node built to #254's acceptance text would have been **vacuous**: green before
the fix, green after, and the reported symptom untouched. It is kept as CASE 3,
labelled in its own comment as a control rather than evidence.

### So what the defect actually is

The scope was never units-per-project. It is **kind of failure**. A project's
`[[vendored]]` durability finding is a fact about that project's *checkout*: true
before the unit sync, true after, and no byte the sync moved can cause or cure
it. Recording it as `PROJECT_SYNC_FAILED` on the unit — and counting the
receipt's PARTIAL into `sync`'s exit code — turns a correct warning about project
A into a refusal of change management for every unit A claims, one tier up.

**Also worth correcting:** the issue's table says `project → root` had *"no"*
prior coverage. `test_graph/sources/home-sync/HomeSyncProjectToRoot.java`
(`home.sync.project.to.root`) has covered `home sync --from <project> --to <root>`
since issue #43. What had **no** coverage is the command the operator actually
runs to bring a unit into the root home — `skill-manager sync <unit>`, which is
what `skt sync` shells out to — and that is where the path broke. The new node is
named for that distinction.

---

## 1. GOAL-one-home-one-answer — direct

**Expected effect: the promotion direction becomes usable.** Measured end to end
through the real CLI, in `home.sync.project.to.root.change.management`
(run `20260824-195838`):

| | before (probe V1, fix reverted) | this branch |
| --- | --- | --- |
| `sync <unit>` at the root home, one claiming project non-durable | **exit 1** | **exit 0** (`syncExitCode: 0`) |
| `PROJECT_SYNC_FAILED` stamped on the unit | yes | **no** |
| the non-durable project is still named, with its repair command | n/a | **yes** |
| `project resolve` on that same project | exit 1 | **exit 1** (`ownResolveExitCode: 1`) |

The last row is the whole point: the direction became usable **without** the
check moving.

**Expected effect: a project stops speaking for units it does not claim.** Not
delivered by this ticket, because it was already true — see §0(b). Stated here
rather than claimed, since the plan's wording would otherwise be read as a
result.

## 2. GOAL-no-spurious-holdback — guard

The guard cuts both ways in this ticket, and both directions are asserted.

**Not a new hold-back.** DEF-103's fix must not make a durability finding
disappear. `ProjectToRootPromotionTest` CASE 1 asserts the skip notice exists,
names the project, carries the finding count, and carries `--repair-vendored` —
so probe **V2**, which deletes the check itself, reddens CASE 1 as well as CASE 2.

**A hold-back removed.** DEF-101: close-out returned `exit 1`, `safe: false` and
two blockers for `skill:skill-manager` and `plugin:skt` on HIS-20, both
unmodified checkouts at published refs that the destination's own
`skill-project.toml` declares. Nothing would have been destroyed, and the only
route past the gate was to sync bytes the destination can fetch, out of a home
about to be deleted. `HomeCloseOutSelfObtainableTest` CASE 1 clears it;
CASES 2–4 keep the gate: an undeclared new unit blocks, a declared-but-edited
unit blocks, and a destination with no manifest of its own exempts nothing.

---

## 3. Both halves of the owner's sentence

> *"We need to be enforcing change management of the root skill manager from
> project home. **However, we should still be able to update the root.**"*

`ProjectToRootPromotionTest` CASE 4 drives the real `SyncCommand` at the root
tier with a non-durable claiming project registered, and asserts **exit 0 AND
that the bytes moved** — a new file published upstream after install has to land
in the root store. Exit 0 over a sync that moved nothing would satisfy the exit
code and not the sentence; that is vacuity-ledger row 19's shape, and it is
guarded against explicitly.

Under probe V1 that case fails with `expected <0> but was <1>` — the exact exit
code `skt sync <unit>` returned on the operator's root home on 2026-08-24.

## 4. Promotion paths covered

| path | direction | before | after |
| --- | --- | --- | --- |
| worktree → project | up | every close-out | unchanged, plus DEF-101's exemption and its three guards |
| **project → root** | **up** | `home sync --from/--to` only (`home.sync.project.to.root`) | **plus `sync <unit>` at the root home with claiming projects registered** |
| root → project | down | clone / inherit | plus DEF-096's shortfall report on `home clone` |
| project → worktree | down | bootstrap | plus DEF-096: a worktree cloned from an unresolved project home is no longer silently short |

## 5. Round 2

Run from `feature/254-his-22` with the five home vars **UNSET** (DEF-074), one
graph at a time.

| signal | result |
| --- | --- |
| `jbang RunTests.java` | **1410 passed, 0 failed** — ALL PASSED |
| `uv run pytest specs/` | **88 passed** |
| `home-sync` (declared) | **20 of 21 nodes green**, run `20260824-210901`. Both HIS-22 nodes pass. The 21st is `home.membership.law` — **DEF-107, HIS-21's**. Attribution **verified, not assumed**: the failure text names only `hs-delta` and `hs-epsilon`, fixture units staged by pre-existing nodes, and mentions neither unit the HIS-22 nodes create. |
| `checkout-home` (declared) | **PASS**, run `20260824-211431` |
| `home-clone` (second set) | **PASS**, run `20260824-211613` |
| `project-child-home` (second set) | **PASS**, run `20260824-211828` |
| `project-resolve` (second set) | **PASS**, run `20260824-211944` |
| `ticket-lifecycle` (second set) | **PASS**, run `20260824-212043` |

`graphs_executed = 6`, `graphs_passed = 5`, `graphs_failed = 1` — and the one
failure is the inherited node named above.

**Why that second set, named as the brief asked.** `home-clone` and
`checkout-home` both compose `home.cloned.into.project`, which this ticket edited
for DEF-096. `project-child-home` and `project-resolve` drive
`ProjectDependencyResolver`, whose `checkVendored` now throws a typed exception.
`ticket-lifecycle` is the only graph that drives `home close-out` end to end,
which DEF-101 changed.

Recorded in `results/epic-home-integrity-sync/evaluation/regression-ledger.yaml`
under `rounds.round_2`, `REG-001` (`fixed_in`, `correction_to_this_row`,
`regression_check`, `round_2_result`) and `carried_findings_his22` for DEF-096
and DEF-101.

## 5b. What the review of PR #255 changed

The review returned **merge with follow-ups, no blockers**, and independently
reproduced the DEF-103 pairing with a different mutation
(`if (false && !report.fatalProblems().isEmpty())` → CASE 1 **and** CASE 2 red).
It also confirmed CASE 3 stays green under V1, so the issue's own prescribed
acceptance node is vacuous by two measurements rather than one. Four things came
back; **two of them were self-doubts filed at §6 that the reviewer judged
understated by my own standard, and it was right.**

### MAJOR 1 — the DEF-101 exemption cleared unpublished work (now DEF-113)

Built with the real CLI, no test scaffolding: a home whose clone baseline had
been stamped over commits on no remote. The gate printed `"safe":true` and
*"nothing in this worktree's copy exists only here"* **in the same document
where the CLI printed `NO_GIT_REMOTE` for that unit.** Two readers, one wrong
answer, inside the gate whose job is to refuse when work would be destroyed.

Fixed: `GitOps.publishedRefContaining` requires HEAD to be reachable from a
`refs/remotes/**` ref — positive evidence, no network — and a unit that is not a
git checkout does not qualify, so *"cannot tell"* blocks. The remedy no longer
asserts what was never checked; it names the ref, and names the declared
coordinate as the destination's claim.

**Reproduced red by probe V7.** Bounds kept from the reviewer: the ordinary flow
was already defended, the source home still blocked, and no actual byte loss was
constructible.

### MAJOR 2 — my advisory's central sentence was false

*"this project's realization was NOT refreshed"*. Measured: `checkVendored` runs
after `register()`, `installMissing()` and `scaffold()` — by its own javadoc,
because the declared source lives inside the child home the scaffolder creates.
So units are installed and the child home is refreshed, **behind exit 0**, where
pre-PR the operator saw exit 1 and went looking. Another output with no way to
detect its own invalidity, inside the fix for a defect of that family.

Now it reports: `PARTIALLY REFRESHED before the refusal — N unit(s) installed
into the store and the units-lock rewritten, child home scaffolded, registration
snapshot rewritten; bindings NOT materialized and the project lock NOT written`.
The installed clause is **conditional**, because `installMissing` returns before
writing anything when nothing is missing — an unconditional "units-lock
rewritten" would have been false exactly as often as the sentence it replaced.
Probes **V8** and the second half of CASE 5 cover both.

### MINOR 1 — the remedy was not runnable

`--project-dir <skip-probe>`: angle brackets are shell redirection and the
project *name* is not a directory. The exception now carries `projectRoot()` and
shell-quotes it. Probe **V9**.

### DEF-109 — fixed here rather than deferred

Both raw NULs replaced with the escape `\0`. Measured before/after on the class
at the centre of DEF-103:

```
before: file -> "data";  wrapper grep -c "enum Status" -> no output, exit 1
after:  file -> "Java source, Unicode text, UTF-8 text";  same grep -> 1
```

An independent byte-level scan of all **5253** tracked files confirmed the
reviewer's count (12 NUL-bearing, 2 tracked Java sources); it is now 11 and **0**.
`SourcesAreGreppableTest` makes it non-recurring, reads with
`Files.readAllBytes` so the instrument cannot suffer the defect it hunts, and
carries a canary control. Probe **V10**. The wrapper's silent-drop behaviour is
split out as not-ours and left open — **it blinded the reviewer's own first
command too**, which is three readers to one instrument.

### MINOR 3 — taken, not deferred

DEF-101 now has a graph node: `home.sync.close.out.self.obtainable`. Its arm 3 is
the assertion that would have caught MAJOR 1 — *the gate's verdict and the
closing error report must never disagree about the same unit in the same
document* — with a positive control, because an implication is trivially true
when its antecedent never holds.

**The lesson worth more than the fixes.** V1–V6 were honest and each reddened its
claim, and all six were blind to MAJOR 1, because every one called
`HomeCloseOut.inspect` in process and the defect lived between two readers. Six
probes, six reds, **one oracle**.

## 6. What is NOT delivered, stated rather than omitted

- **The TLA+ model is unchanged.** `CloseOutIsSafe` still reads
  `SyncStatusOf(...) = "unchanged"`, and DEF-101 adds a second way for the gate
  to clear that the model cannot express without a new variable and a constant in
  ~25 cfgs. The assignment says `tlc: N/A unless the fix states a new invariant`,
  and this states a weakening of an existing predicate. Filed as **DEF-112** with
  the exact model change and the regression cfg it owes.
- **DEF-101 has no graph-layer coverage**, only the four unit cases. Named in
  DEF-112.
- **DEF-110**: `sync`'s exit code still contradicts its own stated rule for
  every project-sync failure *other* than the durability one.
