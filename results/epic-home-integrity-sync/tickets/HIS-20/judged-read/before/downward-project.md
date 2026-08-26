# BEFORE — DOWNWARD, PROJECT tier

- **Direction:** downward (root → project → worktree; how work is handed out)
- **Tier:** project — `SKILL_MANAGER_HOME=/Users/hayde/IdeaProjects/skill-manager/.skill-manager`
- **Corpus:** 80 md files; every `SKILL.md` / `README.md` / `references/**.md` the
  project home's `skills/**` and `plugins/**` put in front of an agent, copied
  read-only. Four skills: deploy-helm, spec-double-compiler, test-graph,
  tracing-observability. No `plugins/` at all.
- **Date:** 2026-08-24. Corpus state: `feature/247-his-20` at `4ed578a`, before any
  documentation change.

---

**D-Q1.** Skill-manager homes come in tiers. Which tier is `/Users/hayde/IdeaProjects/skill-manager/.skill-manager`, what other tiers exist, and how would an agent determine which tier its session is writing?

```
ANSWER: Three tiers exist, and the corpus draws them explicitly:

    root       ~/.skill-manager              where the operator installs
       |  copy
    project    <repo>/.skill-manager         one per repository, gitignored
       |  copy
    worktree   <worktree>/.skill-manager     one per ticket, gitignored

`/Users/hayde/IdeaProjects/skill-manager/.skill-manager` matches the
`<repo>/.skill-manager` shape, i.e. the PROJECT tier — but that identification
is INFERRED from the path, and it is NOT decisive on the corpus's own terms:
the worktree tier has the identical `<something>/.skill-manager` shape, so a
path alone cannot separate project from worktree. (The corpus never says the
directory is named after the repo, only that it sits inside the checkout.)

How to determine the tier authoritatively, per the corpus: run
`skill-manager home describe --json`, which reports "the env, the resolved CLI,
the unit snapshot", and `skill-manager home drift`, which "refuses a launch
while a unit moved unread". The corpus offers no non-CLI way to tell the tiers
apart. I did not run either command (forbidden by my instructions), so I report
the tier as inferred, not verified.

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md — "There is no single Skill Manager home. A machine has up to three tiers, and each one is a **real copy** of the one above it, not a symlink:" followed by the root/project/worktree diagram; and "Check which home answered before trusting a run: `skill-manager home describe --json` # the env, the resolved CLI, the unit snapshot". The identification of THIS path as the project tier is INFERRED, not stated.
CONFIDENCE: high (on the tier model and the detection command); medium (on this specific path being project rather than worktree)
HOW I FOUND IT: Grepped the corpus for `SKILL_MANAGER_HOME|tier|worktree|\.skill-manager`, which pointed at `spec-double-compiler/references/runtime_requirements.md`; opened that file in full.
```

---

**D-Q2.** What does this tier inherit from the tier above it, and by what mechanism?

```
ANSWER: It inherits the units — the installed skills and the generated CLI
wrappers (`tlc2`, `pytest`, `jinja2`, `tla-spec-dev`, and per the deploy-helm
docs `bin/cli/computeq`) — by COPY, not by symlink. Each tier is "a real copy
of the one above it". The corpus is emphatic that the copy is deliberate: a
symlink farm would make child and parent the same bytes, so two ticket
worktrees editing "their" `tlc2` wrapper or their `spec-double-compiler` would
be editing each other's.

Two consequences the corpus states directly:
- The wrappers are generated shell scripts with that home's absolute path baked
  into the body, so no environment variable redirects them after the fact.
- The copy is a SNAPSHOT taken at creation time, not a live view: "`wt new`
  snapshots a home at worktree-creation time; the operator's home moved during
  the epic" — after which 3 of 6 surveyed skill files differed between the two
  homes, and two copies of `validate_epic_plan.py` differed by 19,507 bytes and
  disagreed on a verdict. That case is about the worktree tier inheriting from
  root, but the mechanism described (copy-at-creation, then divergence) is the
  same one the tier diagram describes for every level.

The corpus does not name the command that creates a PROJECT home from root
(only `wt new` for the worktree tier, and `skill-manager home sync --from ...
--to ... --merge` for pushing changes back up).

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md — "each one is a **real copy** of the one above it, not a symlink"; "The copies are deliberate. A symlink farm makes the child and the parent the same bytes, so two ticket worktrees editing 'their' `tlc2` wrapper ... are editing each other's."; "the wrappers are generated shell scripts with that home's absolute path baked into the body — no environment variable redirects them after the fact." Snapshot behavior: skills/spec-double-compiler/NEXT-EPIC.md — "`wt new` snapshots a home at worktree-creation time; the operator's home moved during the epic."
CONFIDENCE: high
HOW I FOUND IT: Same file as D-Q1; corroborated the snapshot/divergence point by reading NEXT-EPIC.md §12 ("TWO SKILL MANAGER HOMES, AND THIS EPIC RAN ON THE OLDER ONE").
```

