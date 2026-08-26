# HIS-19 — vacuity checks

Fourteen probes over five production files, plus a baseline. **Revised after
the review of PR #250**, which found one probe pointing at a verdict-neutral
branch and one production arm that had to be removed — the probe for it went
with the arm rather than being left as a record of coverage that no longer
exists. Every row names
**which branch it moves** and **which assertion reddened**; the harness
(`probes.py`) classifies each red as landing on the CLAIM or on a PRECONDITION
and prints both counts into the `.out` file, so the table below is generated
evidence rather than my reading of a transcript.

Re-run: `python3 results/epic-home-integrity-sync/probes/his-19/probes.py --baseline`
then `… probes.py`.

**Baseline: `ALL PASSED`, 20 assertions.**

| probe | file | branch it moves | verdict | claims red | preconditions red |
| --- | --- | --- | --- | --- | --- |
| V1 | `LauncherShims` | the writer's pin STRING — the whole ticket, reverted. `choose` is still *called*, so the path is reached and only its answer discarded (mechanism C). | FAILURES: 9 | 9 | 0 |
| V2 | `DurableCliPin` | the `HOMEBREW_LINKED` candidate alone | FAILURES: 4 | 2 | 2 |
| V3 | `DurableCliPin` | the `HOMEBREW_KEG` candidate alone | FAILURES: 1 | 1 | 0 |
| **V4** | `HomeCommand` | **`home shims`'s REPORTER** — the pin printed and put in the `--json` `cli` field, versus the pin written. **This is MAJOR 1's measured red.** | FAILURES: 2 | 2 | 0 |
| V5 | `DurableCliPin` | the **same-file gate** | FAILURES: 1 | 1 | 0 |
| V6 | `DurableCliPin` | the **versionlessness gate** on a candidate | FAILURES: 1 | 1 | 0 |
| V7 | `DurableCliPin` | the `NO_VERSION_TO_LOSE` short-circuit | FAILURES: 1 | 1 | 0 |
| V8 | `DurableCliPin` | existence / executability | ALL PASSED | 0 | 0 |
| V9 | `DurableCliPin` | the **version predicate itself**, widened to `.*` | FAILURES: 14 | 12 | 2 |
| V10 | `HomeCommand` | `home verify`'s **verdict disjunction** | FAILURES: 2 | 2 | 0 |
| V11 | `HomeCloner` | the **detector** in `verifyRoots`, still called | FAILURES: 2 | 2 | 0 |
| V12 | `HomeRepair` | the **repairer's target** — the remedy's honesty only | FAILURES: 1 | 1 | 0 |
| **V13** | `DurableCliPin` | the **unresolvable-located MESSAGE branch** — added at review, see m4 below | FAILURES: 1 | 1 | 0 |
| **V14** | `DurableCliPin` | **MAJOR 2's invariant**: every candidate is derived from the located path. Plants one that is not. | FAILURES: 13 | 10 | 3 |

**V4 is the row that matters most**, because before the review it did not exist
and the branch it moves was covered by nothing:

```
# revert HomeCommand's `pin = result.pin();`
EXIT=0 ; [FAIL] count = 0 ; [PASS] count = 1402      <- the reviewer's run
```

```
# the same revert, now
[FAIL] MAJOR 1: `home shims` PRINTS the pin it wrote, byte for byte
[FAIL] MAJOR 1: the --json `cli` field is the pin that was written too
```

**The probe that was DELETED rather than re-aimed.** The old V4 moved the
`PATH_ALIAS` candidate. That arm was removed (MAJOR 2), so the probe went with
it — a probe naming a branch that no longer exists is a record that reads as
coverage and is not, which is this file's whole subject one level out.

## Mechanism D, which is what this ticket was warned about

A pin has branches production decides separately and in an order the author does
not control. There is **one probe per branch** above, and V2/V3/V4 are the proof
that they really are separate: removing the linked-prefix candidate reddens *only*
the case named after it, because the keg alias still fires and a home still
survives an upgrade. A single "durability" probe would have shown green through
two of the three arms being dead.

**And the mechanism caught this ticket once, in its own suite.** The first draft
of `GATE: an alias that ALSO names a version` pointed `PATH` at the keg's own
`bin/`, whose `skill-manager` **is** the located path — so the candidate was
thrown out by the *identity* gate before the version gate ever ran. The probe
passed while testing a branch the claim is not about. It was caught by running
it and reading *which rejection string came back*, never by reading the code. It
is fixed by aiming at `libexec/bin` (a different path to the same file) and by a
`precondition:` assertion that the candidate was considered at all.

## Mechanism D again, found by the reviewer — ledger row 18

