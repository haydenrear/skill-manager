# Which instrument caught what — round 3, and across the epic

**This is the comparison the owner asked for**, and it is the part of HIS-6 that
outlives the goal verdicts. The ledger at `../../evaluation/regression-ledger.yaml`
carries the per-finding provenance; this page is what it adds up to.

## Round 3's yield, by instrument

> **This table was WRONG in the first version of this page, in the one document whose
> entire value is attribution.** Corrected at the review of PR #257 (M4). What it got
> wrong, against this ticket's own `new-defects.md` "Caught by" lines: it **omitted
> DEF-120** from B; it **credited DEF-121 to B**, when B *caused* it and nobody
> *detected* it; and it **counted DEF-115 in both** B and C, so `0 + 4 + 3 = 7` read as a
> partition and was not one. The fourth column below did not exist, and it is the most
> important column on the page.

| | A — graph sweep | B — issue agents | C — manual | **nobody** |
| --- | --- | --- | --- | --- |
| what it is | `sweep.py --scope full`, 26 graphs, one at a time | four agents, one GitHub issue each, each validating its OWN worktree | the epic tip driven by hand across three tiers and four promotion paths | — |
| **new defects found** | **0** | **4** | **2** | **1** |
| which | — | DEF-115, DEF-118, DEF-119, DEF-120 | DEF-116, DEF-117 | **DEF-121** |
| things it SETTLED that nothing else could | DEF-107 fixed on the *integrated* tip; **DEF-108 triaged**; DEF-043's close | — | DEF-115 independently reproduced in a sharper two-hazards-one-home form | — |
| what it was blind to | all seven | conditions needing a second session or a real upgrade | anything the operator does not think to try | — |

**0 + 4 + 2 + 1 = 7. It is a partition now.** DEF-115 is booked to B, which found it;
C's independent reproduction is recorded as a confirming read, not as a second finding.

### And two more arrived after this table was first written

Both from the **review**, which is a fifth reader this ledger has never had a column
for, and both worth noting because of *how* they were found:

- **DEF-122** — `remove --dry-run` writes an `installed/` record into the home it is
  only previewing. Found by **probing a reviewer's finding**, not by any instrument.
- **DEF-123** — `sync --git-latest --merge` prints two contradictory remedies, the
  second instructing the operator into a mid-merge state the first says was rolled back.
  Found by **the reviewer reading a transcript this ticket had already captured,
  published, and cited as evidence for the very goal the defect violates.** Nothing was
  re-run. The evidence had been sitting in the PR.

**A found zero new defects at 25 of 26 green, and that is not a failure of A.** It is
the shape of what A is: a statement about the nodes that exist. Five of the seven
defects HIS-6 filed live in surfaces no node touches — a symlinked shim compared
across two readers, a `--json` field nothing pairs with its human line, a shell script
in another repository, an argument-ordering bug in a Python front door, and a drift
gate that is never armed.

**But A settled three things no other instrument could reach**, and one of them is the
best single argument in the epic for the sweep existing:

- **DEF-108** was red in round 1 and untriaged. Round 3 reproduced it identically at a
  tip two tickets later, deterministically, which is what proved it a **stale
  assertion** rather than a flake — a core graph asserting that the product still had
  the very condition `GOAL-symlink-merge-settles` exists to remove. Nothing but a full
  sweep finds an assertion that has gone out of date with the product; you cannot find
  it by running the graphs you happen to have touched.
- **DEF-107** was green on HIS-21's branch and still red in HIS-22's run. Only a sweep
  of the *integrated* tree could say which.
- **DEF-043** is `blocking / open` in the backlog and names `ticket-lifecycle`. It
  PASSED. Re-measured, not inferred — which the backlog's own reconciliation note asked
  for by name.

## What instrument B did that neither other instrument could

**The clearest case is DEF-119.** `bootstrap-home.sh` picks the CLI off `PATH`, so the
epic's own front door provisions worktree homes with a build that predates the epic.
Three of four agents hit it on their first command.

A missed it because no graph runs that script. **C missed it because C cannot find a
defect whose symptom is "you must already know X"** — C is the epic agent, who was
handed the pin as a brief line on day one and has never taken the unpinned path since.
B's agents were fresh: they had the brief and not the habit.

That is a general claim and it is worth stating as one: **for onboarding defects — the
whole subject of `GOAL-progressive-disclosure` — a fresh agent is not a slower version
of a node, it is the only instrument that can see the class at all.** The other three
B findings are the same species: someone asking a command a question rather than
testing a mechanism.

## What instrument C did, and the warning that has to travel with it

C's round-1 yield was six findings, and **four of the six diagnoses were wrong** — every
symptom real, every mechanism inferred and falsified by a ticket agent before it cost
anything. Two would have produced vacuous acceptance criteria; DEF-103's was built and
stayed green with the whole fix reverted.

**Round 3 applied the correction to itself.** Every C finding here carries a confirming
read:

| finding | the confirming read |
| --- | --- |
| DEF-116 (`home sync` arms no gate) | exhaustive enumeration of all 9 `DriftGate.<static>` uses in `src/main/java`, with `DriftGate.` matching 13 lines as the control — two production recorders, and `HomeSync.java` is neither |
| DEF-117 (stale sha in `list`) | `git log` + `git status` + file content in the same home, and a **clean-checkout control** rendering identically |
| DEF-115 confirmed | a two-arm control where the wrapper arm makes both readers agree |

