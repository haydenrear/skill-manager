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
| `jbang RunTests.java` | **1408 passed, 0 failed** — ALL PASSED |
| `uv run pytest specs/` | **88 passed** |
| `home-sync` (declared) | **19 of 20 nodes green**, run `20260824-195838`. The 20th is `home.membership.law` — **DEF-107, HIS-21's**, the law flagging deliberate fixture staging (`hs-delta`, `hs-epsilon`). Inherited, not mine. |
| `checkout-home` (declared) | **PASS**, run `20260824-200314` |
| `home-clone` (second set) | **PASS**, run `20260824-200433` |
| `project-child-home` (second set) | **PASS**, run `20260824-200624` |
| `project-resolve` (second set) | **PASS**, run `20260824-200719` |
| `ticket-lifecycle` (second set) | **PASS**, run `20260824-200804` |

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
