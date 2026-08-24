# HIS-20 — goal contribution

Issue #247. Branch `feature/247-his-20`, base `epic/home-integrity-sync` at
`4ed578a`. Wave 13, promotion order 19, predecessor HIS-19. Schedule revision 9.

Goals: **`GOAL-progressive-disclosure`** (`direct`, and this ticket measures it)
and **`GOAL-mechanism-documented`** (`guard`).

---

## 0. Headline, and the part a reviewer should attack first

**Measured in both directions at all three tiers, gaps closed where a document
could close them, re-measured with fresh agents — and the largest single gap
turned out not to be a documentation gap at all.**

Read §1 before the numbers.

Three things are weaker than they look, and none of them is hidden below:

1. **The lower two tiers moved because the HOME changed, not because prose
   changed — and review measured the split rather than accepting the estimate.**
   It built the arm this ticket did not: the real worktree home after `project
   resolve`, with both units at their **pre-ticket** GitHub `main`. Resolve, zero
   prose. **9 of 11 cells reach PASS with no prose at all**; 1 stays PARTIAL
   (C-Q1); **exactly 1 required this ticket's prose (F-Q2)**.

   The converse is a proof, not a measurement: every line this ticket wrote lands
   in `skill-manager` and `skt`, and neither unit exists at the two lower tiers
   before `project resolve` — so prose-without-provisioning is **byte-identical
   to the BEFORE corpus**. **This ticket's documentation delivers zero at 2 of 3
   tiers until DEF-096 runs.**

   Two attributions in the first version of this document are **withdrawn**:
   "C-Q4 entirely" (the resolve-only reader answered it correctly from the
   pre-existing `unit publish --home` flag, with three honest cautions) and
   "C-Q1's repository column at every tier" (four mappings were already
   derivable; the genuinely new thing is the `installed/<unit>.json` lookup).
2. **Two of the three AFTER corpora exist nowhere on disk.** The root corpus is a
   sandbox holding this ticket's units *as published* in two open leaf PRs; the
   real root home gets them when those merge and it syncs. The project corpus is
   an explicitly labelled counterfactual. Only the worktree AFTER corpus is a
   home that exists. HIS-8 carried the same caveat and it is carried again here.