---

**D-Q3.** What may I change here? Name what is legitimately editable in this home and what editing it means.

```
ANSWER: The corpus says installed SKILL directories inside your own home are
editable in practice — "If you improve `spec-double-compiler` while using it —
inside `<worktree>/.skill-manager/skills/spec-double-compiler/`" — and then
tells you what that edit MEANS:

  1. It is invisible. A home is gitignored, so the edit is "invisible to
     `git status`, invisible to the repository's diff, and deleted with the
     worktree."
  2. It is local to this tier only. Two commands move it, answering different
     questions:
       - up a tier, so teardown does not take it with it (local to this machine):
         `skill-manager home sync --from <worktree>/.skill-manager --to <repo>/.skill-manager --merge`
       - to the skill's own git repository, "the only path that reaches another
         project": `skill-manager unit publish <unit> --ticket <ticket>`
  3. `skill-manager home close-out --home <worktree>/.skill-manager --into <repo>/.skill-manager`
     REFUSES while such an edit is still only in the worktree, and names the
     command to run per unit. Run it before `git worktree remove`, never after.

IMPORTANT COUNTER-RULE, and the corpus contains both sides: several epic
charters in this corpus forbid editing skills at all from inside the working
session — "Skills are READ, never edited from here. Anything that must change
in `SKILL_MANAGER_HOME` is proposed as a diff and escalated. **Never run
`skill-manager sync`.**" Those are scoped to specific epics/measurement runs,
not stated as a global rule, but they are the strictest instruction present.

NOT ANSWERABLE FROM THE CORPUS: an enumeration of what else inside a home is
editable (config files, `bin/`, bindings, MCP/CLI tool registrations). The
corpus only ever discusses editing `skills/<unit>/` content. Note also the
one explicit "do not edit" inside a consumer scaffold — "do not edit `sdk/`,
`build-logic/`, or `standard-nodes/` ... Real changes belong in
`project_sdk_sources/` in this skill repo and reach consumers through
`skill-manager sync`" — but that is about a project scaffold, not about the
home.

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md, section "An edit you make to this skill inside a home is not in any diff" (quotes above). Counter-rule: skills/spec-double-compiler/SCORE-DRIVES-VALIDATION-EPIC.md — "Skills are READ, never edited from here. Anything that must change in `SKILL_MANAGER_HOME` is proposed as a diff and escalated. **Never run `skill-manager sync`.**" Scaffold rule: skills/test-graph/SKILL.md:124.
CONFIDENCE: high on what is stated; the reconciliation between the two rules is INFERRED
HOW I FOUND IT: The final section of runtime_requirements.md; then grepped `skill-manager sync|publish|home sync` to find the epic-level prohibitions.
```

---

**D-Q4.** What must I NOT touch above me?

```
ANSWER: The corpus names these, all stated:

1. The operator's GLOBAL/root home `~/.skill-manager` — do not read from it or
   write to it in preference to the bound home. Two files carry the same
   warning comment verbatim: "NOT `~/.skill-manager`: the test-graph unit lives
   in the home THIS checkout is bound to (a project or worktree
   `.skill-manager`), and only that copy matches the units this checkout was
   resolved against."
2. `skill-manager sync` — forbidden outright in three separate charters:
   "**Never merge to `main`. Never run `skill-manager sync`.**"; "**Never run
   `skill-manager sync`** or update `$SKILL_MANAGER_HOME`."; and "Anything that
   must change in `SKILL_MANAGER_HOME` is proposed as a diff and escalated.
   **Never run `skill-manager sync`.**"
3. Do not sync mid-epic at all, even legitimately: "**Decide which home is
   authoritative for an epic and say so in the charter** — and do NOT sync
   mid-epic, which moves text under tickets already running."
4. Do not let a worktree end up writing the operator's global home by accident:
   "Every ticket worktree goes through the front door, never `git worktree
   add`... A bare `git worktree add` leaves the agent writing the operator's
   global Skill Manager home."
5. Related PATH prohibition (see F-Q2): "**Never invoke `tla-spec-dev` from
   PATH** — it execs the installed clone at `da0a7ff`, containing none of this
   epic's work."

Caveat I must flag: items 2-4 appear in EPIC charter/starter-prompt documents
(SCORE-DRIVES-VALIDATION-EPIC.md, EPIC-HANDOFF.md, PORTS-AS-ADAPTERS-STARTER-
PROMPT.md, CUT-THE-APPARATUS-EPIC.md, ARCHITECTURAL-COHERENCE-EPIC.md,
COMPLEXITY-DESCRIPTOR-EPIC.md, NEXT-EPIC.md), i.e. constraints on particular
measurement runs. Whether they bind an arbitrary session standing in this home
is INFERRED, not stated. The one non-epic, reference-level statement is item 1
plus the read-order doctrine in runtime_requirements.md.

SOURCE: skills/spec-double-compiler/README.md:210-212 and skills/spec-double-compiler/SKILL.md:994-996 (the "NOT `~/.skill-manager`" comment); skills/spec-double-compiler/EPIC-HANDOFF.md — "**Never run `skill-manager sync`** or update `$SKILL_MANAGER_HOME`."; skills/spec-double-compiler/PORTS-AS-ADAPTERS-STARTER-PROMPT.md — "A bare `git worktree add` leaves the agent writing the operator's global Skill Manager home."; skills/spec-double-compiler/NEXT-EPIC.md — "do NOT sync mid-epic, which moves text under tickets already running."
CONFIDENCE: high on the quotes; medium on their scope binding this session
HOW I FOUND IT: Grepped for `skill-manager sync|~/\.skill-manager|from PATH|worktree add` across the corpus and read each hit in context.
```

