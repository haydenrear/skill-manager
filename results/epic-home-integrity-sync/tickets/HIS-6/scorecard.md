# HIS-6 — the scorecard. Nine goals, one verdict per clause.

**Ticket:** #218. **Tree measured:** `feature/218-his-6` at `17648705e67b`, which is
`epic/home-integrity-sync` @ `1764870` plus this ticket's harness fix and its one
deliberately-red assertion.

## Read this before reading a number

**Every configuration is stated, because DEF-074 says a number without one is not a
measurement.**

| what | how it was run |
| --- | --- |
| the sweep, the unit suite, the spec suites, TLC | **the five home vars UNSET** — `env -u SKILL_MANAGER_HOME -u CLAUDE_CONFIG_DIR -u CLAUDE_HOME -u CODEX_HOME -u GEMINI_HOME …`, plus `GRADLE_OPTS="-Dorg.gradle.daemon=false -Djava.net.preferIPv6Addresses=true"` and `COMPOSE_PROJECT_NAME=skill-manager` |
| every CLI measurement | the five vars **pinned** to this worktree or to a scratch home under it, and `SKILL_MANAGER_CLI` pinned to `<wt>/skill-manager` — never a `bin/cli` pin, never a `skill-manager` from `PATH` |
| the read-only reads of the operator's homes | `--home <path>`, no writes, no `--fix` |

**And the build behind every home measured**, because a home built by the released CLI
measures the old product:

| home | CLI pin | that build |
| --- | --- | --- |
| root `~/.skill-manager` | `/opt/homebrew/Cellar/skill-manager/0.24.0/…` | **`0.24.0`, artifact `f61e97a46579`, built 2026-08-21T17:41:12Z.** The release. It predates HIS-21 and HIS-22 entirely, and `skt check` there still reports artifacts unsupported. |
| project `<repo>/.skill-manager` | `<repo>/skill-manager` | `0.24.0+g17648705e67b` |
| this worktree home | its own `bin/cli` pin | `0.24.0+g17648705e67b` |

---

## Phase 1 — the sweep. Read from the artifact, not inferred.

```
$ python skills/test_graph/scripts/sweep.py --scope full \
      --out results/epic-home-integrity-sync/tickets/HIS-6/sweep
graphs_executed=26  graphs_passed=24  graphs_failed=2
  FAILED git-latest-source-tracking: gls.conflict            [+3 skipped downstream]
  FAILED artifact-dag: uninstall.prunes.the.subgraph         [+2 skipped downstream]
```

**`graphs_selected=26, graphs_executed=26, graphs_passed=24`**, read from
`sweep/sweep.json`. 2,942 s of graph time, one graph at a time, in registered order.
Resumption was available and **not used**: no graph was re-run, and the sweep was never
restarted from the beginning.

`--out` names a HIS-6 path on purpose. The default path is the canonical round-1
`sweep.json`, and writing it would have clobbered the baseline — DEF-111, which HIS-21
did by accident. **Both rounds are on disk and readable side by side.**

