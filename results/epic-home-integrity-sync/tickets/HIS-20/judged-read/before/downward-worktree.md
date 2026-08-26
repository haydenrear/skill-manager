# BEFORE — DOWNWARD, WORKTREE tier

- **Direction:** downward (root → project → worktree; how work is handed out)
- **Tier:** worktree — `SKILL_MANAGER_HOME=/Users/hayde/IdeaProjects/wt-247-his-20/.skill-manager`
- **Corpus:** 80 md files. Four skills: deploy-helm, spec-double-compiler,
  test-graph, tracing-observability. **No `plugins/` directory at all.**
- **Date:** 2026-08-24, before any documentation change and before
  `project resolve` was run against this home.

---

**D-Q1.** Which tier is `/Users/hayde/IdeaProjects/wt-247-his-20/.skill-manager`, what other tiers exist, and how would an agent determine which tier its session is writing?

```
ANSWER:
The corpus states there are three tiers, and the exact quoted diagram is:

    root       ~/.skill-manager              where the operator installs
       |  copy
    project    <repo>/.skill-manager         one per repository, gitignored
       |  copy
    worktree   <worktree>/.skill-manager     one per ticket, gitignored

STATED: the three tiers are root, project and worktree.

INFERRED, not stated: that this path is the WORKTREE tier. The corpus never
names this path. My grounds are (a) it is not `~/.skill-manager`, so it is not
root, and (b) the corpus's own worktree directories are consistently named with
a `wt-` prefix as siblings under IdeaProjects — `../wt-epic-ports-as-adapters`,
"No `wt-epic-*` ticket worktree of this repository", and `wt new <ticket>` as the
creating command. `wt-247-his-20` matches that shape. This is a naming-convention
inference, not a documented fact; a directory literally named `wt-247-his-20`
could equally be a primary checkout, and nothing in the corpus rules that out.

HOW TO DETERMINE THE TIER — stated mechanisms:
  - `skill-manager home describe --json` — "the env, the resolved CLI, the unit
    snapshot"
  - `skill-manager home drift` — "refuses a launch while a unit moved unread"
  - `echo "$SKILL_MANAGER_HOME"` (test-graph debug reference uses exactly this)
  - `skt status` is injected at session start "in homes carrying the `skt`
    plugin" — but the corpus only claims it reports THIS SKILL's surface. It
    does NOT say in the corpus that `skt status` prints the home tier.

INFERRED, not stated: matching the printed `$SKILL_MANAGER_HOME` value against
the three path shapes in the tier diagram is how you name the tier. The corpus
supplies the diagram and supplies the commands, but never joins them into an
explicit "here is how you tell which tier you are in" procedure.

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md — "There
is no single Skill Manager home. A machine has up to three tiers, and each one is
a **real copy** of the one above it, not a symlink" and the tier diagram at lines
31-37; "Check which home answered before trusting a run: `skill-manager home
describe --json`". Corroborating: skills/spec-double-compiler/references/testgraph_adapters.md.
Naming convention: skills/spec-double-compiler/PORTS-AS-ADAPTERS-STARTER-PROMPT.md.
Env echo: skills/test-graph/references/debug-python-uv-env.md line 74.
CONFIDENCE: high for the tier model and the determination commands; medium for
the claim that this specific path is the worktree tier (inference from naming).
HOW I FOUND IT: grepped the corpus for `SKILL_MANAGER_HOME|skill-manager home|
tier`, which surfaced `references/runtime_requirements.md`; opened that file in
full first, then cross-checked `references/testgraph_adapters.md`.
```

---

**D-Q2.** What does this tier inherit from the tier above it, and by what mechanism?

