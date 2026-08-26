# `ticket-lifecycle` was red from the day HIS-10 merged

Found by the **HIS-12 agent**, wave 4, while running a graph outside its own
declared set. Not by wave 3's validation, which is the point.

## What was red, and why it is not a bug in HIS-10

`ticket.lifecycle.provisioned` asserts
`no_path_in_a_worktree_home_resolves_back_to_the_homes_above_it`, over a
**bespoke reference walk** (`TicketLifecycleSupport.filesNaming`). The node's own
comment states the rule it encodes:

> "A home is a pure function of its root — nothing in a correct one names ANY
> absolute home path, its own included (`home clone` re-anchors every link and
> record)."

**HIS-10 (#227) changed that contract deliberately.** Every clone now records its
descent so the sanction for an inherited parent-store shim is a *fact in the copy*
rather than a flag an operator types:

```json
{ "schemaVersion": 1,
  "clonedFrom": "/Users/hayde/IdeaProjects/skill-manager/.skill-manager",
  "parentStores": [ "/Users/hayde/.skill-manager" ] }
```

Production exempts exactly that file from its own isolation rule, gated on byte
accounting (`HomeProvenance.mentionsOnlyRecordedDescent`). The test's copy of the
rule did not follow. All four scans hit, the node failed, and **11 nodes skipped
behind it.**

So this is the epic's signature defect — **two readers of one rule** — living
inside the epic's own instrument. Confirmed deterministically by reading the
record in two live worktree homes; no graph run was needed to establish the cause.

## Why wave 3 missed it

`ticket-lifecycle` was not in HIS-10's declared `conflict_keys.test_graph`, and
wave 3 ran `home-integrity`, `sync-settles`, `project-child-home` and
`harness-smoke`.

Under the validation policy set at the wave-3 gate, a ticket owes *its declared
graphs plus any graph whose fixtures exercise the sources it edited*. HIS-10
edited `HomeCloner`, and `ticket-lifecycle` clones homes. **The policy is right;
the selection was incomplete.** That is the lesson, not "run more graphs".

## The fix, in two parts

1. **The walk exempts the sanctioned record** — the single filename, at the home
   root only, not a pattern. Anything else naming another home is still a leak.
2. **The node now also requires production's own verdict** — `home verify` exit 0
   on each worktree home, as
   `production_agrees_the_worktree_home_reaches_no_other_home`.

Part 1 fixes today. **Part 2 is the one that matters**: it converts a duplicate of
the rule into a cross-check against the owner of the rule. If the contract moves
again, this fails on the day of the change instead of a wave later.

## Measured

| | |
| --- | --- |
| before | `ticket.lifecycle.provisioned` **failed**, 11 nodes skipped |
| after | **BUILD SUCCESSFUL in 6m 25s**, run `20260821-190550`, **13 nodes**, all 10 assertions pass |

**Control for the new cross-check** — its predicate has to be able to say no, so
that was measured rather than assumed, on a minimal home:

```
no foreign path planted        -> home verify exit 0
one foreign symlink planted    -> home verify exit 1, 1x FOREIGN_HOME
```

The node's pre-existing controls still hold: the walk read 30 entries per home,
and the planted-reference decoy was found (2 hits).
