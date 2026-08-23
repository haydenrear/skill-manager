# HIS-16 — goal contribution

Issue #237. Branch `feature/237-his-16`, base `epic/home-integrity-sync` at
`28378e7`. Wave 7, promotion order 12, predecessor HIS-15.

Goals: **GOAL-no-destructive-recovery** (`direct`) and
**GOAL-one-home-one-answer** (`direct`).

Declared expected effects: *"a unit vanishing from a home nobody named is bytes
destroyed by an operation that reported success"*, and *"`SKILL_MANAGER_HOME`
and the working directory stop being two answers to which project this command
is about."*

---

## 0. What the first version of this document claimed, and did not deliver

**Read this before the rest.** Review of #241 returned four blockers, and the
first unmade the headline deliverable. Everything below §1 has been re-measured
against the fixes; this section is what the reader is owed up front.

**The membership law did not catch its own stated claim.** Its sentence is *"no
home gains a unit nobody installed into it; none loses one nobody removed."* The
GAINED half was **structurally unreachable against a real home**, because the
law's only reader — `home describe` — was classified `WRITE`, so
`tryReconcile` ran ahead of it and *manufactured the missing installed-unit
record microseconds before the comparison*. It fired only in the synthetic
self-test. §9 of the first version said a reader "could reasonably believe it
catches more than it does". That was right and badly understated: it did not
catch the headline claim either.

**And on the only home in the entire evidence set that was actually damaged, the
law returned PASS.** From the shipped envelope, the victim home after the graph
node's own control reproduced the escape for real:

```
PASS  …/victim/.skill-manager
   disk    [his16-unit-claimed-later]      ← seeded as his16-unit-kept
   records [his16-unit-claimed-later]
   lock    []
```

0-for-1 against real damage; 3-for-3 against its own fixtures. The escaping
resolve rewrote the records to match what it had done, so the home was
internally consistent about a membership nobody asked for — which is DEF-047's
own lesson, arriving through my instrument instead of being caught by it.

**Three further claims in the first version were true of the report and false of
the product:**

| claimed | actually |
| --- | --- |
| *"An UNSET axis is an ESCAPE, not a pass"*, with V3 attached | `confined()` and `escapes()` were **computed and never enforced**. With every home axis unset under a declared confinement, `project resolve` exited 0 and scaffolded `$HOME/.skill-manager`. |
| the guard protects an operator | **nothing armed it.** `grep SKILL_MANAGER_CONFINE_ROOT` returned three hits: the constant, the test-graph helper, and this document. DEF-046's shape re-run with the diff merged still went through at exit 0. |
| DEF-051 defers a symlink gap | it deferred the false-*negative*. The false-*positive* was live: `/tmp` and `java.io.tmpdir` are both symlinks, so any driver declaring the obvious root got a **100% refusal rate**. |

All four are fixed and re-measured — `probes/his-16/review-241-blockers.txt`
carries the before/after for each. What the law catches **now**, against a real
home, is the GAINED direction and the residue form of LOST. It still does not
see a clean re-realization, and **DEF-054** records that its one reachable LOST
mode depends on a separate bug HIS-13 is pointed at: fix that and the mode
disappears.

The reviewer also measured the cost the first version did not: **~8–15 s per
graph, ~4–9 minutes on a ~7-minute sweep** (DEF-055). That is not worth paying
for a law that only fires in its own fixtures. It is worth paying now that
GAINED reaches a real home, and it roughly halves if the two laws share one
pass instead of paying 2× JVM starts per candidate.

---

## 1. Half the slice already existed, and that changed what the ticket is

The slice reads: *either the `project` verbs accept an explicit project root, or
they refuse when the CWD-derived target is not the home `SKILL_MANAGER_HOME`
names.*

**`--project-dir` already exists**, on `register`, `resolve`, `sync`, `remove`
and `profiles list`, and every graph node that drives a project verb already
passes it. So the first half of the "either/or" was shipped before this ticket
and the defect happened anyway — because the escape is not "there was no way to
say which project"; it is **"nothing noticed that nobody had said."**

That makes the ticket the second half, and it forces one design question the
issue does not settle: *when* may a `project` verb refuse?

**It cannot refuse whenever `SKILL_MANAGER_HOME` names a home other than the
CWD-derived project's.** That is the product's main path —
`cd ~/myrepo && skill-manager project resolve`, root home in
`SKILL_MANAGER_HOME`, the project's own child home somewhere else entirely — and
it is the whole of the `project-child-home` graph. A rule that fired there would
refuse the feature this epic exists to make work.