```
ANSWER: It inherits the whole home — the installed units and the generated CLI
wrappers — as a REAL COPY, not a symlink. Mechanism, stated: each tier "is a
**real copy** of the one above it, not a symlink". The copy is taken as a
SNAPSHOT at worktree-creation time: "`wt new` snapshots a home at
worktree-creation time" (and `skt ticket new` is named elsewhere as the command
that creates "its own per-worktree home"). Because it is a snapshot, the child
does NOT track the parent afterwards — the corpus records exactly that drift:
"the operator's home moved during the epic. **3 of the 6 surveyed skill files
differ between them**", and "The two `validate_epic_plan.py` differ by **19,507
bytes** and disagree" (47,433 b vs 27,926 b, giving INVALID vs OK on the same plan).

The stated REASON for copying rather than linking: "A symlink farm makes the
child and the parent the same bytes, so two ticket worktrees editing 'their'
`tlc2` wrapper, their `spec-double-compiler`, or their generated cases are
editing each other's."

One important consequence, stated: the inherited CLI wrappers are "generated
shell scripts with that home's absolute path baked into the body — no environment
variable redirects them after the fact."

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md; NEXT-EPIC.md §12; CUT-THE-APPARATUS-EPIC.md.
CONFIDENCE: high
HOW I FOUND IT: same grep; the "real copy … not a symlink" paragraph is the first
substantive section of runtime_requirements.md.
```

---

**D-Q3.** What may I change here?

```
ANSWER: The corpus gives two answers that are in tension, and both are in scope.

(1) THE GENERAL RULE — editing a unit inside your own home is legitimate.
runtime_requirements.md has a section titled "An edit you make to this skill
inside a home is not in any diff" whose opening premise is that you DO improve
the skill in place. So the legitimately editable thing named is: the installed
unit's own files inside `<this home>/skills/<unit>/`.

WHAT EDITING IT MEANS — stated:
  - "the edit is invisible to `git status`, invisible to the repository's diff,
    and deleted with the worktree." A home is gitignored.
  - Two commands move it: `skill-manager home sync --from … --to … --merge`
    ("up a tier … local to this machine") and `skill-manager unit publish
    spec-double-compiler --ticket <ticket>` ("to this skill's own git
    repository, which is the only path that reaches another project").
  - `skill-manager home close-out --home … --into …` "refuses while such an edit
    is still only in the worktree, and names the command to run per unit. Run it
    before `git worktree remove`, never after."

(2) THE EPIC-CHARTER OVERRIDE — under an epic, editing is forbidden outright.
Three separate charters state: "**Skills are READ, never edited.** Propose any
`SKILL_MANAGER_HOME` change as a diff and escalate. **Never run `skill-manager
sync`.**"

So: whether I may edit depends on which regime this session is under. The corpus
does not tell me which.

INFERRED, not stated: that the general rule is the default and the charter rule
is a scoped override.

NOT ANSWERABLE FROM THE CORPUS: any enumeration of a home's non-unit contents
(config files, bindings, registry metadata) and whether those are editable.

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md lines 101-118;
CUT-THE-APPARATUS-EPIC.md; SCORE-DRIVES-VALIDATION-EPIC.md; EPIC-HANDOFF.md.
CONFIDENCE: high that both rules are stated as quoted; medium on how they compose.
HOW I FOUND IT: read runtime_requirements.md in full, then grepped for the
competing charter rule.
```

---

**D-Q4.** What must I NOT touch above me?

