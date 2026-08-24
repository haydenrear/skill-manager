# GOAL-progressive-disclosure — evidence root

> An agent dropped at any tier — root home, project home, worktree home — finds
> what it needs by following what is in front of it, in BOTH directions … And
> from any tier it can find out how a change to any skill reaches its GitHub
> repository.

**Decided by HIS-6**, from the committed before/after transcripts. HIS-6 reports
them; it does not re-run them selectively.

Everything is under HIS-20's evidence root and is **not copied here**, because a
second copy is covered by nothing and drifts — this epic's founding defect, and
`GOAL-mechanism-documented` clause 1:

- **Method, per-question scoring, the change-management table, and the honest
  limits of the instrument** —
  [`../../tickets/HIS-20/judged-read/README.md`](../../tickets/HIS-20/judged-read/README.md)
- **The six BEFORE transcripts**, committed before any documentation change —
  [`../../tickets/HIS-20/judged-read/before/`](../../tickets/HIS-20/judged-read/before/)
- **The six AFTER transcripts**, fresh agents —
  [`../../tickets/HIS-20/judged-read/after/`](../../tickets/HIS-20/judged-read/after/)
- **What the ticket concluded, and what it says is weaker than it looks** —
  [`../../tickets/HIS-20/goal-contribution.md`](../../tickets/HIS-20/goal-contribution.md)

## The one sentence HIS-6 should not miss

The largest gap this goal names is **not a documentation gap**. Two of the three
home tiers had no unit installed that documents the tier model, and this
repository's `skill-project.toml` already declared two of the missing units. The
remedy is one `skill-manager project resolve`, it is a write to the operator's
project home, and it is escalated as **DEF-096** rather than taken by a ticket
agent.

## Which numbers are real on which machine

| tier | AFTER corpus | exists on disk? |
| --- | --- | --- |
| root | real root home **+ this ticket's units as published in two open leaf PRs** | no — a sandbox until `skill-manager-skill#5` and `skill-publisher-skill#35` merge and root syncs |
| project | the shape `project resolve` of its own manifest produces, + the same overlay | **no — explicitly counterfactual** |
| worktree | the real HIS-20 worktree home after `project resolve`, + the same overlay | **yes** |
