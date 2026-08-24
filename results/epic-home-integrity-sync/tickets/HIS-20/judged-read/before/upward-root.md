# BEFORE — UPWARD, ROOT tier

- **Direction:** upward (worktree → project → root; how work is handed back)
- **Tier:** root. Premise: the agent edited a file under
  `~/.skill-manager/skills/test-graph/`.
- **Corpus:** 193 md files (the agent counted 199 entries); the root home's
  20 skills + 3 plugins.
- **Date:** 2026-08-24, before any documentation change.

---

**U-Q1.** How does my edit reach the tier above this one?

```
ANSWER: There is NO tier above this one. My session's home is /Users/hayde/.skill-manager, which the corpus names as the ROOT tier — the top of a three-tier model (root -> project -> worktree) in which copies flow downward only. `home sync` exists to move an edit "up exactly one tier", and from root there is no destination for it.

What the documents say the edit must reach instead: test-graph's OWN git repository. The root tier's stated obligation is "publish local edits globally". The exact command, run from the root home:

    skt publish test-graph
      # which the corpus says runs, underneath:
    skill-manager unit publish test-graph --ticket <ticket>
      # optionally targeting the home explicitly: unit publish <name> [--ticket T] [--home H]

That commits the home's edits to that unit on branch `skill/<ticket>-test-graph`, pushes, and opens a PR against the unit's trunk. It is described as "the only route that reaches a sibling project or outlives this machine."

(For test-graph specifically there is also a content rule: "Real changes belong in `project_sdk_sources/` in this skill repo and reach consumers through `skill-manager sync`." That is about which directory of the test-graph repo the change belongs in — the publish route above is still how a home-resident edit gets into that repo.)
SOURCE: plugins/skt/skills/skt/SKILL.md — "| root | `~/.skill-manager` | operator installs; `skt sync` | publish local edits globally (`skt check` prompts here) |" and "`home sync` moves it one tier up (and no further), `unit publish` is the only route to the skill's own repository and to other machines."; "skt publish [<unit>]  # a home-edited skill -> up one tier -> its own git repo". skills/skill-manager/references/projects.md — "This commits the home's edits to that unit on `skill/<ticket>-<unit>`, pushes, and opens a pull request against the unit's trunk." plugins/skt/references/migration.md — "Copies flow **down** (root → project → worktree) and never update themselves". skills/test-graph/SKILL.md.
PARTLY INFERRED: no page says the words "root has no tier above it". That root is the top is inferred from the three-tier table's ordering, from "copies flow down", and from "The global `~/.skill-manager` is never a teardown target."
CONFIDENCE: high (command), medium-high (the "no tier above" framing)
HOW I FOUND IT: grepped the corpus for "skt publish|unit publish|one tier" and opened plugins/skt/skills/skt/SKILL.md first, then skills/skill-manager/references/projects.md.
```

---

**U-Q2.** What does git carry, and what does it not?

```
ANSWER: Git carries the REPOSITORY's tracked files — the checkout, the commits, the branch, the PR, and (in an integration repo) the fan-out of that parent diff to constituents.

Git does NOT carry the home. `<checkout>/.skill-manager` is gitignored/excluded, so a unit edit made inside a home is "in no git diff, no PR, and no fan-out": `git add -A` never sees it, `propagate.sh` can never carry it, and `git worktree remove` deletes it without a word. (The exclusion mechanism is itself worth naming: it is a rule written into `$GIT_COMMON_DIR/info/exclude` by bootstrap-home.sh, not necessarily a tracked .gitignore — so it is invisible to teammates and to review.)

What crosses the boundary between a home and a git repository:

  * INWARD (repo -> home): the store copy of a git-backed unit IS a git checkout of a REMOTE ref. `$SKILL_MANAGER_HOME/installed/<unit>.json` records `origin`, `gitRef`, `gitHash`, and `skill-manager sync <unit> --git-latest` fetches that remote into the store. It pulls from the remote, never from your working tree — a sync over an unpushed commit is a silent no-op that still exits 0.
  * OUTWARD (home -> repo): `skill-manager unit publish <unit> --ticket <t>` (wrapped by `skt publish`). Commits on `skill/<ticket>-<unit>`, pushes, opens a PR against trunk. Flags: `--direct`, `--no-pr`. It only considers units that carry BOTH an `origin` and a `gitHash`; a unit installed from `file://` has neither and is invisible to it.
  * NOT a crossing: `skill-manager home sync --from … --to … [--merge]` is home->home by COPY, one tier, on this machine only.