```
ANSWER: Stated prohibitions, in descending order of explicitness:

1. NEVER RUN `skill-manager sync`. Stated in five separate documents.
   EPIC-HANDOFF.md: "**Never run `skill-manager sync`** or update
   `$SKILL_MANAGER_HOME`." Reason, twice: syncing "moves text under tickets
   already running".
2. THE OPERATOR'S GLOBAL HOME (`~/.skill-manager`) is the thing you fall into by
   accident and must not: "A bare `git worktree add` leaves the agent writing the
   operator's global Skill Manager home."
3. DO NOT READ (let alone write) `~/.skill-manager` FIRST from a worktree.
   "`Path.home()` last is the whole point… from a ticket worktree it reads a home
   another agent is editing right now."
4. Any change that must land in `SKILL_MANAGER_HOME` under an epic charter is
   "proposed as a diff and escalated", not applied.
5. NEVER MERGE TO `main`.
6. DO NOT WRITE SHARED SCRATCH PATHS: "**Write scratch output to a
   TICKET-SPECIFIC path.** Two concurrent tickets corrupted a shared
   `baseline.txt`."

WHAT IS *NOT* FORBIDDEN, and this is worth stating: writing UP ONE TIER into
`<repo>/.skill-manager` is the sanctioned move, by name — `home sync --merge` and
`home close-out --into`. So the project tier is reachable through those two
commands; the root tier is reachable through no command the corpus sanctions.

NOT ANSWERABLE FROM THE CORPUS: `~/.claude`, `~/.codex`, `~/.gemini` as write
targets. `~/.claude` appears only as a READ path. Nothing states a rule about
writing them.

SOURCE: EPIC-HANDOFF.md; PORTS-AS-ADAPTERS-STARTER-PROMPT.md;
references/runtime_requirements.md; CUT-THE-APPARATUS-EPIC.md; NEXT-EPIC.md §12.
CONFIDENCE: high
HOW I FOUND IT: grepped `skill-manager sync|operator's global|~/.skill-manager|
escalate|never merge`, then read the "Standing constraints" blocks.
```

---

**F-Q1.** Is `SKILL_MANAGER_HOME` alone sufficient?

```
ANSWER: NO — and the corpus says so directly and gives the reason.

Verbatim: "So **`tlc2`, `pytest`, `jinja2` and `tla-spec-dev` are not 'the ones
on PATH'**. They are the ones in the home this checkout is bound to, and the
wrappers are generated shell scripts with that home's absolute path baked into
the body — **no environment variable redirects them after the fact**."

The missing ingredient is PATH, and the documented instruction is to not
hand-export at all: "Launch through the home's `bin/launch/{claude,codex,gemini}`
shims (or `skill-manager exec`) rather than exporting variables by hand; the
shims put this home's `bin/` first on PATH and remove other homes' `bin/`,
**which is the part hand-exporting always misses**."

So:
  - `SKILL_MANAGER_HOME` — necessary but not sufficient.
  - PATH — this home's `bin/` first AND other homes' `bin/` REMOVED.
  - Preferred: `<home>/bin/launch/claude` (or codex/gemini), or `skill-manager exec`.
  - Verify: `skill-manager home describe --json` and `skill-manager home drift`.

TWO OTHER VARIABLES ARE NAMED: `SPEC_DOUBLE_COMPILER_HOME` (outranks
`SKILL_MANAGER_HOME`; "Set but wrong **refuses**") and `SKILL_MANAGER_BIN_DIR`
(installer fallback only).

NOT ANSWERABLE FROM THE CORPUS: the exact syntax of `skill-manager exec`, and
whether any variable beyond PATH + SKILL_MANAGER_HOME is required.

SOURCE: skills/spec-double-compiler/references/runtime_requirements.md;
skills/deploy-helm/references/computeq-cli.md.
CONFIDENCE: high
HOW I FOUND IT: the second half of the "Which Skill Manager home those tools come
from" section of runtime_requirements.md.
```

---

**F-Q2.** Is the `skill-manager` on my `PATH` the right binary?

