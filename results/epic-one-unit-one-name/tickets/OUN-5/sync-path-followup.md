# OUN-5 follow-up — the sync path was wired and never exercised

Filed against my own delivery of OUN-5, the same day, after the owner asked
the right question: *"I'm a bit concerned also that the migration path isn't
well defined."*

## What was wrong

OUN-5 declared `test_graph: ["home-sync"]` as its conflict key — the sync path
was in the ticket's slice from the start. I wired `RetireSupersededUnits` into
`SyncUseCase` and then wrote **every** test against `install`. The wiring
compiled, read correctly, and was wrong.

**Defect 1 — `sync skill-manager` performed no migration.** The sync-side
trigger asked "is the carrier among the sync targets?". A whole-home `sync`
walks every installed unit, so `skt` was there and it worked. A targeted
`sync skill-manager` named the *retired* unit and not its carrier, so nothing
fired — and that is the single command a person runs after being told this
migration exists. Same for `upgrade skill-manager`.

The sync path now asks the **home** what it is due
(`UnitSupersession.dueInThisHome`) rather than asking the target list. This is
safe for exactly the reason it was safe before: the `skill-manager` row still
requires the carrier to be present AND to actually contain a skill of that
name, which is only ever true of a home already holding two copies of one
name.

**Defect 2 — a passing test asserted the defect was correct.**

```
suite.test("nothing is due when the carrier is not the unit being installed", …)
```

It passed, it was green in the PR, and what it encoded was the bug. Replaced by
`"a home that is DUE is due whatever is being synced"`.

## And one thing that was right but under-specified

A blocked retirement — a unit with unpublished work — used to halt whatever
operation found it. That is correct when the carrier is what is being installed
or synced, because proceeding creates the two-copies state on purpose. It is
wrong when some unrelated unit is being synced: `sync deploy-helm` failing over
an uncommitted edit in `skill-manager` is a migration holding the whole home
hostage.

| situation | blocked retirement |
| --- | --- |
| the carrier is being installed or synced | **halt** — `UnitSupersession.isMandatory` |
| an unrelated unit is being synced | **report and skip** — the home is no worse than it was |

## The goal, re-scoped at the owner's instruction

> "each project and skill manager home is responsible for migration [...] We
> need to validate that instead of worrying about every home that exists."

The metric was a census: how many of the 38 homes on this machine hold
`(0,0,1)`. That is not a property of the software. It counts homes nobody has
synced, it reads *not yet upgraded* and *cannot be upgraded* as one number, and
no amount of correct code drives it to zero.

It now grades the **mechanism**, once per route:

```
install <carrier>   how a home ACQUIRES skt; before OUN-5, exit 3
sync                how a home that already has skt migrates ITSELF;
                    before this follow-up, silent
```

The existing homes are still read and printed — they are the population the
mechanism has to work on — as context, not as the verdict. `schedule_revision`
is 4.

## Where the migration now fires

| verb | fires | note |
| --- | --- | --- |
| `install <carrier>` | yes | before the collision gate, in the same operation |
| `sync` (whole home) | yes | |
| `sync <any unit>` | yes | the home decides what is due |
| `upgrade` | yes | builds the same sync program |
| `project resolve` | yes | runs install programs |
| `upgrade <non-git unit>` | **no** | `UpgradeCommand` drops non-git units before a program is built, so an empty target list returns early. `sync` still does it. |

The last row is stated rather than fixed: it is a property of `upgrade`'s
pre-filter, not of the migration, and changing it widens this follow-up into
`upgrade`'s target selection.

## A third defect, found by a law node

The first version of both new fixtures reproduced the two-copies state by
writing the standalone's tree AND its `installed/*.json` straight onto disk.
That produces a unit with a record and **no `units.lock.toml` entry** — a shape
the product never creates. The retirement then removed the tree and left the
record, and `home.membership.law` failed the whole `plugin-smoke` graph over
it:

```
LOST [skill-manager] — an installed/ record names them and the home does
not hold them. A unit nobody removed.
LOCK DISAGREES — units.lock.toml names [] with no installed/ record, and
installed/ names [skill-manager] with no lock entry.
```

**The product was not at fault**, and proving that took one assertion:
install the standalone properly, let the migration retire it, and check the
`installed/` record went with the tree. It does.

The fixtures now build the state the way a home actually reaches it:

1. install the standalone while nothing carries the name;
2. install the carrier **without** the skill — nothing is due;
3. add the contained skill to the installed carrier's tree, which is what a
   git pull of skt delivers;
4. `sync skill-manager` — now it is due.

The graph node also does this in a home of its own, because the first version
mutated the shared fixture home and left it damaged for every node after it.

### Two assertions that should have been there from the start

- **the command must exit 0.** The harness graded only the counts, and the
  sync route was reporting `after (0,0,1), exit 1` — the target triple reached
  by a command that failed. Grading the easy half of the question is the same
  mistake the re-scope had just corrected one function higher up.
- **no orphaned `installed/` records.** An entry naming a tree the home does
  not hold is what the membership law calls a LOST unit, and a migration that
  leaves one has not migrated the home.

Both now decide the verdict, in the harness and in the node.

## Validation

- `jbang RunTests.java` — the suite, with 4 new sync-path cases and 2 rewritten
  ones.
- `test_graph plugin-smoke` — `plugin.supersession.migrates` now drives BOTH
  routes: the install upgrade, then the home put back into the two-copies state
  on disk and a `sync <retired unit>` over it. Its control asserts the home
  really was returned to that state, so "the sync retired it" cannot pass
  against a home that never had two copies.
- The goal harness, both routes.

Final reading, both routes:

```
install -> before (1,1,0)  after (0,0,1)  exit 0  orphaned_records []
sync    -> before (0,1,1)  after (0,0,1)  exit 0  orphaned_records []
```
