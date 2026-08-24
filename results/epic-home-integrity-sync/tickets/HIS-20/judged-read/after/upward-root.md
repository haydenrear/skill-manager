# AFTER — UPWARD, ROOT tier

- **Direction:** upward. **Tier:** root. Premise: edited `~/.skill-manager/skills/test-graph/`.
- **Corpus:** 193 md files — the real root home with this ticket's edited units
  overlaid as published.
- **Date:** 2026-08-24. Fresh agent. Grades the corpus before the round-2 trims.

---

**U-Q1.** How does my edit reach the tier above?

> ANSWER: **There is no tier above.** Root is the top of the documented three-tier model. `home sync` "moves an edit exactly one tier" *up*, and root has nothing above it.
> What the documents say the edit must reach instead: **`test-graph`'s own git repository**, via `skill-manager unit publish test-graph --ticket <ticket>` (equivalently `skt publish`'s second leg; the first leg is inapplicable at root). That commits `<home>/skills/test-graph/` onto `skill/<ticket>-test-graph`, pushes, and opens a PR against the unit's trunk. The root tier's stated obligation is exactly this: "publish local edits globally."
> Two documented preconditions: `unit publish` only works on a unit **this home installed** whose store copy is a git checkout — otherwise exit 1, `not a git checkout at <home>/skills/test-graph`; and publish "only considers units that carry an `origin` *and* a `gitHash`", so a `file://`-installed unit is invisible to it.
> One caveat the corpus itself raises against calling root "the top": *"Any Skill Manager home can itself be a parent. Do not assume there is a distinguished global root."*
> CONFIDENCE: high (that `unit publish` is the route; that root is the top tier as documented). Medium on "no tier above" being universally true.

**U-Q2.** What does git carry?

> ANSWER: **Git does NOT carry the home.** An edit inside one is "in no diff, no PR, and no fan-out", invisible to `git status` and `git add -A`, uncarryable by `propagate.sh`, absent from a ticket PR / epic branch / epic PR, and deleted by `git worktree remove` "**without asking and exactly as quietly** whether it held a week of skill edits or nothing."
> **The ignoring mechanism** is *not* a `.gitignore` list: `bootstrap-home.sh` writes a per-checkout rule into **`$GIT_COMMON_DIR/info/exclude`** — because `.gitignore` is tracked, "and a repo nobody can commit to must still be able to get a home." Cost, stated: a rule there "is invisible to everyone else" — a teammate, CI, or a fresh clone still sees `?? .skill-manager`. A tracked `.gitignore` is **recommended, not required**; nothing reads or checks it.
> **What crosses:** the store copy of a git-backed unit *is itself a git checkout*, carrying its own `.git`, with `origin`/`gitRef`/`gitHash` in `installed/<unit>.json`. So the crossing is done by git, on that inner checkout — outward `unit publish`, inward `install` / `sync --git-latest` (which "pulls from the **remote**, never from a working tree — an unpushed commit makes sync a silent no-op that still exits 0"). `home close-out` knows about the seam: a git-backed unit is "judged by git before any record", and a history neither side contains is `.git (history)`.
> CONFIDENCE: high

**U-Q3.** The close-out gate.

> ANSWER: full and correct — the command, `--home`/`--into`, the dry-run-sync nature, exits 0/1/2/9 with 2's and 9's rationales, the LINKED rule, the no-`--force` design ("a gate with no escape hatch … routes them to `rm -rf` … by hand, which skips this check *and* every other one"), the callers (`close-change.sh` exit 4; `skt ticket sweep` skip-not-remove; degrade-to-refuse: "Absence of the tool is absence of *proof*… A gate that opens when it cannot check is not a gate"), and the limit it names about itself: **"Reconciling is not publishing… Clearing the gate makes the edit survive the worktree; it does not make it survive the machine."**
> CONFIDENCE: high

**U-Q4.** `held-back` / `conflicted`.