---

**F-Q1.** Environment variables to make a CLI act on THIS home, not the operator's global one. Is `SKILL_MANAGER_HOME` alone sufficient?

```
ANSWER: No — `SKILL_MANAGER_HOME` alone is explicitly NOT sufficient, and the
corpus says so in two independent ways.

(a) The wrappers ignore it. "`tlc2`, `pytest`, `jinja2` and `tla-spec-dev` are
    not 'the ones on PATH'. They are the ones in the home this checkout is
    bound to, and the wrappers are generated shell scripts with that home's
    absolute path baked into the body — no environment variable redirects them
    after the fact." So exporting `SKILL_MANAGER_HOME` cannot re-point a
    wrapper that is already on your PATH from a different home.

(b) PATH is the part hand-exporting misses. The prescribed method is:
    "Launch through the home's `bin/launch/{claude,codex,gemini}` shims (or
    `skill-manager exec`) rather than exporting variables by hand; the shims
    put this home's `bin/` first on PATH and remove other homes' `bin/`, which
    is the part hand-exporting always misses."

So: set `SKILL_MANAGER_HOME` AND fix PATH — and the corpus's answer is to not
do that by hand at all, but to launch via `<home>/bin/launch/<harness>` or
`skill-manager exec`.

Additional variable, specific to this skill's Python import resolution:
`SPEC_DOUBLE_COMPILER_HOME` names the skill directory itself and is the
explicit override; "Set but wrong **refuses**; it does not fall through." The
scaffolded resolver order is: (1) `SPEC_DOUBLE_COMPILER_HOME`, (2)
`$SKILL_MANAGER_HOME/skills/spec-double-compiler`, (3) nearest enclosing
`<checkout>/.skill-manager/...` walking up, (4) `~/.skill-manager/...` LAST,
(5) inherited `PYTHONPATH` only when none of the four answered. Note item (2)
beats an exported `PYTHONPATH`: "when `SKILL_MANAGER_HOME` and the
`tla-spec-dev` you invoked name **different** builds of this skill, the bound
home wins."

Also mentioned but secondary: `SKILL_MANAGER_BIN_DIR` — "If `SKILL_MANAGER_HOME`
is unset, the installer falls back to the parent of `SKILL_MANAGER_BIN_DIR` and
still writes `bin/cli/computeq`."

Verify before trusting a run: `skill-manager home describe --json` and
`skill-manager home drift`.

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md — "no environment variable redirects them after the fact. Launch through the home's `bin/launch/{claude,codex,gemini}` shims (or `skill-manager exec`) rather than exporting variables by hand; the shims put this home's `bin/` first on PATH and remove other homes' `bin/`, which is the part hand-exporting always misses." Plus the numbered resolver list in the same file. `SKILL_MANAGER_BIN_DIR`: skills/deploy-helm/references/computeq-cli.md:9-10.
CONFIDENCE: high
HOW I FOUND IT: runtime_requirements.md, sections "Which Skill Manager home those tools come from" and "How a scaffolded module finds this skill"; grepped `SKILL_MANAGER_BIN_DIR` for the deploy-helm detail.
```

---

**F-Q2.** There is a `skill-manager` executable on my PATH. Is that the right binary for work on this home?

