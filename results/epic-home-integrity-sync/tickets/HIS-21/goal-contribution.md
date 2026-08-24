# HIS-21 — goal contribution

Issue **#253** · branch `feature/253-his-21` · base `9c55c07` · wave **15** ·
promotion order **20** · predecessor **HIS-20**.

Goals: **GOAL-one-home-one-answer** (`direct`) and
**GOAL-no-destructive-recovery** (`guard`).

Declared expected effect, verbatim from the plan:
*"verify and repair stop disagreeing about the root tier; describe stops
inventing a fifth answer about which home owns what"*, and for the guard:
*"a home whose damage verify cannot see is a home nobody repairs; closing the
gap is what makes HIS-13's detector reachable from the ordinary verb"*.

---

## 0. The measurement that decides the ticket

`GOAL-one-home-one-answer`'s metric is *"distinct verdicts returned by the
readers over one fixed scenario matrix; the target is one verdict per
scenario."* One home, one wrapper shim, two readers:

| scenario | `home verify` | `home repair` | verdicts |
| --- | --- | --- | --- |
| **before**, home with no parent | exit 0, *"no path in it reaches any other Skill Manager home"* | exit 1, `FOREIGN_PATH_IN_SHIM bin/cli/tool` | **2** |
| **before**, same home `--against C` | exit 0 | exit 1 | **2** |
| **before**, same shim as a **symlink** | exit 1, `FOREIGN_HOME` | exit 1 | 1 |
| **after**, home with no parent | exit 1, `FOREIGN_PATH_IN_SHIM bin/cli/tool` | exit 1, same subject | **1** |
| **after**, `--against C` | exit 1, same subject | exit 1 | **1** |
| **after**, undamaged home (control) | exit 0 | exit 0 | 1 |
| **after**, grandchild clone's inherited wrapper (control) | exit 0 | exit 0 | 1 |
| **after**, same clone with its descent record deleted (control) | exit 1 | exit 1 | 1 |

And on the operator's **real root home**, read-only, no repair run:

```
$ skill-manager home verify --home ~/.skill-manager
✗ 5 path(s) in /Users/hayde/.skill-manager resolve into another Skill Manager home
✗   FOREIGN_PATH_IN_SHIM bin/cli/helm-deploy  (x2)
✗   FOREIGN_PATH_IN_SHIM bin/cli/monitoring   (x2)
✗   FOREIGN_PATH_IN_SHIM bin/cli/tla-spec-dev
exit 1
```

— the same five `home repair` reports. **The root home is NOT repaired.** That
is the owner's call and the issue says so.

## 1. The headline in the issue is wrong, and correcting it changed the fix

#253 offers, explicitly "to be confirmed rather than assumed", that
`verifyRoots` frames the foreign-home question against a home's SOURCE and the
root home has no parent, so the branch never runs — *"if that is right,
`home verify` is weakest exactly where the damage keeps happening."*

**It is not right.** Rows 2 and 3 of the table above are the refutation, and
both were measured on the pre-fix tree. With a source supplied the check was
equally blind; with the same target reached through a **symlink** it was not
blind at all. The foreign-home question was asked only of symlinks; for a
regular file the walk searched for the source needle and nothing else. **A
wrapper naming a third home was invisible at every tier, with or without a
parent.**

`HomeRepair.foreignPathsInShims` had said as much in its own javadoc since
HIS-13 — *"every check built on `Files.isExecutable` or on link resolution
passes over it"* — and nobody had put the same question to `home verify`.

This is not pedantry about a sentence. The fix the stated mechanism implies is
"run the branch when `srcRoot == null`", and that branch is the symlink branch,
which already ran. A ticket that implemented the mechanism as filed would have
shipped green tests and the defect. So the regression test asserts the
`--against` case on purpose.

**It also narrows the goal's own baseline.** The baseline for
GOAL-one-home-one-answer describes four readers and three answers *"on one
cloned home"*. What this ticket measured is that the reader split does not need
a clone, a parent, or a flag: it needed a **regular file**.

## 2. Contribution to GOAL-one-home-one-answer (direct)

- **Clause 1** — *one verdict per scenario, with no operator-supplied flag.*
  Rows 4–5 above. The two readers now share the extraction rule
  (`HomeRepair.absolutePathTokens`) and the verdict
  (`HomeCloner.unsanctionedForeignHome`) through one method,
  `HomeCloner.foreignPathsInShimContent`, over the same scope. They cannot
  drift apart by scope or by rule; only by report text.
- **Clause 2** — *spelling-invariance.* Untouched. `unsanctionedForeignHome`
  resolves both sides, and HIS-10/#206's symlinked-root cases still pass.
