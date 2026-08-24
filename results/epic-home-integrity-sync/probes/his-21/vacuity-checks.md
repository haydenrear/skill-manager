# HIS-21 — vacuity checks

**Eleven probes over five production files and one graph node.** V10 and V11 were
added at the review of PR #256, for the two majors it found. Every row names
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
| **V10** | `HomeCloner.verifyRoots` | the shared `scanShimDirs` call, replaced by the per-file check **inside the walk** — i.e. the PR-#256 state | `FAILURES: 1` | only `MAJOR-1: verify and repair agree when bin/cli IS A SYMLINK`. **Both DEF-104 cases stayed GREEN**, which is the proof the two exercise different branches — one link apart |
| **V11** | `HomeCommand.print` | the `cloneFailureRemedy(report)` call | `FAILURES: 1` | `MAJOR-2: a refused `home clone` names a remedy…`. The refusal itself still prints, so a test asserting only "clone refuses" stays green |

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
**`[19/19]`, dead last**. All twenty-four wirings in `build.gradle.kts` declare
a predecessor. Ordering was never the cause, and it could not have been the fix
either — the staged units are still on disk at the end of the graph, which is
exactly when a post-condition looks. A `dependsOn` on `SPEC` itself is also
unimplementable as stated: the node has a *different* predecessor in each of the
twenty-four graphs, so a SPEC-level declaration would name a node most of them
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


---

# Addendum — review of PR #256

Three findings came back, no blockers. All three reproduced before being fixed.

## MAJOR-1 — the scope was declared twice, and it reached the verdict

The PR claimed *"one extraction rule, one verdict, one scope"*. **Two of three
were true.**

```
HomeRepair.java:128    List.of("bin/cli",  "bin/mcp")
HomeCloner.java:2071   List.of("bin/cli/", "bin/mcp/")
```

and they **enumerated** differently: `Files.newDirectoryStream` follows a
symlinked `bin/cli`, `Files.walkFileTree` does not. Reproduced on the fixed
tree, `bin/cli` a link at a directory outside the home that is not itself a
home:

```
home verify --home X   -> exit 0, "no path in it reaches any other Skill Manager home"
home repair --home X   -> exit 1, FOREIGN_PATH_IN_SHIM bin/cli/wrapper
```

That is V1's red shape, on the fixed tree — DEF-104 **not fully removed**, one
*link* down instead of one *segment* down.

**Fixed by moving the enumeration to where the rule and the verdict already
were**: `HomeCloner.scanShimDirs` is now the single enumerator, `HomeRepair`
consumes it (keeping only its remedy text and its unrepairable-container
finding), `SHIM_DIR_NAMES` is declared once and the prefix spelling is derived
from it, and `HomeRepair.collectRegularFiles` / `shimDirLinkedOutOfHome` were
deleted rather than left as a second copy. Controls: the clean home still
verifies clean, and a `bin/cli` that links into another **home** is still
reported **once** (from the walk's symlink arm) and not twice.

## MAJOR-2 — a refusal with no remedy, on the command that leaves a directory behind

`home clone` verifies its copy with the same check, so it inherited the new
refusal and printed **nothing to do about it**, while leaving a populated
destination that fails its own `home verify`.

Fixed: `cloneFailureRemedy` names the **source** first — re-anchoring rewrites
paths naming the source, and these name a *third* home, so repairing only the
copy leaves the next clone failing identically — offers the repair-in-place
exit second, and says out loud that the copy was left and why (deleting it is
the walk-back-that-destroys shape of #186).

**And the remedy was run as printed, which is the epic's actual standard:**

```
1. clone            -> exit 1, remedy printed
2. eval "$REMEDY"   -> exit 0, "repaired 1 of 1 finding(s)"
3. re-clone         -> exit 0, "cloned home to …"
```

## MAJOR-3 — recorded, not fixed: DEF-112

An **unrepairable** `FOREIGN_PATH_IN_SHIM` gives `HomeFixpointLaw` a remedy
that cannot converge. Filed with the blast radius measured independently rather
than taken from the review — 24 carriers, 16 run green (7 mine, 9 the
reviewer's), 2 skipping the law via a pre-existing upstream failure, and **6
unrun by anyone: `smoke`, `plugin-smoke`, `skill-dev-smoke`, `source-tracking`,
`onboard`, `project-profiles`.**

The reviewer is right that "five clone-heavy graphs" was the wrong frame. It
understated the radius by naming the graphs I judged most *likely* to break
rather than the population the change can reach.

One thing the review did not have: the operator's real root home is **fully
repairable**. Measured read-only (`home repair`, no `--fix`), all five findings
print `repair: rewrite that path to /Users/hayde/.skill-manager/...`. So the
non-converging case has no known real instance.

## The minors, and what was done

| minor | done |
| --- | --- |
| the success sentence is universal and the scope is not | one `isolationScopeCaveat()` appended to all three verify sentences and to clone's, naming the boundary in the OUTPUT rather than in a pull request nobody reads |
| DEF-105 fixed one direction only | `policy show` now names `home.policy.toml` and what it governs — the filed defect was *"neither verb names the other"* |
| a test name asserting a universal the code does not hold | renamed to *"a home that declared no gateway does not report the default as OWNED"*, which is what the body drives |
| describe human vs `--json` disagree | recorded in the ledger entry as a known, latent divergence with no in-repo consumer |
| shared extraction blind spots | written into `pathTokensIn`'s javadoc: `$`-interpolation, relative escapes, and spaces — with the note that the spaces case is the only one that can report something FALSE rather than nothing |
| "twenty-three wirings" | **24**, counted; and 24 of 24 declare `dependsOn`, so the argument survives. Corrected in the law's javadoc, the backlog and the ledger |
| a spec pin that matches its own inversion | the pin now includes `if (!` and asserts the inverted spelling is **absent**; proved to discriminate |
