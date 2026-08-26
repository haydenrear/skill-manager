# AFTER — DOWNWARD, ROOT tier

- **Direction:** downward. **Tier:** root — `SKILL_MANAGER_HOME=/Users/hayde/.skill-manager`.
- **Corpus:** 193 md files — the real root home, with this ticket's edited
  `skill-manager` unit and `skt` SKILL.md overlaid **as published** in
  skill-manager-skill#5 / skill-publisher-skill#35. HIS-8's method exactly: the
  real root home does not carry these until the leaf PRs merge and it syncs.
- **Date:** 2026-08-24. Fresh agent; it did not see the BEFORE read.
- **Note:** this transcript grades the corpus *before* the round-2 trims that this
  read and its two siblings caused. None of its answers depended on them.

---

**D-Q1.** Which tier is `/Users/hayde/.skill-manager`?

> ANSWER: **root** tier. The other two are **project** (`<repo>/.skill-manager`, one per repository, gitignored) and **worktree** (`<worktree>/.skill-manager`, one per ticket, gitignored). Each tier is a real copy, never a symlink. To determine the tier: `skt status`, which reports the home tier. Where `skt` is absent, the documented fallbacks are `skill-manager home describe --json` / `skill-manager list`, and `<skill-manager-skill>/scripts/env.sh --pretty`. The path shape itself is diagnostic.
> SOURCE: `plugins/skt/skills/skt/SKILL.md` tier table; `skills/skill-manager/references/projects.md` tier diagram; `skills/spec-double-compiler/references/runtime_requirements.md`.
> CONFIDENCE: high (tier identity: medium — the `~` → `/Users/hayde` expansion is inferred)

**D-Q2.** What does root inherit?

> ANSWER: **There is no tier above root.** Copies flow downward only, so root inherits nothing from a home. What fills that role is (a) **the operator**, who installs into it, and (b) **each unit's own git repository**, in both directions — inbound `skill-manager install` / `onboard` / `skt sync <unit>` = `skill-manager sync <unit> --git-latest`; outbound `skill-manager unit publish <unit>`, the only route that reaches another machine or a sibling project. `home sync` "moves an edit exactly one tier", and from root there is no tier up.
> Caveat the corpus itself states: *"Any Skill Manager home can itself be a parent. Do not assume there is a distinguished global root."* — so "root" is a role, not an absolute.
> CONFIDENCE: high

**D-Q3.** What may I change here?

> ANSWER: (1) **Installed unit content** — `<home>/skills/<unit>/`, `<home>/plugins/<unit>/`. The edit is in **no git diff**, is local to this machine, a later `install`/`upgrade`/`sync` can overwrite it, and the **only** way it reaches other machines is `skill-manager unit publish`. The root row's stated obligation is exactly that: "publish local edits globally (`skt check` prompts here)". (2) **Home policy** via `home policy [live|frozen]`. (3) **Drift acknowledgement** via `home drift --ack`.
> Not editable as source of truth: generated `.skill-manager/` state, generated env files, `units.lock.toml`, managed doc-import blocks, `~/.skill-manager/policy.toml` ("do not loosen … without explicit instruction"), `cli-lock.toml`, `registry.properties`, and `<home>/bin/cli/skill-manager` (owned by `home shims`; "a script that rewrote it corrupted 17 of 25 homes").
> There is a direct conflict in the corpus on point 1 — see M-Q2.
> CONFIDENCE: high

**D-Q4.** What must I NOT touch above me?

> ANSWER: **NOT ANSWERABLE FROM THE CORPUS as posed** — there is nothing above the root tier in this model, and no page enumerates an "above" for a root-tier session. Every "do not touch the operator's global home" rule in the corpus is addressed to agents *below* root. The nearest true analogue of "above" for root is **each unit's own git repository**, and the documented rule for reaching it is `unit publish` → branch → PR; `--direct` exists but is not the default, and `unit-authoring` says "Pushing and syncing are outward-facing — confirm with the user before running them unless they already asked."
> CONFIDENCE: high (that nothing is above root); medium (that the corpus intends the unit repos as the analogue — INFERRED)

