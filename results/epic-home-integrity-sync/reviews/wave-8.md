# Wave 8 review — epic/home-integrity-sync

**Range:** `a527221..e35e4a5` · **Merged:** HIS-18 (#240 → #239), HIS-17 (#242 → #238, plus docs follow-up #243)
**Schedule revision at close:** 7 → **8** · **28 files, +4,852 / −82**, 2 production files, 10 test/graph files

> Written late, at the epic PR — see wave 5's note.

---

## What the wave was for

**The wave with almost no production code and two of the epic's most portable
results.** HIS-17 changed *nothing* under `src/` — `git diff -- src/` is empty —
and produced the rule the whole epic now cites.

| ticket | what it fixed |
| --- | --- |
| HIS-18 | a scaffolded re-derivable tree was still hashed, so the drift report's **input** never shrank. `GitIgnoreRules` excludes them at the source. |
| HIS-17 | four graphs each kept a private copy of "nothing in a clone names its source", and HIS-10 moved that rule. One copy was fixed; three reddened a wave later. |

## Goal movement

| goal | measured | verdict |
| --- | --- | --- |
| `GOAL-sync-quiet` | retrodiction 889 → 124 file lines (86.1%); **forward-going: 22 net-new exclusions over 23 live units** | **two numbers, both real** |
| `GOAL-one-home-one-answer` | four readings of one rule, three in tests → one reading plus three asserted cross-checks | on track |

**HIS-18's own disclaimer is the important half.** The 86% is a retrodiction over
a frozen record whose paths are in `removedFiles` *because they were deleted*.
`spec-double-compiler` is 87% of that number and contributes **zero** today. The
plan now carries `measurement_caveat_added_2026_08_22` stating both figures, so
HIS-6 cannot conflate them.

## Three graphs green, one still red

| graph | result |
| --- | --- |
| `home-clone` | **green** — 14 nodes, 100 assertions |
| `checkout-home` | **green** — 8 nodes, 59 assertions |
| `onboard` | **green for the first time in this epic** — 15 nodes, 45 assertions |
| `artifact-dag` | its own node passes; `uninstall.prunes.the.subgraph` remains red — ARTI-08's declared red, DEF-066 |

`onboard`'s red had a different cause than the issue stated: not text drift, but
**ARTI-07 landing** and making the assertion unsatisfiable. Four `onboard` budgets
also sat below the ~14 s fixed per-node cost (one misreported as 60 s where the
source said 15 s); all four now ship at 90 s (DEF-065).

## The rule, and the two artifacts that outlive the ticket

> **An independent reading is only worth having if its disagreement is asserted;
> otherwise delete it and use production's.**

Written into `HomeIsolation`'s javadoc under **"BEFORE YOU CONSOLIDATE THIS AWAY"**,
because that is where the reader who counts spellings will be standing. Applied
back to its own leftovers it condemns **DEF-063**, a duplicate with no agreement
assertion — the ticket rewrote its earlier position rather than exempting itself.

**Mechanism D** was added to the vacuity ledger. The review found that HIS-17's
first fix widened the walk on the **regular-file** branch while the only control
added was a **symlink** decoy — a branch `verifyRoots` decides *earlier*, and
without consulting descent records or byte accounting at all. Proved by loosening
production's accounting to a filename check: **every assertion the first version
shipped passed.** Counts moved honestly with it — 10 → 11 instances, 8-of-10 →
8-of-**11** self-caught, because row 11 was the reviewer's catch.

> *The taxonomy was incomplete for ten instances and nothing looked for D because
> D had no name. A ledger of mechanisms is itself an enumerated list.*

## The finding this wave produced that most threatens the epic's own results

**DEF-067, promoted to major.** `HomeFixpointLaw` parses the remedy out of a
refusal and **runs** it. *An observer that repairs is no longer an observer* — it
can silently fix the condition it was checking, across 24 graphs, and report PASS.
Nothing distinguishes a foreign shim the product wrongly created from one a
fixture planted. **This epic's founding defect can be laundered by the instrument
built to catch it.**

## Findings recorded

DEF-058 through DEF-067, including DEF-063 (condemned by the wave's own rule),
DEF-065 (node budgets below the fixed per-node cost), DEF-066 (`artifact-dag`'s
declared red) and DEF-067 above.

## Also: the sweep enumeration was falsified

HIS-17's sweep for private isolation scans listed eleven. A twelfth existed —
`OnboardingSupport.escapingLinks`, in a file three of whose scans were already on
the list. The conclusion ("no fifth instance of the defect") survived and was
independently confirmed; the **count** was wrong and is corrected at source.
