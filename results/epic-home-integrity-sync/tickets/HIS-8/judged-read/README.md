# GOAL-mechanism-documented, clause 2 — the judged read

> *"whether a fresh agent, given only the docs, answers three questions correctly:
> what a derived artifact is, what a clone inherits versus declares, and when to
> rebuild."*

## Method

Three reads, each by a fresh agent with **no repository source access**. Each was
given one directory containing *only* the markdown of the four units that
instruct agents about homes (`skt`, `skill-manager`, `git-issue-workflow`,
`git-epic-workflow`) — 51–52 `.md` files — and told:

> "You must NOT read any program source code, NOT read anything outside that
> directory, NOT run any skill-manager/skt command, and NOT use
> WebSearch/WebFetch or your background knowledge of this codebase. If you cannot
> find an answer in those documents, the correct response is `NOT ANSWERABLE FROM
> THE DOCS` — that is a valid and useful answer, not a failure on your part."

Each answer had to carry **SOURCE** (file path + quoted sentence, or the literal
string `INFERRED, not stated`) and **CONFIDENCE**. Per the ticket brief, *an
answer reachable only by reading source is a failure, not a pass with a caveat* —
so the sandbox made that mechanically impossible rather than relying on
compliance, and the citation requirement is how a lucky guess is caught.

| read | corpus | transcript |
| --- | --- | --- |
| **BEFORE** | the four units at `origin/main`, i.e. the state this ticket found | `before.md` |
| **AFTER-1** | the four units with the first draft of the contract page | `after-1.md` |
| **AFTER-2** | the four units after AFTER-1's findings were fixed | `after-2.md` |

`AFTER-1` and `AFTER-2` were separate agents; neither saw the other's answers.

## Score

| question | BEFORE | AFTER-1 | AFTER-2 |
| --- | --- | --- | --- |
| Q1 what a derived artifact is, and what names them | **FAIL** — `NOT ANSWERABLE FROM THE DOCS` | PASS (high) | PASS (high) |
| Q2 what a clone inherits vs declares; healthy or broken | **PARTIAL/FAIL** — mechanics found, verdict `INFERRED, not stated`, and the two doc passages it found gave **opposite** verdicts | PASS (high) | PASS (high) |
| Q3 when to rebuild, and the command | **FAIL** — `NOT ANSWERABLE FROM THE DOCS`; "there is no general rebuild command" | PASS (high) | PASS (high) |
| **total** | **0 of 3** | **3 of 3** | **3 of 3** |

Q2's BEFORE result is scored **fail, not partial credit**. The agent reconstructed
the mechanics correctly from `git-issue-workflow/references/skill-homes.md`, but
had to label the healthy-vs-broken verdict `INFERRED, not stated` and reported
`CONFIDENCE: low-medium` on it — and the passage it leaned on was *itself wrong*
(§ below). An inference from a stale page is the failure mode this ticket exists
to close, not a near miss.

## What the reads changed, which is the point of running three

They were not a rubber stamp. Each AFTER read was asked an adversarial question
and each one found real defects that were then fixed:

- **AFTER-1** found that `git-issue-workflow/references/skill-homes.md` carried a
  flatly contradicting account of this exact contract — "`home verify` refuses the
  home", "that is a skill-manager gap" — naming the *same* example binary
  (`bin/cli/jinja2`). Re-measured: cold shim, exit 86, `home verify` exit 0. That
  page was corrected in place and marked as a correction.
- **AFTER-2** found six more, including one that made the page contradict its own
  worked example: `rebuildable` was defined as "stale, **present on disk**, …",
  and a cold shim *is* a file on disk, so the page's own "13 stale, 0
  rebuildable" reading was unreachable from its own definition. The predicate is
  `materialized`. It also found the correction above had a **twin passage 100
  lines below it** that was left stale — the drift this ticket exists to stop,
  reproduced inside the fix for it.

Both were fixed before hand-off. No AFTER read was re-run after AFTER-2's fixes,
so AFTER-2 grades the corpus *minus* those six fixes; the three answers it got
right did not depend on them.

## Honest limits of this instrument

- **A doc-only sandbox is not a fresh agent in a real session.** A real agent has
  the repo in front of it and a task pulling it toward the source. This measures
  whether the docs *can* answer, not whether an agent *will* read them.
- **The corpus is four units, not everything an agent reads.** `CLAUDE.md`, the
  issue body and the epic assignment were excluded deliberately, because the goal
  is about the units.
- **n=1 per condition.** Three agents, one read each.