| | round 1 (2026-08-24, pre-HIS-21/22) | round 3 (this ticket, integrated tip) |
| --- | --- | --- |
| executed | 26 | 26 |
| passed | 23 | **24** |
| `home-sync` / `home.membership.law` | **RED** (DEF-107) | **GREEN** |
| `git-latest-source-tracking` / `gls.conflict` | RED, untriaged | RED, **now triaged: a stale assertion** |
| `artifact-dag` / `uninstall.prunes.the.subgraph` | RED, declared (DEF-066, ARTI-08's) | RED, declared — unchanged |

**Both numbers for the harness fix, kept separate, because one of them is the product
and one of them is this ticket's own repair:**

| | value |
| --- | --- |
| **as measured, before any change by this ticket** | **26 executed, 24 passed** |
| after the `GlsConflict.java` stale-assertion repair, that one graph re-run in isolation to a `--out` of its own | `git-latest-source-tracking` **PASS**, 12 of 12 nodes, all **7** assertions green including the three new DEF-108 ones and the precondition |

The first is the number the epic is graded on. The second is the evidence the repair
works. **They are not the same number and are not added together.**

**What the sweep found that was new: nothing.** That is the honest headline for
instrument A and it is not a failure — five of the seven defects this ticket filed live
in surfaces no node touches. What only a full sweep could settle is in
`instrument-comparison.md`, and it is three things: DEF-107 fixed on the *integrated*
tip, DEF-043 resolved by re-measurement, and DEF-108 triaged.

## GOAL-no-spurious-holdback — **PASS / PASS**

| clause | verdict | measured |
| --- | --- | --- |
| 1 — 0 of the 18 records cause a hold-back | **PASS** | `ProjectChildHomeMaterializationTest`, *"a pristine unit whose record names a store that is gone is not held back"* — PASS in `jbang RunTests.java`, home vars unset |
| 2 — a unit that genuinely differs is STILL held back, same message | **PASS** | the same suite's *"a unit that GENUINELY differs is still held back, foreign record or not"*, and `ScaffoldedTreeIsNotContentTest`'s *"a unit that GENUINELY differs is still held back, excluded paths or not"* — both PASS |

**Confirmed independently at the CLI**, which no clause required: a whole-home
`home sync` with the destination's copy of `his6-control` genuinely edited printed
`held-back skill:his6-control — locally modified` and left the destination bytes
untouched, in the same run in which `his6-probe` was promoted. Clause 2 is not held
only by a fixture.

## GOAL-sync-quiet — **PASS / PASS**

Rendered through the product's own `DriftReport` over the committed baseline record
`baseline/root-home-drift.json` — 7 units, the real thing, not a lookalike:

```
units            = 7
ROLLUP  lines    = 8          ROLLUP  chars    = 473
DETAIL  lines    = 896        DETAIL  chars    = 88308
char reduction   = 99.4644%   line reduction   = 99.1071%
```

| clause | target | measured | verdict |
| --- | --- | --- | --- |
| 1 | ≤ 10 lines, ≥ 99% character reduction | **8 lines, 99.4644%** | **PASS** |
| 2 | the per-file list still reachable, unchanged | `renderDetailed()` = **896 lines / 88,308 chars**, byte-identical to the baseline; `home sync --json` carries `files` per unit; `home drift --detail` answers in full however many times the gate has been shown | **PASS** |

The rollup is one line per unit plus one total — exactly the target's shape.

> **The two numbers this goal has, kept apart.** The **retrodiction** — that 86–94% of
> the baseline's volume was one unit's dereferenced symlink trees — is a claim about
> *why the old number was what it was*. The **forward-going measurement** is 22 paths
> over 23 live units. Neither is the other, and neither is the clause-1 number above,
> which is a rendering ratio over one committed record. All three are real; conflating
> any two is a reporting defect.

## GOAL-gate-settles — **PASS / PASS, with clause 2's scope narrowed by DEF-116**

Measured on a real CLI against a real gate, with a positive control:

```
surface 1 (full)        4 lines   484 chars   EXIT=8
surface 2               1 line    345 chars   EXIT=8   "1 unit still unread — … --ack … to clear"
surface 3               1 line    345 chars   EXIT=8
surface 4 --detail      4 lines   382 chars   EXIT=8   the explicit ask still answers in full
--ack                   2 lines               EXIT=0
after ack               1 line                EXIT=0   "no unread change"
```

| clause | verdict | note |
| --- | --- | --- |
| 1 — 2nd and later surfacings are one line naming the count and the ack command | **PASS** | measured above and in `HomeDriftGateTest`'s *"the second surfacing of one gate is a line, and a new change re-opens it"* |
| 2 — a change the agent has not seen still gates the next launch, and `DriftGate`'s reason for not clearing on digest refresh still holds | **PASS as specified, and NARROWER than it reads** | the regression the rationale cites is still caught (*"a second measurement does not retire an unread change"*, *"a change arriving on top of an unread one is added, not substituted"* — both PASS). **But `home sync` arms no gate at all** (DEF-116), so "the next launch" is gated for `project sync` and for `home drift --record`, and not for the promotion command this epic is about. |

**Clause 2 passes and the goal is weaker than the clause.** Reported as measured; not
repaired, because repairing it would be changing the product to widen a goal this
ticket grades.

## GOAL-symlink-merge-settles — **PASS / PASS**

| clause | verdict | measured |
| --- | --- | --- |
| 1 — 0 units left in `MERGE_CONFLICT` by the synthetic fixture, `skt sync` completes for both | **PASS** | `sync-settles` PASSED in the round-3 sweep |
| 2 — an installed-record error is re-derived from the live tree | **PASS** | `sync-settles` PASSED; `ARecordedErrorIsAFunctionOfTheLiveTree`'s expected-violation cfg still refutes exactly that invariant under TLC |

**And the goal has a second, unplanned witness: DEF-108.** `gls.conflict` is red
*because* the product stopped stranding a unit's repository in a merge conflict. The
node still requires `<<<<<<<` markers and `UU SKILL.md`; the CLI now rolls the merge
back and prints a remedy that works. A graph asserting the pre-goal behaviour is the
strongest evidence available that the behaviour changed.

## GOAL-home-invariants — **PASS / PASS, held partly by hand**

```
uv run pytest specs/                                   88 passed
SPEC_REQUIRE_TLC=1 … test_tlc_expected_violations.py   25 passed  (desired_program_model)
SPEC_REQUIRE_TLC=1 … test_tlc_expected_violations.py   25 passed  (current)
```

`test_the_model_checking_half_is_not_silently_skipped` PASSED with
`SPEC_REQUIRE_TLC=1`, so TLC really ran — a skip here is a gap, not a pass, and it did
not skip.

| clause | verdict | measured |
| --- | --- | --- |
| 1 — every new invariant carries an expected-violation cfg that fails, and something asserts TLC named THAT one | **PASS** | **14** `HomeIntegrityInternal_regression_*` cfgs, each parameterised by the invariant it declares, all passing; plus 2 reachability probes, the healthy cfg, and 1 guard cfg. Baseline was 4 invariants / 5 cfgs. |
| 2 — all 5 pre-existing regression cfgs still fail | **PASS** | run by hand, each naming a *distinct* invariant: `Internal_regression_nonatomic` → `InFlightMaterializationLeavesTheChildUnitIntact`; `…_overwrite` → `AgentEditsSurviveMaterialization`; `…_partialclone` → `NoUnfinishedDestinationSurvivesAFailedClone`; `…_unreanchored` → `EveryOwnedSurfaceIsReanchoredBeforeAHomeIsHandedOver`; `ClaimantRefreshInternal_regression` → `AutomaticClaimantRefreshDoesNotPullTrunk`. All rc=12. |

**The gap inside the pass, and it is the one to attack.** HIS-5's mechanised runner —
the thing that makes "the cfg fails" a claim about the *right* invariant rather than
about iteration order — covers `HomeIntegrityInternal` **only**. The five pre-existing
cfgs above are outside it, so clause 2 is currently held by a human running five
commands. That is precisely the condition HIS-5 built the runner to end, and it should
be extended, not celebrated.

## GOAL-mechanism-documented — **PASS / PASS at the root tier; unreachable at the other two**

**Clause 1 — decided by READING THE PROSE, not by the count.** The count is
`4 of 4 instructing units reach the contract` (`uv run .github/scripts/check-docs-coverage.py`
against the real root home, exit 0), and the script's own docstring says that number
cannot decide this clause — it counts term hits, so a restating unit scores *higher*
than a linking one. So:

| unit | what it actually does | verdict |
| --- | --- | --- |
| `skt` | **states the contract**, once, in `references/derived-artifacts.md`. `SKILL.md` points at it three times and carries no copy — the machine-specific "13 stale of 39" example now lives inside the contract page where it belongs | the single statement |
| `skill-manager` | *"are **not** documented here. The contract is stated once, in the skt plugin, at …"* + the GitHub fallback for a home without skt | **links** |
| `git-issue-workflow` | *"Go to `…/plugins/skt/skills/skt/references/derived-artifacts.md` … not to the source"* + the fallback | **links** |
| `git-epic-workflow` | *"skt owns it; this skill does not restate it, and it is absent in a home without skt"* | **links, and says so** |

**PASS.** Stated once, linked three times, no copy anywhere.

**Clause 2 — the judged read.** **PASS at the root tier**, and this is the cell whose
contamination audit came back cleanest, so it is the one to trust.

A fresh reader, given only `*.md` under the root home's `skills/` and `plugins/`, was
asked the goal's three questions — *what is a derived artifact, what does a clone
inherit versus declare, when do you rebuild.* It answered all three correctly, from
**one file**, `plugins/skt/skills/skt/references/derived-artifacts.md`, and cited
sentences for each:

- *"anything a home **produced** rather than authored"*, with the ledger's identity-only
  property and the reconciliation rule *"the home wins on facts, the ledger wins on
  existence"*
- inherit versus declare, including **exit 86** — *"declared, not built. It does not
  mean broken"* — and the distinction between the immediate source home's `bin/` and
  the whole descent chain, and that `home.provenance.json` **grants nothing**
- *"Rebuild after **you** change the unit that owns the artifact. Not on arrival. A
  fresh clone that has built nothing is a healthy clone."*

**Its own contamination audit rates this cell CLEAN**, and argues the point rather than
asserting it: nothing in the ~40-skill listing, the operator's auto-memory, the injected
`gitStatus`, or the `SubagentStart` hook describes derived artifacts; the nearest brush
is a memory row naming *"CLI shim"*, which supplies the noun and none of the contract.
The reader states it would have had to answer `NOT ANSWERABLE` with no corpus. **That is
the goal's baseline exactly — the last two agents to need this read the source and got
it wrong.**

The same read also confirms `exit 79` reached the root home: it is at
`skills/skill-manager/references/projects.md:450` with its cause and its remedy, verified
by grep with a control (`skill-manager` → 186 hits in that unit; `\b79\b` → 2, both the
right lines).

> **The caveat that belongs in the same breath.** All three pointers name a path that
> **does not exist** at the project or worktree tier, because neither home has the skt
> plugin. Each pointer says so and gives a GitHub fallback, which is honest and is why
> this is a pass rather than a fail — but *"the contract is one `git clone` away"* is a
> weaker property than *"the contract is in front of you"*, and it is the property two
> of three tiers actually have.

## GOAL-one-home-one-answer — **FAIL / PASS / PASS**

| clause | verdict | measured |
| --- | --- | --- |
| 1 — one verdict per scenario; no two readers disagree about the same path in the same home, with no operator-supplied flag | **FAIL** | **DEF-115.** One home holding a symlinked shim into another home: `home verify` exit 1 `FOREIGN_HOME bin/cli/linktool`; `home repair` exit 0 `✓ nothing … is damaged (0 entries examined)`. Control, same home, a *wrapper* naming the same path: both exit 1, same subject. With both hazards present, verify names two subjects and repair names one. |
| 2 — the verdict is spelling-invariant, and the assertion fails when the resolution is removed | **PASS** | `HomeVerifyPathSpellingTest` 4/4; and measured at the CLI by an evaluation agent through an alias path — identical verdict, identical finding set, identical exit |
| 3 — a clone keeps its inherited shims and declares them; nothing prunes and re-provisions a toolchain nobody changed | **PASS**, and on the REAL project home | read-only: `home verify --home <project home>` exits **0** with *"5 shim(s) … link at its parent store — a child home shares the parent's provisioned tools by design"*. The baseline was five `FOREIGN_HOME` failures needing an operator-supplied `--against`. The sanction is now durable and in the descent record. |

**Clause 1 fails, and the failure is DEF-104 with the mirror flipped.** DEF-104 was
verify blind to shim *content*; HIS-21 fixed that and declared *"one extraction rule …
one verdict … for both readers, because two spellings of the question is what DEF-104
WAS."* Repair was never taught to read *links*. Every existing comparison of the two
readers plants a wrapper, which is exactly why nothing caught it.

**Not fixed here, on purpose.** Fixing it would be repairing the product to make a goal
this ticket grades come out green. Filed as DEF-115 with the assertion in the tree
**and red**.

## GOAL-no-destructive-recovery — **PASS / PASS-with-a-live-counterexample / PASS**

| clause | verdict | measured |
| --- | --- | --- |
| 1 — a failed resolve/install into a non-empty home leaves every previously installed unit byte-identical | **PASS** | the walk-back cases in the unit suite pass; `home-integrity` PASSED in the sweep |
| 2 — one command names what is damaged and what would repair it, idempotently | **PASS as specified; the specification is narrower than the goal sentence** | `home repair` exists, reports, repairs, is idempotent, and `home-integrity` PASSED. **But the goal's sentence is "a home that has ALREADY been damaged can be told apart from a healthy one", and this evaluation produced a home neither reader can tell apart** — see below. |
| 3 — the guard does not become a new hold-back; a merely STALE home is not damaged | **PASS** | *"a merely STALE home is not reported as damaged"* PASSES; `GOAL-no-spurious-holdback`'s fixture did not regress |

**The live counterexample, found by accident and worth more than the verdict.** During
this evaluation an agent's `skt publish` wrote a foreign unit into the operator's
project home. The result is a home holding a unit in `skills/` with a
`.materialization/skill/<unit>.json` record and **no `installed/<unit>.json`**. Both
readers, read-only, at the epic tip:

```
home verify --home <project home>   EXIT=0   ✓ every reference … resolves
home repair --home <project home>   EXIT=0   ✓ nothing … is damaged (16 entries examined)
```

Clause 2 asks that damage be *"detectable by a command rather than by an operator
noticing"*. This damage was detected by an operator noticing. **The clause passes
because it is written about four enumerated shapes and this is a fifth** — which is the
honest reading, and it is also the argument for why an enumerated list of damage shapes
is the wrong long-term design. Filed as DEF-121.

## GOAL-progressive-disclosure — **PASS at one tier / PARTIAL / PARTIAL**

**The headline correction, and it is checkable in one command.** HIS-20's finding was
*"the lower-tier gap is provisioning, not prose — one `skill-manager project resolve`
closes it"*, and it escalated that write as DEF-096. **That remedy has still not been
run.** Measured today, read-only:

```
<project home>/skills     -> deploy-helm  spec-double-compiler  test-graph  tracing-observability
<project home>/plugins    -> (empty)
<project home>/projects   -> (empty)
skill-project.toml        -> [skills.spec-double-compiler] [plugins.skt] [skills.skill-manager]
```

So on the real machine **the project and worktree tiers are still at HIS-20's BEFORE
corpus**, and HIS-20's AFTER readings at those two tiers remain counterfactual. HIS-22's
manifest-shortfall reporter confirms it from the product side — a `home clone` of that
home prints *"2 of 3 unit(s) declared by … are not installed in this home … declared but
absent: skill:skill-manager, plugin:skt"* with the remedy. **The reporter works; the
remedy has not been run.**

**What HAS changed since HIS-20, and it is real:** the root tier is measurable for the
first time. The leaf PRs merged and the root home synced, so `check-docs-coverage.py`
reports **4 of 4** on the operator's actual root home, and `exit 79` — HIS-20's headline
F-Q3 cell — is present at `skills/skill-manager/references/projects.md:450`.

| clause | verdict | why |
| --- | --- | --- |
| 1 (downward: tier, inheritance, what I may change, what I must not touch) | **PASS at root; FAIL at project and worktree** | root's corpus now carries the tier model and the two-axes fact. The lower tiers hold four consumer skills and no plugin; every correct tier answer HIS-20's lower-tier readers gave traced to **one ~18-line section of `spec-double-compiler/references/runtime_requirements.md`**, a consumer skill explaining the home system incidentally in order to say where `tlc2` comes from. That is still the state on disk. |
| 2 (upward: how my change reaches the tier above, what git carries, what close-out decides) | **PARTIAL** | PASS at root. At the lower tiers, unmeasurable from the corpus that is actually there. And `D-Q3` stayed PARTIAL at **all three** tiers even in HIS-20's overlay, because three pages disagree about whether editing a store copy in place is supported practice or an antipattern. |
| 3 (change management: the command, the tier, the repository, per unit) | **PARTIAL, and now measured at the root tier** | see below |

### Clause 3, measured at the root tier by a judged read

The reader was asked for the **command**, the **tier** and the **repository**, per unit.

| part | result |
| --- | --- |
| **command** | **PASS** — `skill-manager unit publish <name> --ticket <t>`, with the plugin rule stated correctly (*"the unit is the plugin"*, so an edit to a contained skill publishes to the plugin's repo). **But CONTAMINATED, and the reader said so first:** the `skill-dev-skill` entry in every subagent's system prompt contains, verbatim, *"`skt publish` (home sync one tier up, then unit publish)"*. It reports it would have answered this correctly **with no corpus at all.** |
| **tier** | **PARTIAL** — "the home that installed the unit, whose bytes you edited", correctly sourced. The reader then flagged a real gap unprompted: the corpus documents the two-leg split **only at the worktree tier** and never says what `skt publish`'s sync leg does at the **root** tier, where there is nothing above. |
| **repository** | **PARTIAL, and the corpus is right to be** — 16 units named from prose, **7 NOT ANSWERABLE**, and the mechanical answer (`<home>/installed/<unit>.json`, `origin`) points at a **non-Markdown record**, so a docs-only reader cannot execute it. The corpus states outright that no command prints it and labels its own examples *"illustration, not a registry"*. |

**Two things this read produced that a score would have hidden.**

The reader refused a temptation and named it: the operator's auto-memory advertises
*"[Skill unit-to-repo map] — unit names differ from repo names"*, pointing at a file
that plausibly contains the whole answer to the repository column. It did not open it,
and said so. **That is the four-channel contamination problem behaving visibly for once**
— and it is also why this read is evidence about the corpus and not about a fresh agent.

And the sentence that decides `skt check` — *"compares each change-managed unit's
installed hash against its source's tip"* — lives in **`git-epic-workflow/SKILL.md`,
not in the skt skill**, which is where the reader looked first. The fact is documented;
it is documented in the wrong unit.

**And the epic's own front door is the counterexample this goal was written about.**
DEF-119: `bootstrap-home.sh` picks the CLI off `PATH`, three of four fresh evaluation
agents hit it on their first command, and **no tier discloses it.** It joins the four
facts HIS-20 lists that every ticket agent needed and that appear in no unit's
documentation. The list did not get shorter during this epic; it got one longer.

> **The judged reads are NOT blind, and this ticket's own read is not either.** Four
> inbound channels are documented in HIS-20's README — the ~40-skill listing, the
> operator's auto-memory, the injected `gitStatus`, and the `SubagentStart` hook. HIS-6
> has no harness that closes them; a subagent dispatched from this session inherits all
> four **by construction**. The root-tier read this ticket ran is reported with its own
> contamination audit and is **evidence about the corpus, not about a fresh agent.**

---

## Summary

| goal | clause 1 | clause 2 | clause 3 |
| --- | --- | --- | --- |
| GOAL-no-spurious-holdback | PASS | PASS | — |
| GOAL-sync-quiet | PASS | PASS | — |
| GOAL-gate-settles | PASS | PASS *(narrowed by DEF-116)* | — |
| GOAL-symlink-merge-settles | PASS | PASS | — |
| GOAL-home-invariants | PASS | PASS *(held by hand for 5 cfgs)* | — |
| GOAL-mechanism-documented | PASS | PASS *(root tier only)* | — |
| GOAL-one-home-one-answer | **FAIL** *(DEF-115)* | PASS | PASS |
| GOAL-no-destructive-recovery | PASS | PASS *(live counterexample, DEF-121)* | PASS |
| GOAL-progressive-disclosure | **PASS at root, FAIL at project + worktree** | PARTIAL | PARTIAL |

**Nineteen clauses. Fifteen PASS, two FAIL, two PARTIAL** — and four of the fifteen
passes carry a qualification stated above rather than a footnote.

**No target was edited, no goal was repaired, and nothing was re-run selectively until
a number passed.** The one product-side defect that decides a clause (DEF-115) is filed
with its assertion in the tree and red.
