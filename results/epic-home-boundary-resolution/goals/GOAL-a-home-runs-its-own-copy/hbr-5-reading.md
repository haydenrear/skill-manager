# GOAL-a-home-runs-its-own-copy — HBR-5 interim reading, 2026-09-02

| | baseline 2026-08-29 | now | target |
|---|---|---|---|
| unsanctioned pairs | 19 | **46** | 0 |
| homes scanned | 27 | 37 | — |
| pairs per home | 0.70 | **1.24** | — |
| sanctioned fallbacks | 0 | 0 | — |

## The number went up, and the epic did not cause it

Two units account for all 46 — `deploy-helm` (44) and `spec-double-compiler`
(2) — across **16 homes, exactly one of which belongs to skill-manager**. The
other 15 are unrelated projects (`commit-diff-context-parent`,
`hyper-experiments-*`, `support-agent-rears`, and a dozen `wt-*cdc*`
worktrees).

The mechanism is propagation, not regression: the crossing is a **symlink**
into the root home, and `home clone` copies it. Every new worktree home in any
project inherits three more pairs. So the metric grows with a population
skill-manager neither controls nor creates, and it will keep growing while
those projects make worktrees.

**This is a defect in how the goal is measured, and it should be decided at
terminal evaluation rather than papered over.** "Pairs on this machine" makes
the epic accountable for homes it never touched. Candidate re-readings, for the
owner: pairs per home; pairs in homes this repository owns; or pairs in homes
created after the fix landed. Each answers a different question and the first
is the only one that is stable under other people's work.

## What changed here, and what did not

Fixed and validated (#289): `home repair` now reports the condition. It
previously called every one of these 16 homes clean while the walker counted
46 pairs — two readers, one truth, two answers, inside the verifier. They now
return the same count home by home (1 and 1 on the project home; 3 and 3 on
`wt-165-cdc-isf-013`), over the same entry count, so it is the verdict that
moved and not the scope.

**The metric is unchanged at 46.** Detection is not remediation. Reaching 0
needs each affected home's entry point rebuilt, and 15 of the 16 belong to
other projects — so the remediation is a decision about scope, not a commit.
