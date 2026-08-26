# The root-tier judged read — HIS-6

**One reader, one tier, five change-management questions.** Corpus: `*.md` under
`/Users/hayde/.skill-manager/skills/**` and `/Users/hayde/.skill-manager/plugins/**`,
nothing else. No source, no commands, no web, no background knowledge. Every answer
carried `ANSWER` / `SOURCE` (path + a directly quoted sentence, or the literal
`INFERRED, not stated`, or `NONE`) / `CONFIDENCE` / `HOW I FOUND IT`.

**Why only the root tier, and why only one read.** HIS-20 ran twelve. What changed since
is that the seven leaf PRs merged and the root home synced, so **the root corpus exists
on disk for the first time** — HIS-20's AFTER-root was an overlay that existed nowhere.
The other two tiers did **not** change: DEF-096's remedy has still not been run, so they
are still HIS-20's BEFORE corpus and re-reading them would re-measure a known number.
**n = 1. This is a judged read, not a statistic, and it must not be reported as one.**

## Results

| question | verdict | contamination |
| --- | --- | --- |
| **C-Q1** publish command + tier + repository, per unit | command **PASS**; tier **PARTIAL**; repository **PARTIAL** (16 named, 7 `NOT ANSWERABLE`, the mechanical lookup points at a non-Markdown file) | **CONTAMINATED** — the command was in the system prompt verbatim |
| **C-Q2** `home sync` vs `skt sync` vs `unit publish` vs `skt publish` | **PASS** — and it caught the trap: `skt sync` is a *pull* from a remote, a third direction the question did not offer | **partially contaminated** — the coarse split was in the prompt; the `--merge` / held-back semantics and the "sibling project" argument were not |
| **C-Q3** what `skt check` compares | **PASS** | **CLEAN** |
| **C-Q4** brief forbids the project home — can I still publish | **PASS** | stem contaminated; the substantive half **clean** |
| **C-Q5** derived artifacts: what, inherit vs declare, when to rebuild | **PASS**, all three parts | **CLEAN** |

## The three findings, which are worth more than the five verdicts

**1. The reader would have answered C-Q1(a) correctly with no corpus at all.** The
`skill-dev-skill` entry in every subagent's system prompt reads, verbatim:
*"`skt publish` (home sync one tier up, then unit publish)"*. That is the publish
command and its decomposition, delivered before a file was opened. The ~40-skill listing
also enumerates essentially this home's inventory, and `CLAUDE.md` supplies the GitHub
owner. **Three of five questions had a head start the documentation did not earn.**

This is HIS-20's channel 1 and channel 2, unfixed and unfixable from inside this
harness. It is stated here rather than discounted, because the previous ticket certified
four cells clean and two of them were not.

**2. It refused the one shortcut that would have made its weakest column look strong.**
The operator's auto-memory advertises *"[Skill unit-to-repo map](skill-repo-map.md) —
unit names differ from repo names"* — a file that plausibly holds the entire answer to
C-Q1's repository column. The reader did not open it, said it did not, and said it
could not claim to have approached the question ignorant of its thesis. **That is the
contamination problem behaving visibly for once**, and it is the reason this read is
usable as evidence about the corpus while being useless as evidence about a fresh agent.

**3. The `skt check` sentence is in the wrong unit.** The one sentence that answers
C-Q3 — *"`skt check` compares each change-managed unit's installed hash against its
source's tip and names what is stale and the command that pulls it"* — is in
`git-epic-workflow/SKILL.md`. The reader looked in the skt skill first, where the
command lives, and did not find it there. The fact is documented; it is documented
somewhere an agent asking about `skt` would not look.

## What the corpus does well, in the reader's own framing

Its most valuable act was a **refusal**: it states flatly that no command prints a
unit's repository, that `units.lock.toml` is not a reliable second source, and that
`installed/<unit>.json` is the answer — so C-Q1(c) came back as *"seven-tenths a
documented refusal and three-tenths scavenged citations, rather than the confident table
it would have been"*. A corpus that tells a reader where the answer is **not** is doing
the job `GOAL-mechanism-documented` was written about: the last two agents to need the
artifact contract read the source and got it wrong.

## The gap this read opens

The corpus documents the two-leg split of `skt publish` **only at the worktree tier**.
It never says what the `home sync` leg does at the **root** tier, where there is nothing
above. The reader flagged this unprompted and declined to infer. It is a real hole in
clause 3's *tier* column, at the one tier that is now measurable — and it is exactly the
shape of the four undocumented facts HIS-20 listed: something an agent standing at a
tier needs, and that the tier does not disclose.