3. **The doc-only sandbox is not airtight — and the first version of this
   document audited ONE leak channel of four, then certified as "clean" the two
   cells that channel 2 answers standalone.** The operator's auto-memory
   `MEMORY.md` is injected into every subagent context and contains
   *"SKILL_MANAGER_HOME alone still writes into the real ~/.claude; CLAUDE_HOME
   is the parent of .claude/"* (**F-Q1, entire**) and *"drive scratch homes with
   the raw build, not a home's bin/cli pin"* (**F-Q2**). Two of three fresh
   readers quoted it unprompted; one said *"my abstention was a choice, not
   ignorance."* The `gitStatus` block is a third channel.

   **The strongest evidence for the goal was the most contaminated evidence, and
   the section asserting otherwise had not looked.** Corrected, with a cell-by-cell
   re-certification against all four channels, in
   [`judged-read/README.md`](judged-read/README.md#the-instruments-own-contamination--corrected-2026-08-24-after-review).
   The cells are **discounted, not struck**. F-Q3's exit-79 delta survives
   re-certification and the survival was *checked*, not asserted: no channel
   carries any numeric exit code, and the nearest `gitStatus` subject introduces
   `EXIT_CODE = 12`, not 79.

Full method, per-question scoring and the change-management table:
[`judged-read/README.md`](judged-read/README.md). Twelve transcripts:
[`judged-read/before/`](judged-read/before/) and
[`judged-read/after/`](judged-read/after/).

---

## 1. The finding this ticket is really about

> **An agent standing in a project or worktree home could not learn the tier
> model from the documentation, because the units that document it were not
> installed there — and the manifest that home is meant to realize already
> declared two of them.**

Measured 2026-08-24, read-only, before any change:

| | skills | plugins | `projects/` |
| --- | --- | --- | --- |
| root `~/.skill-manager` | 20, incl. `skill-manager`, `git-issue-workflow`, `git-epic-workflow` | 3, incl. `skt` | populated |
| project `<repo>/.skill-manager` | 4 | **none** | **empty** |
| worktree `<wt>/.skill-manager` | the same 4 | **none** | **empty** |

`skill-project.toml` declares `[skills.spec-double-compiler]`, `[plugins.skt]`
and `[skills.skill-manager]`. The project home holds one of those three and
three units the manifest's own comments explain at length why it deliberately
does *not* declare. `project resolve` had never run against it.

The consequence is the whole of the lower-tier BEFORE result. Both readers found
the same thing without being asked, and said so in almost the same words:

> "essentially every correct answer above traces to ONE file's ONE section … That
> section happens to be unusually good … but it is describing the tier system in
> order to explain where `tlc2` comes from, not to specify it."

That file is `spec-double-compiler/references/runtime_requirements.md` — a
consumer skill, in an unrelated unit, carrying the tier contract as a
prerequisite for finding its own runtime. It is simultaneously why the lower
tiers scored above zero and a textbook instance of the defect
`GOAL-mechanism-documented` clause 1 exists to prevent.

**Escalated as DEF-096**, with its second half stated as a decision for the
owner rather than taken by a ticket agent: resolving the manifest as written
still leaves `git-issue-workflow` — which owns `skill-homes.md`, and therefore
the two-axes fact, the CLI-pin resolution order and `bootstrap-home.sh`'s exits
— uninstalled at both lower tiers, and declaring it pulls in exactly the units
`skill-project.toml` gives a reasoned refusal to declare.

---

## 2. `GOAL-progressive-disclosure` — the three clauses

The goal's metric is (a) judged reads scored per question, (b) the count of
questions whose correct answer required reading SOURCE rather than
documentation, and (c) whether an agent can name, per installed change-managed
unit, the publish command and its tier.

**Clause 1 (downward)** and **clause 2 (upward)**: measured at all three tiers,
before and after, per question, in
[`judged-read/README.md`](judged-read/README.md). Not restated here.

**Metric (b) — questions answered only from source: zero, by construction.** The
sandbox made reading source mechanically unavailable rather than relying on
compliance, and every reader's `COMPLIANCE` field is in its transcript. What the
metric was really asking — *how many questions could only have been answered
from source* — is the set of cells scored `FAIL`, and they are named as failures
per question rather than aggregated.

**Clause 3** is the change-management table in
[`judged-read/README.md`](judged-read/README.md#the-change-management-table--goal-progressive-disclosure-clause-3):
six units, each with its GitHub repository, its publish command, the tier to run
it from, and a `--dry-run` verification of each. It also records the two things
an agent could not previously get: that **no command prints the unit→repository
mapping**, and where it is recorded instead.

### Four cells stay PARTIAL or FAIL after the fix, and are named as failures

- **D-Q3 at all three tiers.** Three pages disagree about whether editing a store
  copy in place is the supported practice (`projects.md`: "An agent that improves
  a unit does it *inside a home*") or an antipattern (`unit-authoring`: "**Never
  edit the store copy in place.**"), with skt's tier table giving the project
  tier "none — pull-side only" in between. The `unit-authoring` half was scoped
  to authoring in `skill-publisher-skill#35`; the tier-table row was corrected;
  the three-way reconciliation is not this ticket's to finish.
- **C-Q1's *tier* column at root.** `skill-homes.md` says "push from the **main
  checkout**. From a worktree the skill's upstream is the wrong target"; three
  other pages make the worktree tier canonical and, inside an epic, the only tier
  a ticket agent may use. The corpus genuinely conflicts. **DEF-097.**
- **F-Q1 at the lower tiers, BEFORE.** Scored `PARTIAL`, not `PASS`, and this is
  the scoring call most worth arguing with. Both readers said "no,
  `SKILL_MANAGER_HOME` is not enough" — correctly — but from a PATH argument,
  never naming `CLAUDE_CONFIG_DIR`, `CLAUDE_HOME`, `CODEX_HOME` or `GEMINI_HOME`.
  An agent acting on that answer exports one variable and a PATH and still writes
  the operator's `~/.claude`. The HIS-14 fact is that a home has **two axes**;
  that answer has one and a half.
- **`skt check`'s scope** is described two ways by two skt pages ("stale
  artifacts" vs "unpublished-work notifications"). Reported, not fixed.

---

## 3. `GOAL-mechanism-documented` — the guard, and it bit

The guard says: close gaps by **linking** to HIS-8's single statement, never by
restating it. `check-docs-coverage.py` cannot decide this — it counts term hits
per unit and scores a restating unit *higher* than a linking one — so clause 1
was decided by reading the prose, and by asking two readers directly.

**The instrument's number, run 2026-08-24 against the real root home:
`0 of 4 instructing units reach the contract`.** That is unchanged by this
ticket and is not this ticket's to move: it reads `~/.skill-manager`, and HIS-8's
four leaf PRs (`skill-publisher-skill#34`, `skill-manager-skill#4`,
`git-issue-workflow-skill#23`, `git-epic-skill#16`) are still open. Reported as
found, not adjusted.

**Where the guard bit this ticket:**

1. **It restated a measurement in two units.** The same 2026-08-24 finding was
   written out in different words in `skt/SKILL.md` and in
   `skill-manager/references/skill-imports.md`. A reader: *"a reader who meets
   both has to stop and check whether they are two measurements or one. One
   should own it; the other should cite it."* Fixed — skt owns it, skill-imports
   cites it.
2. **It wrote a measurement that went stale the same day.** "its project home and
   a ticket worktree cloned from it each held four skills, no plugins, and no
   `skt`" was true before `project resolve` and false of the home the reader was
   standing in. **The epic's founding defect — a copy that becomes wrong and
   fails nothing — reproduced inside the fix for it, in under a day.** The
   sentence now carries its tense and names the command that changed the state.
3. **It found a pre-existing restatement and cut it.** `workflows.md` re-derived
   `projects.md`'s whole upward path — flags, held-back/conflicted outcomes, the
   no-`--force` argument — nearly sentence for sentence, while itself saying
   "stated once in git-issue-workflow's `references/skill-homes.md` … point
   there, do not re-derive it". That pointer was **false** (the model is stated
   in at least three installed places) **and dangling** (git-issue-workflow is
   not installed at two of three tiers). Both corrected; the section cut to three
   routing lines.
4. **`skill-manager/SKILL.md` re-derived the tier model two lines above its own
   pointer to `projects.md`**, and called that page's upward path the one
   "nothing else documents". Cut to a pointer plus the one fact worth carrying
   before the hop.

**An index term is not a restatement**, per the epic's own reversal on `exit 86`.
Applied here: the literal `79`, the literal refusal string, and four symptom
phrasings routed from `SKILL.md` are pointers. The sentence explaining what 79
*means* is stated once, in `projects.md`.

---

## 4. What was changed, and where

Two units, both leaf-first per this repository's own rule, and both mirrored into
the tracked snapshot so this PR carries the text.

| unit | repository | PR | what |
| --- | --- | --- | --- |
| `git-issue-workflow` | `haydenrear/git-issue-workflow-skill` | [#24](https://github.com/haydenrear/git-issue-workflow-skill/pull/24) | `worktrees.md`'s copy-on-write claim, corrected against a measurement run twice on two homes (DEF-098) |
| `skill-manager` | `haydenrear/skill-manager-skill` | [#5](https://github.com/haydenrear/skill-manager-skill/pull/5) | what `unit publish` can publish and the two remedies; where a unit's repository is recorded; the two legs of `skt publish` decided separately at the worktree tier; exit 79; the `--allow-same-home` correction; the `skill-imports` inheritance correction and the `home clone` validation gap; three restatements cut |
| `skt` | `haydenrear/skill-publisher-skill` | [#35](https://github.com/haydenrear/skill-publisher-skill/pull/35) | "on PATH in every skill-manager home" scoped and given fallbacks; the same claim fixed inside the snippet it propagates into other repos' agent files; the retired `skill-publisher` unit name; "never edit the store copy" scoped to authoring; the project tier's obligation |

**Both PRs were opened by hand, mirroring `unit publish`'s own conventions**
(`skill/247-his-20-<unit>`, PR against trunk) — because the ticket worktree could
not publish either unit until `project resolve` ran, and afterwards the `home
sync` leg is forbidden by this ticket's own brief. That is DEF-077, walked rather
than described, in the ticket that documents it.

---

## 5. What HIS-6 should read this as

- `GOAL-progressive-disclosure` is **measured, in both directions, at all three
  tiers, before and after**, per question, with the failures named.
- The direct contribution of *documentation* is: exit 79 at root, the
  unit→repository lookup at every tier, the DEF-077 split at the worktree tier,
  and the CLI-pin mechanism at the two lower tiers.
- The direct contribution of *provisioning* — which is larger, and is not this
  ticket's — is DEF-096.
- **The goal is not met at the project tier on the real machine**, and will not
  be until DEF-096's first half is run. The AFTER project transcript is labelled
  counterfactual for exactly that reason.
- **The goal is not met at the root tier on the real machine either**, until
  `skill-manager-skill#5` and `skill-publisher-skill#35` merge and root syncs.
  The AFTER root corpus is a sandbox.
- **It is met at the worktree tier on this machine, today**, because that home
  was actually changed and actually re-read. **But read the split above before
  crediting that to this ticket:** 9 of the 11 cells that moved there would have
  moved on `project resolve` alone.
- **The close-out gate finding is DEF-101, not part of DEF-096.** They were
  filed as one entry and review was right that this lost the second: DEF-096 is a
  provisioning defect that one command clears; DEF-101 is a product defect in
  `close-out`'s verdict vocabulary that outlives it. `close-out` has no way to say
  *"the destination can obtain this from its own manifest, at a published ref"*,
  so it must block over units that are unmodified checkouts at published SHAs
  which the destination already declares — and the remedy it then prints is one
  an epic ticket agent is forbidden to run.
- **DEF-098 is closed by measurement.** `skill-manager home clone` **is**
  copy-on-write: measured twice, on two different homes, by free-space delta with
  both controls — `home clone` 31.0 MB / 22.2 MB against `cp -R` of exactly the
  same bytes at 511.3 MB / 498.0 MB. `worktrees.md`'s "it is copying rather than
  cloning" is wrong and is corrected in
  [`git-issue-workflow-skill#24`](https://github.com/haydenrear/git-issue-workflow-skill/pull/24);
  its *timings* stand, and only the inference from wall clock to blocks fails.
  **"Nearly free" is justified for disk, not for wall clock.** HIS-6's disk
  arithmetic can now use a number instead of choosing between two pages.
