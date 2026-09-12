# Bug attribution — epic/one-unit-one-name

Every defect this epic has found or fixed so far, attributed to the
architectural component that PRODUCED it — not the file the patch landed in.
The question is which part of the design keeps generating work.

Counted once each. My own instrument errors are counted too: a measurement
that reports the wrong number costs more than most product bugs, and leaving
them out flatters the ledger.

Through wave 5 (OUN-0..OUN-5, OUN-9, OUN-10, OUN-12), plus the #311 fix
and the OUN-11 retirement.

## The table

| component | count | defects |
| --- | ---: | --- |
| **Epic scaffolding — assignments that emit unexecuted strings** | **4** | nonexistent `spec_unit` script; hardcoded per-epic maps + stale `BASE`; `feature/oun-0` vs `feature/OUN-0`; a rationale naming a closed workflow |
| **Measurement instruments (mine)** | 4 | zero-indent `skill-imports` parsed as none; hardcoded operator path as the protected-home guard; temp homes leaked on kill; agent-home axes unpinned in the probe |
| **Spec promotion / close-out** | 2 | accepted `spec_manifest.yaml` replaced by the workflow's; CI hardcoded `specs/current/tests` |
| **Measurement instruments (mine)** — wave 2 | 2 | the harness measured the PREVIOUS build; the harness DELETED the home it was measuring |
| **Product — agent-home boundary** | 1 | `install` removes `<home>/plugins/` when `CODEX_HOME`/`GEMINI_HOME` names a Skill Manager home (#311) |
| **Dev loop — build caching** | 1 | jbang keys its cache on the entry script, so `./skill-manager` runs a build older than its own sources |
| **Product — home portability across a machine boundary** | 4 | DEF-282 a clone carries `auth.token`; DEF-283 `bin/cli` bakes an absolute home path; DEF-284 the gateway state a copy inherits; DEF-285 CoW is APFS-only and `pm/` is Mach-O |
| **Documentation** | 1 | unimplemented behaviour written in the present tense in a doc that ships into homes |
| **Test-and-registration ergonomics (mine)** — waves 3–5 | 4 | a suite registered against an anchor that exists on one branch only, so ALL PASSED with it never running; `shebang.contains("sh")` matching a temp dir named `shim-home-1234`; `assertEquals(1, …count())` failing as "expected \<1\> but was \<1\>" on long-vs-int; a graph node reading its control AFTER the cleanup that erased it |
| **Deletion tickets leave the hole behind** | 1 | OUN-4 removed `skill-dev-skill` from git and left its nested `.git/` plus untracked build litter in the working tree, which then blocked the next worktree's clean-slate check |
| **Plan modelling (mine)** | 3 | a goal bundling four independent properties, so retiring one contributor forced all-or-nothing; the metric renumbered at the retirement while the baseline prose kept the old letters; a goal graded as a CENSUS of the homes on one laptop, which no amount of correct code moves |
| **Wired but not exercised (mine)** | 2 | OUN-5's sync path keyed on the target list, so `sync skill-manager` — the retired unit named without its carrier — performed no migration; and a passing test that asserted exactly that behaviour was correct |
| **Fixtures that are not the world (mine)** | 3 | a migration fixture with no git checkouts, where every real unit is one; a two-copies fixture planted as a record with no lock entry, a shape the product never creates; and a graph node that built it inside the SHARED fixture home and left it damaged |

Twenty-eight. Through wave 1 not one was in the product's resolver, installer or
store — correct for a measurement-only ticket. Wave 2 found the first product
defect (#311), and it is **not** in the surfaces this epic changes either: it
is in the agent-home boundary, reached by a harness, not by the resolver work.

## The largest class is the machinery, not the product

**Epic scaffolding produced four, and all four are one shape:** the
assignment declared something that was never resolved against reality.

- `spec_unit` named `.skill-manager/skills/spec-double-compiler/scripts/run_spec_units.py`
  — present in no installed version of that skill and in no checkout. Every
  assignment this renderer has ever produced carried it, so the declared spec
  validation was never run by anyone it was handed to.
- Three per-epic constants were hardcoded maps keyed by slug. A new epic died
  with a `KeyError` on its own name, and the near-miss was worse than the
  crash: adding a map entry would have stamped the *previous* epic's base
  commit onto every assignment.
- The declared branch was `feature/oun-0`; `skt ticket new OUN-0` — the front
  door the skill itself names — creates `feature/OUN-0`. On a case-insensitive
  filesystem those are one ref, so the disagreement would have surfaced as
  something stranger than a missing branch.
- `validation.tlc` explained itself by naming a workflow that had been closed
  and removed in the very commit the epic is based on.

The common cause is structural: **`validate_assignment.py` checks shape, never
truth.** It confirms `spec_unit` is a string. Nothing confirms the string can
run, that the branch matches what the tooling creates, or that the named
graphs exist.

*Recommendation:* the validator should RESOLVE what the assignment declares —
commands, branch, base commit, graph names — before the issue URL is handed
out. Four accidental discoveries collapse into one gate.

## My instruments produced as many defects as the machinery

Four, and the instructive one is the frontmatter parser: a top-level
`skill-imports:` whose sequence items sit at zero indent parsed as *no
imports*. No unit on this machine is written that way, so every measured
number was correct — and would have stayed correct right up until the day it
silently was not.

The other three are all the same admission: **a harness that writes is a
harness that needs the same guardrails as the product.** It installs units,
so it needed a real protected-home set instead of one absolute path; it
clones homes, so it needed to take its litter with it; it spawns the CLI, so
it needed every home axis pinned, not the one that is easy to remember.

## The close-out is a two-defect component with one cause

Both came from `e07e577`: a promotion performed by copying files rather than
through the promoter, and a CI step that hardcoded a directory which only
exists while a workflow is open. Closing the workflow removed the directory
and turned every CI run on every branch red before collecting a test.

*Recommendation:* neither defect is about specs. Both are about **a step that
assumes a transient tree is permanent.** Worth checking whether anything else
in CI or the skills names `specs/current` unconditionally.

## Wave 2: the instruments produced more defects than the feature did

OUN-1's product change is one branch in one method and four test cases. It
worked the first time. What did not work was everything used to *observe* it,
and both failures had the same signature — **the harness reported a defect
the product did not have.**

**It was measuring the previous build.** jbang keys its cache on the hash of
the ENTRY script. `SkillManager.java` barely changes, so editing anything
under `src/main/java` leaves the cached jar in place and `./skill-manager`
keeps running the old one. `RunTests.java` is a different entry script, so the
unit suite rebuilt and went green while the harness, driving the wrapper,
stayed red. Two instruments disagreeing about the same change, and the one
that was wrong was the one closer to the user.

**It was deleting the home it measured.** Wave 1's review added
`CLAUDE_HOME`/`CODEX_HOME`/`GEMINI_HOME` pinning to the probe on the theory
that more pinning is safer. `CODEX_HOME` and `GEMINI_HOME` are the config
*directory*, not its parent, so pointing either at a Skill Manager home makes
`install` remove `<home>/plugins/<plugin>` — exit 0, silent. The probe emptied
the plugins directory and then reported that a plugin-contained skill was not
addressable. Both statements true, unrelated, the second caused by the first.

*Recommendation, and it is the same one both times:* **a measurement must
assert the conditions it depends on.** The harness now checks that the CLI it
ran is newer than the sources, and that the unit it is asking about is still
on disk after the probe. Neither check is clever; the absence of both is what
turned a working change into an hour of chasing.

## And a defect worth having found

#311 is the wave's only product defect and it came from misusing the product,
which is the least respectable way to find one and does not make it less real:
**silent deletion of a store directory, reported as success.** It joins #262
and #289 in the "which home does this write?" class, with the distinguishing
property that this one's answer is destructive rather than merely wrong.

## The #311 fix, and what its own test caught

The fix was written against one write path and was **incomplete**. Both faces
come from the same expression — `CodexAgent.pluginsDir()` is
`$CODEX_HOME/plugins` and `CodexAgent.skillsDir()` is `$CODEX_HOME/skills`, and
those two variables name the config directory itself rather than its parent —
but they are reached through different code:

| face | path | effect |
| --- | --- | --- |
| **a** | `PluginMarketplace.cleanupLegacyAgentPluginEntries` | deletes every installed plugin |
| **b** | `LiveInterpreter.syncAgents` → projector | replaces each installed unit with a link to its own path |

I fixed **a**, wrote the graph node, and the node's *control* found **b**. The
control exists for an unrelated reason — "the plugins survived" is satisfied
by an install that failed before reaching the cleanup, so the node also asserts
the install it asked for really happened. That assertion came back
`exit=0 unitInstalled=false`, which is what a self-referential symlink looks
like to `Files.isDirectory`.

*The generalisable part:* **a control written against vacuity found a second
defect, not a vacuous pass.** The two are the same shape — both are "this
assertion is true for a reason other than the one you think" — which is why
controls keep earning their cost in this repository.

The guard is now one question on the store (`ownsUnitDirectory`) asked from
both paths, rather than two guards that could drift apart. That is the same
correction the wave-1 attribution recommended for a different component: one
place to ask, not one per caller.

## An instrument note, counted separately because it cost time

`checkout-home` went red during this fix's graph sweep. It was **not** a
regression: I invoked the runner with `SKILL_MANAGER_HOME` exported, and that
graph is precisely the one that asserts a pinned shim binds the home it lives
in — so an inherited home variable naming a different home is a conflict it is
right to refuse (`exit=79`). Green with `env -u SKILL_MANAGER_HOME`, same
commit.

Every other graph tolerated the variable, which is what made it look like a
finding. Recorded so the next sweep does not re-diagnose it.



---

# Waves 3–5: the shape changes

Waves 1 and 2 were dominated by the epic's own machinery and by my
instruments. Waves 3–5 are the first where **the product's own findings are
the biggest class** — DEF-282 through DEF-285, four defects about a home
crossing a machine boundary, none of which any test on this repository could
have caught, because every one of them is invisible on the machine that made
the home.

## The near-miss worth more than any of the bugs

OUN-5 retires the standalone `skill-manager` and `skill-dev-skill` from a home
on upgrade. I went to check DEF-OUN-008 — the owner's "if it's in a unit store,
migration has to be flawless" — and looked at what those two units are on the
real root home:

```
/Users/hayde/.skill-manager/skills/skill-manager/.git/
/Users/hayde/.skill-manager/skills/skill-dev-skill/.git/
```

**Every installed unit is a git checkout.** A retirement is an uninstall, an
uninstall deletes the tree, and a home is the only place a unit's history lives
until `unit publish` moves it. The migration as designed would have silently
eaten unpushed skill edits — the single worst thing a migration can do, on the
one path every existing home is required to take.

It is not in the defect count because it never shipped. It is here because of
**how** it was found: not by a test, not by review, but by opening the actual
directory the code was about to delete. The unit tests I had already written
all passed, and would have passed against the destructive version, because
`TestHarness.scaffoldUnitDir` does not create git repos — the fixture was
tidier than the world.

*Recommendation:* where a change deletes something, the fixture has to be built
from what the real thing looks like, not from the minimum that satisfies the
type. `UnitSupersession.blockedFrom` now refuses, and its control pushes to a
real bare remote so "published" is proved rather than assumed.

## The registration defect recurred, and the fix caught it

OUN-10's suite was registered by anchoring an edit on a string that existed on
the epic branch and not on `main`. The edit silently did nothing and the run
said **ALL PASSED**. The correction was one line — assert the anchor is
present before replacing it.

That assertion **fired for real two tickets later**, on OUN-5, where the
anchor I guessed (`dev.skillmanager.effects.ContainedNameCollisionIsRefusedTest.run()`)
was written in the file as an imported short name. A silent no-op became a
loud failure in the same session that introduced the guard.

*The generalisable part:* every edit that is "insert next to X" is a claim
about X, and an unverified claim about a file is exactly as reliable as an
unverified claim about anything else.

## An attribution I got wrong, corrected in public

The owner reported test-graph runs "failing on CI because of otel". I turned
the SDK off — the `Exporter failed` lines are real, from the Spring server's
`PeriodicMetricReader` with no collector on the runner — and then said plainly
that it fixes **none** of the seven failing graphs, which fail for five other
reasons, three of them "the runner is not a developer's machine".

Recorded here because the tempting move was to ship the change and let the
green PR imply the diagnosis. A fix that does not fix the reported problem is
worth less than the sentence saying so.

## What the four portability findings have in common

DEF-282 (credential), DEF-283 (frozen shim), DEF-284 (gateway), DEF-285
(CoW + Mach-O) were filed from **outside** this repository, by the effort trying
to put a home in a container. Every one is a property of a home *elsewhere*:

- the credential is correct where it was written and a leak where it lands;
- the shim resolves where it was written and dies where it lands;
- the clone economics hold where they were measured and vanish where they land;
- `pm/` executes where it was built and answers ENOEXEC where it lands.

**No test that runs on the machine that made the home can see any of them.**
That is the class, and it is why three of the four fixes are about making the
home *carry an answer* — a skipped file, a self-deriving path, a platform stamp
— rather than about making the copy smarter. The fourth, the gateway, was
retired to `substrate-home-model` because nobody could state the rule it should
hold to, and inventing a rule to fit the current state settles nothing.

*Recommendation for the container work:* the meta-harness image build should
run `home clone --portable` and then `home verify` **inside the image**, not on
the host. Two of these four would have been caught at build time by exactly
that, and the other two now announce themselves in the clone output.


---

# The follow-up the owner's question produced

OUN-5 merged, and the owner asked: *"I'm a bit concerned also that the
migration path isn't well defined?"* It was not. Two defects, both mine, in
work that was green an hour earlier.

**The declared slice was not delivered.** OUN-5's own conflict key is
`test_graph: ["home-sync"]` — the sync path was in the ticket from the start.
I wired the effect into `SyncUseCase` and wrote every test against `install`.
The wiring compiled and read correctly, and the trigger was keyed on "is the
carrier among the sync targets", so:

| command | migrated? |
| --- | --- |
| `sync` (whole home) | yes — the sweep names every unit, `skt` among them |
| `sync skill-manager` | **no** — names the retired unit, not its carrier |

The second is the command a person runs after being told the migration exists.

**And a green test asserted the defect was correct.** `"nothing is due when the
carrier is not the unit being installed"` passed in the merged PR, and what it
encoded was the bug.

*The generalisable part, and it is not the one I would have guessed:* the
ticket's own metadata said where the risk was. `test_graph: ["home-sync"]` is a
declaration that the sync path is in scope, written down before any code
existed, and I read it as a merge-conflict key rather than as a coverage
obligation. **The conflict keys are a checklist of surfaces the ticket must
prove it did not break, and nothing checks that the tests touch them.**

*Recommendation:* the close gate should compare a ticket's declared
`conflict_keys.test_graph` against the graphs its evidence actually names. A
ticket declaring `home-sync` and citing only `plugin-smoke` should have to say
why — which is a check on the same footing as the assignment validator
resolving what it declares, and would have caught this before the PR opened.

## And a goal that could not be met by writing correct code

The same conversation retired the census. `GOAL-migration-lands-on-one-skt`
graded "how many of the 38 homes on this laptop hold (0,0,1)", which counts
homes nobody has synced yet and reads *not yet upgraded* and *cannot be
upgraded* as one number. Every home migrates itself when it syncs, so the
mechanism is the thing to grade — now measured once per route, `install` and
`sync`, with the real homes reported as context.

Filed as plan modelling rather than as an instrument defect, because the
harness was faithfully measuring the wrong question.


## The same lesson twice in one afternoon, from opposite directions

The git-checkout near-miss was a fixture **tidier** than the world:
`scaffoldUnitDir` makes no git repositories, so the tests could not see that a
retirement deletes a working copy.

The two-copies fixture was a fixture **sloppier** than the world: it planted an
`installed/` record with no `units.lock.toml` entry, a half-registered state the
product never produces, and the retirement then left an orphaned record that
`home.membership.law` failed the graph over.

Both were caught by something other than the tests written for the change — the
first by opening a real home, the second by a law node written for an unrelated
reason that runs over every home a graph produces. Neither would have been
caught by more of the tests I was writing.

*What generalises:* the fixture is a claim about the world, and it is the one
claim in a test that nothing checks. Where a change touches a state the product
GETS INTO rather than one it merely accepts, the fixture has to be built by the
product's own operations — install it, sync it, let it arrive — not assembled
from the files that state happens to consist of. Both defects here are the same
sentence: *I wrote down what the state looks like instead of asking the product
to reach it.*

The corrected fixtures do the second thing, and the goal harness gained the two
assertions that would have caught the difference on its own: the command must
exit 0, and the home must hold no `installed/` record for a tree it does not
have.
