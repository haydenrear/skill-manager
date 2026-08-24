# HIS-20 — the six judged reads, and how they were scored

Issue #247. `GOAL-progressive-disclosure`, direct; `GOAL-mechanism-documented`, guard.

## Method

Six reads BEFORE any documentation change, six AFTER, twelve different agents, no
agent saw another's answers. Each was given **one directory** — a read-only copy
of one home tier's `skills/**` and `plugins/**` front-door and reference markdown
— and told:

> The ONLY directory you may read is `<corpus>`. You must NOT read any program
> source code … NOT run any `skill-manager`/`skt`/`tla-spec-dev`/`git`/`gh`
> command … NOT use WebSearch or WebFetch … NOT answer from background knowledge
> of this codebase. `NOT ANSWERABLE FROM THE CORPUS` is a CORRECT and VALUABLE
> answer. **Do not guess to be helpful. A confident guess corrupts the data.**

Every answer carried four fields: `ANSWER`, `SOURCE` (corpus-relative path plus a
directly quoted sentence, or the literal `INFERRED, not stated`, or `NONE`),
`CONFIDENCE`, and `HOW I FOUND IT`. **The citation requirement is how a lucky
guess is caught**, and per the brief, an answer reachable only by reading source
is scored a failure of its clause, not a pass with a caveat.

Each agent's session `SKILL_MANAGER_HOME` was given to it, because a real agent
has it; nothing else about the environment was.

| read | corpus |
| --- | --- |
| BEFORE root | the operator's real root home — 193 md, 20 skills + 3 plugins |
| BEFORE project | the real project home — 80 md, 4 skills, **no plugins** |
| BEFORE worktree | the real worktree home — 80 md, the same 4 skills |
| AFTER root | root + this ticket's `skill-manager` and `skt` edits overlaid **as published** |
| AFTER worktree | **the real worktree home after `project resolve`**, + the same overlay — 94–102 md |
| AFTER project | the same shape a `project resolve` of the project's own manifest produces, + overlay — **counterfactual, and labelled so** |

### Two things about the AFTER corpora that a reviewer should attack first

1. **The root overlay is a sandbox, not the operator's home.** HIS-8 did the
   same and said so; the same caveat applies here. The real root home gets this
   text when `skill-manager-skill#5` and `skill-publisher-skill#35` merge and it
   syncs. Until then the AFTER root read grades a corpus that exists nowhere.
2. **The project AFTER corpus is counterfactual.** This ticket is forbidden to
   write the project home. What it shows is *what that home holds once resolved
   against its own manifest* — a one-command remedy — so **the delta at that tier
   is attributable to provisioning, not to prose.** The worktree tier's delta is
   real: that home was changed, by `skill-manager project resolve`.

### The instrument's own contamination, measured rather than assumed

Every AFTER read was asked, as a question: *"Your system prompt contains a
listing of ~40 skills with full descriptions. For EACH answer above, say whether
that listing could have supplied the answer independently of the corpus."*

All six answered, unprompted-by-defensiveness and in detail. The finding is
uncomfortable and it is the honest limit of both this ticket's instrument and
HIS-8's:

- **`skt`'s own listing description contains the complete tier enumeration
  verbatim** — "root ~/.skill-manager, project `<repo>/.skill-manager`, or a
  ticket worktree home" — **and** the tool that reports it. Every D-Q1 and U-Q1
  answer in this ticket is contaminated by it.
- **`skill-dev-skill`'s description contains "`skt publish` (home sync one tier
  up, then unit publish)"** — the whole of C-Q2's central claim.
- **`git-issue-workflow`'s description contains "a gate that refuses while
  removing it would destroy unpublished skill work"** — U-Q3's headline.

**What is clean, on all six readers' own accounting:** `held-back`/`conflicted`
(U-Q4), the unit→repository mapping and the `installed/<unit>.json` lookup
(C-Q1's repository column), exit 79 and the CLI-pin material (F-Q1's real answer
and F-Q2 entirely), the close-out exit ladder, and the worktree-tier publish
split (C-Q4). **Those are the questions whose deltas mean the most**, and it is
not a coincidence that they are also the four facts the epic's own record said
were undocumented.

One reader noted the contamination cuts the other way too: *"my prompt lists
`skt:skt` as an available skill, which would have led me to confidently tell you
to run `skt status`. The corpus is what told me it is probably absent in this
home."*