```
ANSWER: PARTLY ANSWERABLE.

What the corpus DOES state: PATH is not trustworthy for skill-manager-installed
CLIs in general. "`tlc2`, `pytest`, `jinja2` and `tla-spec-dev` are not 'the
ones on PATH'. They are the ones in the home this checkout is bound to," and
the concrete failure mode is spelled out for `tla-spec-dev`: "**Never invoke
`tla-spec-dev` from PATH** — it execs the installed clone at `da0a7ff`,
containing none of this epic's work"; elsewhere, "**A gate can outlive its
removal in an installed copy** — the repo retired a refusal and the skill
home's stale module still enforced it, because Python puts a script's own
directory first on `sys.path`."

HOW TO TELL: `skill-manager home describe --json` — the corpus says it reports
"the env, the resolved CLI, the unit snapshot", and instructs "Check which home
answered before trusting a run". `skill-manager home drift` "refuses a launch
while a unit moved unread". (I did not run either; running skill-manager is
forbidden for this exercise.)

WHAT TO DO IF IT IS NOT the right one: launch through this home's
`bin/launch/{claude,codex,gemini}` shims, or use `skill-manager exec` — "the
shims put this home's `bin/` first on PATH and remove other homes' `bin/`."
For this skill's Python imports specifically, set `SPEC_DOUBLE_COMPILER_HOME`
to override.

NOT ANSWERABLE FROM THE CORPUS: whether the `skill-manager` binary ITSELF is
per-home (i.e. whether each home ships its own `skill-manager`), and therefore
whether a PATH `skill-manager` is the wrong one. Every statement above is about
UNITS' CLI wrappers (`tlc2`, `tla-spec-dev`, `computeq`) living under
`<home>/bin/cli/`. The corpus always writes `skill-manager ...` bare, never as
`$SKILL_MANAGER_HOME/bin/skill-manager`, and never warns against a PATH
`skill-manager`. Applying the wrapper rule to the `skill-manager` binary is
INFERENCE on my part. Note also the bootstrap problem: `home describe` is
itself a `skill-manager` subcommand, so using it to validate `skill-manager`
assumes the answer — the corpus does not address that.

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md ("not 'the ones on PATH'"; "Check which home answered before trusting a run: `skill-manager home describe --json` # the env, the resolved CLI, the unit snapshot"); skills/spec-double-compiler/EPIC-HANDOFF.md ("Never invoke `tla-spec-dev` from PATH — it execs the installed clone at `da0a7ff`"); skills/spec-double-compiler/PORTS-AS-ADAPTERS-STARTER-PROMPT.md ("A gate can outlive its removal in an installed copy"). The extension of these to the `skill-manager` binary is INFERRED, not stated.
CONFIDENCE: medium (high on the general PATH doctrine, low that it is stated about `skill-manager` itself)
HOW I FOUND IT: Grepped `on PATH|from PATH|bin/launch|skill-manager exec|home describe` across the corpus; all hits are unit CLIs, none is the `skill-manager` binary.
```

---

```
COMPLIANCE: I read nothing outside the corpus directory.
I ran no skill-manager, skt, skill-dev, tla-spec-dev, git or gh command, no
WebSearch/WebFetch, and no MCP tool. I created, modified, moved and deleted
nothing anywhere, including /tmp — every command was find/grep/sed -n/cat and
the Read tool. No redirection was used. One note for full honesty: I recognized
the tier vocabulary before finding it, but every field above is sourced to a
corpus file and I marked as INFERRED the two places where I extended a stated
rule (this path being project-tier, and the PATH doctrine applying to the
`skill-manager` binary itself).

CORPUS NOTES: 77 files total. I enumerated all of them, grepped all of them,
and read in full or in relevant part: spec-double-compiler/references/
runtime_requirements.md (the single load-bearing file — it answers D-Q1, D-Q2,
D-Q3 and F-Q1 almost by itself), spec-double-compiler/README.md,
EPIC-HANDOFF.md, SCORE-DRIVES-VALIDATION-EPIC.md, PORTS-AS-ADAPTERS-STARTER-
PROMPT.md, NEXT-EPIC.md 12, deploy-helm/references/computeq-cli.md, plus
targeted sections of test-graph SKILL.md/workflows.md/github-actions.md.

The corpus feels TRUNCATED with respect to these questions specifically. It
contains four skills — deploy-helm, spec-double-compiler, test-graph,
tracing-observability — and NONE of the units that actually own the home/tier
model: there is no `skills/skill-manager/`, no `skt` plugin, no
`git-issue-workflow`, no `git-epic-workflow`, no `skill-dev-skill`. Those are
referenced from inside the corpus (e.g. "`~/.claude/skills/git-issue-workflow/
scripts/wt new`", "`~/.claude/skills/git-epic-workflow/scripts/
validate_epic_plan.py`", "`git-issue/references/regression-close.md`") but
their documents are absent, and one reference points at `~/.claude/skills/`
rather than a skill-manager home at all. So everything I could answer about
tiers came from ONE reference page inside an unrelated skill, which is why F-Q2
falls apart: the authoritative doc for the `skill-manager` CLI is not here.
Also absent: any doc-repos/ or plugins/ subtree — the corpus root contains only
skills/.
```
