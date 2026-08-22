# HIS-15 — goal contribution

Issue #235. Branch `feature/235-his-15`, base `epic/home-integrity-sync` at
`854f2ea`. Wave 6, promotion order 11, predecessor HIS-14.

Goals: **GOAL-one-home-one-answer** (`direct`) and
**GOAL-no-destructive-recovery** (`guard`).

Declared expected effects: *"a machine-readable verdict and a human-readable
refusal are currently two answers and one of them is empty; after, one answer in
two renderings"*, and *"the gate stops recommending `--force`, which discards
work it just refused to destroy."*

---

## 1. The issue's root cause was wrong, and the correction is the ticket

The issue said the losing side of a concurrent close-out *"exits non-zero with
its explanation on stderr and nothing on stdout."* Measured on the epic tip,
with a peer holding the project home's lock:

```
$ skill-manager home close-out --home <wt> --into <proj> --json
exit=0                                    <-- THE COMMAND SUCCEEDED
home sync --dry-run: waiting for /…/proj-home — another skill-manager process holds this home's lock
{"home":"/…","into":"/…","safe":true,"exitCode":0,"blockers":[],"units":[]}

json.decoder.JSONDecodeError: Expecting value: line 1 column 1 (char 0)
```

That error is **byte-identical to the one in the red graph node**, and the run
did not fail at all. The cause is `HomeLock.announceWait` — the line HIS-11
added so a wait would not look like a hang — reaching **stdout** through
`Log.info`. It corrupts the document on any contended run, whether or not that
run then fails.

Three distinct breaks of one contract, all measured
(`probes/his-15/probe-1-root-cause.txt`):

| # | what happens | exit | stdout | which defect |
| --- | --- | --- | --- | --- |
| 1 | an **extra** non-JSON line | **0** | unparseable | **the red graph** |
| 2 | `not_a_home` paths | 2 | correct JSON already | none — this is the shape the others should have |
| 3 | an exception escapes the command | 1 | **empty** | the one the issue describes; real |
| 4 | `FrozenHomeException` caught in-command | 9 | **empty** | found while reading; see §3 |

**So the contract is not "emit JSON when you fail."** It is:

> under `--json`, stdout carries **exactly one JSON document and nothing else**,
> on every exit path.

A fix aimed only at failure paths closes #3 and #4 and leaves #1 — the red one —
untouched. That restatement is the whole ticket, and the epic owner has adopted
it into #235 and the plan entry.

---

## 2. What I built

**`Log.setJsonMode`**, latched from the parsed command before anything can
print. Every human line — `info`, `ok`, `step`, `detail` — goes to stderr for
the duration. One choke point covers all thirty-one `--json` commands and every
library path beneath them, present and future.

Two properties worth stating because both are load-bearing:

- **The diagnostic is moved, not deleted.** `announceWait` exists because a
  silent 120-second wait is indistinguishable from a hang, and that reason has
  not gone away. A guard assertion pins it: the notice must still appear on
  stderr, so "fix it by deleting it" fails.
- **`BuildCommand` had already solved this privately**, by redirecting
  `System.out` to `FileDescriptor.err` for the length of its program. That is
  this epic's signature defect again — one command solving alone what every
  command needed — and it is why the fix is a latch rather than a second
  private redirect.

**`JsonExitEnvelope`**: the CLI counts the bytes a command wrote to stdout, and
if a `--json` run is about to exit non-zero having written none, the CLI writes
the document the command did not. It never fires when the command wrote its own,
so a richer payload always wins.

> **Why a net and not sixteen fixes.** Sixteen silent failure paths were
> enumerated — and the argument is not the count, it is *where two of them sit*:
> `HomeCommand.CloseOutCmd` catches `NotAHomeException` and answers a `--json`
> caller with a proper error document, and **one arm below**, catches
> `FrozenHomeException` and answers nothing at all. Nobody decided that. The
> second catch was written later than its sibling by someone with no reason to
> read the first. Fixing sixteen sites leaves the seventeenth to whoever writes
> it next; **V2 measures this** — removing the net alone reddens eight-plus
> commands that nobody edited for this ticket.

**`HomeContendedException`**: the lock refusal is now a type, so a consumer
branches on `error:"home_locked"` instead of matching a substring of English.
Classification is by type, never by message.

**Two further production defects the guard found while being written**, neither
in the issue:

- **A parse failure never reaches the execution strategy**, so the `--json`
  latch never ran and the envelope was never armed: `bind no-such-unit --json`
  exited 2 with an empty stdout. The flag is in the raw argv whether or not the
  parse succeeded, so the parameter-exception handler consults that. **V3.**
- **`close-out`'s frozen arm** now answers in its sibling's shape
  (`error:"home_frozen"`, with `safe` / `blockers`), because a *caught*
  exception never reaches the classifier and the generic net can only say
  `"failed"`. **V4** pins parseable-vs-useful so the arm is not deleted as
  redundant later.

---

## 3. The guard, and the hole its own vacuity check found

`JsonContractTest` enumerates the `--json` surface by **walking the live picocli
tree** — deduplicated by command-spec identity, because picocli exposes an alias
(`list` / `ls`) as two map entries. A hard-coded list goes stale the day someone
adds the thirty-second command, silently, which is the failure mode being fixed.
A `--json` command with no table entry fails `every_json_command_is_covered`;
that gate fired for real on the first run, naming 23 uncovered commands.