Two readers independently noticed the listing's `skt` description is an **older
revision** than the corpus's frontmatter, so the listing is not even a superset.
A `SubagentStart` hook also injected a line about the live filesystem into three
readers' contexts; one said plainly, *"Your harness leaks state into the
sandbox."* Neither affected an answer, and both are recorded because a sandbox
whose leaks are catalogued is worth more than one assumed airtight.

## What the corpora actually contained — the finding underneath every score

**Before this ticket, two of the three home tiers put ZERO units in front of an
agent that document the tier model.** The project home and a worktree cloned from
it held `deploy-helm`, `spec-double-compiler`, `test-graph`,
`tracing-observability` and **no plugins**. Not `skt`, not `skill-manager`, not
`git-issue-workflow`, not `git-epic-workflow`.

Every correct answer both lower-tier BEFORE readers gave about tiers traces to
**one ~18-line section of `spec-double-compiler/references/runtime_requirements.md`**
— a consumer skill documenting the home system incidentally, in order to explain
where `tlc2` comes from. Both readers said so without being asked:

> "essentially every correct answer above traces to ONE file's ONE section … That
> section happens to be unusually good … but it is describing the tier system in
> order to explain where `tlc2` comes from, not to specify it."

**And this repository's `skill-project.toml` already declared `skt` and
`skill-manager`.** The home had simply never been resolved against it —
`projects/` was empty in both the project home and the worktree home. One
`skill-manager project resolve` closed it. **The lower-tier gap is provisioning,
not prose**, and no documentation change this ticket could make would have closed
it: this ticket owns no unit that those homes held.

## Scoring — per question. This is not a statistic and must not be read as one.

n = 1 agent per (tier, direction, phase). Twelve agents total. A delta below is
one agent's answer against one other agent's answer.

### DOWNWARD — root → project → worktree

| question | ROOT before | ROOT after | PROJECT before | PROJECT after | WORKTREE before | WORKTREE after |
| --- | --- | --- | --- | --- | --- | --- |
| **D-Q1** which tier, what others, how to tell | PASS | PASS | PARTIAL — model found only in an unrelated unit; identity `INFERRED`, and "a path alone cannot separate project from worktree" | PASS | PARTIAL — same, plus the tier inferred from a `wt-` naming convention the corpus never states | PASS |
| **D-Q2** what it inherits, by what mechanism | PASS | PASS | PARTIAL — copy/snapshot found; "the corpus does not name the command that creates a PROJECT home from root" | PASS (`home clone`, "not `project resolve`") | PARTIAL — same | PASS |
| **D-Q3** what I may change | PASS | PASS | PARTIAL — general rule + four epic charters that forbid it outright; composition `INFERRED` | PARTIAL — better sourced, and the reader found a **three-way** contradiction (skt's "pull-side only", unit-authoring's "Never edit the store copy") | PARTIAL — same | PARTIAL — same |
| **D-Q4** what I must not touch above me | PARTIAL — "nothing above root" `INFERRED` | PARTIAL — answered `NOT ANSWERABLE AS POSED`, which is the more honest form of the same result | PARTIAL — every quote from an epic charter; scope `INFERRED` | PASS | PARTIAL — same | PASS — and correctly returns `NOT ANSWERABLE` for "does *my* brief forbid the project home", which lives in the brief, not the corpus |
| **F-Q1** is `SKILL_MANAGER_HOME` alone enough | PASS — names `CLAUDE_CONFIG_DIR`, `CLAUDE_HOME`, `CODEX_HOME`, `GEMINI_HOME`, PATH | PASS | **PARTIAL** — correctly says "no" and names PATH, but **never names a single agent-home variable.** The HIS-14 fact is that a home has two axes; this answer has one and a half | PASS — names the agent homes **and** exit 79 | **PARTIAL** — same | PASS |
| **F-Q2** is the `skill-manager` on PATH right | PASS | PASS | **FAIL** — "NOT ANSWERABLE FROM THE CORPUS: whether the `skill-manager` binary ITSELF is per-home"; the reader added that the corpus's own usage "arguably cuts against" the inference | PASS | **FAIL** — same | PASS |
| **F-Q3** `bootstrap-home.sh`, and a refusal I do not recognise | **FAIL** — found exits 5 and 6, high confidence, verbatim citation. **Exit 79 appears in zero of the four instructing units' markdown** (measured: `79` → 0 files; control term `exit` → 19 files). The reader could not have found it, and what it found instead reads as a complete answer | **PASS** — 79 named with its cause, its usual trigger, and its remedy, inside an eleven-row table separating it from the ten codes it is routinely confused with | not asked | not asked | not asked | not asked |
| **F-Q4** does the printed `tla-spec-dev` command work | PASS — syntax confirmed; ticket identity correctly `NOT ANSWERABLE` | PASS | not asked | not asked | not asked | not asked |

