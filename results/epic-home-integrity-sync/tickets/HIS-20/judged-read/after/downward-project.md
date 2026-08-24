# AFTER — DOWNWARD, PROJECT tier

- **Direction:** downward. **Tier:** project.
- **Corpus:** 102 md files — the worktree home *after* `skill-manager project
  resolve` was run against this checkout's own `skill-project.toml` (which adds
  `skill-manager` and the `skt` plugin, both of which that manifest already
  declared), with this ticket's edited `skill-manager` unit and `skt` SKILL.md
  overlaid as they are published in PR skill-manager-skill#5 and
  skill-publisher-skill#35.
- **Counterfactual, and labelled as such.** The real project home was NOT
  changed — this ticket is forbidden to write it. This corpus is *what the
  project home holds once it is resolved against its own manifest*, which is a
  one-command remedy. The delta at this tier is therefore attributable to
  **provisioning**, not to prose. See `../../SCORING.md`.
- **Date:** 2026-08-24. Fresh agent; it did not see the BEFORE read.

---

**D-Q1.** *Which tier is `/Users/hayde/IdeaProjects/skill-manager/.skill-manager`, what other tiers exist, how would an agent determine which tier it writes?*

**ANSWER:** It is the **project** tier — the middle of three. The tiers are: `root` = `~/.skill-manager` (where the operator installs); `project` = `<repo>/.skill-manager` (one per repository, gitignored); `worktree` = `<worktree>/.skill-manager` (one per ticket, gitignored). An agent determines its tier by running `skt status`, which reports "units, plugins, home tier, epic/ticket state"; where `skt` is not installed the documented equivalents are `skill-manager list` and `skill-manager home describe --json`, plus `<skill-manager-skill>/scripts/env.sh --pretty`, which "reports the active `SKILL_MANAGER_HOME` … child home path, and child-local agent homes that exist." One caveat the corpus makes explicit: a path of the shape `<project>/.skill-manager` can have arrived two ways — "A cloned home and a `project resolve` child home both want the path `<project>/.skill-manager`, and they are not the same thing." Both are the project tier; the corpus does not say which of the two this particular home is.

**SOURCE:** `skills/skill-manager/references/projects.md` (the tier diagram); `plugins/skt/skills/skt/SKILL.md`: "skt status            # startup report: units, plugins, home tier, epic/ticket state".

**CONFIDENCE:** high (that it is the project tier); medium (on clone-vs-`project resolve` provenance — not stated).

**HOW I FOUND IT:** Grepped the corpus for `tier|SKILL_MANAGER_HOME` and read `projects.md`, `skt/SKILL.md`, and `skill-manager/references/skill-imports.md` in full.

---

**D-Q2.** *What does this tier inherit from the tier above, and by what mechanism?*

