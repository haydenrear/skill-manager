# OUN-5 — the migration that satisfies the gate instead of weakening it

`GOAL-migration-lands-on-one-skt`: still **NOT MET on real homes**, and that is
the correct reading. `GOAL-one-name-one-copy`: **guard held.**

```
value  = root (1, 1, 1); 0 of 38 homes at (0, 0, 1)
met    = False
upgrade_works_on_a_fixture = True
probe  = before (1, 1, 0) -> after (0, 0, 1), upgrade_exit 0,
         name_still_resolves_to 'skt'
```

## Why NOT MET is the right answer here

The metric counts real homes, and they do not move until `skt` actually carries
the `skill-manager` skill — that is OUN-6, and it lands after this. What this
ticket changed is that the upgrade is now **possible**: before it, the operation
that clears the collision was the operation the collision gate refused, so every
existing home was stuck behind a guard the epic itself installed.

So the harness gained the second half the goal declares — "bootstrap a home at
the pre-epic shape, upgrade it, and read the three counts" — and it is reported
**beside** the real-home count rather than folded into it. *Not yet upgraded*
and *cannot be upgraded* are different states and only one of them is a defect.
Folding them together is how a ledger stops distinguishing progress from
capability.

## The design

`UnitSupersession.TABLE` holds two rows and nothing else:

| unit | carrier | kind |
| --- | --- | --- |
| `skill-dev-skill` | `skt` | OBSOLETE — deleted upstream at OUN-4 |
| `skill-manager` | `skt` | MOVED_INTO_CARRIER — the name survives, the copy moves |

`RetireSupersededUnits` runs immediately before `RejectContainedNameCollision`
in the install program, and before `CommitUnitsToStore` on the sync path. It
retires only those names, and only when the unit named as their carrier is the
one being installed. Every other collision reaches the gate untouched — which is
why the gate is **satisfied** (there is genuinely one claimant afterwards) and
not disabled.

`MOVED_INTO_CARRIER` carries one extra condition that is the whole safety of it:
the carrier must **actually contain** a skill of that name. An `skt` that does
not carry it yet must not retire the only copy in the home — which is precisely
the state this epic passes through, since OUN-6 lands after this ticket.

## Two decisions worth the argument

**A table in the product, not a `supersedes` key in the manifest.** The manifest
key is the general mechanism and it is right for a unit that knows what it
replaces. It cannot serve here: the retirement would then be believed on the
word of a unit *fetched from a repository*, which could name any installed unit
as superseded and have it deleted. These two rows are historical facts about
this product's own units. The manifest key is in the backlog for the third case.

**A nested Remove program, not a bespoke deletion.** A retirement *is* an
uninstall, and an uninstall here is not "delete the directory": it
unmaterializes every projection, removes bindings, unregisters MCP servers,
prunes orphaned CLI deps and records the audit entry. Reimplementing a subset
inside one handler is how a migration leaves dangling agent symlinks and a
registered MCP server for a unit that no longer exists.

## The near-miss, which is the real content of this ticket

Checking DEF-OUN-008 — the owner's "if it's in a unit store, migration has to be
flawless" — meant opening what the migration was about to delete:

```
/Users/hayde/.skill-manager/skills/skill-manager/.git/
/Users/hayde/.skill-manager/skills/skill-dev-skill/.git/
```

**Every installed unit is a git checkout.** A home is the only place a unit's
history lives until `unit publish` moves it, so the migration as designed would
have silently eaten unpushed skill edits — on the one path every existing home
is required to take.

`UnitSupersession.blockedFrom` now refuses on uncommitted changes or commits on
no remote, and names `unit publish` as the way through. Absence of evidence is
read as unpublished: the git probe answers null both for "nothing contains HEAD"
and for "not a checkout", and a gate about destroying work must read the
ambiguous case as the unsafe one.

The unit tests I had already written all passed, and would have passed against
the destructive version, because `TestHarness.scaffoldUnitDir` does not create
git repositories. **The fixture was tidier than the world.** The guard's control
now pushes to a real bare remote, so "published" is proved rather than assumed.

## The window, stated rather than hidden

The retirement runs before the install it is clearing the way for, so there is
an interval in which the old unit is gone and the new one has not landed. It is
small — resolve has already succeeded by then — and the honest answer is to
print the origin the home recorded so the operator can put it back, rather than
to pretend the interval does not exist.

## Validation

- `jbang RunTests.java` — **ALL PASSED**, 11 new cases in
  `MigrationSatisfiesTheGateTest`, four of which are about collisions that must
  still be refused.
- `test_graph plugin-smoke` — **27/27**, including the new
  `plugin.supersession.migrates` with both controls: a bystander the table does
  not name survives a carrier claiming its name, and an ordinary collision is
  still refused in the same home immediately afterwards.
- The goal harness above, run on 38 homes plus the fixture probe.

## Deviation, recorded

The ticket declares `test_graph: ["home-sync"]` as its conflict key; the node
went into `plugin-smoke`, beside the gate it has to get past. The install path
is what the goal's harness exercises and what OUN-2's node already fixtures.
OUN-2 is merged, so the key conflicts with nothing live.
