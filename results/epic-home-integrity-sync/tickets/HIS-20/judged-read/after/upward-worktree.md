# AFTER — UPWARD, WORKTREE tier

- **Direction:** upward. **Tier:** worktree. Premise: the agent edited a file under
  `<home>/skills/test-graph/`; the home is gitignored; **and its ticket brief
  forbids writing the project home** (the DEF-077 situation, put to it directly).
- **Corpus:** 102 md files — the real worktree home after `project resolve`, with
  this ticket's edited units overlaid as published.
- **Date:** 2026-08-24. Fresh agent. Grades the corpus before the round-2 trims.

---

**U-Q1.** How does my edit reach the tier above?

> ANSWER: The tier above a worktree home is the **PROJECT** home it was cloned from (`<repo>/.skill-manager`, **not** `~/.skill-manager`). `skill-manager home sync --from <worktree>/.skill-manager --to <repo>/.skill-manager --merge` — equivalently the first leg of `skt publish`. Run from the worktree tier — the home whose edit is being moved. Both homes are named explicitly by `--from`/`--to`; the corpus never states a required cwd for `home sync`, so that part is only partly answerable. The one constraint stated is that the `skill-manager` you invoke and the `SKILL_MANAGER_HOME` you export must agree (exit 79 otherwise). **Note also that my ticket brief forbids this exact leg — see C-Q4.**
> CONFIDENCE: high (command), medium (the "directory it must be run from", which the corpus does not pin down)

**U-Q2.** What does git carry?

> ANSWER: Git carries the repository's tracked working-tree files. It does **not** carry anything under `<worktree>/.skill-manager` — "in no diff, no PR, and no fan-out, and it is deleted with the directory."
> Two mechanisms cross, and they are not alternatives: (1) `home sync` — a **file copy**, one tier up, this machine only, **not git at all**; (2) `unit publish` — **git, but of a different repository**: the unit's own, committing onto `skill/<ticket>-<unit>`, pushing, opening a PR against that unit's trunk. A third, read-only mechanism sits at the boundary: `home close-out`, which refuses teardown while an edit has crossed neither.
> CONFIDENCE: high

**U-Q3.** The close-out gate.

> ANSWER: `skill-manager home close-out --home <worktree>/.skill-manager --into <repo>/.skill-manager`, run by `skt ticket close` and again per worktree by `skt ticket sweep`. Decides whether discarding this home would destroy work. Writes nothing.
> Verdicts: **0** nothing to lose; **1** blocked, the only exit that prints blockers, each with a literal remedy (`home sync` fast-forward, `home sync --merge` merge/conflict with files listed, `unit publish` for a git checkout carrying unpushed work "a file copy cannot carry"); **2** the path given as a home is not one — "this exists because it used to answer `safe: true` when `--home` was the worktree DIRECTORY rather than its `.skill-manager`"; **9** destination policy `frozen`, refused, nothing attempted — "branch on it separately".
> Refuses over a **LINKED** unit: "cannot tell" must block rather than clear. **No `--force`, deliberately** — "the CLI owns the verdict and the override lives in the caller".
> CONFIDENCE: high

**U-Q4.** `held-back` vs `conflicted`.

> ANSWER: **HELD BACK** — `home sync`'s DEFAULT (no `--merge`) when the destination copy has itself been edited: "held back and reported, not overwritten", because "holding back costs a conflict and overwriting costs an edit nobody sees again" / "When unsure, conflict." Nothing was written for that unit. **What the agent does:** re-run with `--merge` — exactly the remedy `close-out` prints.
> **CONFLICTED** — `--merge` could not settle the three-way merge: "reported, never resolved; local work is kept either way and a conflicted unit writes nothing." `close-out` reports it with the conflicted files listed; for a git-backed unit whose history neither side contains, the single entry `.git (history)` names the two fixes.
> **What the agent does:** resolve it — the corpus is explicit that the tool will not. Beyond the remedy command and the `.git (history)` shape, the corpus gives **no step-by-step conflict-resolution procedure**. The escape hatch that always works is `unit publish`, since it goes to the unit's own repo and does not touch the destination home.
> CONFIDENCE: high for what the states mean; medium for "what the agent is supposed to do about a conflict", which the corpus states only as a remedy command, not a procedure.

**C-Q1.** Per-unit publish, tier, repository.