Note one inconsistency in the corpus: git-issue-workflow/references/skill-homes.md's push-back table names the outward mechanism as "trunk-style, via `skill-manager project sync`" and says to push "from the main checkout", while every other page (skill-manager, skt, git-issue, git-epic-workflow) names `skill-manager unit publish`. I report both; the `unit publish` spelling is the one stated in detail and in the most places.
SOURCE: skills/git-issue-workflow/references/skill-homes.md; plugins/skt/skills/unit-authoring/SKILL.md — "The store copy for a git-backed unit is a checkout of a **remote** ref."; skills/skill-manager/references/projects.md — "| Moves | home → home, one tier up | home → the unit's own git repo |"; skills/plugin-repository/references/lifecycle.md — "It only considers units that carry an `origin` *and* a `gitHash`."
CONFIDENCE: high
HOW I FOUND IT: grepped "gitignored|propagate|origin.*gitHash" and read skill-homes.md end-to-end, then projects.md's "Publishing An Edit" section.
```

---

**U-Q3.** The close-out gate.

```
ANSWER: The gate is `skill-manager home close-out --home <worktree>/.skill-manager --into <project-home>/.skill-manager [--json]`, run by `close-change.sh` / `wt close` / `skt ticket close` BEFORE anything is deleted.

What it decides: whether the home about to be discarded "holds nothing that removing it would destroy". It writes nothing and is safe to re-run. `--into` must be the project home the worktree's home was actually cloned from, or the verdict is about the wrong two homes.

What it refuses, and why:
  * exit 1 — the blocked verdict: this worktree still holds work. The only exit that prints blockers; each names the unit, its status, its conflicts, and a LITERAL remedy command (home sync / home sync --merge / unit publish). It refuses because `git worktree remove` deletes a gitignored home "without asking" and "succeeds exactly as quietly whether that home held a week of skill edits or nothing".
  * exit 2 — the path given to `--home` is not a home at all (typically the worktree DIRECTORY rather than its `.skill-manager`). This exit exists because the gate used to answer `safe: true` for exactly that typo.
  * exit 9 — the destination home's policy is `frozen`, so the gate was refused and nothing was attempted. "refused, nothing attempted" is not "this worktree holds work".
  * a `LINKED` unit blocks on purpose — the gate cannot tell whose bytes a symlink target is, and "cannot tell" has to block rather than clear.
  * It also refuses when the tool is absent, or when the `--into` project home is missing: "Absence of the tool is absence of *proof* … A gate that opens when it cannot check is not a gate."
  * A project plugin `worktree-pre-remove` callback that fails or times out refuses too — not bypassed by --force.

There is deliberately NO `--force` on `home close-out` itself: "the CLI owns the verdict", and the override lives in the caller (`close-change.sh --force`).

