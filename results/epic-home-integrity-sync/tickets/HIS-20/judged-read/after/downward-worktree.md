# AFTER — DOWNWARD, WORKTREE tier

- **Direction:** downward. **Tier:** worktree — `SKILL_MANAGER_HOME=/Users/hayde/IdeaProjects/wt-247-his-20/.skill-manager`.
- **Corpus:** 94–102 md files — **the real worktree home**, after
  `skill-manager project resolve` was run against this checkout's own
  `skill-project.toml` (which already declared `skill-manager` and the `skt`
  plugin), with this ticket's edited units overlaid as published.
  This tier's delta is **real, not counterfactual**: the home was changed.
- **Date:** 2026-08-24. Fresh agent. Grades the corpus *before* the round-2 trims
  this read caused.
- **This is the read that was invited to say the fix went too far. It did.**

---

**D-Q1.** Which tier is this?

> ANSWER: The **worktree tier**, the bottom of three (root → project → worktree, `home clone` at each step). How to determine which tier the session writes: `skt status`; `skill-manager home describe [--json]`; `<skill-manager-skill>/scripts/env.sh --pretty`; or path shape.
> The identification of *this specific path* is INFERRED from path shape plus one corroborating measurement: `projects.md`'s *"Measured on a worktree home, 2026-08-24: `$ skill-manager unit publish skt --dry-run --ticket 247-his-20`"* — the ticket id matches this worktree's directory name.
> CONFIDENCE: high for the tier model and the identification.

**D-Q2.** What does it inherit?

> ANSWER: **a full byte-for-byte copy of the project home's store as it stood at clone time** — units, installed-records, lock state, registered plugins. It inherits **nothing after that instant**. The mechanism is `skill-manager home clone --to`, invoked by `skt ticket new` / `wt new`. **Not** `project resolve` — "This — not `project resolve` — is how a checkout an agent works in gets its home."
> Three consequences: real copy not symlink, deliberately; **what was never installed above is not here** ("a worktree home cloned from a project home that never installed `skt` cannot publish `skt`"; "every import pointing at those units dangles there"); and nothing flows back up on its own — "Upward is the whole difficulty."
> CONFIDENCE: high

**D-Q3.** What may I change here?

> ANSWER: the content of the installed units themselves. What it means, in order: (1) gitignored, "in no diff, no PR, and no fan-out, and it is deleted with the directory"; (2) `home sync` to survive teardown; (3) `unit publish` to reach anyone else — "If you improved a unit, `unit publish` is the one you owe"; (4) `home close-out` will refuse teardown while unreconciled — "that refusal is the mechanism, not a courtesy".
> Not editable: generated child-home state, generated env files, managed doc-import blocks, `cli-lock.toml` / `policy.toml` / `registry.properties`.
> **Caveat I must flag rather than resolve:** `unit-authoring` says *"**Never edit the store copy in place.**"* See M-Q3.
> CONFIDENCE: high on the mechanism; medium on "may", because the two pages disagree.

**D-Q4.** What must I NOT touch above me?

> ANSWER: Partially answerable. Stated: the operator's root `~/.skill-manager` ("Do not touch the operator's root"; "do not loosen `policy.toml`"); **the project home, if your brief is an epic ticket brief** — *"A ticket brief that forbids a ticket agent from writing the project home — the normal rule inside an epic, because that home is one shared destination several tickets would race for — forbids that leg, and running `skt publish` anyway violates it before the publish leg is reached"*, with the sanctioned split named; a `frozen` home at all; `home sync --from` is never written; and the pre-home-exists trap.
> **NOT ANSWERABLE FROM THE CORPUS:** whether *your* brief in *this* ticket forbids writing the project home. The corpus states the epic default and the remedy; the binding instruction lives in the ticket brief. "Assume forbidden until your brief says otherwise, then take the split path above."
> CONFIDENCE: high for root and frozen/policy; medium for the project home (conditional on the brief).

**F-Q1.** Is `SKILL_MANAGER_HOME` alone sufficient?