> ANSWER: Six units — `skill-manager`, `test-graph`, `spec-double-compiler`, `deploy-helm`, `tracing-observability` (skills) and `skt` (a **plugin** containing `skt` and `unit-authoring`, which are **not separately publishable units**). The command is the same shape for every one: `skill-manager unit publish <unit> --ticket <ticket>`.
> **TIER:** "whichever home actually installed that unit", because `unit publish` "publishes a unit **this home installed** … it commits `<home>/skills/<name>/` or `<home>/plugins/<name>/`". For my edit that is the worktree tier — publishing from a higher tier would publish the un-edited copy. The corpus records a measured refusal for exactly this: `unit publish skt` from a worktree home exits 1 with "not a git checkout at `<home>/skills/skt`".
> **FINDING EACH UNIT'S REPO: no command prints it.** `list` shows `SOURCE git` and a short SHA; `show --json` has `source`, `sha`, `path` and **no** `origin`. "The repository URL lives in `<home>/units.lock.toml` and in `<home>/installed/<unit>.json` (`origin`) — read one of those rather than guessing." `gh repo list <owner> --limit 400` when you have no home.
> Stated mappings: spec-double-compiler → tla-spec-dev; test-graph → test_graph_skill; deploy-helm → deploy-cdc; skt → skill-publisher-skill. **NOT STATED:** `skill-manager` and `tracing-observability`. (github.com/haydenrear/tracing_skill appears, but only as the source of the **Python package** at `#subdirectory=sources/python` — "that is not a statement about where the skill unit publishes, and I will not promote it to one.")
> CONFIDENCE: high for command/tier/lookup-method and the four stated mappings; high for "not stated" on the other two.

**C-Q2.** `home sync` / `skt sync` / `unit publish` / `skt publish`.

> ANSWER: Four different things; two are not even in the same direction. `home sync` — home-to-home copy, one tier UP, this machine. `skt sync <unit>` — **the opposite direction: a PULL**, wrapping `skill-manager sync <unit> --git-latest`; "it brings someone else's pushed work down to you". (Trap: a sync over an unpushed commit is a silent no-op that exits 0 with a full success report.) `unit publish` — **home to the unit's own git repo**. `skt publish` — the composite, in that order.
> "So: `home sync` moves an edit one tier up. `unit publish` reaches GitHub. `skt publish` does both. `skt sync` does neither — it is the download."
> CONFIDENCE: high

**C-Q3.** What does `skt check` compare?

> ANSWER: a unit's **installed hash** against its **remote tip**; when they differ it reports "new version available" with the pull prompt. But it reads the home's recorded installer errors FIRST — `MERGE_CONFLICT`, `NO_GIT_REMOTE`, `NEEDS_GIT_MIGRATION` — and where such a record exists, that record IS the explanation, so it emits a `unit-error` notification INSTEAD of the pull prompt, which "must not be answered with `skt sync` or `skill-manager sync --merge`".
> CONFIDENCE: high for the comparison and the error-record override; low for "stale artifacts", which is named but undefined in the corpus.

**C-Q4. My brief forbids writing the project home. Does that stop me publishing?**

> ANSWER: **Yes I can still publish. The corpus answers this case by name and by ticket number.**
> `skt publish` is forbidden — not the publish, the FIRST LEG. From a worktree home leg one writes the project home, "and running `skt publish` anyway violates it before the publish leg is reached." But this is "not a blocked path, it is a split one."
> What to run:
> 1. `skill-manager list` first — confirm the unit is installed in THIS home as a git checkout, because a failure here is ambiguous between "not installed" and "you typed it wrong".
> 2. `skill-manager unit publish test-graph --ticket 247-his-20 --dry-run`, then without `--dry-run`. Run it from the worktree tier. Lands branch `skill/247-his-20-test-graph` and a PR on `test_graph_skill`.
> 3. **Do NOT run the `home sync` leg. Do NOT run `skt publish`.**
> 4. If step 1 shows the unit is not a publishable checkout here, the corpus gives the by-hand equivalent for exactly this case: clone the unit's repository, branch `skill/<ticket>-<unit>` off its trunk, commit, push, open a PR — "Same branch name, same base, same PR shape, so the result is indistinguishable downstream from the command's own."
>
> What to say, and to whom — **this part is NOT optional**: in the PULL REQUEST, state the edit has NOT been reconciled into the project home and name who owns doing it; and to whoever runs the teardown, the same fact, because "an unreconciled worktree home is exactly what `home close-out` refuses over, and whoever runs the teardown needs to know the answer is 'published, deliberately not synced' rather than 'forgotten'." Expect `skt ticket close` / `sweep` to give a non-clean verdict and skip this worktree; **that is the gate working, not a fault.**
> SOURCE: `skills/skill-manager/references/projects.md` §"At the worktree tier, the two legs of `skt publish` are decided separately".
> CONFIDENCE: high
> HOW I FOUND IT: "That section is titled for this exact situation and even carries the ticket id 247-his-20 in its measured transcript."

---

## M-Q1 — Instrument check

