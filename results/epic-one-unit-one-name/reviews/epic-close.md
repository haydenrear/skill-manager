# Epic close review — `one-unit-one-name`

Written 2026-09-11 against `epic/one-unit-one-name` at `0bee19e1`, 63 commits
ahead of `origin/main`. This is the whole-epic review the plan's
`review_policy` requires before finalization (`cadence: wave, gate: true,
walkthrough: required`).

**Read the readiness section first.** The epic's work is substantially done and
well evidenced, but it cannot honestly be reported as *complete against its own
declared goals*: two of six are NOT MET by their own harnesses, one active goal
has no harness at all, and one ticket's stated objective was silently narrowed.
None of those is a reason to throw work away; all three are reasons the close
needs a decision rather than a rubber stamp.

## 1. What the epic was for

> A unit name does not mean one thing in a home, and nothing can tell you what
> depends on what.

Three measured facts on 2026-09-05 opened it:

1. a skill contained in a plugin is **not addressable at all** —
   `MarkdownImportValidator.installedRoot` resolves a `unit:` name against
   `plugins/`, `harnesses/`, `docs/` and `skills/`, and never looks *inside* a
   plugin. `skt` has carried a contained `unit-authoring` since it shipped, and
   nothing imports it because nothing can;
2. there are **two forward-edge mechanisms** with two addressing schemes —
   markdown `skill-imports` by name, manifest `skill_references` by coordinate;
3. there was **no reverse edge** — answering "what imports `skill-manager`"
   took recursive grep over two homes.

## 2. Goal ledger — the honest number is 3 of 5 reported, 4 of 6 declared

`python3 scripts/measure_goals.py --epic one-unit-one-name`

| goal | verdict | note |
| --- | --- | --- |
| `GOAL-a-contained-skill-is-addressable` | **MET** | `unit: unit-authoring` resolves |
| `GOAL-who-imports-this` | **MET** | one command, both mechanisms, transitive, cycle-safe; agrees with an independent walk in 52 of 52 homes |
| `GOAL-the-front-door-is-found` | **MET** | 6 of 6 eval cases find the front door |
| `GOAL-one-name-one-copy` | **NOT MET** | 0 live, **11 latent** over 55 homes |
| `GOAL-migration-lands-on-one-skt` | **NOT MET** | root `(0, 1, 1)`; **1 of 53** homes at `(0, 0, 1)` |
| `GOAL-a-home-survives-being-copied-into-an-image` | **NOT REPORTED** | no harness exists |
| `GOAL-a-copied-home-knows-its-gateway-state` | **CARRIED** | correctly — see below |

Three things to be clear about, because the summary line "3 of 5 met" hides all
of them.

**(a) `GOAL-a-home-survives-being-copied-into-an-image` has no harness.** It is
an *active* goal served by OUN-9, OUN-10 and OUN-12, and named by OUN-8 — the
terminal evaluation ticket whose entire slice is deciding the goals. Yet
`EPICS["one-unit-one-name"]` in `scripts/measure_goals.py` registers five
harnesses and none of them measures it. The epic therefore cannot report a goal
it declared and did work toward. This is the single clearest thing standing
between the epic and an honest close, and it is cheap to fix: the three
properties are stated in the plan as independently checkable ((a) no credential
travels, (b) no `bin/cli` entry names the source home, (c) the copy is usable),
and OUN-9/10/12 all have committed evidence to build the harness from.

