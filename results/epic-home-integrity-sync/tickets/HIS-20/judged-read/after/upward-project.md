# AFTER — UPWARD, PROJECT tier

- **Direction:** upward. **Tier:** project. Premise: edited `<home>/skills/test-graph/`.
- **Corpus:** 100 md files — **counterfactual**, the same shape a `project resolve`
  against this checkout's own manifest produces, plus this ticket's edited units
  overlaid as published. The real project home was not changed.
- **Date:** 2026-08-24. Fresh agent. Grades the corpus before the round-2 trims.

---

**U-Q1.** How does my edit reach the tier above?

> ANSWER: My session home is the **project** tier, so the tier above is **root**. `skill-manager home sync --from <repo>/.skill-manager --to ~/.skill-manager [--merge]` (or `skt publish`, whose first leg is exactly that). Moves the edit **exactly one tier**, by copy, on this machine only. `--from` is never written.
> **On "the tier it must be run from": the corpus never states a required working directory for `home sync`.** Both ends are explicit path flags. The only adjacent constraints stated are (a) the CLI-vs-home pin and exit **79**, and (b) for `skt ticket sweep` specifically, "Run it **from the primary checkout**." No such sentence exists for `home sync`. **Partially answerable — command yes, mandated cwd no.**
> One caveat that bites this exact case: skt's tier table assigns the project tier "none — pull-side only", i.e. it does not contemplate a project-home edit at all. See M-Q2 item 7.
> CONFIDENCE: high for the command and tier; high that the cwd requirement is *not stated*.

**U-Q2.** What does git carry?

