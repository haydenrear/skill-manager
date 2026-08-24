# BEFORE — UPWARD, PROJECT tier

- **Direction:** upward (worktree → project → root; how work is handed back)
- **Tier:** project. Premise: the agent edited a file under
  `<home>/skills/test-graph/`, and the home is gitignored.
- **Corpus:** 79–80 md files, four skills, no `plugins/`.
- **Date:** 2026-08-24, before any documentation change.

---

**U-Q1.** How does my edit reach the tier above this one?

```
ANSWER: The corpus documents the command as `skill-manager home sync --from <lower>/.skill-manager --to <higher>/.skill-manager --merge`. It is written out only for the worktree→project pair. My session's home is the PROJECT tier, and the corpus's tier diagram puts `root ~/.skill-manager` directly above it, so the instance for my edit would be `--from /Users/hayde/IdeaProjects/skill-manager/.skill-manager --to ~/.skill-manager --merge`. THAT SUBSTITUTION IS INFERRED — the corpus never shows a project→root sync. The tier/directory the command must be RUN FROM is NOT STATED anywhere; both paths are passed as explicit `--from`/`--to` arguments. (The only "run it before X" instruction in the corpus is for close-out: "Run it before `git worktree remove`, never after.")
SOURCE: skills/spec-double-compiler/references/runtime_requirements.md — "# up a tier, so closing the worktree does not take it with it (local to this machine)" / the tier diagram in the same file.
CONFIDENCE: medium (command name and flag shape: high; project→root instance and run-location: low, inferred/absent)
HOW I FOUND IT: Grepped the corpus for "home sync|unit publish|tier|gitignor" and opened runtime_requirements.md, the only file that documents the tier model.
```

---

**U-Q2.** What does git carry out, and what crosses the boundary?

```
ANSWER: STATED: a Skill Manager home is gitignored, so an edit made inside it is "invisible to `git status`, invisible to the repository's diff, and deleted with the worktree." Git therefore carries the repository's tracked files out and carries NOTHING of the home. Two commands move a home edit: `home sync … --merge` moves it up a tier and is explicitly "local to this machine" — it does NOT cross to another project; `unit publish <unit> --ticket <ticket>` sends it "to this skill's own git repository, which is the only path that reaches another project." So the boundary is crossed by the UNIT'S OWN git repo, not by this repository's git. INFERRED (from a separate file, about a different unit): once in the unit's own repo, consumers pick it up via `skill-manager sync`.
SOURCE: runtime_requirements.md — "A home is gitignored. …"; skills/test-graph/SKILL.md — "Real changes belong in `project_sdk_sources/` in this skill repo and reach consumers through `skill-manager sync`."
CONFIDENCE: high for the stated parts; medium for the consumer-pickup leg
HOW I FOUND IT: Same file as U-Q1, section "An edit you make to this skill inside a home is not in any diff".
```

---

**U-Q3.** The close-out gate.

```
ANSWER: PARTIAL. The corpus names one close-out gate: `skill-manager home close-out --home <worktree>/.skill-manager --into <repo>/.skill-manager`. What it decides: whether any edit made inside the worktree's home still exists ONLY in that worktree. What it refuses: it "refuses while such an edit is still only in the worktree, and names the command to run per unit." Why: the home is gitignored and "deleted with the worktree". Ordering is stated: "Run it before `git worktree remove`, never after." NOT STATED: any richer verdict vocabulary, per-unit statuses, exit codes, or what the gate does beyond refusing. The corpus also never describes a close-out gate for the project→root boundary — only worktree→project.
SOURCE: runtime_requirements.md lines 116-118.
CONFIDENCE: high for what is quoted; the corpus is simply silent beyond it
HOW I FOUND IT: Grepped for "close-out|closeout|refuses while|worktree remove"; all other "close-out" hits are the TLA+ spec-ticket close-out, a different mechanism.
```

---

**U-Q4.** `held-back` and `conflicted` units.

```
ANSWER: NOT ANSWERABLE FROM THE CORPUS
SOURCE: NONE — a case-insensitive grep for "held.back", "held_back", "held-back" and "conflicted" across all 79 corpus files returns no occurrence of either as a close-out status. ("held"/"conflict" hits are unrelated: judge scores holding still, TLA+ revision-conflict semantics, OpenTofu value-merge conflicts, epic wave conflict keys.) The corpus tells me only that close-out "refuses while such an edit is still only in the worktree, and names the command to run per unit" — it never enumerates per-unit verdicts.
CONFIDENCE: high (that it is absent)
HOW I FOUND IT: Grepped the whole corpus for held/conflict variants, then re-read the only close-out-gate paragraph to confirm no status vocabulary is defined there.
```

---

**C-Q1.** Publish command + tier, per unit.

