# Wave 5 review — epic/home-integrity-sync

**Range:** `fb9205b..c44a2a0` · **Merged:** HIS-11 (#233 → #186), HIS-14 (#234 → #232)
**Schedule revision at close:** 5 · **40 files, +34,667 / −98**, 10 production files

> **Written late, at the epic PR.** Waves 5–8 were merged and walked through
> conversationally at the time; the committed artifact rule 13 requires was not
> written until the epic PR was prepared. That is recorded here rather than
> backdated — a review written after the fact is worth less than one written at
> the gate, and the difference should be visible to whoever reads this next.

---

## What the wave was for

Two tickets, both about **destroying something the operator did not ask to lose.**

| ticket | what it fixed |
| --- | --- |
| HIS-11 | `Executor.java:307` paired `CommitUnitsToStore` with an EMPTY pre-state compensation, so a failed install deleted the destination it had overwritten and restored nothing. Zero pre-existing bytes survived, **by construction.** |
| HIS-14 | `--home` bound ONE of a home's two axes, so a remedy carrying it sent the units to the named home and the **agent projections to the operator's real `~/.claude`**. This is the mechanism behind both live incidents that damaged this machine. |

## Goal movement

| goal | measured | verdict |
| --- | --- | --- |
| `GOAL-no-destructive-recovery` clause 1 | 0 bytes survive → **120/120 and 376/376 bytes restored**, proved by a fixture that fails when the move-aside is removed | on track |
| `GOAL-one-home-one-answer` | two spellings of "which home is this command about" → one; `--home` refuses rather than binding half | on track |

## Integrated validation

| lane | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `uv run pytest specs/` | passed |
| `home-integrity`, `home-sync`, `ticket-lifecycle`, `project-child-home` | green |
| 13-graph epic-relevant sweep | **9 green, 4 red** — `home-clone`, `checkout-home`, `artifact-dag`, `onboard` |

## The mistake this wave made, and what it cost

**HIS-11 was merged on branch-measured greens**, and `ticket.lifecycle.concurrent.close.out`
then went red on the integrated tip. The epic agent filed a root cause — non-zero
exit, empty stdout — and **it was wrong.** The ticket agent measured it: the
command exited **0**, and `HomeLock.announceWait` wrote a human line to **stdout**,
corrupting the `--json` stream. Both framings are kept side by side in DEF-043 and
in HIS-15's contribution rather than the wrong one being deleted.

That correction became wave 6.

## What the sweep found that no ticket did

This was the wave where the **13-graph epic-relevant sweep** ran for the first
time — and `home-clone` had **never been run in this epic**, despite `HomeCloner`
being the single most-changed production file. Four graphs were red. Three of the
four were fixed by HIS-17, three waves later.

**The lesson, for the scorecard:** a per-ticket graph list chosen by the ticket
agent cannot see a graph whose fixtures the ticket did not know it touched.

## Findings recorded

DEF-036 (a second statement of "what home X means"), DEF-037 (home-mutating verbs
that do not declare `--home`), DEF-038 (the binding is thread-local; children
inherit the real environment), DEF-041 (the lock's patience is fixed at 120s),
DEF-043 (blocking — the concurrent close-out red), DEF-046/DEF-047 (the `project`
family resolving from the working directory, and the home re-realization it caused).

**DEF-047 was found by the home close-out gate**, not by any test — the gate asks
a question three other instruments do not.