Crucially: clearing the gate is reconciling, not publishing. "Clearing the gate makes the edit survive the worktree; it does not make it survive the machine."
SOURCE: skills/skill-manager/references/projects.md; skills/git-issue-workflow/references/complete.md; skills/git-issue-workflow/references/skill-homes.md.
CONFIDENCE: high
HOW I FOUND IT: grepped "close-out" for the file list, then read complete.md §6a and projects.md's "Discarding A Child Home" section.
```

---

**U-Q4.** `held-back` and `conflicted`.

```
ANSWER: First, a correction the corpus makes explicitly: these are NOT close-out verdicts. "`held-back` is a `home sync` status; `close-out` never reports it." They come back from `skill-manager home sync --from <child home> --to <parent home>`.

  * `held-back` (the DEFAULT, without `--merge`): the destination home has its own edited version of that unit, so the sync held it back and reported it rather than overwriting it. "That is the default because holding back costs a conflict and overwriting costs an edit nobody sees again." In an epic the usual cause is that an earlier worktree in the same wave already reconciled that unit.
  * `conflicted` (under `--merge`): `--merge` three-way merges the unit against its recorded per-file baseline; conflicts are "reported, never resolved", local work is kept either way, and a conflicted unit WRITES NOTHING.

What the agent is supposed to do — the same for both:
  * Treat it as A DECISION, NOT A RETRY. "This is the designed outcome, not a failure … read both versions, take the merge that keeps both improvements, and say in the wave review which ticket's edit won and why. Serialization prevents overwriting; it does not choose for you."
  * Never treat `held-back` as success. It is listed under "Never".
  * Record it in the worktree ledger as `held-back <units> (resolved: ...)`, with the wave it happened in, and report it in the human review.
  * For `conflicted` specifically: a conflicted unit writes nothing, the conflicted files are listed, resolution is yours; re-running the same sync is not the answer.
  * And do not stop there if the improvement should travel: "A blocker cleared by the wrong remedy is an improvement that reaches this one project home and nowhere else, forever." Where intent is unclear, ask the ticket's author or the user rather than guessing between `home sync --merge` and `unit publish`.
  * Ownership: the EPIC agent does these syncs, serialized, one worktree at a time; a ticket agent must not sync into the shared project home.
SOURCE: skills/git-epic-workflow/references/worktree-lifecycle.md; skills/git-epic-workflow/references/finalize.md — "`held-back` is a `home sync` status; `close-out` never reports it."; skills/skill-manager/references/projects.md — "Conflicts are **reported, never resolved**; local work is kept either way and a conflicted unit writes nothing."
CONFIDENCE: high
HOW I FOUND IT: grepped "held-back|conflicted" across the corpus; worktree-lifecycle.md §3 and finalize.md §1b carried it.
```

---

**C-Q1.** Per-unit publish command and tier.

```
ANSWER: The corpus states ONE rule for all of them and gives no per-unit publish command except by substituting the unit name:

    skt publish <unit>                                  # front door where the skt plugin is present
    skill-manager unit publish <unit> --ticket <ticket> # the plumbing it runs

TIER: run it from/against the home that HOLDS the edit — `unit publish` takes `--home H`. The corpus states this concretely for two tiers only:
  - worktree tier: the ticket agent "may run `skill-manager unit publish <unit> --ticket <ticket>` for its own edits";
  - main checkout: skill-homes.md's push-back table says "push from the **main checkout**. From a worktree the skill's upstream is the wrong target".
  These two statements are in tension in the corpus; I report both rather than reconciling them.

Accounting for every unit in front of me (23 top-level units):

GROUP 1 — plain skills. Command: `skt publish <name>` / `skill-manager unit publish <name> --ticket <t>`, run from the tier whose home holds the edit: acp-cdc-ai-python, debugging, deploy-helm, discovery, git-epic-workflow, git-integration-repo, git-issue, git-issue-workflow, hyper-experiments, hyper-experiments-finance, llm-wiki, plugin-repository, skill-dev-skill, skill-manager, slm-agent, spec-double-compiler, test-graph, tracer-agent, tracing-observability, vision-toolbelt-skill.

GROUP 2 — PLUGINS. Here the answer differs in an important way: the unit is the PLUGIN, so publishing lands on the PLUGIN's repository, not the contained skill's own repository:
  * skt (contains `skt` and `unit-authoring`) -> lands on github:haydenrear/skill-publisher-skill per migration.md.
  * andrej-karpathy-skills -> github:haydenrear/andrej-karpathy-skills.
  * cdc-agent-substrate-plugin -> the corpus names no repo coord for this one.
  For a plugin repo, reaching the CONTAINED skill's own repo is a SECOND, separate step: `propagate.sh <ticket> --push --mr`, run in the plugin repo, not in a home.

