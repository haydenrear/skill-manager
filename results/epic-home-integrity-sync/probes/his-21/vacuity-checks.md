# HIS-21 — vacuity checks

**Nine probes over four production files and one graph node.** Every row names
**which branch it moves** and **which assertion reddened**, and every red below
was observed by running it — no row here is an argument about what would happen.

Re-run: apply the mutation a V-number names, run `jbang RunHis21.java`, then
revert **by copying the saved file back** — never with `git checkout --`, which
eats uncommitted work (DEF-035, five agents in this epic).

**Baseline: `ALL PASSED`, 30 cases across three suites**
(`DamagedHomeIsRepairableTest` 21, `HelpIsTextOnlyTest` 4,
`HomeReportsMarkWhatTheyInventTest` 5).

| probe | file | branch it moves | verdict | which assertions reddened |
| --- | --- | --- | --- | --- |
| **V1** | `HomeCloner.verifyRoots` | **the whole DEF-104 fix**: the `foreignPathsInShimContent` loop on the regular-file arm | `FAILURES: 2` | `DEF-104: verify and repair return the SAME verdict…` (**verify said `[]`; repair said `[bin/cli/wrapper]`** — the disagreement itself) and the sanction test's own control |
| **V2** | `SkillManagerCli.completeExecution` | the `!helpOrVersionRequested(pr)` guard | `FAILURES: 2` | `DEF-102: --help … no project scan`, `DEF-102: --version is text too`. **The POSITIVE CONTROL stayed green**, which is what makes the two reds mean anything |
| **V3** | `HomeCommand.PolicyCmd` | the `reportInstallPolicy(store)` call | `FAILURES: 2` | both DEF-105 cases |
| **V4** | `HomeCommand.reportInstallPolicy` | the **absent** arm only (`if (!present) return;`) | `FAILURES: 1` | only the "with no `policy.toml` the same verb says so" case — so the two DEF-105 rows really are two branches |
| **V5** | `HomeCommand.renderHuman` | the `descriptor: … (absent — every field below is DERIVED)` line | `FAILURES: 1` | only `DEF-106: a home with no descriptor says every field below is DERIVED` |
| **V6** | `HomeCommand.renderHuman` | the **gateway** arm, restored to `owned ? "owned" : …` | `FAILURES: 1` | only `DEF-106: two homes never both report one gateway as OWNED` |
| **V7** | `HomeMembershipLaw.stagedUnits` | the marker test — made to return **every** unit directory | node red | the law's own self-test: `IT DID NOT FLAG AN UNMARKED INTRUDER standing beside a marked staged unit` |
| **V8** | `HomeSyncSupport.mkUnit` | the `STAGED_MARKER` write (i.e. DEF-107's fix, removed) | node red | `home.membership.law`: `GAINED [hs-delta, hs-epsilon]`, three homes — the round-1 failure, reproduced on demand |
| **V9** | `DamagedHomeIsRepairable` (graph) | run against the **pre-fix** `HomeCloner` (V1 applied) | node red | `DEF104_home_verify_names_the_same_wrapper_home_repair_does` |

---

## The mechanism the ticket was warned about, and where it actually bit

The brief named **mechanism D** — *the probe exercises a different branch than
the change* — as the one most likely to catch this ticket, "because DEF-104 is
explicitly about a branch that does not run for parentless homes."

**It bit, but not there, and the reason is the correction below.** It bit in
`HelpIsTextOnlyTest`'s `--version` case. That case was written as:

```java
var displaced = AgentHomes.bind(home);
control = run("home", "describe", "--home", home.toString());
r = run("--version");
```

`run()` clears the agent-home overrides on its way out, so the binding covered
the FIRST call and not the second. `--version` therefore ran against the
**ambient** home, which has no outstanding errors, and

```
=== RED RUN V2 ===
  [FAIL] DEF-102: `--help` for a verb performs no project scan and exits 0
  [PASS] DEF-102: `--version` is text too      <- under the mutation
```

It was green with the fix and green without it. Caught by running the mutation,
not by reading the test. The bind is per-invocation now (`bound(home, argv)`)
and the arm was re-run:

```
=== RED V2c ===
  [FAIL] DEF-102: `--help` for a verb performs no project scan and exits 0
  [FAIL] DEF-102: `--version` is text too
FAILURES: 2
```

## The headline correction: DEF-104 is not about the tier

Issue #253 states the plausible mechanism as *"`verifyRoots` frames its
foreign-home question against a home's source, and the root home has no parent,
so the branch never runs"*, and adds that this is *"to be confirmed rather than
assumed"*. **Confirmed false.** Two throwaway homes, A holding a regular-file
wrapper that execs a path inside B, measured on the pre-fix tree:

```
home verify --home A                 -> exit 0
home verify --home A --against C     -> exit 0     <- a source, and still blind
home repair --home A                 -> 1 finding
A's shim rewritten as a SYMLINK:
home verify --home A                 -> exit 1  FOREIGN_HOME bin/cli/tool
```

The blindness is about the **surface**, not the tier and not `--against`: the
foreign-home question was asked only of symlinks, and for a regular file the
walk searched for the source needle and nothing else. A wrapper naming a
**third** home was invisible at every tier, with or without a parent.

`HomeRepair.foreignPathsInShims` had said so in its own javadoc since HIS-13 —
*"every check built on `Files.isExecutable` or on link resolution passes over
it"* — and nobody had asked `home verify` the same question. The root home was
simply where the wrappers happened to be mis-pointed.

**Why the distinction is not pedantry.** The fix #253's mechanism implies is
"run the foreign-home branch when `srcRoot == null`", which changes nothing:
the branch it names is the symlink branch, which already ran. A ticket that had
implemented the stated mechanism would have shipped green tests and the defect.
That is mechanism D at the level of the **fix**, and it is the reason the
regression test asserts the `--against` case explicitly:

```java
HomeCloner.Verification withSource = HomeCloner.verify(fx.other, fx.store, false);
assertTrue(… FOREIGN_PATH_IN_SHIM on bin/cli/wrapper …,
        "the same wrapper is found with a source supplied — so the fix is not
         conditioned on the home being parentless, and neither was the defect");
```

## The second correction: DEF-105's premise

#253 filed it as *"the root home carries a security policy the product does not
read"*. **The product reads it.** On a throwaway home holding

```toml
allowed_backends = ["tar"]
require_hash = true
allow_init_scripts = true
```

`skill-manager policy show` printed back all three, from `Policy.load`, which
resolves `<store>/policy.toml`. `policy.toml` is the **install/security** policy
and `home.policy.toml` is the **live/frozen** policy; they are two live files
with two jobs and two verbs.

The defect is narrower and worse than a stale file: **two files in one directory
are both called policy, both govern that home, and neither verb mentioned the
other.** An operator reading `home policy --home ~/.skill-manager` and seeing
`home.policy.toml (absent — live by default)` concludes the home declares no
policy, with a live security policy permitting init scripts beside it.

## The third correction: DEF-107 is not an ordering defect

The brief states the deeper defect as *"`HomeMembershipLaw.SPEC` declares no
`dependsOn`, so it runs mid-scenario and calls an intermediate state final"*.

Measured, run `20260824-193907`: `home-sync` wires the node
`.dependsOn("home.sync.authored.agent.tree")` and the planner ran it
**`[19/19]`, dead last**. All twenty-three wirings in `build.gradle.kts` declare
a predecessor. Ordering was never the cause, and it could not have been the fix
either — the staged units are still on disk at the end of the graph, which is
exactly when a post-condition looks. A `dependsOn` on `SPEC` itself is also
unimplementable as stated: the node has a *different* predecessor in each of the
twenty-three graphs, so a SPEC-level declaration would name a node most of them
do not contain.

What is true is that `SPEC` declaring no `dependsOn` leaves a **future** graph
free to wire the law with no predecessor and get a mid-scenario answer. That is
a latent hazard, it is not this defect, and it is filed as **DEF-109** rather
than fixed here.

## Where an assertion is deliberately NOT claimed

- **`--json` is unchanged for DEF-106.** The descriptor JSON is the file
  `--write` persists and `bootstrap-home.sh` consumes; marking a field
  "not recorded" inside the file whose existence makes fields recorded is a
  contradiction. The synthesis marks live on the human surface, which is the
  surface the defect was measured on. No assertion here claims otherwise.
- **`GatewayConfig.owned()` still defaults to true.** DEF-106 is about the
  REPORT. Changing the resolution default would change what `gateway up` and
  `gateway down` do in every home with no `gateway.properties`, which this
  ticket has no measurement for. The assertion is *"two homes never both
  **report** one gateway as owned"*, and it says report.
- **`CliSource.PINNED_ENV` is not asserted from in-process.** A JVM cannot set
  its own environment, so that arm of `cliProvenance` is unreachable from a unit
  test; the test drives `HOME_ENTRYPOINT` and says so rather than asserting a
  branch it cannot reach. `PINNED_ENV` was observed by hand:
  `cli: …/skill-manager  (from $SKILL_MANAGER_CLI in this process — not a fact
  about this home)`.