### UPWARD — worktree → project → root

| question | WORKTREE before | WORKTREE after | PROJECT before | PROJECT after | ROOT before | ROOT after |
| --- | --- | --- | --- | --- | --- | --- |
| **U-Q1** how my edit reaches the tier above | PARTIAL — command found; "run from" not stated; the generalization from `spec-double-compiler` to `test-graph` marked `INFERRED` | PASS | PARTIAL — the project→root instance never shown, only implied; substitution `INFERRED` | PASS | PASS | PASS |
| **U-Q2** what git carries and what it does not | PASS — from that one section | PASS — plus `$GIT_COMMON_DIR/info/exclude` as the actual mechanism, and the store-copy-is-a-git-checkout seam | PASS — same source | PASS — plus the tracked-symlink exception | PASS — and it found the `project sync` contradiction unprompted | PASS |
| **U-Q3** what close-out decides | PARTIAL — what it refuses found; "NOT STATED: that this gate is invoked by `wt close` / `skt ticket close`, or any exit codes" | PASS — 0/1/2/9 with 2's and 9's rationales, `LINKED`, no-`--force`, the callers | PARTIAL — same, plus "the corpus never describes a close-out gate for the project→root boundary" | PASS | PASS | PASS |
| **U-Q4** `held-back` / `conflicted` | **FAIL** — `NOT ANSWERABLE FROM THE CORPUS`, verified by case-insensitive grep for seven spellings | PASS — both statuses, the default/`--merge` split, what the agent does, and the `MERGE_CONFLICT` distinction | **FAIL** — same | PASS | PASS | PASS |
| **C-Q1** publish command + tier + repo, per unit | **FAIL** — 1 of 4 units answered; three `NOT ANSWERABLE`; tier not stated; and the reader noted the corpus says "own **git repository**", never GitHub | PASS on command+tier (6/6); repository stated for 4 of 6, with the lookup that closes the other two | **FAIL** — same | same as worktree | PARTIAL — command for all 23; **repository `NOT ANSWERABLE` for ~16, with no route out** | PARTIAL→better — command 23/23; repository 12/23 **plus the mechanical lookup**; tier still ambiguous, because the corpus genuinely conflicts (DEF-098) |
| **C-Q2** `home sync` / `skt sync` / `unit publish` / `skt publish` | PARTIAL — the two `skill-manager` commands right; `skt sync` and `skt publish` `NOT ANSWERABLE` ("the string `skt` appears five times in the whole corpus") | PASS | PARTIAL — same | PASS | PASS | PASS |
| **C-Q3** what `skt check` compares | **FAIL** — `NOT ANSWERABLE`; the reader named `home drift` and explicitly **declined** to promote it | PASS | **FAIL** — same, and again declined to guess | PASS | PASS | PASS |
| **C-Q4** my brief forbids the project home — can I still publish | not asked (the AFTER-only question, because it is DEF-077) | **PASS** — named the split, which leg is forbidden, the by-hand fallback with its branch/base/PR shape, the reporting obligation, and that a non-clean close-out verdict is "the gate working, not a fault" | — | — | — | — |

### Reading the table

- **The two lower tiers moved because the home changed, not because prose
  changed.** Say that plainly. `skill-manager project resolve` installed the two
  units the manifest already declared, and eleven `PARTIAL`/`FAIL` cells became
  `PASS`. This ticket's *prose* changes account for: F-Q3's root delta (exit 79),
  C-Q1's repository column at every tier, F-Q2's mechanism, and C-Q4 entirely.
- **Four cells are failures the fix did not close, and they are named as
  failures.** D-Q3 stays PARTIAL at all three tiers after the change, because
  three pages disagree about whether editing a store copy in place is the
  supported practice or an antipattern (DEF-101). C-Q1's *tier* stays ambiguous
  at root because `skill-homes.md` forbids the tier three other pages designate
  (DEF-098). Both are in units this ticket does not own.