```
ANSWER: The corpus does NOT address the `skill-manager` binary itself. It gives a
strongly-stated rule about exactly this hazard for FOUR OTHER tools, and the
extension to `skill-manager` is my inference.

WHAT IS STATED, about other binaries: "`tlc2`, `pytest`, `jinja2` and
`tla-spec-dev` are not 'the ones on PATH'"; "**Never invoke `tla-spec-dev` from
PATH**" (six documents) — "it execs the installed clone at `da0a7ff`, containing
none of this epic's work"; "**A gate can outlive its removal in an installed
copy**".

HOW TO TELL — stated: `skill-manager home describe --json` reports "the resolved
CLI"; `home drift` "refuses a launch while a unit moved unread". The corpus also
demonstrates a cruder observational test: BYTE-COMPARE THE TWO COPIES (47,433 vs
27,926 bytes, disagreeing on the same input).

WHAT TO DO IF IT IS NOT: launch through `bin/launch/*` or `skill-manager exec`;
or invoke the copy you want by ABSOLUTE PATH (the corpus does this itself twice);
or, for tla-spec-dev, "prefer this repository's own `scripts/`".

INFERRED, not stated: that `skill-manager` on PATH is subject to the same hazard.
There is a real reason to doubt the inference: `home describe`, `home sync`,
`home close-out` and `unit publish` are all written as bare `skill-manager …`
invocations with explicit `--home`/`--from`/`--to` arguments, which reads as a
tool that TAKES the home as an argument rather than one that IS pinned to a home.
The corpus's own usage weakly suggests the PATH `skill-manager` is fine when you
pass explicit paths — but it never says that.

NOT ANSWERABLE FROM THE CORPUS: whether the `skill-manager` executable is itself
installed per-home under `<home>/bin/`.

SOURCE: references/runtime_requirements.md; EPIC-HANDOFF.md;
PORTS-AS-ADAPTERS-STARTER-PROMPT.md; CUT-THE-APPARATUS-EPIC.md.
CONFIDENCE: high for the stated rule about the four named tools; LOW for the
extension to `skill-manager` itself, which is unstated and which the corpus's own
usage arguably cuts against.
HOW I FOUND IT: grepped `on PATH|from PATH|\$PATH|bin/cli`, which returned every
"never invoke from PATH" line and NO line naming the `skill-manager` binary as
PATH-sensitive.
```

---

```
COMPLIANCE: Clean. I read only files under the corpus directory. I ran no
skill-manager, skt, skill-dev, tla-spec-dev, git or gh command; no
WebSearch/WebFetch; no MCP tool. I created, modified, moved and deleted nothing.
I did not read the repository I am cwd'd in, CLAUDE.md, ~/.claude,
~/.skill-manager, or git history.

One disclosure that is not a violation but is relevant to the measurement: my
system prompt independently contains a listing of ~40 available skills with full
descriptions, including descriptions of `skt`, `skill-manager`, `skill-dev-skill`
and `git-issue-workflow` that describe home tiers, `skt ticket new/close`, and
`skt publish`. That is prior context I did not choose to receive and could not
unsee. I have deliberately NOT sourced any answer from it, and where the corpus
was silent — notably F-Q2 and the `skt status` tier claim in D-Q1 — I marked the
gap rather than filling it from that listing, even though the listing would have
answered both.

CORPUS NOTES: 80 files. I enumerated all 80, grepped all 80 several times, read 1
in full (runtime_requirements.md, 119 lines — the single load-bearing file for
five of the six questions) and read substantial sections of 12 others.

THE CORPUS FEELS SUBSTANTIALLY TRUNCATED FOR THESE QUESTIONS. It contains exactly
four skills and NO `plugins/` directory at all; `find` returned only `./skills` at
depth 1. No `skt` plugin documentation, no `skill-manager` unit documentation, no
`git-issue-workflow` and no `git-epic-workflow` — i.e. none of the units that
actually OWN the home-tier model, the `wt`/`skt ticket` lifecycle, and the
`skill-manager` CLI surface. Confirming this: the spec-double-compiler SKILL.md
frontmatter declares `skill-imports` pointing at `unit: skill-manager, path:
references/cli.md` and `unit: skt, path: references/skills.md` — two files the
skill itself says are the authority, and **neither is present in the corpus**.

The consequence is that every answer above is reconstructed from ONE consumer
skill's incidental documentation of a system it does not own. That single file
happens to be unusually good, but it is describing the tier system in order to
explain where `tlc2` comes from, not to specify it. Anything the tier system does
that spec-double-compiler does not happen to care about is simply absent, which
is why F-Q2 and parts of D-Q1 and D-Q3 have holes.

One more gap: the corpus never states the naming convention for worktree
directories as a convention. A corpus that included git-issue-workflow's `wt`
documentation would presumably settle D-Q1 outright.
```