WHAT I CANNOT DETERMINE, and I say it for every unit above: whether each specific unit actually carries an `origin` and a `gitHash` in this home. "`skt publish` only considers units that carry an `origin` *and* a `gitHash`. A plugin installed from a local file … has neither and is invisible to `skt publish`." That fact lives in `$SKILL_MANAGER_HOME/installed/<unit>.json`, which is NOT in this corpus. Likewise, the corpus names a GitHub coord for only a handful; for the rest — acp-cdc-ai-python, debugging, deploy-helm, discovery, git-epic-workflow, git-issue, git-issue-workflow, hyper-experiments, hyper-experiments-finance, llm-wiki, skill-manager, spec-double-compiler, test-graph, tracing-observability, vision-toolbelt-skill, cdc-agent-substrate-plugin — the repository NAME is NOT ANSWERABLE FROM THE CORPUS (the command still is, because it takes the unit name, and "the unit name is not the repo name").
SOURCE: skills/skill-manager/references/projects.md; skills/git-epic-workflow/references/worktree-lifecycle.md; skills/git-issue-workflow/references/skill-homes.md; skills/plugin-repository/references/lifecycle.md; skills/spec-double-compiler/references/runtime_requirements.md.
CONFIDENCE: high for the command and the plugin-granularity rule; medium for the tier (the corpus gives two different answers); the per-unit repo names and per-unit git-backing are explicitly not determinable.
HOW I FOUND IT: listed the corpus tree for the unit names, then grepped "unit publish|skt publish" and read plugin-repository/references/lifecycle.md flow E.
```

---

**C-Q2.** `home sync` vs `skt sync` vs `unit publish` / `skt publish`.

```
ANSWER: They are three different things and only two of them are even in the same direction.

  * `skill-manager home sync --from <child> --to <parent> [--merge] [--dry-run] [--json]` — UPWARD, home to home, BY COPY, exactly ONE TIER, local to this machine. `--from` is never written. Without `--merge` an edited destination unit is held back. Answers: "will closing this worktree destroy the edit?" THIS IS THE ONE THAT MOVES AN EDIT ONE TIER UP.
  * `skt sync <unit>` — DOWNWARD/pull-side, a different axis entirely: it "pull[s] a unit to its latest pushed source", wrapping `skill-manager sync --git-latest`. It moves nothing of yours anywhere; it brings other people's merged work in. (Sync pulls from the remote, so an unpushed commit leaves the store on old bytes while still exiting 0.)
  * `skill-manager unit publish <unit> --ticket <t>` / `skt publish [<unit>]` — OUTWARD, home to the unit's own git repository. Answers: "will anyone else ever get it?" THIS IS THE ONE THAT REACHES GITHUB, and "the only route that reaches a sibling project or outlives this machine".
  * `skt publish` is a composite of the first and the third, in that order: "home sync one tier up, then unit publish — in that order".

They are explicitly NOT alternatives: "A chain-only design would need the same improvement merged up twice and would still never reach a **sibling** project. If you improved a unit, `unit publish` is the one you owe."
SOURCE: skills/skill-manager/references/workflows.md; skills/skill-manager/references/projects.md; plugins/skt/skills/skt/SKILL.md.
CONFIDENCE: high
HOW I FOUND IT: skt SKILL.md's command block, then the routing list at the top of skill-manager/references/workflows.md and the comparison table in projects.md.
```

---

**C-Q3.** What `skt check` compares.

```
ANSWER: It compares, per change-managed unit, the unit's INSTALLED HASH (the `gitHash` recorded in `$SKILL_MANAGER_HOME/installed/<unit>.json`) against ITS SOURCE'S TIP (the remote branch tip it was installed from). When they differ it tells you the unit is STALE — it names what is stale and the command that pulls it.