- **F-Q1 at the lower tiers is the clause-1 failure most worth arguing about.**
  The BEFORE readers said "no, `SKILL_MANAGER_HOME` is not enough" and were
  right — but from a PATH argument in a consumer skill, never naming
  `CLAUDE_CONFIG_DIR`, `CLAUDE_HOME`, `CODEX_HOME` or `GEMINI_HOME`. An agent
  acting on that answer would have exported one variable and a PATH and still
  written the operator's `~/.claude`. **Scored PARTIAL, not PASS**, because the
  answer that matters is the one the epic paid for twice.

## The change-management table — `GOAL-progressive-disclosure` clause 3

Every change-managed unit installed in the HIS-20 worktree home, measured
2026-08-24 by `skill-manager unit publish <u> --dry-run --ticket 247-his-20`
against that home.

| unit | kind | GitHub repository | publishes with | tier to run it from | verified |
| --- | --- | --- | --- | --- | --- |
| `deploy-helm` | skill | `haydenrear/deploy-cdc` | `skill-manager unit publish deploy-helm --ticket <t>` | any home whose store holds it as a git checkout — here, the worktree | ✓ dry-run: would push `skill/247-his-20-deploy-helm` → PR against `main` |
| `skill-manager` | skill | `haydenrear/skill-manager-skill` | `… unit publish skill-manager …` | same | ✓ |
| `skt` | **plugin** | `haydenrear/skill-publisher-skill` | `… unit publish skt …` — **the unit is the plugin**; there is no separate publish for the contained `skt` or `unit-authoring` skills | same | ✓ |
| `spec-double-compiler` | skill | `haydenrear/tla-spec-dev` | `… unit publish spec-double-compiler …` | same | ✓ |
| `test-graph` | skill | `haydenrear/test_graph_skill` | `… unit publish test-graph …` | same | ✓ |
| `tracing-observability` | skill | `haydenrear/tracing_skill` | `… unit publish tracing-observability …` | same | ✓ |

**Not one of those repository names is the unit's name.** The mapping is not
printed by any command: `list` gives `SOURCE git` and a short SHA, and
`show --json` carries `source`, `sha`, `path` and **no `origin`**. It is in
`<home>/installed/<unit>.json`.

**And `units.lock.toml` is not a reliable second source.** Measured the same day:
the operator's root home records `origin` for **29 of 29** lock entries; this
worktree home records it for **4 of 6** — the two without it being exactly the
units `project resolve` installed. The first draft of this ticket's own
documentation said "read `units.lock.toml` or `installed/<unit>.json`"; that
sentence was measured wrong and corrected, and the CLI gap is DEF-100.

### Before `project resolve`, four of those six rows did not exist

`skt` and `skill-manager` were not installed. DEF-077 reproduced exactly:

```
$ skill-manager unit publish skt --dry-run --ticket 247-his-20
✗ skt: not a git checkout at <home>/skills/skt — reinstall the unit from a
  git source, or materialize it into the child home with --checkout, to
  publish from it
$ echo $?
1
```

and `unit publish zzz-no-such-unit` produces the **byte-identical** message with
the name substituted. "Not installed in this home" and "you typed it wrong" are
indistinguishable from the output, and `--checkout` is not a flag `unit publish`
accepts. That is now documented (DEF-102 files the CLI half).

## Honest limits of this instrument

- **A doc-only sandbox is not a fresh agent in a real session.** It measures
  whether the docs *can* answer, not whether an agent *will* read them.
- **The sandbox is not airtight, and the leak is documented above rather than
  denied.** The `skt` skill listing in every subagent's system prompt states the
  tier model. HIS-8's reads had the same exposure and did not record it.
- **n = 1 per condition.** Twelve agents, one read each. Any single cell could
  turn on which file the agent happened to open first.
- **Two of the three AFTER corpora do not exist on disk anywhere** (the root
  overlay and the counterfactual project home). The worktree AFTER corpus does.
- **The AFTER reads grade the corpus *before* the round-2 trims they themselves
  caused.** As with HIS-8's AFTER-2, none of their answers depended on those
  changes — the trims removed restatement and corrected two claims the readers
  had already flagged as wrong.