Three rules, per the epic owner's instruction:

- **PURITY**, all 31: stdout holds zero or one document and no other bytes.
- **PRESENCE**, non-zero exits: a failing run emits a document.
- **THE PINNED SILENT PATHS**, by name: `harness rm` with no match and
  `home drift --ack` assert they print **nothing** today. Not deferred — a
  deferred gap is an unasserted state, and a change that starts writing either
  prose *or* a document there now fails and forces a deliberate decision.
  This earned its keep immediately: `home drift --ack` reddens under V1 without
  anyone having written a case about drift.

### The hole

**V1 found my guard reddening on a precondition instead of on its claim.**

The contended close-out case is the only one that reproduces the actual defect —
a progress line on a run that *succeeds*. Its first version asserted the wait
notice was on **stderr** as its *precondition*. Revert the routing and the notice
moves to stdout, so the precondition failed and the case never reached its claim.
That is **HIS-4's probe 7a**, one epic later, inside the guard written to prevent
exactly this.

Corrected: the precondition is now **stream-blind** — the notice appeared on
either stream, which is all "contention happened" requires — the purity check is
the claim, and a third assertion holds the notice on stderr so the fix cannot be
"delete the diagnostic". It now reddens on the claim and quotes the contaminating
line.

### Sandbox

The driver runs commands in-process through the real entry point, against a
throwaway home pinned via `AgentHomes` — and **asserts the sandbox held** before
running anything, rather than assuming it. That assertion is why I noticed the
one thing it cannot cover: see DEF-046.

---

## 4. Validation

```
jbang RunTests.java     ALL PASSED
uv run pytest specs/    38 passed
jbang RunHis15.java     35 passed, 0 failed
```

`tlc` — **N/A** per the assignment; HIS-5 carries the model work.

### The graph this ticket exists to clear

```
ticket-lifecycle   BUILD SUCCESSFUL in 6m 20s   status: passed
                   13/13 nodes, 117 assertions, 0 not passed
                   runId 20260822-171850
```

The two that decide the ticket:

- `ticket.lifecycle.concurrent.close.out` — **passed**, all 15 assertions,
  including `every_remedy_the_gate_printed_is_an_absolute_runnable_command`,
  which is the one that was failing because `remediesB` was empty.
- `home.fixpoint.law` — **passed**. It was one of the six nodes skipping behind
  the red one; all six now run.

Summary copied to `probes/his-15/ticket-lifecycle-summary.json` rather than
described.

> **A reading error worth recording**, because it nearly became a false report.
> My first pass over the node envelope asked each assertion for a `passed`
> field. The schema names it `status`, so every assertion came back `None` and
> printed as FAIL — a node reporting `status: passed` with fifteen failed
> assertions under it. That shape is impossible, which is what prompted a second
> look rather than a report. The lesson is the epic's own: read the artifact's
> schema, do not assume its field names, and treat a self-contradictory
> measurement as a bug in the measurement until proven otherwise.

| # | mutation | what reddened |
| --- | --- | --- |
| V1 | the json routing reverted | the Log unit, **the contended case (on its claim, after correction)**, and pinned `home drift` |
| V2 | the `JsonExitEnvelope` net removed | 8+ commands nobody edited — the argument for a net |
| V3 | the parse-error branch removed | exactly the four `project` entries |
| V4 | `close-out`'s frozen `if (json)` reverted | the typed-payload case, showing the generic `"failed"` fallback |

---

## 5. What I cut

- **`close-change.sh`.** Different repository; cross-repo publishing is HIS-8's
  path and a second PR was ruled out. **DEF-045** records precisely which branch
  is which, because the output misled two readers already: its *empty-verdict*
  branch was **already correct** (re-run fix, no `--force`); the `--force`
  advice came from the *non-empty-but-unparseable* branch, which this ticket's
  stdout fix eliminates **for this cause**. What remains is defence in depth
  against a future cause, not a live defect.
- **The lock's scope and its 120 s patience** — DEF-041, still open, explicitly
  out of scope.
- **A contract for the three silent success paths** — **DEF-044**, with their
  current behaviour pinned rather than deferred.
- **Making the `project` family testable** — **DEF-046**.

Three deferrals against a budget of five.

---

## 6. What I am unsure about

**The envelope's `"failed"` is honest but thin.** For a command that catches its
own exception and returns a code, the CLI genuinely does not know the reason, so
the envelope carries `error:"failed"` plus the human sentence. That is correct
and less useful than a typed code. The right long-term shape is for commands to
throw typed refusals rather than catch-and-return — a much larger change than
this ticket.

**`Log.lastError()` is a latch, and latches are stateful.** It exists so the
envelope repeats the *same* sentence the command already put on stderr rather
than composing a second one that could disagree. Nothing branches on it and it
is cleared per invocation, but it is global mutable state and I would rather it
were not.

**I did not measure the graph's six skipped nodes individually.** The claim
"`ticket.lifecycle.concurrent.close.out` is green and the six behind it run" is
decided by the graph run recorded in §4, not by my reasoning about the node's
assertions.
