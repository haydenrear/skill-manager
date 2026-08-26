# Wave 6 review — epic/home-integrity-sync

**Range:** `c44a2a0..7437c72` · **Merged:** HIS-15 (#236 → #235)
**Schedule revision at close:** 5 → **6** · **14 files, +2,502 / −8**, 6 production files

> Written late, at the epic PR — see wave 5's note.

---

## What the wave was for

One ticket, and **its value is the correction, not the fix.**

The issue said the losing side of a concurrent `home close-out --json` *"exits
non-zero with its explanation on stderr and nothing on stdout."* Measured on the
epic tip with a peer holding the lock, that was **false**: it exited **0**, and the
JSON was corrupted by a human-readable line `HomeLock.announceWait` wrote to
**stdout** via `Log.info`.

So the ticket delivered:

- `Log.setJsonMode` — a JSON mode no code path can write around;
- `JsonExitEnvelope` — one verdict, two renderings;
- `HomeContendedException` — a **typed** contention signal instead of a text sniff.

## Goal movement

| goal | measured | verdict |
| --- | --- | --- |
| `GOAL-one-home-one-answer` | a machine-readable verdict and a human refusal were two answers, one of them empty → one answer in two renderings | on track |
| `GOAL-no-destructive-recovery` (guard) | the gate stops recommending `--force`, which discarded work it had just refused to destroy | on track |

## Integrated validation

| lane | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `uv run pytest specs/` | passed |
| `ticket-lifecycle` | red → **green**; the 6 nodes DEF-043 was taking with it are back |

## Vacuity

**Instance 9 of the ledger came from this ticket, and it is the sharpest one.**
Probe V1 reddened a **precondition** — *inside the guard written to stop exactly
that*. The ticket found it itself and rewrote the probe to prove the code ran
before proving what the code did. That exit-13 pattern is now the ledger's model
for mechanism C.

## Findings recorded

DEF-048 (the membership law is a within-home consistency law, not a temporal one),
DEF-050 (four more commands resolve a target from the working directory),
DEF-053 (`home policy` / `home shims` still classified `WRITES_HOME` while
declaring `--init`).
