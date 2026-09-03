# Bug attribution — epic/home-boundary-resolution

Every defect this epic found or fixed, attributed to the architectural
component that PRODUCED it — not the file the patch landed in. The question is
which part of the design keeps generating work.

Counted once each. My own instrument errors are counted too: a measurement that
reported the wrong number cost this epic more than several of the product bugs
did, and leaving it out would flatter the ledger.

## The table

| component | count | defects |
| --- | ---: | --- |
| **Error reporting / relay** | **4** | #264 (bootstrap half), #264 (skt half), giw#25, giw#27 |
| **Shim resolution — "which home's copy?"** | 3 | #262, #289, goal-1 walker |
| **Home copy semantics — what a clone carries** | 2 | #261, #281 |
| **Measurement & vendoring** | 2 | goal-4 measured a vendored 0.3.0 snapshot; goal-3 never re-run after its fix |
| **Launch environment** | 1 | #263 |
| **Tier classification** | 1 | #285 |

## The largest class is not the epic's subject

**Error reporting produced four defects, and all four are one shape:** a
message was truncated or mislabeled, so the reader was sent to the wrong cause
and the real one was never printed.

- **#264a** — `bootstrap-home.sh` piped its CLI probe through `grep -q`, which
  discarded both the exit status and everything the child said. A *refusal*
  ("this shim binds another home") rendered as *"too old"*, with an upgrade
  remedy that cannot work.
- **#264b** — `skt ticket new` relayed a failed bootstrap by printing
  `tail[-1]`. Bootstrap's report ends with a REMEDY, so the one surviving line
  was the tail of a sentence and the cause was cut.
- **giw#27** — `wt close` quoted the last non-empty stderr line, yielding
  `error: either.` — a sentence with its subject dropped.
- **giw#25** — the same probe, the same discarded status.

The common cause is structural: **a summariser that picks ONE line out of a
child's output by position.** Last line, last non-empty line, `grep -q`. Every
one of them is a bet that the most important line is at a fixed offset, and a
`die` that names its subject first and explains itself afterwards loses that
bet every time. The fix landed in each place separately (`child_reason` prefers
the child's `error:` line; skt relays the whole report bounded at 40 lines) —
**there is no shared contract**, so the next tool that summarises a child will
make the same bet.

*Recommendation:* one relay contract, in one place, that every wrapper uses.
This is the strongest architectural signal in the epic and it is not what the
epic was about.

## The epic's own subject came second

**"Which home's copy does this run?"** — 3 defects, and the interesting part is
where they landed. #262 is the product defect. #289 is the **verifier** being
blind to it. The goal-1 walker is the **measurement** getting it wrong in the
other direction, calling 53 sanctioned fallbacks unsanctioned.

So one question was answered by three components and all three disagreed. That
is the "two readers of one truth" class the epic named, with a third reader
nobody had counted.

## What the instrument errors cost

Recorded because they were expensive, not for completeness:

- The **goal-1 walker** asked "does this home hold the unit DIRECTORY" when a
  shim runs a command line. Reported **55** unsanctioned pairs; the real number
  was **2**. It also produced the 2026-08-29 baseline of 19, and its claim of
  "0 sanctioned fallbacks" was an artefact. Two sessions of analysis were spent
  on a metric-construction problem that did not exist.
- **Goal 4** was measured against `skill-publisher-skill/` in this repository —
  a **vendored snapshot at 0.3.0** — while the installed plugin was 0.8.1 and
  had been correct since HBR-3. I fixed it a second time before noticing.
- **Goal 3** was implemented by giw#25 and sat at its `0 of 3` baseline for six
  days because nothing re-ran it.

All three share a cause: **a measurement whose target is implicit.** The
harnesses now name what they measure and refuse rather than guess
(`scripts/measure_goals.py`).

## Not attributed

#285 (tier classification) and #281 (auth.token on clone) are open and
unworked; they are counted where they landed but neither has been traced to a
cause. #261 is an installer-contract defect that predates this epic.