**(b) `GOAL-a-copied-home-knows-its-gateway-state` is correctly carried, not
dropped.** Owner decision 2026-09-05 ("let's defer that one for now — we don't
really use it"), `resolution: carried`, successor workflow
`substrate-home-model`, successor issue #317, receipt at
`specs/.history/one-unit-one-name/retired-ticket-011-OUN-11/manifest.json`. The
affected-goal disposition explicitly removes property (c) from the surviving
goal rather than leaving it silently unmeasured. This is the model for how (a)
above should be resolved if the answer is "not in this epic".

**(c) The two NOT MET goals are not failures of the work.** `one-name-one-copy`
reads 0 live collisions with 11 *latent* pairs, and the harness's own `why`
states the trap: 0 live was VACUOUS before this epic, because a contained skill
was unaddressable and therefore could not collide. The epic made the name
addressable, which is what *created* the latent pairs it now counts — the
number got worse because the instrument got honest. `migration-lands-on-one-skt`
reads 1 of 53 homes at the target triple: the migration works, and 52 homes have
not been run through it.

## 3. Tickets — 13 planned, 12 active, 1 retired

| ticket | wave | evidence | note |
| --- | --- | --- | --- |
| OUN-0 | 1 | yes | baseline + the written addressing rule; reviewed in `wave-1.md` |
| OUN-1 | 2 | yes | contained skill addressable |
| OUN-3 | 2 | yes | `skt deps --who-imports` |
| OUN-2 | 3 | yes | collision gate |
| OUN-4 | 3 | yes | remove `skill-dev-skill` |
| OUN-9 | 3 | yes | a copied home carries no credential |
| OUN-5 | 4 | yes | migration |
| OUN-10 | 4 | yes | a shim survives its home being copied |
| OUN-6 | 5 | yes | `skill-manager-skill` into the `skt` plugin |
| OUN-11 | 5 | *retired* | carried to `substrate-home-model` (#317), receipt recorded |
| OUN-12 | 6 | yes | copy without copy-on-write |
| **OUN-7** | 6 | **NO** | see below |
| **OUN-8** | 7 | **NO** | terminal evaluation — substantially performed, never closed |

**OUN-7's objective was narrowed without a record.** As written:

> Rename the hosting repository to `skt` so **the repo**, the plugin, the CLI
> and the unit agree, and repoint `installSource` in existing records rather
> than leaving them on a GitHub redirect.

What was delivered repoints the records — its `local_signal` genuinely passes,
0 installed records and 0 `units.lock.toml` rows name `skill-publisher-skill`
— but leaves the repository named `skill-publisher-skill`. The decision is
recorded only as a comment in `skill-project.toml`:

```toml
# The tracked DIRECTORY stays skill-publisher-skill/; only the installed
# unit renamed. Migration: references/migration.md in the plugin.
[plugins.skt]
source = "github:haydenrear/skill-publisher-skill"
```

So "the repo, the plugin, the CLI and the unit agree" is not true: the repo
still disagrees, and it is the one thing the objective named first. A TOML
comment is not the receipt mechanism this epic uses for scope changes — OUN-11
shows what one looks like. This is also the coordinate the owner saw being
cloned repeatedly during a root sync, so it is not invisible in practice.

**OUN-8 is the work of this session and is not closed.** The eval suite, the
scorecard wiring and the attribution ledger all exist and run; there is no
`results/epic-one-unit-one-name/tickets/OUN-8/` evidence directory and no close
record. Since OUN-8 is the ticket that *decides the goals*, the epic cannot
finalize on the letter of its own plan until it closes.

## 4. What landed

**Product — addressing and the edge graph**
- `installedRoot` descends into plugins, so a contained skill is addressable
- the collision gate refuses a plugin whose contained skill name already exists
  standalone
- `skt deps --who-imports` — the reverse edge, over both mechanisms,
  transitive, cycle-safe
- `skill-dev-skill` deleted everywhere; `skill-manager-skill` moved into `skt`

**Product — homes that survive being copied**
- a copied home carries no credential
- a shim survives its home being copied (`HomeCloner.reanchorProvisioned`,
  boundary-aware, every alias spelling — #330)
- a home can be copied on a platform without copy-on-write

**Product — the sync experience** (this session, from owner feedback)
- `skill-manager sync` says the home's drift **once** and counts child homes
  instead of looping for an ack. Measured on the real project home: `skt sync
  skt` → 2 lines; full `skill-manager sync` → 11 lines, exit 7, one named
  blocker, a log path, no loop
- `skt check` answers "is this home CURRENT" — the question `home drift` and
  `home verify` both exit 0 on
- `skt ticket new` refuses an inside-the-repo worktree; per-verb help; a
  three-way verdict

**Instrumentation** — this is the durable part
- a six-case eval suite over the real front doors, graded by a Stop hook that
  writes where the sandboxed Bash tool cannot
- `scripts/measure_goals.py` keyed by epic, with the eval suite joined to it
- a 38-row bug-attribution ledger separating product defects from instrument
  defects
- `units-template/verify/lib/front_door.py` with a corpus of commands agents
  really issued, run as a **build precondition** so a grader that fails its own
  tests cannot reach a paid run

## 5. Validation at this tip

| instrument | result |
| --- | --- |
| test graph, full sweep | **24 of 25 green**, 33m47s |
| `artifact-dag` | red on `uninstall.prunes.the.subgraph` — **pre-existing**, filed as **#334** |
| `jbang RunTests.java` | **ALL PASSED**, including `CloneReanchorsEveryAliasTest` and `SyncReportsDriftOnceTest` |
| `home verify` / `home drift` | clean on the project home |
| goal ledger | 3 of 5 met |

Eval suite, newest archived run per case:

| case | score | runs | cost |
| --- | --- | --- | --- |
| `epic-provisions-a-ticket-worktree` | 1.00 | 1 | $0.37 |
| `ticket-agent-opens-a-ticket` | 0.94 | 3 | $1.46 |
| `bootstraps-a-home-for-a-repo` | 0.93 | 3 | $1.65 |
| `ticket-agent-closes-a-ticket` | 0.90 | 3 | $0.96 |
| `syncs-a-stale-home-from-root` | 0.71 | 1 | $0.53 |
| `reconciles-a-worktree-into-the-project-home` | 0.62 | 3 | $1.22 |

Two of those are `n=1` and should be read as data points, not distributions —
three-run sweeps are what separated a real regression (bootstrap, consistent
0.10/0.10/0.30) from a coin flip (2/4/2/4) earlier in this epic, and reading a
single run as a trend is itself a recorded finding (`EV-I-18`).

## 6. Hot spots — where the bugs probably are

**The artifacts ledger and what deletes from it (#334).** The teardown's rule
— "delete only what the ledger recorded" — is right, and the bug is that a row
it *declines to act on* is also left in place as a row that nothing later reaps.
A refusal to delete a file becomes a permanent phantom in `artifacts list`. The
same subsystem is where `artifacts.lock.toml` survives an uninstall. It is the
newest large surface in the epic and the least exercised by anything but this
one graph node.

**Contained skills have no upstream coordinate (`DEF-OUN-013`, open,
blocking).** A manifest `skill_references` entry naming a repository cannot
reach a skill that lives inside a plugin. This is the direct shadow of the
epic's central change: making contained skills addressable *by name* inside a
home did not make them addressable *by coordinate* from outside one. If any
finding deserves to be promoted into this epic rather than deferred past it,
it is this one — it is the half of the thesis that did not land.

**`skt` already claims its own name twice (`DEF-OUN-002`, open, major).** Every
home carrying `skt` has the plugin at `plugins/skt` and a contained skill of
the same name. The collision gate this epic built refuses *new* collisions of
exactly this shape while the shipped unit is an instance of it.

**Homes that were never migrated.** 1 of 53 at the target triple. Nothing fans
out to the other 52, and `skt check` answers the currency question only for the
home you run it in. The gap between "the migration works" and "the fleet is
migrated" is the whole of `GOAL-migration-lands-on-one-skt`'s remaining
distance.

**The eval harness's two weakest cases.** `reconciles-a-worktree-into-the-
project-home` at 0.62 has never been diagnosed with the commands artifact that
made the sync case tractable (31 → 9 calls, $1.54 → $0.53). It is the obvious
next dollar.

## 7. Decisions made implicitly, and guardrails overridden

- **CI graphs are suspended on push and PR** at the owner's instruction, so the
  only pre-merge graph signal is a local run. That is a standing cost against
  GOAL-validation-floor metric (a) — 8 nightly, 0 per push and per PR — and it
  means this review's 24-of-25 is the *only* evidence the epic branch has.
- **OUN-7's scope reduction** (§3), recorded in a TOML comment rather than a
  receipt.
- **The eval sandbox network grant** was widened to `github.com` and two
  content hosts, on explicit owner instruction, after being reported as
  impossible. The report was wrong — it rested on a probe the staleness guard
  had refused (`EV-I-14`).
- **`--keep-temp` was unconditional** for the life of the suite, which filled
  the disk and made every command fail (`EV-I-17`/`EV-I-23`). Now it follows
  `--keep`, and diagnosis moved to an 8 KB text artifact archived beside the
  committed score.
- **A fixture unit is installed in the operator's real project home**
  (`DEF-OUN-016`) — `eval-skill`, origin an ephemeral scratchpad path that no
  longer exists, with an uncommitted marker edit. `skill-manager sync` in that
  home stops on it, correctly. Removal is the owner's call; the bytes are
  preserved read-only under `evidence/`.

## 8. Deferred findings

20 recorded, `results/epic-one-unit-one-name/deferred/backlog.yaml`.

| | |
| --- | --- |
| status | 10 open, 3 scheduled, 2 closed, 1 resolved, 1 carried, 1 worked_around, 1 partially_repaired, 1 filed |
| severity | 5 blocking, 7 major, 6 minor, 2 non-blocking |
| **open + blocking** | **`DEF-OUN-013`** — a contained skill carries no upstream coordinate |
| open + major | `DEF-OUN-002`, `DEF-OUN-003`, `DEF-OUN-006` |

The epic's `deferment_policy` is `mode: batch, blocking: escalate, budget: 5`.
`blocking: escalate` means `DEF-OUN-013` was supposed to be escalated rather
than accumulated, and it is still open at close time.

Also note a trap recorded in `DEF-OUN-016`: `DEF-121` from
`epic-home-integrity-sync` describes the same `eval-skill` unit in a *different*
shape, and its verified remedy is a raw `rm -rf` of two paths. Applied to the
current home — where the unit is fully installed and consistent across nine
places — that remedy would *manufacture* the damaged shape `DEF-121` exists to
clean up. Two correct remedies, opposite to each other, for the same unit name.

## 9. Readiness — what actually blocks the close

**Does not block.** #334 (pre-existing, filed, not a regression); the two NOT
MET goals, whose numbers are honest and explained; the eval cases below 1.00;
`DEF-OUN-016`.

**Blocks an honest close, in order of cheapness:**

1. **OUN-8 has no close record.** The ticket that decides the goals has not
   been closed with evidence. Mechanical, but the plan's rule 8 is explicit
   that only finalization closes the workflow and every delivered ticket needs
   its record.
2. **`GOAL-a-home-survives-being-copied-into-an-image` has no harness.** An
   active goal the epic cannot report. Either build the harness from the
   OUN-9/10/12 evidence, or carry it with a receipt the way OUN-11 was carried.
   Silence is the one option the plan does not allow.
3. **OUN-7's objective versus what shipped.** Either rename the repository, or
   amend the objective with a receipt. As it stands the plan claims something
   the tree does not do.
4. **`DEF-OUN-013` is open and blocking** under a policy that says blocking
   findings escalate.

## 10. Recommended next steps

1. Decide (2) — harness or receipt — for `GOAL-a-home-survives-being-copied-
   into-an-image`. This is the one I would not close without.
2. Amend OUN-7 with a receipt, or finish the rename.
3. Close OUN-8 with the evidence that already exists: the eval suite, the
   scorecard wiring, the 38-row attribution ledger, and this review.
4. Triage `DEF-OUN-013` — promote into this epic, or escalate it explicitly to
   a successor workflow with a receipt.
5. Then finalize: promote the accepted model, close the spec workflow, and
   merge the epic PR. **The release waits on #334**, per the owner's decision.
6. After the merge is verified, sweep every worktree the epic created — late
   and together, per `worktree-lifecycle.md`.

## 11. The ask

This is the gate. The epic branch is pushed and the PR is open; nothing merges
to `main` until you answer.

Specifically: are items (2) and (3) in §9 things you want done before the
merge, or recorded as carried and merged as-is?