Rows 16 and 17 were mine. **Row 18 is the reviewer's, and it is the third
instance of D in one ticket.** The shape:

```
delete `if (candidate.equals(abs)) …`        -> suite fully green
delete `if (real == null) { … return … }`    -> suite fully green
```

The second one is the sharper finding, because the case *named after that
branch* — `GATE: a build that does not resolve is left exactly as it was` —
passed with it removed. Its fixture used an empty `PATH` and asserted only the
verdict, and the verdict is reached either way. A probe can carry the branch's
name, run, redden under other mutations, and still be unable to see the thing it
is named for.

The fix is not a bigger fixture. It is asking **what the branch decides**: this
one decides a *message*, so the case asserts the message.

## Mechanism A: two preconditions were moved out of a claim

`CLAIM 1`'s first draft asserted "the pin written is versionless" as a
*precondition*. Under V1 that assertion reddens first and the run stops, so the
claim — "the home still runs a CLI after the upgrade" — was never evaluated by
the probe that matters most. The harness reported it as `claims=4
preconditions=1`; the honest reading is that CLAIM 1 was credited without being
tested. It is now two cases: the preconditions that remain are **blind to the
change** (the located build is versioned; the sandbox path is not; the home ran
before the upgrade), all true both before and after HIS-19, and the versionless
assertion is its own claim. V1 now reddens **6 claims, 0 preconditions**.

Same correction for the two GATE cases: they now assert `precondition: the
candidate was considered`, which is why V4's reds are classified as
preconditions rather than credited to gates that never saw an input.

## V8, declared green — and what the review made of that declaration

`isRegularFile` and `isExecutable` sit above the same-file gate. Every input they
refuse is refused again below them: a dangling symlink does not resolve, a
directory does not resolve to the located *file*, and a symlink takes its
target's mode. They buy an earlier exit and a better rejection message, not a
different verdict. Inventing a fixture that reddens them would mean inventing an
input that cannot exist, which is worse than saying so.

**That reasoning was right and I did not apply it to the two branches beside
them.** The review of #250 found that the *identity* gate and the
*unresolvable-located* short-circuit were equally verdict-neutral, while the
javadoc claimed *"four separately-decided gates… each is its own branch with its
own control"*. Two different treatments of one situation, three lines apart,
and the one I wrote down was the wrong one. The identity gate is now **deleted**
(unreachable once every candidate is derived from a keg decomposition); the
short-circuit is **kept, declared neutral, and probed for the thing it actually
decides** — the recorded reason (V13). Ledger row 18.

## The instruments, validated

Ledger row 14: a zero is evidence only when the instrument has been shown capable
of a non-zero.

* **The version predicate** has a positive-control case in the suite itself —
  six strings it MUST flag (`0.23.0`, `0.24.0_1`, `v1.2.3`, `1.4.0-rc2`,
  `skill-manager-0.24.0`, `3.11`) asserted alongside the eight it must not.
  V9 is the same control from the other side.
* **The deferment grep** was run with a known-present control first
  (`grep -c "cli pin" backlog.yaml` → 2, `grep -c -i home skill_feedback.md` → 9)
  before its zeros for `versionless` and `DANGLING_CLI_PIN` were believed.
* **`transcript.sh` asserts its own fixture.** Its first version did not, and it
  is the third instance of ledger row 14 in this epic: a `python3 -c` regex that
  failed to compile left the "legacy" home holding a *durable* pin, the script
  carried on under `set -u` without `-e`, and the transcript confidently reported
  "the two homes are indistinguishable … both exit 0". Both statements were true
  and neither meant anything. The script now runs under `set -euo pipefail`, the
  rewrite asserts `n == 1`, and the pin line is read back and compared before any
  measurement is taken.
* **The pin-line grep in my first manual check** returned nothing on a file that
  *does* contain the pin (a brace-quoting slip), and I nearly read that as "no
  pin was written". Same shape, caught by looking at the file.
* **And a fourth, while reproducing the reviewer's MAJOR 2.** My first harness
  called `DurableCliPin.forPin`, which read the **real** `PATH`, not the
  fixture's — so it reported *"no substitution"* and read exactly like the
  reviewer's finding being wrong. Rejecting a correct review on the strength of
  a broken instrument would have been the most expensive version of row 14 in
  this epic. Caught by a `PATH contains userbin: true` assertion added to the
  harness before its result was believed. `downgrade.out` keeps both runs and
  its header says which is which.
* **`transcript-pre-his19.out` was regenerated with FIVE call sites reverted**,
  not three. The three-site version supported the opposite of the claim it was
  cited for (m7). The header of that file now lists every site and says why.