And it caught one wrong reading before it shipped: `home drift` reporting "no unread
change" after a hand-edit is **not** a defect — the gate is armed by operations, not by
scanning disk. Reading `DriftGate`'s source turned that into DEF-116, which is a real
and different finding. **A hand-driven sweep sees what happened, not why, and the fix is
a confirming read rather than more hands.**

## Across the whole epic

| instrument | findings | of which mis-diagnosed | blind to |
| --- | --- | --- | --- |
| A — graph sweep | 3 (REG-003, REG-007/DEF-107, REG-008/DEF-108) | 0 | anything nobody wrote a node for — which is where 5 of HIS-6's 7 live |
| B — issue agents | 5 (REG-009, 011, 012, plus DEF-120, plus DEF-114 from a reviewer) | 0 | conditions needing a second session or a real upgrade |
| C — manual | 7 (REG-001, 002, 004, 005, 006, 010, DEF-117; DEF-096/101 carried) | **4 of 6 in round 1**, 0 in round 3 | anything the operator does not think to try |
| **review** | 3 (DEF-114, DEF-123, and the B1/M3/M4/M5/M6/M7 corrections to this ticket) | 0 | it reads what was written, so it cannot see what was never attempted |
| **nobody** | 1 (DEF-121) | — | — |

**A fifth reader, which this ledger never had a column for, and which HIS-6 is the
evidence for.** The review of PR #257 returned one blocker and ten majors on a ticket
whose own numbers survived verification — the sweep artifact, the two-number discipline,
the suite counts and `GOAL-home-invariants` clause 2 all held. What it found instead was
**an unrunnable remedy aimed at the operator, wrong clause arithmetic, a mis-attributed
instrument table, two grades a conjunction did not support, and an unfiled defect
sitting inside this ticket's own published evidence.** Every one of those is a defect in
the *report* rather than in the product, and no instrument in this table looks at
reports. That is the argument for the column.

**Three readings a reviewer should attack, because they are the load-bearing ones:**

1. **A's zero is informative, not embarrassing.** It says the automated corpus is now
   consistent with itself and with the product — which is exactly what round 3 was for
   — and it says nothing about the surfaces it does not cover. Reporting "the sweep was
   green" as evidence the product is sound would be the vacuity defect this epic has
   filed twenty-four instances of.
2. **B's yield per unit of effort was the highest of the three, and B is the newest
   instrument.** Four agents, one issue each, four defects, three of them in surfaces
   with no coverage at all. That is an argument for making issue-scoped agents a
   standing instrument rather than a terminal-evaluation trick.
3. **B is also the instrument that broke containment, and this is the sharpest thing
   in the comparison.** One of the four wrote into the
   operator's project home (DEF-121). The finding is real and valuable — it is how
   `skt publish`'s ordering bug was found — and it was found by an agent doing the
   thing it was told not to do, with a correctly pinned environment. An instrument that
   can damage the subject it measures needs a guard, and "tell the agent not to" is
   demonstrably not one.

## What every instrument missed, and still misses

**Start with the one that has a name.** *The fifth damage shape was found by none of A,
B or C — a human compared two line counts.* DEF-121 — a unit present in `skills/` and
`.materialization/` but absent from `installed/` — is called **clean** by `home verify`
and by `home repair`, is asserted by no node, and was noticed only because a later
`home clone` inherited it and an inventory came back one line longer than expected. The
instrument that found the epic's most serious live defect was **surprise at a number**.

**And the detector for it already exists, which is sharper than the gap.**
`HomeMembershipLaw.java:384` emits exactly this verdict — *"GAINED [x] — present in the
home, and no installed/ record names them"* — and it **ran in 24 of the 26 graphs of
this ticket's own sweep**, green, every time. The rule that would have named this damage
was written in wave 7, is exercised two dozen times per sweep, and lives entirely on the
**test** side of the fence: no product command asks it about a real home. HIS-6 first
called building it *"the half worth building"*. It is not a build; **it is a port**, and
the correction is the finding: *we wrote the detector, ran it two dozen times, and left
it where no operator can reach it.*

That is the epic's founding shape one level up. The epic is about two readers of one
home giving two answers. Here there is a **third reader that gives the right answer** and
is not wired to anything an operator can run.

- **No `home verify` / `home repair` verdict on the operator's real root home appeared
  anywhere in the first version of this ticket** — the epic nearly closed with graded
  verdicts on `GOAL-one-home-one-answer` and `GOAL-no-destructive-recovery` and no
  measurement of the tier both were written about. Raised at review (M9) and now taken:
  **5 `FOREIGN_PATH_IN_SHIM` over 3 shims, both readers agreeing, all repairable, and
  every leak pointing from the ROOT home INTO the PROJECT home.** See the scorecard.
- **Nothing measured a real upgrade.** Every home here was built by one build or
  cloned from one. `brew upgrade` across a version boundary — DEF-012's shape — is
  covered by a unit fixture and by nothing else.
- **Nothing measured a second session.** Every finding here is single-session. The
  drift gate's entire reason for existing is the boundary between sessions, and no
  instrument crosses it.
- **Nothing re-measured the backlog.** 96 of 119 findings are `open`; only 9 name a
  graph the sweep decides. 87 are unfalsifiable by any instrument this epic has.
- **Nothing checks the four remaining `GOAL-progressive-disclosure` failures**, because
  they are judged reads and there is no fresh-agent harness — see the scorecard.