```
ANSWER, unit by unit (four units, all under skills/; there is no plugins/ directory at all):

1. spec-double-compiler — ANSWERED (command), NOT ANSWERED (tier).
   `skill-manager unit publish spec-double-compiler --ticket <ticket>`, "the only path that reaches another project."
   Tier: NOT STATED. The framing suggests the worktree tier, but the corpus never says the command is tier-sensitive and never states a required cwd. Treat the worktree reading as INFERRED.
2. test-graph — NOT DETERMINABLE. No `unit publish` invocation appears for it. The closest statement is directional only. (This is the unit I edited, and the corpus does not answer it.)
3. deploy-helm — NOT DETERMINABLE. No publish/sync-up path for an edit to the skill itself.
4. tracing-observability — NOT DETERMINABLE for the unit itself. The corpus gives only the PYTHON PACKAGE distribution path (a `uv add … git+https://github.com/haydenrear/tracing_skill.git@<40-char SHA>` line), which is how a consumer depends on the wheel, not how a home edit is published back.

Also stated but cutting the other way: several epic documents forbid the publishing family outright for their agents — "Skills are READ, never edited. Propose any `SKILL_MANAGER_HOME` change as a diff and escalate. Never run `skill-manager sync`."
SOURCE: runtime_requirements.md; skills/test-graph/SKILL.md; skills/tracing-observability/references/python/distribution.md; CUT-THE-APPARATUS-EPIC.md.
CONFIDENCE: high (the four-unit list and the spec-double-compiler command are directly quoted; the three "not determinable" verdicts rest on exhaustive greps)
HOW I FOUND IT: `find` for the full corpus file list to enumerate units, then grepped every file for "unit publish|publish|github.com|skill repo".
```

---

**C-Q2.** `home sync` vs `skt sync` vs `unit publish` / `skt publish`.

```
ANSWER: PARTIAL — two of the four are documented, two are not.
- `home sync --from … --to … --merge` — moves the edit UP A TIER, "(local to this machine)".
- `unit publish <unit> --ticket <ticket>` — "the only path that reaches another project". This is the one that reaches GitHub.
- `skt sync` — NOT ANSWERABLE FROM THE CORPUS. The string `skt` appears six times total and only as `skt status`, `skt report`, `skt ticket new`, and a `unit: skt` skill-import.
- `skt publish` — NOT ANSWERABLE FROM THE CORPUS, same reason.
One adjacent distinction worth flagging: `skill-manager sync` (no `home`) is a THIRD, separately-named command, used for consumers pulling a unit's real changes — several epic documents ban it outright mid-epic because "syncing mid-epic moves text under tickets already running."
SOURCE: runtime_requirements.md; CUT-THE-APPARATUS-EPIC.md.
CONFIDENCE: high
HOW I FOUND IT: The runtime_requirements.md block, plus `grep -rnow "skt"` over the whole corpus to confirm only four `skt` subcommand mentions exist, none of them sync or publish.
```

---

**C-Q3.** What `skt check` compares.

```
ANSWER: NOT ANSWERABLE FROM THE CORPUS
SOURCE: NONE — `skt check` does not appear; the only `skt` subcommands present are `status`, `report` and `ticket new`. I am deliberately NOT extrapolating from two adjacent, differently-named things I did find, but I will name them so you can see what I declined to guess from: `skill-manager home drift`, annotated "refuses a launch while a unit moved unread", and the epic-level observation "11 change-managed units are stale against their sources at the epic base". Both concern a unit-in-home vs unit-source comparison, but neither is stated to be what `skt check` does.
CONFIDENCE: high (that the corpus does not answer it)
HOW I FOUND IT: `grep -rniE "skt check"` returned nothing; confirmed with an exhaustive `grep -rniE "skt [a-z]+" -o` enumerating every `skt` subcommand mention.
```

---

```
COMPLIANCE: I read nothing outside the corpus directory. I ran no skill-manager,
skt, skill-dev, tla-spec-dev, git, or gh command; no WebSearch or WebFetch; no
program source code was opened (the corpus contains only markdown). I created,
modified, moved and deleted nothing anywhere. I also did not consult background
knowledge of this codebase — where I recognized a concept but could not locate it
in the corpus (skt check, held-back/conflicted), I marked it not answerable rather
than filling it in.

CORPUS NOTES: 79 files total. I read one in full (runtime_requirements.md, 119
lines — it carries essentially every answer available), read substantial sections
of 8 more, and grep-swept all 79.

The corpus feels SEVERELY TRUNCATED with respect to these questions, in a
specific and consistent way. Four skill units, and NO `plugins/` directory
whatsoever. The units that actually OWN the lifecycle being asked about are all
missing: no `skills/skill-manager/`, no `plugins/skt/`, no
`skills/git-issue-workflow/`, no `skills/skill-dev-skill/`. That is why U-Q4,
C-Q3 and most of C-Q1 come back empty: every answer that exists here exists
incidentally, because spec-double-compiler happens to document the home tiers as
a prerequisite for finding ITS OWN runtime.

Two further gaps worth naming. First, everything documented about tiers is
worktree→project; the project→root direction that my own situation actually needs
is never shown, only implied by the three-tier diagram. Second, the unit I edited
— test-graph — is the one unit for which the corpus gives no publish path at all.

One tension I noticed and did not resolve: runtime_requirements.md treats
improving a skill in-home as normal and tells you how to preserve it, while four
separate epic documents state flat prohibitions ("Skills are READ, never edited",
"Never run `skill-manager sync`"). Those epic rules read as scoped to measurement
runs rather than general policy, but the corpus never reconciles them explicitly.
```