> ANSWER: Git carries the repository's tracked working tree — checkout, ticket branch, diff, PR, and in an integration repo the fan-out. **One nasty exception the corpus calls out:** a *tracked symlink* inside the working tree means git stores the **target bytes**, so whatever `SKILL_MANAGER_HOME` was when the scaffolder ran is baked into every later clone — that is what `[[vendored]]` exists to catch.
> Git does not carry anything inside a `.skill-manager`. Two mechanisms cross, and they are not alternatives: `home sync` (filesystem copy, one tier up, this machine only) and `unit publish` (git, into **the unit's own repository**, `skill/<ticket>-<unit>` + PR against trunk; `--direct`, `--no-pr`). **"The repository you are standing in is *not* the destination of either."**
> CONFIDENCE: high

**U-Q3.** The close-out gate.

> ANSWER: `skill-manager home close-out --home <worktree>/.skill-manager --into <repo>/.skill-manager [--json]`. `--into` must be **the project home it was cloned from, not `~/.skill-manager`** — "Get the pair wrong and the verdict is about the wrong two homes." Writes nothing, safe to re-run. Run by `skt ticket close`, re-measured per worktree by `skt ticket sweep`, and by `close-change.sh` before `git worktree remove` (refusing at **exit 4**).
> Verdicts 0 / 1 / 2 / 9 as documented, with 2's rationale quoted: "this exists because the gate used to answer `safe: true` when `--home` was the worktree **directory** rather than its `.skill-manager` — and that directory is exactly what `git worktree remove` takes."
> Refuses over a **LINKED** unit; over a git-backed unit unless its working tree is clean and every ref it holds is reachable from the project home, reporting a history neither side contains as the single entry **`.git (history)`** whose detail names the two fixes. **No `--force`, deliberately.** A `worktree-pre-remove` callback failure "refuses even with `--force`".
> CONFIDENCE: high

**U-Q4.** `held-back` / `conflicted`.

> ANSWER: `held-back` is `home sync`'s default when the destination has its own edit — "held back and reported, not overwritten … because holding back costs a conflict and overwriting costs an edit nobody sees again"; the same word covers the concurrency case, where the second of two serialized whole-home syncs is "a result to read, not a step that succeeded". **What the agent does:** re-run with `--merge` — the remedy `close-out` prints.
> `conflicted` is `--merge`'s outcome: "reported, never resolved; local work is kept either way and **a conflicted unit writes nothing**", governed by "When unsure, conflict": "A merge base that is too old costs a spurious conflict a human resolves; one that is too new costs an edit nobody sees again."
> The corpus states the *outcome* but **gives no in-corpus resolution procedure** beyond the remedy command, the listed conflicted files, and the `.git (history)` fixes. Because a conflicted unit writes nothing, the durable answer remains `unit publish`.
> **Do not confuse either with `MERGE_CONFLICT`,** a recorded *installer* error, whose instruction is explicit and opposite.
> CONFIDENCE: high for `held-back` and for the semantics of `conflicted`; medium-low that the corpus prescribes a complete conflict-resolution procedure — it largely does not.

**C-Q1.** Per-unit publish, tier, repository.

> Six units. Same command shape for all. **TIER:** the corpus does not name a required tier; it names a required **property of the home** — `unit publish` publishes "a unit **this home installed**", and that directory must be a git checkout. `--home H` selects which. Two documented consequences: a worktree cloned from a project home that never installed a unit cannot publish it; and from the worktree tier the two legs of `skt publish` are decided separately.
> **REPOSITORY:** spec-double-compiler → `tla-spec-dev` (stated twice); test-graph → `test_graph_skill` (three times); deploy-helm → `deploy-cdc` (twice); skt → `skill-publisher-skill`. **`skill-manager` — NOT ANSWERABLE.** **`tracing-observability` — NOT ANSWERABLE as a unit repo**; the only concrete coordinate is for its *Python package*, and promoting it is INFERRED, not stated.
> **How to find out: no command prints it.** `list` → `SOURCE git` + short SHA; `show --json` → `source`, `sha`, `path`, **no `origin`**. Read `<home>/units.lock.toml` or `<home>/installed/<unit>.json`. `gh repo list <owner> --limit 400` to go the other way, "because the repo name is usually not the installed unit name."
> CONFIDENCE: high for the four stated mappings, the command shape, the plugin rule and the discovery method; high that `skill-manager`'s repo is absent; medium-low (explicitly inferred) for `tracing-observability`.

**C-Q2.** Four commands, tabulated correctly; `home sync` moves one tier up, `unit publish` reaches GitHub, `skt publish` is the composite, `skt sync` is the download. "They are explicitly **not alternatives**." CONFIDENCE: high.

**C-Q3.** Installed hash vs remote tip, with the `errors[*].kind` override read **first**. CONFIDENCE: high for the comparison and the error-first behaviour; medium for "stale artifacts", which the corpus names but never defines.

---

## M-Q1 — Instrument check

> **Heavily contaminated:** U-Q1, U-Q3, C-Q2, and C-Q3 partially — `skill-dev-skill`'s listing line "that is `skt publish` (home sync one tier up, then unit publish)" and `git-issue-workflow`'s "a gate that refuses while removing it would destroy unpublished skill work" supply the headlines directly.
> **Lightly:** U-Q2, C-Q1's command half.
> **Clean:** U-Q4 entirely; **C-Q1's repository mapping** and the `installed/<unit>.json` lookup method entirely.
> **Separate leak, and you should know about it:** "a `SubagentStart` hook injected the line 'CDC worktree overlay: not configured; `.skill-manager/cdc-index/worktree.json` is absent' into my context. That is out-of-corpus information about the actual filesystem state of your home. It did not bear on any answer above, but it reached me without my asking … **Your harness leaks state into the sandbox.**"

## M-Q2 — Quality

> **1. Two pages name different commands for sending a home edit to a unit's own repository — a direct contradiction.** `git-issue-workflow/references/skill-homes.md`'s push-back table: *"That skill's own repo, trunk-style, via **`skill-manager project sync`**"*. Everyone else: `unit publish`. Three problems compound: the command differs; "trunk-style" contradicts the PR-branch shape everyone else documents (and `plugin-repository/references/lifecycle.md` explicitly corrects an earlier draft on exactly this point); and `project sync` appears nowhere else as a publishing command. **"`skill-homes.md` looks stale/wrong. This is the single most consequential defect in the corpus, and it sits on the page most likely to be read at teardown."**
> **2. Which tier `unit publish` runs from — a flat contradiction.** `skill-homes.md`: "push from the **main checkout**. From a worktree the skill's upstream is the wrong target." Three other pages make the worktree tier the canonical place — `worktree-lifecycle.md` §2, `complete.md`, `regression-close.md` ("only `unit publish` is the ticket agent's to run"). Unreconciled.
> **3.** The `--checkout` refusal-message defect (documented honestly; live).
> **4.** `unit-authoring` is stale about what `skill-publisher-skill` installs.
> **5.** skt's tier table says the project tier is "pull-side only"; the epic pages make it a write destination — "Reading the table alone strands work in the project home permanently — exactly the failure `worktree-lifecycle.md` calls *'an improvement that reaches this one project home and nowhere else, forever.'*"
> **6.** `skt check`'s scope is described two ways ("stale artifacts" vs "unpublished-work notifications").
> **7.** Page ownership is stated inconsistently.
> **8. Non-defects I checked and found consistent:** the close-out exit ladder agrees across five pages; the caller's exit 4 and sweep's exit 9 are consistently distinguished; **the copy-on-write measurements (3.8% and 3.1%) are explicitly cross-cited as different homes, not a conflict**; plugin-granularity publishing is stated identically in two places.

```
COMPLIANCE: Nothing read outside the corpus. No forbidden command. No file
created, modified, moved or deleted. One out-of-corpus fact reached me without
my asking, via the SubagentStart hook (above). I did not use it in any answer.

CORPUS NOTES: 100 files. Read in full: skt/SKILL.md, skt/references/
{bindings-and-sync,coords-and-distribution}.md, skill-manager/SKILL.md,
skill-manager/references/{workflows,projects}.md; targeted reads and two
exhaustive greps over all 100 for the lifecycle vocabulary and every concrete
github coordinate. MISSING: git-issue-workflow, skill-dev, git-integration-repo;
no page names `skill-manager`'s or `tracing-observability`'s own repository; no
units.lock.toml / installed/*.json / CLI --help, so which units this home holds
as a git checkout — the precondition for `unit publish` — cannot be determined
from documentation.
```

---

## Grader's note

M-Q2 #8 is the reason this transcript matters beyond its answers: the reader
**checked four things it expected to be inconsistent and reported them
consistent**, including the copy-on-write figures that the AFTER downward-root
read flagged as contradictory. The two disagreements are about *different pairs
of pages* — 3.8% vs 3.1% (different homes, cross-cited, fine) and
`skill-homes.md`'s "copy-on-write" vs `worktrees.md`'s "it is copying rather than
cloning" (a real conflict). DEF-099 records the second and explicitly excludes
the first, because a ledger row that names the wrong pair is worse than none.

M-Q2 #1 and #2 are **DEF-098**: `skill-homes.md` names a pull command as the
push-back route and forbids the tier three other pages designate. Both were found
independently by the BEFORE upward-root read; two agents converging on it from
opposite ends of the corpus is why it is filed as major rather than minor.