**F-Q1.** Is `SKILL_MANAGER_HOME` alone sufficient?

> ANSWER: First, flag the premise: **for this session those are the same home.** Answering the general question: **No.** The full set the corpus names: `SKILL_MANAGER_HOME`; `CLAUDE_CONFIG_DIR` **and** `CLAUDE_HOME`; `CODEX_HOME`, `GEMINI_HOME`; `SKILL_MANAGER_INSTALL_DIR`; **plus PATH order**, which is not a variable at all — "the launch PATH puts this home's `bin/` first **and removes other homes' `bin/`**", because `skill-script` CLI deps are generated shell scripts with a home's absolute path baked in and "No variable redirects those." The documented correct move is not to export by hand: launch through `<home>/bin/launch/{claude,codex,gemini}` or `skill-manager exec`. "export variables by hand and you get the part you remembered." Related hazard: **exit 79**.
> SOURCE: `skills/git-issue-workflow/references/skill-homes.md` — "`SKILL_MANAGER_HOME` alone is **not** enough, and assuming it is has cost this epic real time".
> CONFIDENCE: high

**F-Q2.** Is the `PATH` `skill-manager` the right binary?

> ANSWER: **Probably not.** It is the last rule in `HomeDescriptor.resolveCli` (after `$SKILL_MANAGER_CLI`, the running process's own command, and `<store>/bin/cli/skill-manager`), and on the machines this was measured on the PATH answer is the released **0.19.2**, which lacks `home clone|shims|policy|describe|close-out` and `exec`, and "exits 2 for the operator who copy-pastes it".
> **How to tell** — read the CLI's help **text**, not its exit status: "the released 0.19.2 answers `home close-out --help` by printing top-level usage and **exiting 0**. A status-only probe would accept a CLI with no `close-out` at all."
> **What to do:** use the home's own pin `<home>/bin/cli/skill-manager` (identifiable by the `skill-manager:cli-pin` marker on line 2); or export `SKILL_MANAGER_CLI` **at a real launcher** and **never** at a home's shim — "it re-execs **forever** … 7:03 of CPU over 13:06 of wall clock from one teardown". If the pin is missing, re-run `home shims` from the build the home should use; it "exits 127 having written nothing" if nothing answers.
> CONFIDENCE: high

**F-Q3.** An unrecognised refusal from a wrapper; and `bootstrap-home.sh`'s exit codes.

> **Part A — the wrapper.** The corpus names exactly this symptom and calls it out as "One symptom worth recognising: a `skill-manager` that refuses to run":
> ```
> skill-manager: refusing to run against a home you did not name.
> ```
> **Exit 79.** The pin binds *that* home; when `SKILL_MANAGER_HOME` names a different one, the pin refuses rather than silently rebinding. "It surfaces most often through a wrapper script that resolves its CLI off `PATH` and then runs it under a `SKILL_MANAGER_HOME` you set, which is how the same command can have worked yesterday and refuse today."
>
> **Part B — `bootstrap-home.sh`** … clone the home first, export `SKILL_MANAGER_HOME` second, re-assert before every mutating step; policy `live` by default; verifies (never rewrites) the CLI pin; projects into `.claude`/`.codex`/`.gemini`; writes per-checkout exclusion rules into `$GIT_COMMON_DIR/info/exclude`; prints five stderr lines.
>
> **Every numeric exit code in the corpus for either situation:**
>
> | Code | Owner | Meaning |
> |---|---|---|
> | **79** | `skill-manager` pin | "refusing to run against a home you did not name" — invoked CLI and `SKILL_MANAGER_HOME` disagree. **Part A's answer.** |
> | **5** | `bootstrap-home.sh` | Source home held no units. `--allow-empty` accepts deliberately. |
> | **6** | `bootstrap-home.sh` | Skills not projected — N of M with N < M. `--allow-unprojected` accepts one. |
> | **127** | `home shims` (`RunningCli`), surfaced verbatim | No CLI candidate answered the probe; nothing written. |
> | **8** | `skill-manager exec` / launcher shims | Change-awareness (drift) gate. |
> | **3** | `wt new` | "no project home yet". |
> | **7** | `wt new` | Stale base branch. |
> | **4** | `close-change.sh` / `wt close` | Refuses removal — non-zero close-out verdict, or a failed `worktree-pre-remove` callback (not bypassed by `--force`). |
> | **0 / 1 / 2 / 9** | `home close-out` | nothing to lose / blocked / not-a-home / frozen destination. |
> | **0 / 1 / 9** | `skt ticket sweep` | pass completed / something failed / destination frozen. |
> | **11** | `skill-manager sync` | `skill-imports` validation failure from syncing out of dependency order. |
> | **2** | bare released 0.19.2 on `PATH` | Exits 2 on a remedy naming a subcommand it does not have. |
>
> CONFIDENCE: high for the codes; medium for the identification of Part A's message (the question gives no message text, so 79 is the corpus's *only* documented "unrecognised message + non-zero from a wrapper" case).

