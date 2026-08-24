# BEFORE — UPWARD, WORKTREE tier

- **Direction:** upward (worktree → project → root; how work is handed back)
- **Tier:** worktree. Premise given to the agent: it edited a file under
  `<home>/skills/test-graph/`, and the home is gitignored.
- **Corpus:** 80 md files, four skills, no `plugins/`.
- **Date:** 2026-08-24, before any documentation change.

---

## Corpus inventory (the agent established this first)

> The corpus root contains **only** `skills/`, with four units: `deploy-helm`,
> `spec-double-compiler`, `test-graph`, `tracing-observability`. There is **no
> `plugins/` directory** and no `skt`, `skill-manager`, `git-issue-workflow`, or
> `skill-dev` documentation. 80 files total. Essentially all lifecycle/tier
> material lives in one file:
> `skills/spec-double-compiler/references/runtime_requirements.md`.

---

**U-Q1.** How does my edit reach the tier above this one?

```
ANSWER: The command is:
  skill-manager home sync --from <worktree>/.skill-manager --to <repo>/.skill-manager --merge

The tier above the worktree home is the PROJECT home (the tier ladder documented
is root -> project -> worktree, each a real copy of the one above it).

PARTLY NOT ANSWERABLE: the corpus does NOT state a tier or directory the command
must be RUN FROM. Both endpoints are passed as explicit --from/--to path
arguments, so no working directory is documented as required.
SOURCE: skills/spec-double-compiler/references/runtime_requirements.md — "# up a tier, so closing the worktree does not take it with it (local to this machine)" / "skill-manager home sync --from <worktree>/.skill-manager --to <repo>/.skill-manager --merge"; lines 32-36 for the tier ladder.
CONFIDENCE: high for the command and the destination tier; the "run from" part is not found.
HOW I FOUND IT: Grepped for "home sync", "tier", "SKILL_MANAGER_HOME"; opened runtime_requirements.md first and read it in full.
```

Caveat the agent flagged: the documented example is written about improving
`spec-double-compiler`. `home sync` syncs the *home*, not a named unit, so it
covers a `skills/test-graph/` edit too — but that generalization is **INFERRED,
not stated**.

---

**U-Q2.** What does git carry out, and what does it not?

```
ANSWER:
Git does NOT carry the home. An edit inside <worktree>/.skill-manager/skills/…
is "invisible to `git status`, invisible to the repository's diff, and deleted
with the worktree." All three home tiers below root are gitignored. So git
carries out only the repository's own tracked files — the ticket's real code
changes — and nothing under .skill-manager.

Two mechanisms cross that boundary:
  1. `home sync … --merge` — moves the edit UP A TIER, "local to this machine";
     not a git operation on this repository.
  2. `skill-manager unit publish <unit> --ticket <ticket>` — sends the edit to
     THAT SKILL'S OWN git repository. "the only path that reaches another project."

So the git that carries a skill edit out is the *unit's own* repo, reached by
`unit publish` — not this worktree's repo.
SOURCE: runtime_requirements.md — "A home is gitignored. If you improve `spec-double-compiler` while using it … the edit is invisible to `git status`, invisible to the repository's diff, and deleted with the worktree."
CONFIDENCE: high
HOW I FOUND IT: Grepped for "gitignore" and "worktree"; the section "### An edit you make to this skill inside a home is not in any diff" answers it directly.
```

---

**U-Q3.** The close-out gate.

```
ANSWER:
The gate is `skill-manager home close-out --home <worktree>/.skill-manager --into <repo>/.skill-manager`.
What it decides: whether tearing the worktree down is safe.
What it refuses: it "refuses while such an edit is still only in the worktree".
Why: the home is gitignored and "deleted with the worktree".
On refusal it "names the command to run per unit".
Ordering: "Run it before `git worktree remove`, never after."

NOT STATED in the corpus: that this gate is invoked by `wt close` / `skt ticket
close`, or any exit codes / report format it emits.
SOURCE: runtime_requirements.md — the quoted sentence above.
CONFIDENCE: high for what is stated; the linkage to a teardown command is not found.
HOW I FOUND IT: Grepped for "close-out"/"closeout"/"home close"; the only hit describing a gate is in runtime_requirements.md.
```

---

**U-Q4.** A unit marked `held-back` or `conflicted`.

```
ANSWER: NOT ANSWERABLE FROM THE CORPUS
SOURCE: NONE. I grepped the entire corpus case-insensitively for "held-back",
"held_back", "held back", "heldback", "conflicted", "in conflict", and
"unpublished". The only hits were unrelated. No per-unit close-out status
vocabulary exists in this corpus at all.
CONFIDENCE: high (that it is absent)
HOW I FOUND IT: Case-insensitive recursive grep across all 80 files for every spelling variant of both terms.
```

---

**C-Q1.** Per-unit publish command and tier.