So the two halves of the slice are **one mechanism**: a process **declares** that
everything it touches lives under one root, and a command whose target escapes
that declaration refuses and names the conflict. Slice item 2 (*"a sandbox is
assertable"*) is not a separate deliverable; it is what arms slice item 1.

---

## 2. What I built

### `dev.skillmanager.sandbox.Confinement` — the one call

```java
Confinement c = Confinement.current();   // never throws, never null
c.declared();      // was a root declared at all?
c.confined();      // ...and does EVERY axis resolve inside it?
c.escapedAxes();   // ...and if not, which ones?
c.covers(path);    // is this path inside it?
```

Six axes: `SKILL_MANAGER_HOME`, `CLAUDE_HOME`, `CLAUDE_CONFIG_DIR`,
`CODEX_HOME`, `GEMINI_HOME`, and **`cwd`**. Read through
`AgentHomes.resolve`, so an in-process driver declares
`SKILL_MANAGER_CONFINE_ROOT` with the same thread-local mechanism it already
uses for the other five — one mechanism, not a sixth spelling.

Two properties are load-bearing and both are asserted:

- **An UNSET axis is an ESCAPE, not a pass** — and, since review of #241, that
  is now true of the **product** and not only of the report. An unset agent root
  resolves, eventually, to the operator's real `~/.claude`; `SmEnv`'s class
  comment records what that cost the last time — five units projected into all
  three real agent homes, eighteen symlinks repaired by hand. **V3** covers the
  report; `SkillManagerCli.refuseIfConfinementEscapes` is what makes the product
  refuse, measured in `probes/his-16/review-241-blockers.txt` under H2.
  `CLAUDE_HOME` is reported but enforced only when `CLAUDE_CONFIG_DIR` is unset,
  because `AgentHomes.claude()` consults the config dir first — enforcing it
  unconditionally was itself a false refusal, found while fixing H2.
- **`cwd` is expected to escape, and that is the point.** A JVM cannot change
  its own working directory, so an in-process driver can never be *fully*
  confined. `escapedAxes()` names it instead of hiding it, and the honest
  assertion a driver makes is *"the only axis outside my confinement is the one I
  cannot pin"* — see §3.

### `dev.skillmanager.project.ProjectRoot` — one place, six call sites

The same three lines —
`projectDir == null || projectDir.isBlank() ? Path.of(System.getProperty("user.dir")) : Path.of(projectDir)`
— were written **six** times: the five `project` verbs and `EnvCommand`. The
sixth is the one nobody would have thought to change while fixing the `project`
family, which is this epic's signature shape.

`ProjectRoot.resolve(projectDirOption, verb)` is now that one place, and it is
where the CWD axis is checked. When the process is confined and the resolved
root escapes, it throws `ConfinementEscapeException` — **exit 14**, typed
`confinement_escape`, with a message that names the target, where the target came
from, the confinement root, the escaped axes and the remedy.

**Nothing changes for an unconfined process.** `Confinement.covers` answers true
for everything when nothing is declared. That property has its own test, because
it is the one that keeps the product working.

**And something does declare one.** The first version shipped a guard nobody
armed — review of #241, H4. Two mechanisms now do it, and both were needed:
`AgentHomes.binding` carries the confinement root, so every consumer of the one
binding (`--home`, `exec`, `env run`, the printed remedy prefix, and
`home.runtime.json`'s `env` block that the epic's bootstrap and `skt` read) is
confined by construction; and a per-checkout home *implies* a confinement to its
own checkout, enforcing the **CWD axis only**, with the root tier excluded so an
ordinary resolve in a repository outside `$HOME` is untouched. DEF-046's exact
shape now exits 14 with no variable set anywhere.

An explicit `--project-dir` outside the root is refused too — a confinement is a
statement about what may be *touched*, not about how the path was *spelled* —
and the message distinguishes the two, because the remedy differs.

### `skill-manager sandbox status` — the same question from a shell

Exit `0` confined, `1` no confinement declared, `14` declared but escaping. One
call for a driver that is not a JVM. Its `--json` document names every axis and
whether it is inside.

---

## 3. The driver that caused DEF-046 now asserts the axis it could not cover

`JsonContractTest`'s `invokeIn` pinned five variables and asserted the default
home resolved inside its temp directory. That assertion was **correct** and it
answered a narrower question than the one that mattered — the ticket's recurring
shape, in the instrument rather than the product.

It now also declares a confinement over the sandbox and asserts:

```java
assertEquals(List.of(Confinement.CWD), confinement.escapedAxes(),
        "SANDBOX: the working directory is the ONLY axis outside the sandbox");
```

Not `confined()`. A boolean would be asserting a falsehood, and — worse — a
regression that unpinned `CODEX_HOME` would leave it false either way, hidden.
The list form fails and *names the new escape*.

**The payoff is measurable in the guard's own table.** The four `project`
entries were driven on a deliberate parse error, because *"its working directory
cannot be sandboxed"*. They now run on their **real execution paths** —
`project resolve --skip-gateway --json` and friends — and get a typed refusal
document instead of a parse error, because production refuses. DEF-046's
`candidate_shapes` listed exactly two options, `(a)` an explicit
`--project-dir` and `(b)` a subprocess per case; the confinement is a third that
makes the family testable in-process without either.

---

## 4. The membership law

`HomeFixpointLaw` is this project's best instrument — one post-condition, 24 of
30 graphs, *"does every home this graph produced still verify?"*. **It would not
have caught DEF-047**, because a re-realized home verifies fine: internally
consistent, merely wrong about what it holds.

`common/HomeMembershipLaw` asks the close-out gate's question instead, of every
home, in one implementation, wired **beside `HomeFixpointLaw` in all 24 graphs**.

**Three readers, one answer**, per home:

| reader | what it is | what it means |
| --- | --- | --- |
| DISK | `home describe --json`'s unit snapshot — production walking `skills/`, `plugins/`, `docs/`, `harnesses/` | what the home holds |
| RECORDS | `installed/<name>.json` **and** `installed/<name>.projections.json` | what somebody installed |
| LOCK | the `[[units]]` names in `units.lock.toml` | what the manifest resolved to |

A name in DISK and not in RECORDS is **a unit nobody installed**. A name in
RECORDS and not on DISK is **a unit nobody removed** — which is literally how
DEF-047 was found: `deploy-helm` vanished from `skills/` and *its
`.projections.json` record survived, orphaned*.

Copied from `HomeFixpointLaw` deliberately, because each choice is load-bearing:

- **Structural discovery.** Every upstream context value that is an existing
  directory, plus its `.skill-manager` child, is offered to `home describe`, and
  **production decides what a home is** (exit 2 = `NotAHomeException` = skip). No
  second spelling of `looksLikeStoreRoot`. A key list goes stale silently.
- **Sandbox-only.** Homes outside the JVM temp root are skipped, **counted and
  named**. The onboarding graph's scan once surfaced one of the operator's real
  homes.
- **Zero homes FAILS.** An instrument reporting success because it could not
  look is the failure mode being closed. **V5** demonstrates it without a
  mutation.

### The self-test, and why it runs before every real home

Three readers that all return the empty set agree perfectly — mechanism C in the
epic's vacuity ledger, where a detector that reached nothing reads exactly like a
passing check. So the law plants three homes in a temp directory every run and
runs the **same comparison** over them: one consistent (must be clean), one that
gained a unit with no record, and one whose unit is gone with **only its
`.projections.json` surviving** — DEF-047's residue, to the letter. Both must be
flagged, the consistent one must not. Blind and over-eager both fail here.

### The empty lock abstains — a measured refinement, not a weakening

On the first `home-integrity` run the law went red on one home:

```
victim/.skill-manager LOCK DISAGREES — units.lock.toml names []
  with no installed/ record, and installed/ names [his16-unit-claimed-later]
  with no lock entry.
```

The other four homes had all three readers in exact agreement over 12 units. The
one that disagreed is a **project child home**, whose `units.lock.toml` is empty
**by design** — a child home's resolved closure lives in the project registry.
Confirmed independently on `project-child-home`, where the child home also reads
`lock []`. A reader with nothing to say is not a reader that disagrees, and a law
that fired on every child home in the repository would be switched off within a
week. The law's actual sentence — DISK versus RECORDS — is untouched.

### The limit, measured and recorded rather than glossed

**The law is a within-home consistency law, not a temporal one**, and the graph's
own control proved the difference. When the control removed the confinement and
the escape happened for real, the law reported the damaged home **CLEAN** — it
was, because the resolve rewrote the records to match what it had done. The law
sees the **residue** form, which is the form DEF-047 actually took and the form
no other instrument sees; it does not see a clean re-realization.

A post-condition runs at one instant and cannot hold a before-image of a home it
never saw at the start. The before/after form exists only around a *single
operation*, which is what the direct node does. **DEF-048** names the two
candidate shapes and why neither belongs in this ticket.

---

## 5. The direct node — the escape, reproduced and refused

`home-integrity/ProjectVerbStaysInItsHome`, with a real subprocess and a real
`pb.directory(victim)`:

```
FIXTURE  victim home …/his16-confinement/victim/.skill-manager
             -> [skills/his16-unit-kept]   (seed exit 0)
CLAIM    exit 14 (expected 14); victim [skills/his16-unit-kept];
                                driver [skills/his16-unit-kept]
CONTROL  exit 0; victim [skills/his16-unit-claimed-later]  <- the escape, reproduced
```

Seven assertions, all passed. The fixture rewrites the victim's manifest between
the seed and the claim so a second resolve **must** change membership — without
that, an idempotent resolve leaves membership alone whether the guard works or
not, and the check would pass for the wrong reason.

Vacuity discipline, answered by mechanism:

| mechanism | how it is answered here |
| --- | --- |
| **A** — reddens a precondition, not the claim | the control moves `the_other_homes_unit_set_is_unchanged` by name, and the node logs the before/after sets it moved. The two preconditions are separate, separately-named assertions and the control does not re-run them. |
| **B** — the fixture cannot express the defect | `precondition_victim_home_holds_the_seeded_unit` and `precondition_manifest_now_claims_a_different_unit` are asserted before the claim runs. HIS-14's V8 found neither "frozen" fixture was frozen; a precondition that is not asserted is a hope. |
| **C** — the mutation never reached the code | the control does not merely omit the variable, it **removes an inherited one** (`SmEnv.unconfine`), and `the_control_reached_the_guard_it_removed` asserts the two exit codes DIFFER — 14 vs 0. A control that never reached the guard would exit 14 like the claim and is caught. |

---

## 6. Validation

```
jbang RunTests.java     ALL PASSED
uv run pytest specs/    38 passed
jbang RunHis16.java     8 passed, 0 failed
```

`tlc` — **N/A** per the assignment; HIS-5 carries the model work for the epic.

### Graphs

Every run through `graph-run.sh` — one docker stack, one runner at a time, shared
with HIS-18. `python skills/test_graph/scripts/run.py --all` is **HIS-6's** and
was not run.

| graph | why this one | verdict |
| --- | --- | --- |
| `home-integrity` | carries the new direct node and both laws | see below |
| `project-child-home` | `GOAL-no-destructive-recovery`'s declared local signal, and the graph whose fixtures drive `project resolve` hardest | see below |
| `project-smoke` | the `project` verb family end to end — the six call sites this ticket rerouted | see below |
| `home-tripwire` | runs `sandbox.env.contract`, the oracle that decides whether my two new nodes spell the sandbox recipe themselves | see below |
| `smoke` (53 nodes) | the broadest install / sync / bind / uninstall home in the repository — the strongest available evidence that the membership law is not noisy | see below |
| `project-manifest`, `project-resolve` | `project register` and `project resolve` in isolation | see below |

**Four graphs are red on the epic tip and are not mine** — `home-clone`,
`checkout-home`, `artifact-dag` (HIS-17's) and `onboard` (a 10 s timeout). Not
run, not chased.

### The two nodes that decide the ticket

`project.verb.stays.in.its.home` — seven assertions, all passed, and its control
is live:

```
FIXTURE  victim home …/his16-confinement/victim/.skill-manager
             -> [skills/his16-unit-kept]   (seed exit 0)
CLAIM    exit 14 (expected 14); victim [skills/his16-unit-kept];
                                driver [skills/his16-unit-kept]
CONTROL  exit 0; victim [skills/his16-unit-claimed-later]  <- the escape, reproduced
```

`home.membership.law` — three assertions, and the numbers matter more than the
verdict, because a green law that looked at nothing is the failure mode:

Re-run in full after review of #241, on `b71a253`:

| graph | nodes | runId | homes | units | withMembership |
| --- | --- | --- | --- | --- | --- |
| `home-integrity` | 17 | 20260823-002554 | 5 | 12 | 5 |
| `project-child-home` | 13 | 20260823-003147 | 2 | 8 | 2 |
| `project-smoke` | 8 | 20260823-003554 | 3 | 9 | 3 |
| `home-tripwire` | 8 | 20260823-003903 | 1 | 1 | 1 |
| `project-manifest` | 4 | 20260823-004147 | 1 | **0** | **0** |
| `project-resolve` | 4 | 20260823-004318 | 1 | 5 | 1 |
| `smoke` | 53 | see `probes/his-16/graph-runs.txt` | 1 | 22 | 1 |

Every one **ALL PASSED**.

**`project-manifest` is a WEAK run and now says so.** `homesWithMembership = 0`
is M5 from the review, made visible as a number: that graph registers a manifest
and installs nothing, so its one home is empty and the three readers agree about
nothing. I did **not** turn it into a failure — an empty home that is still empty
is a real post-condition, and reddening a graph for installing nothing would be
inventing a defect. What carries the readers' sensitivity on such a run is the
self-test, which runs before every real home on every one of these runs. HIS-6
gets the number for the sweep.

`home-tripwire` carries **`sandbox.env.contract`**, the oracle that fails when
any file except `sources/lib/SmEnv.java` writes a managed variable into a child
environment, or when a node spawns the CLI without routing through it. Both new
nodes pass it, which is what makes `SmEnv.confineTo` / `SmEnv.unconfine` the
right home for the seventh variable rather than a fifth spelling of the recipe.

**`project-manifest` observed zero units**, and that is exactly the case the
self-test exists for: three readers that all return the empty set agree
perfectly. The self-test runs before every real home on every one of these runs
and is what makes the green mean something.

`descriptorDrift` was **0** on all six — see DEF-049 for why that is measured and
not asserted.

---

## 7. What I cut

Four deferrals against a budget of five: **DEF-048** (the law is intrinsic, not
temporal), **DEF-049** (`home.runtime.json`'s persisted snapshot is measured, not
asserted), **DEF-050** (four more commands resolve from CWD and are not routed
through `ProjectRoot` — `onboard` is the one that matters, because it walks *up*),
**DEF-051** (confinement is a check over path spellings; `toRealPath` is
deliberately not called because a root is declared before it exists).

Out of scope by the assignment and left alone: write confinement at the effect
boundary (HIS-9, merged — it guards *paths*, and this defect writes legitimate
paths in the wrong home), and repair of homes already re-realized (HIS-13).

---

## 8. Containment — the ticket is the hazard, so it is measured

Snapshot as the first command of the session, re-read at the end, over the three
homes the assignment names — unit set, full top-level listing, a SHA-256 of every
top-level `.toml`/`.json`, and the `.materialization` mtime:

```
$ diff snapshot-before/full.txt snapshot-after/full.txt
IDENTICAL — no home changed
```

Plus a census of **every** home reachable on the machine — 20 of them — with a
write count for this ticket's window: **zero writes in every one**. Full listing
in `probes/his-16/containment.txt`. The window opens at 18:08 because the
session's own `skt` currency check refreshed the root home's
`cache/skt-check.json` and its `plugin-marketplace` symlinks at 18:05–18:07;
that is recorded rather than filtered out silently.

The snapshot asks the **membership** question, not the narrower "did it write
where it should not" one that missed DEF-047. And `home.membership.law` reported
`homesOutsideSandbox = 0` on all six runs, so no graph even *named* a real home.

---

## 9. What I am unsure about

**The law does not see a clean re-realization** — see §0, which states the
sharper version the reviewer measured: on the one home in this evidence set that
was genuinely damaged, the law returned PASS. DEF-048 says what would fix it,
and DEF-054 records that its one reachable LOST mode rests on a bug HIS-13 is
pointed at.

**I do not know whether the implicit per-checkout confinement is too clever.**
It is inferred from a path shape rather than declared, it changes behaviour by
default, and it is the piece of this diff I would most want a second opinion on.
It enforces only the CWD axis and excludes the root tier for exactly that
reason, and the two cases that must not break are measured — but "measured on
the cases I thought of" is the sentence this epic keeps paying for.

**The `LOCK` reader may be closer to noise than to signal.** It abstains when
`units.lock.toml` is empty, which is every project child home. On the six runs
above it therefore only ever spoke about root-tier homes, where it agreed
perfectly every time. I have no measurement of it ever *disagreeing* usefully,
and I would not object to it being demoted to a metric.

**I did not measure the confinement's cost on the graphs that carry it.** The
membership law adds 7–13 s per graph and is wired into 24 of them. On `--all`
that is a few minutes; HIS-6 owns the sweep and will find out.

**V4a is unresolved as a design question.** Disabling the `.projections.json`
branch did not change the outcome, because the file also ends in `.json` and the
sibling branch catches it under the mangled name `deploy-helm.projections`. The
shipped behaviour is correct — the specific branch is tested first — but the two
readers overlap, and a refactor that reorders them would still "work" while
reporting a name no unit has. I left it, and recorded it rather than quietly
rewriting the probe until it was green.

**The `sandbox status` exit codes are a guess at what a driver wants.** 0 / 1 /
14, with 1 meaning "no confinement declared" specifically so it does not read as
a failed confinement. Nothing consumes them yet except a human.