**F-Q4.** `tla-spec-dev --spec-root specs open ticket HIS-20`?

> ANSWER: **Yes — the form is exactly the documented one**, appearing verbatim across at least six pages. Caveats: (1) the workflow must already be scaffolded and the ticket must be a planned entry in `ticket_plan.yaml`; in epic mode the agent "must not scaffold another workflow, create an ad hoc ticket"; (2) `tla-spec-dev` is **not** "the one on PATH"; (3) `--spec-root` defaults to `specs`, so the flag is explicit-but-redundant, not wrong; (4) `HIS-20` as a ticket id is not named anywhere in the corpus, so I cannot confirm it is a real ticket — only that the command shape is real.
> Close: `close ticket <id> --summary "…" --result <path>`; `--accept-new`; retirement is a separate verb `retire ticket <id>`.
> CONFIDENCE: high (syntax); medium (whether this particular invocation would work in a given repo)

---

## M-Q1 — Instrument check (contamination)

> Contamination by answer:
>
> - **D-Q1 — heavily contaminated.** The `skt:skt` listing entry says verbatim: "which home tier this session writes (root ~/.skill-manager, project &lt;repo&gt;/.skill-manager, or a ticket worktree home)". Tier enumeration *and* assignment *and* the tool that reports it, in one sentence.
> - **D-Q2 — partial.** `skill-dev-skill`'s entry says "`skt publish` (home sync one tier up, then unit publish)". "One tier up, then the unit's own repo" was available without the corpus. Corpus added: that root has nothing above it, the inbound half, the "no distinguished global root" caveat.
> - **D-Q3 — partial.** `skt:unit-authoring` names the editable surface and "commit, push, then `skill-manager sync`". The policy/lock/generated-state prohibitions came only from the corpus.
> - **D-Q4 — partial.** `git-epic-workflow`'s entry contains "A bare `git worktree add` with no home step leaves the ticket agent writing the operator's global home."
> - **F-Q1 — CLEAN.** The listing never mentions `CLAUDE_CONFIG_DIR`, `CLAUDE_HOME`, `CODEX_HOME`, `GEMINI_HOME`, `SKILL_MANAGER_INSTALL_DIR`, or PATH ordering.
> - **F-Q2 — CLEAN.** Nothing in the listing discusses CLI resolution order, the pin, `SKILL_MANAGER_CLI`, 0.19.2/0.20.0, or help-text-vs-exit-status probing.
> - **F-Q3 — mixed.** `bootstrap-home.sh` and `wt close` are named by path in the listing, so *existence and purpose* is contaminated. But **not one numeric exit code** (79, 5, 6, 127, 8, 3, 7, 4, 0/1/2/9, 11) appears in the listing. **The exit-code table is entirely corpus-derived.**
> - **F-Q4 — weak.** The tool is named; the syntax is corpus-only.
>
> One further vector: a `SubagentStart` hook injected "CDC worktree overlay: not configured; .skill-manager/cdc-index/worktree.json is absent" into my context — a fact about your live filesystem, not the corpus. I did not use it in any answer.