```
ANSWER, per unit (the four documented are deploy-helm, spec-double-compiler,
test-graph, tracing-observability):

- spec-double-compiler: `skill-manager unit publish spec-double-compiler --ticket <ticket>`.
  Destination described as "this skill's own git repository", NOT explicitly as
  GitHub. Tier to run it from: NOT STATED.
- test-graph: NOT ANSWERABLE FROM THE CORPUS. No publish command is documented.
  The nearest statement — "Real changes belong in `project_sdk_sources/` in this
  skill repo and reach consumers through `skill-manager sync`" — is the opposite
  direction. (INFERRED, not stated: the generic form would follow the one
  documented example; I flag this as inference and would not rely on it.)
- deploy-helm: NOT ANSWERABLE FROM THE CORPUS. No publish command, no repo
  coordinate, no tier documented anywhere in its files.
- tracing-observability: NOT ANSWERABLE FROM THE CORPUS. Its git repo IS named —
  `git+https://github.com/haydenrear/tracing_skill.git` — but only as a uv/pip
  dependency source, with no publish command and no tier.

SOURCE: runtime_requirements.md; skills/test-graph/SKILL.md; skills/tracing-observability/references/python/distribution.md.
CONFIDENCE: high for spec-double-compiler's command; high that the other three are absent.
HOW I FOUND IT: Grepped for "unit publish", "publish", "own git repo", "github.com" across the whole corpus, then opened each unit's SKILL.md and its distribution/workflow references.
```

Note the agent added: the corpus says "this skill's own **git repository**". It
never says **GitHub** for `unit publish`. Treating the two as the same is
**INFERRED, not stated**.

---

**C-Q2.** `home sync` vs `skt sync` vs `unit publish` / `skt publish`.

```
ANSWER: PARTIALLY ANSWERABLE.

Documented: `home sync … --merge` moves the edit ONE TIER UP, "(local to this
machine)". `unit publish <unit> --ticket <ticket>` goes "to this skill's own git
repository, which is the only path that reaches another project".

NOT ANSWERABLE FROM THE CORPUS: `skt sync` and `skt publish`. Neither string
appears anywhere. The ONLY `skt` commands documented at all are `skt status` and
`skt ticket new`. There is no documentation of `skt` as a wrapper over
`home sync` / `unit publish`.

Also note the corpus documents `skill-manager sync` (test-graph SKILL.md), a
THIRD, different thing: how a change already in a skill repo reaches consumers.
Do not conflate it with `home sync`.
SOURCE: runtime_requirements.md; skills/spec-double-compiler/SKILL.md; CUT-THE-APPARATUS-EPIC.md.
CONFIDENCE: high for the two skill-manager commands; high that the skt equivalents are absent.
HOW I FOUND IT: Grepped `\bskt\b` across the entire corpus (5 hits total) and enumerated every `skill-manager <verb>` occurrence with a regex.
```

---

**C-Q3.** What does `skt check` compare?

```
ANSWER: NOT ANSWERABLE FROM THE CORPUS
SOURCE: NONE. `skt check` does not appear. An exhaustive grep for `\bskt\b`
returned exactly five hits in the whole corpus, none of them `check`. The nearest
documented staleness concept is `skill-manager home drift`, which "refuses a
launch while a unit moved unread" — but that is a DIFFERENT command and I will
not present it as the answer to `skt check`.
CONFIDENCE: high (that it is absent)
HOW I FOUND IT: Exhaustive `grep -rn "\bskt\b"` over all 80 corpus files.
```

---

```
COMPLIANCE: I read nothing outside the corpus directory. Every Bash invocation was
`cd`'d into the corpus root and used only read-only tools. I ran no
skill-manager, skt, skill-dev, tla-spec-dev, git, or gh command. No
WebSearch/WebFetch. I created, modified, moved and deleted nothing.

Noted for honesty: I did not read all 80 files in full. I read
runtime_requirements.md in full and covered the remainder by exhaustive targeted
greps, including case-insensitive sweeps for the absent terms. The "not found"
verdicts rest on those greps, not on partial reading.

CORPUS NOTES: 80 files, ~1.3M, across exactly four skills.

The corpus feels DELIBERATELY INCOMPLETE for these questions, and this is the most
important observation I can give you. Every question is about the skill-manager /
skt change-management surface, and THE DOCUMENTATION FOR THAT SURFACE IS NOT IN
THE CORPUS. There is no plugins/ directory at all, and no skills/skt/,
skills/skill-manager/, skills/git-issue-workflow/, or skills/skill-dev-skill/ —
even though the corpus itself references them: spec-double-compiler's SKILL.md
declares `skill-imports: - unit: skill-manager, path: references/cli.md` and
`- unit: skt, path: references/skills.md`, and neither target file is present.

The practical consequence: essentially every correct answer above traces to ONE
file's ONE section — the ~18 lines under "### An edit you make to this skill
inside a home is not in any diff". Everything the questions asked BEYOND those
four facts — held-back/conflicted dispositions, `skt sync`/`skt publish`/`skt
check`, per-unit publish for three of the four units, and the required cwd for
any command — is simply not present. Notably, the one unit I am told I edited
(test-graph) has NO publish or home-lifecycle documentation of its own; the
lifecycle guidance survives only incidentally, inside a different unit's runtime
reference.
```