> **Contaminated for three of eight, partial for two more.**
> - **C-Q2 — contaminated.** `skill-dev-skill`'s listing description contains, verbatim: *"that is `skt publish` (home sync one tier up, then unit publish)"*. That single line supplies C-Q2's central claim with no corpus at all.
> - **U-Q1 — headline contaminated**, specifics corpus-only (`--from`/`--to`, `--from` never written, `--merge`, project-not-root as the destination).
> - **U-Q3 — framing contaminated** (`git-issue-workflow`'s description literally says the close command runs "through a gate that refuses while removing it would destroy unpublished skill work"); mechanics corpus-only.
> - **U-Q2, U-Q4, C-Q1, C-Q4 — clean.** *"C-Q4 … is the answer I'd most trust as a clean measurement."*
> - **C-Q3 — direction contaminated, content not.**
> - Leak: the listing's `skt` description is a **different and slightly older revision** than the corpus's frontmatter.

## M-Q2 — Did the fix go too far? **Yes, in places**

> "I'll take the invitation literally rather than politely."
> 1. **`projects.md` §"Concurrency: what actually excludes what"** — "instructions for someone **writing skill-manager's own code**. I was a reader trying to get an edit out of a home; I read all of it and used none of it. Worse, it sits *between* the tier model and every practical section."
> 2. **The "materialization record is evidence about two different things" bullet** — "abstract epistemics … nothing downstream on the page depends on the reader having absorbed it."
> 3. **The `[[vendored]]` section** — "fine content in the wrong place … The page has two audiences bolted together."
> 4. **`skill-imports.md`'s "Onboarding-bundled units" expansion says the same thing three times** (four, counting Validation). "One statement plus the measurement would do. The measured paragraph is the one to keep — it is the only part that is evidence rather than restatement."
> 5. **`skt/SKILL.md`** — the "you are unlikely to be reading this when it matters" line is "the document being wry about its own irrelevance"; the `du`/copy-on-write footnote is misplaced; and **"the three-tier home model table is at line 130 of a 150-line file … It should be at the top."**
> 6. **"The single largest redundancy is one you didn't name, and I'd fix it before any of the above":** `workflows.md` §"Get an edit out of a home before the home is gone" is "a near-sentence-for-sentence second copy of projects.md's material … **The workflows.md copy should be three lines and a pointer.** Not because it's wrong — it's accurate — but because there are now three places that must be updated together, and the file that says 'point there, do not re-derive it' is this same file."
>
> "**Would I have found what I needed faster if some of it were shorter?** Yes, measurably. The two answers I needed most (U-Q1, C-Q4) live at projects.md lines 209-232 and 309-324 — roughly the last third of a 459-line page, behind the concurrency and vendored sections. Everything I recommend cutting sits between the reader and those two sections."
>
> **Would defend:** §"Finding a unit's repository"; §"At the worktree tier, the two legs …" ("this answered C-Q4 exactly, including the reporting obligation, which is the part an agent would otherwise skip"); the measured `unit publish` transcript and the `--checkout` note ("a live trap disarmed"); and `SKILL.md`'s four literal symptom questions ("that is the routing that got me to the right page on the first hop").

## M-Q3 — Quality

> 1. Two pages give opposite answers about onboarding-bundled units, and one names a retired unit.
> 2. A "stated once, do not re-derive" instruction that four pages violate, pointing at a file that isn't here.
> 3. Two pages contradict each other about whether `skt` is on PATH everywhere — **"the false version is the one being distributed into other repositories' agent files."**
> 4. `coords-and-distribution.md` states a stale unit name as its worked example.
> 5. skt's tier table assigns the project tier "no obligation", which the publish story contradicts.
> 6. `skill-manager publish` vs `skill-manager unit publish` differ by one word; "an agent grepping 'publish' lands on the registry section first and can reasonably conclude publishing is optional."

```
COMPLIANCE: Nothing read outside the corpus. No forbidden command. No file
created, modified, moved or deleted. Disclosure: my system prompt's skill
listing overlaps the corpus (M-Q1), including a slightly older revision of
plugins/skt/skills/skt/SKILL.md's frontmatter; I flagged every answer it could
have supplied rather than pretending isolation.

CORPUS NOTES: 102 files. MISSING and load-bearing: git-issue-workflow (cited as
the canonical statement of the tier model and the owner of `scripts/wt`);
skill-dev; git-integration-repo (cited as the owner of `close-change.sh
--force`). Also absent by construction: the actual state of my home
(units.lock.toml, installed/<unit>.json, `skill-manager list` output), which is
why C-Q1's per-unit "is it installed here" and two repo names are unanswerable.
```

---

## Grader's note

**C-Q4 is the DEF-077 clause, and it is the sharpest single result in this
ticket.** The read did not merely find the command; it found the *split*, named
which leg is forbidden, named the by-hand fallback with its branch/base/PR shape,
and independently reconstructed the reporting obligation and the expected
non-clean close-out verdict. It also declined to promote
`github.com/haydenrear/tracing_skill` from a Python-package coordinate into a
unit-repo claim — the exact discipline this ticket's scoring rules ask for.

M-Q2 #6 — `workflows.md` duplicating projects.md wholesale — was **acted on**;
it is the single change this ticket is least likely to have made without being
told.