## M-Q2 — Quality

> **1. Is `home clone` copy-on-write or not? Two pages measured it and disagree flatly.**
> `git-issue-workflow/references/skill-homes.md`: *"a clone is **copy-on-write**, not a copy … a `home clone` of a 189 MB home consumes **7.22 MB — 3.8%**."*
> `git-issue-workflow/references/worktrees.md` (same skill, sibling file): *"it is copying rather than cloning: … `cp -Rc` takes **11.2 s** and `cp -R` takes **26.0 s**, and `home clone` runs at the second speed."*
> `plugins/skt/skills/skt/SKILL.md` sides with the first. Both cite measurements on the same machine. The disagreement is load-bearing: it decides whether a home per ticket worktree costs 3.8% or 100%. The two use *different instruments* (free-space delta vs wall-clock), which may be the resolution — but no page says so.
>
> **2. "Never edit the store copy in place" vs. the entire home-edit/publish workflow.** One page forbids the exact act three other pages build a lifecycle around supporting. No page states the reconciliation.
>
> **3. Is there a `PATH` fallback behind the CLI pin? The same page says both.** § "The pin at …": *"there is **no `PATH` fallback behind it**"*; ~380 lines later: *"then a bare `skill-manager` off `PATH`."* Two different mechanisms (generated launcher body vs `resolveCli`), never distinguished.
>
> **4. `projects.md` contradicts its own framing within eight lines** — "Do not assume there is a distinguished global root" immediately followed by the root/project/worktree table.
>
> **5. A documented remedy that names a nonexistent flag** (`--checkout`). The corpus is honest about it; the defect is live.
>
> **6. Minor: an admitted stale claim about `skt` availability.** skt's SKILL.md measures no-skt homes, while `migration.md` says "once the project home carries skt, every `wt new` / `skt ticket new` after that is done for free." Leaves "will my worktree have skt?" genuinely unresolved.

```
COMPLIANCE: I read nothing outside the corpus. No forbidden command, no
WebSearch/WebFetch. I created, modified and deleted nothing. Two disclosures:
(a) my first `find` produced 34 KB and the HARNESS auto-persisted it under
/Users/hayde/.claude/projects/.../tool-results/ — a write my action caused but
did not issue; I never read it back. (b) the SubagentStart CDC line, above.

CORPUS NOTES: 193 files, ~2.29 MB, 20 skills + 3 plugins. Markdown only — no
scripts, no TOML/JSON manifests, no CLI --help, which is exactly where several
pages say the authority lives. So every flag list I gave is a doc's
restatement, not the authority. Notably absent: any page for `skill-manager
exec` itself, any `wt`/`skt` --help text, and any file naming HIS-20.
```

---

## Grader's note

**F-Q3 is the ticket's headline delta.** The BEFORE read at this same tier
answered the `bootstrap-home.sh` half with exits 5 and 6, high confidence,
verbatim citation — and could not reach exit 79, which appeared in **zero** of
the four instructing units' markdown. This read produced 79 with its cause, its
usual trigger, and its remedy, plus an eleven-row table separating it from the
ten codes it is routinely confused with. That is the DEF-018 fact moving from
*absent* to *stated once and routed to*.

Finding **M-Q2 #1** (copy-on-write vs copying) is DEF-098 — since **resolved**
by measurement; see the ledger for the table. It is not
this ticket's text; it matters because HIS-6's disk arithmetic depends on which
of the two numbers is right.