> ANSWER: Both are **`home sync` result statuses**, not `close-out` statuses ("`held-back` is a `home sync` status; `close-out` never reports it"). Governing rule: *"A reconciliation may destroy bytes in the destination only where it can show the **source** passed through them"* — *"When unsure, conflict."*
> What the agent does — the same discipline for both, and it is explicit: **treat it as a decision, not a retry, and never as success**; read both versions and produce a real merge, then record it (`held-back <units> (resolved: ...)`); **say who won and why** in the wave review / PR body; answer a plain-sync `held-back` by re-running with `--merge` where a three-way merge is what you want, and a genuine `conflicted` by hand; **escalate rather than guess** — "ask the ticket's author or the user before clearing it — the epic agent owns that the change is *managed*, not that it is guessed", because "A blocker cleared by the wrong remedy is an improvement that reaches this one project home and nowhere else, forever." **Ownership:** at the worktree tier the ticket agent must **not** run `home sync` into the project home; the **epic agent** reconciles, serialized, at wave close.
> Related but distinct: `skt check`'s `MERGE_CONFLICT` unit-error must **not** be answered with `skt sync` / `sync --merge`.
> CONFIDENCE: high

**C-Q1.** All 23 units accounted for by name.

> 20 skills + 3 plugins. The four *contained* skills are **not separately publishable units**: "For a **plugin**, the unit is the plugin, so `unit publish` lands on the plugin's repository and not on the repository of a skill contained in it." Consequently `skt publish` on an edit to `plugins/skt/skills/skt/SKILL.md` lands on the skt plugin's repo — "That is the design working, not a gap."
> The command is identical for all 23. Two documented exclusions: `cdc-agent-substrate-plugin` (its README: "Promote this directory to the root of its own Git repository before publishing" — it has no own repository yet), and any `file://`-installed unit.
> **The tier — the corpus does not give one answer.** Uncontradicted: it must run against a home whose store holds that unit as a git checkout; `--home H` names it. Beyond that the pages disagree: root ("publish local edits globally"); worktree (three pages, "may run `skill-manager unit publish … for its own edits — publishing goes to the unit's own git repository, contends with nothing"); main checkout (`skill-homes.md`: "push from the **main checkout**. From a worktree the skill's upstream is the wrong target"). **"Honestly: for every unit, the tier is 'whichever home's store holds your edited copy of that unit', and the corpus contains an unresolved conflict about whether a worktree home qualifies."**
> **How to find out (the documented method — this is the reliable answer):** the "no command prints it" paragraph, quoted in full, plus `gh repo list <owner> --limit 400`.
> **Repositories the corpus states outright — 12 of 23:** test-graph → `test_graph_skill`; spec-double-compiler → `tla-spec-dev`; deploy-helm → `deploy-cdc`; skt → `skill-publisher-skill`; tracer-agent, slm-agent, skill-dev-skill, plugin-repository, git-integration-repo → `git-integration-skill`, andrej-karpathy-skills, hyper-experiments (owner a placeholder), cdc-agent-substrate-plugin (**no repository yet**).
> **NOT ANSWERABLE for the remaining 11:** acp-cdc-ai-python, debugging, discovery, git-epic-workflow, git-issue, git-issue-workflow, hyper-experiments-finance, llm-wiki, **skill-manager**, **tracing-observability**, vision-toolbelt-skill. "Read `<home>/installed/<unit>.json` or `<home>/units.lock.toml` for each."
> CONFIDENCE: high on the command and the 12 stated repositories; **low/none** on the 11 unstated; **low** that any single tier is correct, because the corpus conflicts.

**C-Q2.** Four-row table, correct. "`skt sync` reaches GitHub only in the *read* direction (it fetches from it)." CONFIDENCE: high.

**C-Q3.** Installed hash vs remote tip; the fallback for skt-less homes reads exactly that field (`for f in "$SKILL_MANAGER_HOME"/installed/*.json; do … gitHash …`); scoped per home, so run it in both tiers; the `errors[*].kind` exception; and what it is NOT ("`home drift` … exits 0 on a stale one; `home verify` … neither answers 'is this home *current*', which is why the check exists"). **Scope inconsistency flagged:** skt's one-liner says "stale artifacts", `migration.md` says "unpublished-work notifications". CONFIDENCE: high on the comparison; medium on the full notification set.

---

## M-Q1 — Instrument check