> ANSWER: **It is not sufficient on its own**, because the *binary* also carries a home binding: exit **79**, *"refusing to run against a home you did not name."* You need both halves to agree: `SKILL_MANAGER_HOME` **plus** a CLI that is either this home's own pin or a neutral launcher named by `SKILL_MANAGER_CLI` — "Never point `SKILL_MANAGER_CLI` at a home's `bin/cli/skill-manager` shim — the shim expands that same variable, so it re-execs itself forever."
> Two further variables: `SKILL_MANAGER_TICKET` (`unit publish`'s `--ticket` default); and for **agent launches**, the corpus says do not use variables at all — use `bin/launch/{claude,codex,gemini}`, with `CLAUDE_HOME`, `CODEX_HOME`, `GEMINI_HOME` and `CLAUDE_CONFIG_DIR` as the matching set.
> CONFIDENCE: high

**F-Q2.** Is the `PATH` `skill-manager` right?

> ANSWER: Unknown until you check, and the corpus says do not assume. A `PATH` `skill-manager` may be the operator's root-home pin, giving **exit 79**. "It surfaces most often through a wrapper script that resolves its CLI off `PATH` and then runs it under a `SKILL_MANAGER_HOME` you set, which is how the same command can have worked yesterday and refuse today."
> How to tell: `scripts/env.sh --pretty`; `home describe --json`'s **resolved CLI**; or empirically, a harmless read whose exit 79 proves the PATH binary belongs to a different home.
> What to do: invoke this home's own pin directly, or `SKILL_MANAGER_CLI` at a real launcher. **"Do not 'fix' it by changing `SKILL_MANAGER_HOME` to match the binary; that writes the wrong home, which is exactly the failure exit 79 exists to prevent."**
> CONFIDENCE: high

---

## M-Q1 — Instrument check

> **Heavily contaminated:** D-Q1 (the `skt:skt` listing gives the full tier enumeration and `skt status` verbatim). D-Q2 partly, D-Q4 partly (`git-epic-workflow`'s "bare `git worktree add` … writes the operator's global home").
> **Lightly:** D-Q3 (the *existence* of in-home editing); F-Q1's variable *name*.
> **Clean — corpus-only:** F-Q1's actual answer (that `SKILL_MANAGER_HOME` is *not* sufficient; `SKILL_MANAGER_CLI`; exit 79; the shim self-re-exec trap; `SKILL_MANAGER_TICKET`); **F-Q2 entirely**; the close-out exit-code semantics; the `unit publish` refusal transcript and the `--checkout` defect; the unit-name→repo mappings.
> **And note this one cuts *against* the listing:** my prompt lists `skt:skt` as an available skill, which would have led me to confidently tell you to run `skt status`. **The corpus is what told me it is probably absent in this home.**
> **Instrument anomaly:** my prompt's `skt:skt` description lacks `skt ticket list|sweep`, which the corpus frontmatter has. My session's loaded `skt` is an **older version than the corpus copy**. If you are using the loaded-skill listing as a control, it is not a snapshot of the same bytes.

## M-Q2 — Did the fix go too far? **Yes, in four specific places**

> Verdict: **the expansion was right in substance and overshot in three places, all cuttable without losing a fact.** I am not softening this — but I also want to be precise about which parts I would fight to keep, because "trim it" applied uniformly would destroy the parts that actually answered your F-questions.
>
> **Cut it: `projects.md` §"Concurrency: what actually excludes what".** The clearest overshoot. `HomeLock`, `ReentrantLock`, `FileLock`, `OverlappingFileLockException` are instructions for someone **writing skill-manager's own code**. The page opens "Use this reference when a repository contains `skill-project.toml`"; an agent reading that header and then hitting `OverlappingFileLockException` has been mis-routed. Its one operational sentence is already said better 120 lines down. ~20 lines back.
>
> **Cut it: the "materialization record is evidence about two different things" bullet.** "I read it twice and it changed no action I would take." Its consequence is restated *operationally* in §`home sync`, attached to the flag it governs. Delete the first; keep the second.
>
> **Cut it: the tier model is now stated three times, and a fourth page claims it is stated once elsewhere.** `skill-manager/SKILL.md`, `projects.md`, `skt/SKILL.md` — and `workflows.md` says it is "stated once in git-issue-workflow's `references/skill-homes.md` … point there, do not re-derive it", naming a location **not in this home at all**. "This directly cost me time on D-Q1/D-Q2: I read the model three times before I was confident the three statements agreed rather than differing in some subtle way I was missing." Pick one canonical statement — `projects.md`'s diagram — and make the other two one-line pointers; `SKILL.md`'s is the most cuttable.
>
> **Cut it: the same 2026-08-24 measurement is written out twice, in different words** (skt/SKILL.md and skill-imports.md). "A reader who meets both has to stop and check whether they are two measurements or one. One should own it; the other should cite it."
>
> **Do NOT cut:**
> - `projects.md` §"Finding a unit's repository: no command prints it" — **"the single highest-value paragraph in this corpus"**, unguessable and unavailable anywhere else. "Keep every word."
> - The `unit publish` refusal transcript and its two remedies, including the `--checkout` disclosure. "Documenting a misleading error message verbatim is exactly the right use of prose."
> - §"One symptom worth recognising" — "answered F-Q1 and F-Q2 outright and nothing else in the corpus touches it."
> - `skt/SKILL.md` §"A home does not inherit it" — "It stopped me from confidently recommending a command that probably does not exist in your home."
>
> **Net:** roughly 40–50 lines of the recent expansion are cuttable. The other ~200 lines earned their place, and shortening them would have made my answers worse, not faster.

## M-Q3 — Quality

> **1.** `plugins/skt/references/skill-imports.md` says the onboarding bundle is "**always present**" while *citing* skill-manager's page as canonical — which says the opposite — and names the retired `skill-publisher` unit. **Two defects in one place.**
> **2.** `workflows.md`'s "stated once … do not re-derive it" is false and points at a file this home does not have.
> **3.** `harness-capabilities.md` propagates "on PATH in every skill-manager home" into other repositories' agent files. **"The false version is the one being propagated."**
> **4.** `coords-and-distribution.md` and `unit-authoring` teach "the repo is not the unit name" using the one mapping that has since changed (`skill-publisher`).
> **5.** skt's tier table gives the project tier "none — pull-side only", which the publish story contradicts.
> **6.** `skill-manager publish` (registry) vs `skill-manager unit publish` (PR to the unit's repo) differ by one word and no page disambiguates them.

```
COMPLIANCE: Nothing read outside the corpus. No forbidden command. No file
created, modified, moved or deleted. One caveat for your records: my own system
prompt contains a ~40-skill listing with full descriptions, which I could not
un-read; M-Q1 itemizes exactly where that overlaps my answers.

CORPUS NOTES: 94 files. skills/ (5 units) and plugins/ (skt, 2 contained skills
+ 13 reference pages). Missing and load-bearing: git-issue-workflow is NOT here,
yet workflows.md names its skill-homes.md as the single canonical statement of
the tier model and skt/SKILL.md routes ticket new/close to its scripts/wt — both
dangling from this home. skill-dev is not here either. No units.lock.toml,
installed/<unit>.json, policy.toml or bin/cli/, so F-Q2's "check the resolved
CLI" and D-Q2's "what did this home actually inherit" could only be given as
procedures, not as facts about your home.
```

---

## Grader's note

Every one of M-Q2's four cuts was acted on except the two that are **pre-existing
text this ticket did not write** (the `HomeLock` concurrency subsection and the
materialization-record bullet), which are reported to the epic owner rather than
cut by a ticket that did not author them. The tier-restatement cut and the
duplicated-measurement cut were both made, in
`skill-manager-skill/{SKILL.md,references/workflows.md,references/skill-imports.md}`
and `skill-publisher-skill/skills/skt/SKILL.md`.

**The invitation was worth offering because it was taken, and because what it
returned was specific enough to act on and specific enough to refuse in part.**
The reader defended four passages by name; those were left alone.
