# Wave 7 review — epic/home-integrity-sync

**Range:** `7437c72..a527221` · **Merged:** HIS-16 (#241 → #237)
**Schedule revision at close:** 6 → **7** · **40 files, +6,939 / −87**, 10 production files, 8 test/graph files

> Written late, at the epic PR — see wave 5's note.

---

## What the wave was for

**A command that resolves its target from the working directory escapes every
sandbox this repo has.** Not hypothetically: the `project` family, driven
in-process, re-realized a worktree home against this repository's manifest,
removing one unit and installing two (DEF-047). A JVM cannot change its own
working directory, so "run it somewhere else" is not available as a remedy.

Delivered: `ProjectRoot` (the target is declared), `Confinement` +
`ConfinementEscapeException` (an escape is refused), `SandboxCommand`, and
`HomeMembershipLaw` in the graph — a home's **unit membership** is what the graph
intended, wired into 24 graphs.

## Goal movement

| goal | measured | verdict |
| --- | --- | --- |
| `GOAL-no-destructive-recovery` | a unit vanishing from a home nobody named is bytes destroyed by an operation that reported success — now detected | on track |
| `GOAL-one-home-one-answer` | `SKILL_MANAGER_HOME` and the working directory stop being two answers to "which project is this command about" | on track |

## The review found four blockers, and the first unmade the deliverable

This is the wave's headline and the ticket agent wrote it at the top of its own
contribution rather than burying it:

> *"Review of #241 returned four blockers, and the first unmade the headline
> deliverable. Everything below §1 has been re-measured against the fixes."*

**Adversarial review is the instrument that caught this**, not the graph, not the
unit suite, and not the ticket agent's own vacuity checks.

## Integrated validation

| lane | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `uv run pytest specs/` | passed |
| `home-integrity`, `home-sync`, `project-child-home`, `ticket-lifecycle` | green |
| `HomeMembershipLaw` | added to 24 graphs |

## A decision the epic agent got wrong

`AgentHomes.binding` was marked **over-declared** in HIS-16's conflict keys — and
it then became the arming point for the ticket. The original declaration was
right. Conflict keys were declared wrong in both directions in nearly every wave
of this epic; correcting them once made the plan validator refuse a shared wave
(HIS-16 and HIS-18 both touching `RunTests.java`), which is the validator working.

## Findings recorded

DEF-048 through DEF-055. **DEF-054** matters most for what follows: the membership
law's one detection mode that reaches a real home **depends on a separate bug** —
the removal path leaving an orphaned `.projections.json`. Fix that bug and the
detection disappears. A detector resting on an accident, handed to HIS-13.