> Contaminating strings already in the prompt, quoted: the `skt`, `skill-dev-skill`, `skill-manager`, `git-issue-workflow`, `git-epic-workflow` and `unit-authoring` descriptions. Per answer:
> - **U-Q1 — significantly contaminated.** The listing states the three tiers with root at top *and* the two-leg publish route. Corpus added the exact command, branch/PR shape, the installed-here precondition, and `test_graph_skill`.
> - **U-Q2 — partial.** Corpus-only: `$GIT_COMMON_DIR/info/exclude`, the propagate.sh fact, the store-copy-is-a-git-checkout seam, `installed/<unit>.json`.
> - **U-Q3 — contaminated on the headline** (`git-issue-workflow`'s description gives the gate's purpose for free); everything else corpus-only.
> - **U-Q4 — CLEAN.**
> - **C-Q1 — mixed.** Command contaminated; tier contaminated in outline; **the unit inventory heavily contaminated** (the listing enumerates essentially the same skills by name); **the repository mapping CLEAN** — no repo name appears in the listing, nor the `installed/<unit>.json` lookup method.
> - **C-Q2 — partial.** **C-Q3 — partial.**
> **Net:** "U-Q4 and the repository mapping in C-Q1 are the cleanest signal in this run. U-Q1, U-Q3, C-Q1's command, and C-Q2 are the most contaminated — for those, a model with only the skill listing and no corpus would likely have produced a passable answer."

## M-Q2 — Quality, including "does every page agree about which command sends a home edit to a unit's own repository?"

> **1. No, they do not — and this is the single most consequential defect in the corpus.** `git-issue-workflow/references/skill-homes.md`'s push-back table: *"That skill's own repo, trunk-style, via **`skill-manager project sync`**"*. Everywhere else: `unit publish`. Three compounding problems: the command differs; "trunk-style" contradicts the PR-branch shape (`plugin-repository/references/lifecycle.md` explicitly corrects an earlier draft on this point — "So the edit is on a PR branch of the plugin repo, not on its trunk"); `project sync` appears nowhere else as a publishing command. **"`skill-homes.md` looks stale/wrong … and it sits on the page most likely to be read at teardown."**
> **2. Which tier `unit publish` runs from — a flat contradiction.** One page forbids the worktree tier; three make it canonical. Unreconciled.
> **3.** The `--checkout` refusal-message defect.
> **4.** `unit-authoring` is stale about what `skill-publisher-skill` installs — "Anyone following it would run `skill-manager sync skill-publisher` against a unit that no longer exists."
> **5.** skt's tier table says the project tier is "pull-side only"; the epic pages make it a write destination with a real obligation. "Reading the table alone strands work in the project home permanently."
> **6.** `skt check`'s scope is described two ways.
> **7.** Page ownership is stated inconsistently.
> **8. Non-defects checked and found consistent:** the close-out exits agree across five pages; caller exit 4 and sweep exit 9 consistently distinguished; **"The copy-on-write measurements (3.8% in `skill-homes.md`, 3.1% in `worktree-lifecycle.md`) are explicitly cross-cited as different homes, not a conflict."** Plugin-granularity publishing stated identically in two places.

```
COMPLIANCE: Nothing read outside the corpus. No forbidden command. No file
created, modified, moved or deleted. One incidental note: my very first `find`
produced output large enough that the harness auto-persisted it under
/Users/hayde/.claude/projects/.../tool-results/ — a write performed by the tool
harness, not by a command I issued, and I never read that file.

CORPUS NOTES: 193 files, ~2.7 MB, 20 skill units + 3 plugin units.
MISSING / NOT IN CORPUS: all non-markdown files — notably <home>/units.lock.toml
and <home>/installed/<unit>.json, which are exactly the files the corpus names as
the authority for "which GitHub repository does this unit publish to?". That is
why C-Q1's repo column is answerable for only 12 of 23 units. Also absent:
skill-manager --help / skt --help output, which multiple pages declare
authoritative for syntax.
```

---

## Grader's note

This read is the one that makes **C-Q1 honest**. Asked to account for 23 units by
name, it answered the *command* for all 23, the *repository* for 12, and said
"NOT ANSWERABLE" for 11 by name — and separately gave the mechanical lookup that
closes the remaining 11 from the home rather than from prose. That is the correct
shape of the answer, and it is only reachable because the new §"Finding a unit's
repository: no command prints it" says where the mapping is recorded. The BEFORE
read at this tier reached the same "not determinable" verdict but had **no route
out of it**: it did not know `installed/<unit>.json` carries `origin`.

M-Q2 #1 was asked for directly by the prompt and answered against the corpus's
own interest. Filed as DEF-098.
