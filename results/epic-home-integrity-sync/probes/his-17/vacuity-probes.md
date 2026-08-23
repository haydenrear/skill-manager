# HIS-17 vacuity probes

Every probe here ran with the working tree **committed first** — `probe.sh`
refuses on a dirty tree and did refuse once, which is DEF-035 working rather
than being remembered. Revert is a saved byte copy, never `git checkout`.
Every mutation asserted `count(pattern) == 1` **before** editing, and the script
prints that assertion; a mutation that matched zero or two sites aborts.

Graph: `home-clone`, on `feature/238-his-17`.

**Baseline (unmutated), run `20260823-162727`:** 14/14 nodes pass, 13/13
assertions in `home.cloned.into.project` pass. Metrics: `leakCount=0`,
`toleratedContentReferences=1`, `sanctionedDescentRecords=1`, `verifyExitCode=1`.

---

## V1 — the exemption removed from production

**Mutation.** `HomeCloner.verifyRoots`, the HIS-10 branch, made unmatchable:

```java
} else if (HomeProvenance.isProvenanceRecord(rel + "HIS17-PROBE-NEVER-MATCHES")
```

**Reached?** Yes, and demonstrably: `verifyExit=1`, `home clone` itself exited 1.
Run `20260823-164916`.

**Which assertions reddened — the claim, named:**

| assertion | before | after |
| --- | --- | --- |
| `production_agrees_no_path_in_the_clone_names_the_source` | pass | **RED — the claim** |
| `home_clone_reports_clean` | pass | RED |
| `home_clone_exits_zero` | pass | RED |
| `the_clone_records_its_descent_and_the_scan_counts_it` | pass | pass *(precondition, correctly blind)* |
| `production_still_refuses_a_planted_path_into_the_source` | pass | pass *(different branch)* |
| `independent_scan_finds_no_owned_surface_naming_the_source` | pass | **pass** — see below |

**The finding, stated rather than smoothed over.** The acceptance criterion asks
that each fixed assertion *"fails when the exemption is removed from
production"*. The **node** does. The **independent scan assertion structurally
cannot**, and that is not a shortfall in the probe — it is the reason the
cross-check was required in the first place. The scan reads the filesystem;
`home clone` writes the descent record whether or not `verifyRoots` exempts it;
so the bytes the scan walks are identical either way and `leaks=[]` in both
runs. A test that owns its own copy of a rule cannot notice the rule moving.
**That is the whole defect of this ticket, reproduced under laboratory
conditions, and the assertion that caught it is the one added here.**

## V2 — FIRST ATTEMPT WAS VACUOUS, and this is why it is on the record

**Mutation.** Reassign the leak list before the verdict is built:
`leaks = new ArrayList<>();`

**Result.** `home.clone.fixture.built` failed —
`fixture_units_installed_by_the_real_cli=false`,
`fixture_shim_execs_correctly_before_cloning=false`. Run `20260823-165057`.

**Diagnosis.** The mutation **did not compile**. `leaks` is captured by the
`SimpleWalker` lambda, so reassigning it is `local variables referenced from a
lambda expression must be final or effectively final` — confirmed directly:

```
HomeCloner.java:1754: error: local variables referenced from a lambda expression
    must be final or effectively final                       (×5 sites)
```

Every `skill-manager` invocation in the graph then failed to build, so the
**fixture** collapsed and the node under test never ran.

This is the vacuity ledger's **mechanism C** (the mutation never reached the
code) presenting as its **mechanism A** (a precondition reddened, not the
claim). A run counted at a glance — "I disabled the gate and the graph went
red" — would have been exactly the eleventh instance this ticket was told not to
be. It was caught only because the probe record names *which* assertion
reddened, which is mechanism A's stated countermeasure, and because
`home.clone.fixture.built` asserts its own preconditions, which is mechanism B's.

## V2b — production's isolation check actually disabled

**Mutation.** Empty the findings in place, immediately before the verdict is
assembled: `leaks.clear();`

**Reached?** Yes: `productionAgreesNoPathNamesTheSource=true` while a real
planted path sat in the tree. Run `20260823-165226`.

**Exactly one assertion reddened:**

| assertion | after |
| --- | --- |
| `production_still_refuses_a_planted_path_into_the_source` | **RED — the claim** |
| `production_agrees_no_path_in_the_clone_names_the_source` | pass — **and this is the point** |
| `home.clone.fixture.built` (all) | pass — the precondition held |

The clean half stayed green with the gate switched off. Without the planted
decoy the node would have reported a passing cross-check against an oracle that
could no longer say no — a green that looks identical to a working check. That
is why the control runs on every run rather than once in a probe, which is how
`a4a95cb` measured it and how the second copy of the rule survived the first fix.

## V3 — the scan was narrowed, not blinded

**Mutation.** Make a correct clone leave a REAL state reference to its source,
one line after the sanctioned record is written:

```java
HomeProvenance.recordDescent(src, dst);
Files.writeString(dst.resolve("his17-control.json"), "{\"probe\":\"" + src + "\"}");
```

**Reached?** Yes. Run `20260823-165401`:

```
leaks=[STATE his17-control.json]
descent=[DESCENT home.provenance.json]
```

| assertion | after |
| --- | --- |
| `independent_scan_finds_no_owned_surface_naming_the_source` | **RED — the claim** |
| `production_agrees_no_path_in_the_clone_names_the_source` | RED — both readers agree |
| `home_clone_reports_clean` / `home_clone_exits_zero` | RED |
| `the_clone_records_its_descent_and_the_scan_counts_it` | pass |

The exemption did not absorb it. The descent record is still classified
`DESCENT` and still counted, and a second file naming the source on the same
`STATE` surface is still a leak — so the walk was narrowed to one filename at
one depth, not widened into uselessness. Both readers refused the same file,
which is the agreement `GOAL-one-home-one-answer` asks for.

## Preconditions asserted, and their blindness

Per mechanism A, these are declared as preconditions and are blind to the
mutations above:

* `the_clone_records_its_descent_and_the_scan_counts_it` — reads the filesystem
  and this walk's own classification only. Passed under V1, V2b and V3, i.e.
  under every mutation to production's exemption and gate.
* `the_planted_decoy_is_removed_from_the_clone` — passed in all four runs;
  no decoy has ever survived into a downstream node.
* `home.clone.fixture.built`'s eight assertions — the fixture proving it can
  express the defect. They are what exposed V2 as vacuous.
* `HomeIsolation.plantDecoy` refuses a target that does not exist, so a control
  cannot silently degrade into testing the dangling-reference branch. **It fired
  for real** on the first `artifact-dag` attempt: `home.policy.toml` does not
  exist in that graph's source store, which writes `policy.toml`. That is the
  HIS-14 V8 finding — *neither "frozen" fixture was frozen*, over that same
  filename — caught this time by a guard instead of by a control that passed for
  the wrong reason.