- **Clause 3** — *the lazy contract holds.* This is the risk the widening
  carried, and it is asserted twice: `DEF-104: the SANCTION survives the
  widening` (a grandchild clone's inherited wrapper is not a leak; delete the
  descent record and the same bytes are), and the graph node's
  `DEF104_control_home_verify_is_silent_about_the_undamaged_copy`. Both were
  observed red under the V1 mutation, so neither is decorative.
- **`describe` stops inventing a fifth answer.** A home with no
  `gateway.properties` no longer reports `(owned)`. The measured state — root
  home and project home both claiming one port with one listener — is no longer
  reachable through this verb.

## 3. Contribution to GOAL-no-destructive-recovery (guard)

Clause 2 is *"one command names what is damaged in a home and what would repair
it."* HIS-13 shipped that command; this ticket makes the finding reachable from
the **ordinary** verb, which is the one an operator and `HomeFixpointLaw`
actually run. `home verify`'s isolation remedy now names
`home repair --home <X> --fix` for this kind specifically — `sync
--force-scripts` reaches `CliShimPruner`, which prunes **links** and walks past
a wrapper, so printing it here would have been a remedy that runs and repairs
nothing.

Clause 3 — *the guard does not become a new hold-back* — is the sanction
control above, plus the second graph set in §5.

## 4. What each defect cost, and what it now costs

| defect | before | after |
| --- | --- | --- |
| DEF-102 | `--help` for any verb walked the ambient home and printed the outstanding-error banner | help is text; the closing report is skipped for help and version |
| DEF-104 | `home verify` exit 0 on a home whose shims run another home's code | same verdict as `home repair`, same subjects, at every tier |
| DEF-105 | `home policy` reported one of the home's two policy files and never named the other | both named, defaults marked as defaults, security-relevant values quoted |
| DEF-106 | `policy`, `cli` and `gateway` printed as facts when all three were defaults | each marked with where the answer came from |
| DEF-107 | `HomeMembershipLaw` called deliberate test staging a product violation; CORE graph red | the fixture declares what it staged; the law's self-test proves the declaration cannot swallow the GAINED direction |

## 5. Validation

Declared set:

| signal | result |
| --- | --- |
| `uv run pytest specs/` | **169 passed** |
| `jbang RunTests.java` | **ALL PASSED** (two new suites, 9 new cases) |
| `python skills/test_graph/scripts/run.py home-integrity` | **18/18** (run `20260824-200955`) |
| `python skills/test_graph/scripts/run.py home-sync` | **19/19** (run `20260824-200253`) — round 1 was 18/19 |

**Second set, and its reason.** The DEF-104 widening changes what `home verify`
says, and `HomeFixpointLaw` runs `home verify` over every home its 24 graphs
produce — and then RUNS THE REMEDY IT PRINTS. So a false positive on a
legitimately inherited wrapper would not have reddened this ticket; it would
have reddened graphs nobody was looking at. The graphs chosen are the ones that
clone homes or materialize child homes with mirrored shims:

| graph | why |
| --- | --- |
| `checkout-home` | clones a home into a project checkout and verifies the clone |
| `ticket-lifecycle` | `wt new` / `wt close` create a child home with mirrored shims and run close-out over it |
| `project-child-home` | `ChildHomeMaterializer.mirrorExistingShim` is what the sanction is about |
| `harness-smoke` | the graph whose child home carries real CLI deps — the measured source of the historical `FOREIGN_HOME bin/cli/pycowsay` false positive |
| `home-clone` | the clone path end to end |

Result recorded in §6.

**Not run: the full sweep.** HIS-6 owns round 3.

## 6. Second graph set — result

See `results/epic-home-integrity-sync/tickets/HIS-21/second-graph-set.md`.

## 7. What I am not confident about

1. **Scope of the widening.** It covers `bin/cli/**` and `bin/mcp/**` only,
   matching `home repair` exactly. A generated wrapper that lives anywhere else
   — `bin/launch/*`, a harness entry point — is still judged only by the
   symlink rule. That is deliberate (the two readers must not differ by scope),
   and it is a smaller claim than "no path in this home reaches another one",
   which is what `home verify` still prints.
2. **`home verify` will now go red on real homes that used to pass.** The
   operator's root home is the first. That is the point, and it is also a
   behaviour change for anything that runs `home verify` as a gate.
3. **DEF-107's fix depends on a marker file surviving a real `home sync`.** It
   does today — measured, `stagedUnitsExcused=5` across four homes — because
   sync copies the unit tree. A sync that started filtering dotfiles would turn
   the graph red rather than silently blind, which is the safe direction, but it
   would be a confusing red.
4. **`--json` for `home describe` is unchanged.** A consumer parsing the JSON
   still sees `"owned": true` for a home that has declared nothing. Argued in
   the ledger; not asserted away.
