# HIS-22 — vacuity checks

Six probes over four production files, plus a baseline. Every probe was applied
by copying the file aside first and copying it back afterwards — never
`git checkout --` (DEF-035, which has bitten five agents on this epic).

Re-run one suite: `jbang RunHis22.java` (seconds). The full signal is
`jbang RunTests.java`.

**Baseline: `ALL PASSED`, 11 cases across 3 suites.**

| probe | file | branch it moves | verdict | which assertion reddened |
| --- | --- | --- | --- | --- |
| **V1** | `LiveInterpreter` | the typed `ProjectVendoredDurabilityException` catch in `syncClaimingProjects`, deleted so the finding falls through to the generic branch — **the whole DEF-103 fix, reverted** | FAILURES: 2 | CLAIM. `a unit sync into the root home survives a non-durable claiming project` on *"a project's own non-durable vendored paths are not the unit's fault"*; and `a direct root update still works…` on **`expected <0> but was <1>`** — the exact exit code `skt sync` returned on the operator's root home |
| **V2** | `ProjectDependencyResolver` | `if (false) throw` on the fatal vendored finding — **the check itself, weakened** | FAILURES: 2 | CLAIM. `the project that owns the non-durable paths is still refused its own resolve`; **and CASE 1's "the skipped project must still be named"**, which is the point of the pair |
| V3 | *(not used — see note)* | — | — | — |
| **V4** | `HomeCloseOut` | `selfObtainable` returns null unconditionally — **the DEF-101 exemption, reverted** | FAILURES: 1 | CLAIM. `a new unit the destination's own manifest declares does not block teardown`. The three guard cases stayed green, which is what makes it a scoped exemption rather than a deleted gate |
| **V5** | `HomeCommand` | the human-mode shortfall block in `print(HomeCloner.Report…)` — **DEF-096's reporting surface, silenced** | FAILURES: 1 | CLAIM. `home clone says the copy inherits the source's manifest shortfall` |
| **V6** | `ProjectManifestRealization` | `if (false && !holds(home, d))` — **the shortfall is never computed at all** | FAILURES: 2 | CLAIM. `a home short of what its own manifest declares says so` (`expected <2> but was <0>`) **and** `home clone says the copy inherits…` — one mutation, two surfaces, which is the evidence that they read one mechanism rather than two copies of a rule |

*(V3 was reserved for a separate DEF-096 probe on `manifestBeside`; V6 subsumes
it, and an unused number is left in place rather than renumbered, for the reason
stated in the deferred backlog.)*

---

## The pairing that matters: V1 and V2 are the DEF-103 acceptance, and they oppose each other

The brief was explicit that one assertion is not enough:

> a durability defect in a project that **does** claim the unit **still refuses**.
> Scoped, not weakened. **Without the second, the first is satisfied by deleting
> the check.**

Measured, and it is stronger than the brief anticipated:

```
V1 (fix reverted)   -> CASE 1 red, CASE 2 GREEN     the fix is load-bearing
V2 (check deleted)  -> CASE 2 red, CASE 1 ALSO RED  deleting the check does not satisfy CASE 1
```

CASE 1 goes red under V2 because it does not only assert *"the sync succeeded"*
— it asserts the skipped project is still **named**, with its finding count and
its repair command. A build that deleted the durability check would make the
sync succeed and would produce no skip notice, so the "not silent" half fires.
That is the countermeasure the vacuity ledger asks for at row 19: an arm that
emits a positive assertion it did work at all, rather than a green that means
nothing happened.

---

## Mechanism B, and the one this ticket actually met

### The prescribed acceptance node would have been vacuous. Measured.

Issue #254's acceptance item reads:

> **DEF-103:** a unit sync into the root home **succeeds** while a registered
> project **unrelated** to that unit is durability-broken. The node registers two
> projects, breaks one, and syncs a unit the broken one does not claim.

That is `ProjectToRootPromotionTest` **CASE 3**. It is **green against the
unfixed build** — see the V1 row: under V1, CASE 3 passes. It passes because
`LiveInterpreter.syncClaimingProjects` has scoped failures to
`projectClaimers.get(projectName)` since **#144**, guarded by
`ProjectDependencyResolverTest`'s *"project sync failures are recorded only on
units claiming the failed project"*. The prescribed fix had already shipped, with
a test.

CASE 3 is kept, and labelled in its own comment as **a control, not evidence for
the fix**. Deleting it would have removed the only written proof that the
prescription was already satisfied.

### And the premise it rests on is false

Read from the operator's root home the same day, `command grep` over
`~/.skill-manager/projects/*/project-lock.toml`:

```
meta-harness claims git-issue-workflow
meta-harness claims git-epic-workflow
meta-harness claims skill-manager
meta-harness claims skt
```

Issue #254 says *"none of which names it or is named by it"*. `meta-harness`
claims **all four** of the units that failed. So the scope was never
units-per-project; it is **kind of failure**, and that is what the fix moves.

---

## Mechanism C in a fifth medium, met while writing these probes

`grep` in this shell is a function wrapping ugrep with `-I`.
`ProjectVendoredResolver.java` — the class at the centre of DEF-103 — contains a
literal NUL used as a map-key separator, so `file` calls it *data* and the
wrapper **returns nothing for it, with exit 0 and no warning**.

Three early conclusions in this ticket were false because of it, including one
about the exact durability sentence the issue quotes. It was caught the way
five of the ledger's eight instrument failures were caught — the result was
*implausible*: `grep -c "class" <file>` printed **nothing at all**, where 0 was
the worst honest answer.

Filed as **DEF-109**. It is the ledger's ninth instance of an output that carries
no way to detect its own invalidity, and the first where the corpus rather than
the pattern, the fixture, or the arm was the thing that went missing.

## Every zero in this ticket has a positive control

Two of the new assertions claim a string is **absent**, and an absence proves
nothing until something proves the output could have carried it:

- `home.sync.project.to.root.change.management` asserts `PROJECT_SYNC_FAILED`
  does **not** appear in the sync log. **Arm 3 of the same node** breaks a
  second project in a way that *is* attributable (its registration snapshot is
  deleted), syncs a second unit, and asserts the same log **does** carry
  `PROJECT_SYNC_FAILED`.
- `home.cloned.into.project`'s DEF-096 arm asserts the shortfall notice appears
  for a clone of an unresolved project home. Its **positive control** is the
  seed clone in the same arm, run against a home with no manifest beside it,
  where the notice must **not** appear — otherwise it is unconditional noise
  rather than a finding.