**ANSWER:** It inherits **whatever the root home held at the moment of copy — the store bytes of installed units — and nothing after that**. The mechanism is a **real filesystem copy**, performed by `skill-manager project resolve` or `skill-manager home clone --to`, never a symlink. Inheritance is a one-time snapshot at creation, not a live link: "copies flow down at **creation** time only". Consequences the corpus draws explicitly: capabilities do not propagate — "**A home does not inherit it.** Project and worktree homes are copies of the home above them, so a home cloned from one that never installed `skt` has no `bin/cli/skt` and no `plugins/skt/`"; and "A project or worktree home holds exactly what its parent held when it was cloned." Nothing flows upward automatically; upward requires `home sync` (one tier) or `unit publish` (the unit's repo).

**SOURCE:** `skills/skill-manager/references/projects.md`; `plugins/skt/references/migration.md`: "Copies flow **down** (root → project → worktree) and never update themselves"; `skills/skill-manager/references/skill-imports.md`: "**That is a property of the home, not of the units, and the tiers do not inherit it.**"

**CONFIDENCE:** high

**HOW I FOUND IT:** `projects.md` § "Homes Come In Tiers, And Every Tier Is A Copy", cross-checked against `skt/SKILL.md`, `migration.md`, and `skill-imports.md` § "Onboarding-bundled units".

---

**D-Q3.** *What may I change here?*

**ANSWER:** Two things are legitimately editable in a home, and one thing is not.

*Editable:* the **unit store copies** — `<home>/skills/<name>/`, `<home>/plugins/<name>/`, `docs/<name>/`, `harnesses/<name>/`. What editing one *means*: the edit is **invisible** — "The home is gitignored, so that edit is in no diff, no PR, and no fan-out, and it is deleted with the directory." It therefore incurs an obligation to move it out by one of two non-interchangeable routes: `home sync` moves it exactly **one tier up on this machine**; `unit publish <unit> --ticket <t>` is the **only** route to the unit's own git repo ("If you improved a unit, `unit publish` is the one you owe").

*Also editable, and the actual source of truth for what this home contains:* the checkout's **`skill-project.toml`** — declaring a unit there and running `project resolve` **against this home** is the documented way to add a unit.

*Not editable:* **generated `.skill-manager/` state** — "do not edit generated `.skill-manager/` state as the source of truth."

**Caveat — the corpus does not agree with itself here.** `plugins/skt/skills/skt/SKILL.md`'s tier table assigns the project row the obligation "**none — pull-side only**". And `plugins/skt/skills/unit-authoring/SKILL.md` says flatly: "**Never edit the store copy in place.**" See M-Q2.

**CONFIDENCE:** medium-high on the mechanics; medium on "may I", because the corpus gives conflicting permission signals for the project tier specifically.

**HOW I FOUND IT:** `projects.md` §§ "Moving An Edit Up A Tier" / "Publishing An Edit"; `unit-authoring/SKILL.md`; grep for `do not edit|Never edit|gitignored`.

---

**D-Q4.** *What must I NOT touch above me?*

**ANSWER:** The operator's root home, `~/.skill-manager` — stated as a direct instruction:

- "**Do not touch the operator's root `~/.skill-manager`.**"
- "This skill does not modify `~/.skill-manager/cli-lock.toml`, `policy.toml`, or `registry.properties`."; "do not loosen `~/.skill-manager/policy.toml` without explicit instruction."
- Accidentally, via unset environment: "**Order matters, and getting it wrong writes the operator's home.** `install`, `sync`, `bind`, `upgrade` and `project resolve` all write into whatever `SKILL_MANAGER_HOME` names."
- "`--from` is **never written**."
- A `frozen` home above you must not be written; a close-out gate against one exits **9** meaning "refused, nothing attempted".
- Within an epic, `spec-double-compiler`'s epic pages go further: "**Skills are READ, never edited from here.**" (an epic-local brief, not a general rule).

**CONFIDENCE:** high

**HOW I FOUND IT:** Grep for `~/.skill-manager`, `operator`, `frozen`, and the epic pages' guardrail bullets.

---

**F-Q1.** *Is `SKILL_MANAGER_HOME` alone sufficient?*

**ANSWER:** `SKILL_MANAGER_HOME=<repo>/.skill-manager` selects the home. **But it is not sufficient on its own**, for three documented reasons:

1. **The CLI binary must agree.** "The pin at `<home>/bin/cli/skill-manager` binds *that* home; when `SKILL_MANAGER_HOME` names a different one, the pin refuses rather than silently rebinding to its own" — exit **79**. So you must additionally name the CLI: export `SKILL_MANAGER_CLI` at a *real launcher* (never at a home's `bin/cli/skill-manager` shim — "the shim expands that same variable, so it re-execs itself forever").
2. **Some things no variable can redirect.** "`skill-script` CLI deps are generated shell scripts with a home's absolute path in the body that no variable redirects." Launch through `<home>/bin/launch/{claude,codex,gemini}` — "the shims put this home's `bin/` first on PATH and remove other homes' `bin/`, which is the part hand-exporting always misses."
3. **For agent launches there are three more:** `CODEX_HOME`, `CLAUDE_HOME`, `GEMINI_HOME` — "skills also load from the Claude config dir." (One page writes `CLAUDE_HOME`, another writes `CLAUDE_CONFIG_DIR` for `claude plugin` commands.)

**CONFIDENCE:** high

**HOW I FOUND IT:** Grepped `SKILL_MANAGER_CLI|bin/launch|PATH`, then read `projects.md` § "One symptom worth recognising" and § "The Rest Of The `home` Family".

---

**F-Q2.** *Is the `skill-manager` on my `PATH` the right binary?*

**ANSWER:** **Not necessarily, and the corpus specifically warns against assuming it.** A `PATH`-resolved `skill-manager` is a common cause of the exit-79 refusal: "It surfaces most often through a wrapper script that resolves its CLI off `PATH` and then runs it under a `SKILL_MANAGER_HOME` you set, which is how the same command can have worked yesterday and refuse today." The same logic is stated generally: "**`tlc2`, `pytest`, `jinja2` and `tla-spec-dev` are not 'the ones on PATH'**."

**How to tell:** `skill-manager home describe --json`, which reports "**the resolved CLI**". The loud symptom is `skill-manager: refusing to run against a home you did not name.` with exit **79**; "79 is not a broken install."

**What to do:** invoke the target home's pin `<home>/bin/cli/skill-manager`, or export `SKILL_MANAGER_CLI` at a *real launcher* — never at a home's shim (infinite re-exec). Preferentially launch through `bin/launch/*`.

**CONFIDENCE:** high

**HOW I FOUND IT:** `skills/skill-manager/SKILL.md` routes the symptom *"a `skill-manager` on my `PATH` refused to run instead of running"* to `references/projects.md`; read that section.

---

## M-Q1 — Instrument check (contamination)

> Blunt assessment. My system prompt's skill listing carries **full descriptions**, and several of them restate home-tier facts verbatim.
>
> - **D-Q1 — HEAVILY CONTAMINATED.** The `skt:skt` entry literally says: *"which home tier this session writes (root ~/.skill-manager, project `<repo>/.skill-manager`, or a ticket worktree home)"*. That is the complete tier list **and** the determination method with no corpus needed. Corpus-only parts: "gitignored", "one per repository/ticket", the clone-vs-`project resolve` ambiguity.
> - **D-Q2 — PARTIALLY CONTAMINATED.** The listing implies non-inheritance. It does not say "real copy, not a symlink", nor that copies flow down at creation time only, nor that a home fails to inherit `skt`. The mechanism half is corpus-only.
> - **D-Q3 — PARTIALLY CONTAMINATED.** The listing states *"`skt publish` (home sync one tier up, then unit publish)"*. So the editable-unit premise and both escape routes were available independently. Corpus-only: generated-state rule, the `skill-project.toml` + `project resolve` route, `home close-out`, the contradiction I flagged.
> - **D-Q4 — NOT CONTAMINATED.** Corpus-only.
> - **F-Q1 — NOT CONTAMINATED.** `SKILL_MANAGER_HOME` is not named anywhere in the listing. Corpus-only.
> - **F-Q2 — NOT CONTAMINATED.** Exit 79, the shim re-exec trap, `home describe --json`: none appear in the listing. Corpus-only.
>
> One further contamination-adjacent note: the `skt:skt` description **in my system prompt differs from the same skill's frontmatter in the corpus** — mine lacks the `skt ticket list/sweep` clause. So the corpus is a *later* state than what my session loaded; my prompt is not simply a superset.
>
> **Net:** D-Q1 is the answer you should discount most; D-Q3 and D-Q2 partially. D-Q4, F-Q1 and F-Q2 are clean measurements of corpus retrieval.

---

## M-Q2 — Quality: what is wrong or self-contradicting

**1. The corpus contradicts its own headline measurement about this very home.**

`plugins/skt/skills/skt/SKILL.md`: *"Measured on the skill-manager repository, 2026-08-24: its project home and a ticket worktree cloned from it each held **four skills, no plugins, and no `skt`**."* `skill-imports.md` repeats it. But the corpus *is* that home's `skills/**` and `plugins/**`, and it contains **five** skills and **one plugin** (`skt`). An agent reading `skt/SKILL.md` here is told it is "unlikely to be reading this" — while reading it. The same page says the `spec-double-compiler` import at "`skt: references/skills.md`" had no target; `plugins/skt/references/skills.md` exists in this corpus.

**2. Is `skt` on PATH in every home, or only homes that installed it?** Direct contradiction inside the *same plugin*. `skt/SKILL.md`: "on `PATH` in every home **that installed this plugin** … **A home does not inherit it.**" `plugins/skt/references/harness-capabilities.md`, in the snippet it tells you to paste into every project's AGENTS.md: "run `skt status` (**on PATH in every skill-manager home**)". **The instruction snippet is exactly the artifact that gets propagated to homes without `skt`, so the false half is the one that travels.**

**3. "Stated once" is stated at least four times, and one of the pointers dangles.** `skills/skill-manager/references/workflows.md`: "The three-tier home model itself … is **stated once in git-issue-workflow's `references/skill-homes.md`** … point there, do not re-derive it." But `git-issue-workflow` is **not installed in this home**, so that pointer resolves to nothing here — while the model is fully stated in `projects.md`, in `skt/SKILL.md`, and again in `spec-double-compiler/references/runtime_requirements.md` with the same ASCII diagram. And `skill-manager/SKILL.md` asserts the opposite exclusivity for a different file: "the **upward** path, **which nothing else documents**". `runtime_requirements.md` documents it. So does `skt/SKILL.md`.

**4. "Never edit the store copy in place" vs. the entire home-edit workflow.** `unit-authoring/SKILL.md`: "**Never edit the store copy in place.**" `projects.md`: "An agent that improves a unit does it *inside a home*", and `unit publish` "commits `<home>/skills/<name>/`" — it publishes *from* the store copy you were told never to edit. Both defensible; neither page acknowledges the other, and a reader who loads only `unit-authoring` will conclude the `skt publish` flow is forbidden.

**5. The project tier's obligation is given as "none", then contradicted.** skt's tier table, project row: "**none — pull-side only**". Yet `projects.md`: "**If you improved a unit, `unit publish` is the one you owe**". "Pull-side only" is not true of a home an agent works in.

**6. A documented remedy that is not a real flag — half-fixed.** `projects.md`: "`--checkout`, the remedy it names, is not a flag `unit publish` accepts." Good disclosure — but eleven lines earlier the same page says `--child-home` is "for a unit materialized into a project child home as its own checkout (**see `project sync --checkout UNIT`**)". The corpus does describe a `--checkout`, on a different command, without connecting the two.

**7. Orphan command reference.** `runtime_requirements.md` recommends "the home's `bin/launch/…` shims (**or `skill-manager exec`**)". `skill-manager exec` appears **nowhere else** in the corpus.

---

```
COMPLIANCE: Nothing read outside the corpus directory. No forbidden command. No
file created, modified, moved or deleted anywhere, including /tmp and the
scratchpad — every Bash call was find / wc / grep / sed / ls with no redirection.
I did not invoke any Skill. I did not read CLAUDE.md, ~/.claude, ~/.skill-manager,
the cwd repository, or git history.

CORPUS NOTES: 105 markdown files, 31,279 lines. plugins/skt (2 SKILL.md + 13
references) and skills/{deploy-helm, skill-manager, spec-double-compiler,
test-graph, tracing-observability}. Read in full: skt/SKILL.md,
unit-authoring/SKILL.md, skt/references/{migration, bindings-and-sync,
harness-capabilities, coords-and-distribution}.md, skill-manager/SKILL.md,
skill-manager/references/{projects, workflows, cli}.md, part of skill-imports.md,
and spec-double-compiler/references/runtime_requirements.md; remainder covered by
targeted grep.

MISSING / NOTABLE ABSENCES: (a) git-issue-workflow is NOT present, so
`references/skill-homes.md` — which workflows.md names as the single authority for
the tier model — cannot be opened from this home; likewise `scripts/wt`, which
skt/SKILL.md gives as the no-skt fallback path. (b) skill-dev is NOT present,
though coords-and-distribution.md and unit-authoring both route to "the skill-dev
worktree flow". (c) No `skill-project.toml`, `units.lock.toml`, `installed/*.json`,
`policy.toml`, `bin/`, `scripts/` or `examples/` are in the corpus (markdown only),
so several paths the docs tell you to read for ground truth — e.g. the unit's
`origin` — are not checkable from here. (d) The skt skill's frontmatter description
in the corpus is NEWER than the one loaded into my system prompt.
```

---

## Grader's note

Two of this read's findings are **defects in this ticket's own fix** and were
acted on:

- **M-Q2 #1 is a direct hit.** The measurement sentences I added to
  `skt/SKILL.md` and `skill-imports.md` were true of the home *before*
  `project resolve` and false of the home the reader was standing in. That is
  the epic's founding defect — a copy that becomes wrong and fails nothing —
  reproduced inside the fix for it, in under a day. Both sentences now carry
  their tense and name the command that changed the state.
- **M-Q2 #2 is the sharpest thing either round produced.**
  `harness-capabilities.md` carries the false claim inside the snippet it tells
  you to paste into every project — so the correction stays at root and the
  falsehood is what travels. Fixed in the same leaf PR.

M-Q2 #3, #4, #5, #6 and #7 concern text this ticket did not write; #3 and #4 are
filed. The AFTER answers above were graded on the corpus **as this agent read
it**, before those fixes — as with HIS-8's AFTER-2, none of the six answers
depended on them.
