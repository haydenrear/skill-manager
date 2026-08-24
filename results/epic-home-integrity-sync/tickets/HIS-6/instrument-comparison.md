# Which instrument caught what — round 3, and across the epic

**This is the comparison the owner asked for**, and it is the part of HIS-6 that
outlives the goal verdicts. The ledger at `../../evaluation/regression-ledger.yaml`
carries the per-finding provenance; this page is what it adds up to.

## Round 3's yield, by instrument

| | A — graph sweep | B — issue agents | C — manual |
| --- | --- | --- | --- |
| what it is | `sweep.py --scope full`, 26 graphs, one at a time | four agents, one GitHub issue each, each validating its OWN worktree | the epic tip driven by hand across three tiers and four promotion paths |
| new defects found | **0** | **4** (DEF-115, DEF-118, DEF-119, DEF-121) | **3** (DEF-116, DEF-117, and DEF-115 confirmed independently in a sharper form) |
| things it SETTLED that nothing else could | DEF-107 fixed on the *integrated* tip; DEF-043 resolved; **DEF-108 triaged** | — | — |
| what it was blind to | every one of the seven | conditions needing a second session or a real upgrade | anything the operator does not think to try |

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
| B — issue agents | 5 (REG-009, 011, 012, 013, plus DEF-114 from a reviewer) | 0 | conditions needing a second session or a real upgrade |
| C — manual | 8 (REG-001, 002, 004, 005, 006, 010, DEF-117, DEF-096/101 carried) | **4 of 6 in round 1**, 0 in round 3 | anything the operator does not think to try |

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
3. **B is also the instrument that broke containment.** One of the four wrote into the
   operator's project home (DEF-121). The finding is real and valuable — it is how
   `skt publish`'s ordering bug was found — and it was found by an agent doing the
   thing it was told not to do, with a correctly pinned environment. An instrument that
   can damage the subject it measures needs a guard, and "tell the agent not to" is
   demonstrably not one.

## What every instrument missed, and still misses

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
