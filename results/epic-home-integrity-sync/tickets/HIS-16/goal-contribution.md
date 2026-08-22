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

- **An UNSET axis is an ESCAPE, not a pass.** An unset agent root resolves,
  eventually, to the operator's real `~/.claude`; `SmEnv`'s class comment records
  what that cost the last time — five units projected into all three real agent
  homes, eighteen symlinks repaired by hand. "I could not look" is never reported
  as "I looked and it was fine". **V3.**
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

_Filled in below from the runs themselves; nothing here is reconstructed._

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

## 8. What I am unsure about

_Filled in below._