Two refinements the corpus insists on:
  * A disagreement does not always mean "behind". The installer records `errors[*].kind`, and for `MERGE_CONFLICT`, `NO_GIT_REMOTE` and `NEEDS_GIT_MIGRATION` that record IS the explanation. `skt check` reads that FIRST and emits a `unit-error` notification in place of the pull prompt — and you must NOT answer that with `skt sync` or `skill-manager sync --merge`; resolve in the store directory instead.
  * It also reports recorded unit errors, stale artifacts, and unpublished-work notifications, "with remedies".

And what it is NOT: "`home drift` answers 'did anything change *in* this home' and exits 0 on a stale one; `home verify` answers 'does everything in it *resolve*' — neither answers 'is this home *current*', which is why the check exists."
SOURCE: skills/git-epic-workflow/SKILL.md — "`skt check` compares each change-managed unit's installed hash against its source's tip and names what is stale and the command that pulls it."; plugins/skt/skills/skt/SKILL.md; plugins/skt/references/migration.md.
CONFIDENCE: high
HOW I FOUND IT: grepped "skt check" across the corpus; git-epic-workflow/SKILL.md rule 11 has the exact sentence.
```

---

```
COMPLIANCE: I read nothing outside the corpus directory. I ran no skill-manager,
skt, skill-dev, tla-spec-dev, git or gh command, no WebSearch/WebFetch, and
invoked no skills. I created, modified, moved and deleted nothing. One caveat to
be fully honest: my first command's output exceeded the display limit and the
HARNESS itself persisted it to /Users/hayde/.claude/projects/.../tool-results/
bsf12syt6.txt without my asking; my second command then `cat`-ed that
harness-written file. That is a read of a path outside the corpus, though its
entire content was the corpus file listing I had just produced. I did not write it
and did not use it for anything but the file list. No answer above draws on
background knowledge of this codebase.

CORPUS NOTES: 199 entries; I listed all of them and read 12+ in full or in
substantial part, plus targeted greps across all 199.

The corpus felt COMPLETE for the home/tier/publish/gate questions — those are
documented redundantly and in depth. Three gaps worth naming:
  1. No `installed/<unit>.json` and no `skill-manager list` output, so nothing
     tells me which units are git-backed or what their repo coords are. That is
     what makes C-Q1's per-unit detail undeterminable rather than merely tedious.
  2. No CLI `--help` text for `skt` or `skill-manager` is in the corpus, though
     several pages call it "authoritative".
  3. One genuine internal INCONSISTENCY: git-issue-workflow/references/
     skill-homes.md's push-back table names `skill-manager project sync` and
     "push from the main checkout" as the home->unit-repo route, while every
     other page names `skill-manager unit publish` and permits it from the
     worktree tier. I did not resolve it; I reported both.
  Also notable: finalize.md and worktree-lifecycle.md disagree slightly with the
  phrasing of U-Q4's premise — `held-back` is a `home sync` status and close-out
  "never reports it" — which I flagged rather than answered around.
```

---

## Grader's note, added after scoring

Two things in this transcript were checked against the product and both hold.

1. **The `project sync` inconsistency is real.** `skill-manager project sync`'s
   own help reads *"Pull each project unit's trunk and reconcile the project
   realization in place"* — a **pull**. `git-issue-workflow/references/skill-homes.md`
   names it as the **push-back** route to a unit's own repository. The read found
   a genuine contradiction in a unit this ticket does not own; filed as DEF-098.
2. **C-Q1's per-unit repository answer is a real product gap, not a reading
   failure.** `skill-manager show <unit> --json` carries `source`, `sha` and
   `path` and no `origin`; `list` prints `SOURCE git` and a short SHA. Nothing
   the CLI prints maps a unit to its repository. The AFTER corpus says where the
   mapping is recorded instead.
