# OUN-3 — `deps --who-imports`: the reverse edge, recomputed, cycle-safe

`GOAL-who-imports-this`: **no such command → one command**, and it agrees
with an independent walk of the same trees in **31 of 31 homes** on this
machine.

```
skill-manager deps --who-imports skill-manager
skill-manager deps --who-imports skill-manager --direct-only
```

## The spelling

The plan titled this `skt deps --who-imports`. It landed as
`skill-manager deps --who-imports`, and the reason is where the work is: the
ticket's own conflict key is `cli/deps`, its declared seam is `Resolver` and
`MarkdownImportValidator`, and all of that is this repository's Java. `skt`
lives in a different repository and is a front door, not an owner — giving it
a passthrough is a one-line change that belongs with OUN-7, which opens that
repository anyway for the rename. Recorded rather than assumed.

## What it answers

Both mechanisms, which is the whole difficulty — they address units by
different keys:

```
  direct (6):
    git-epic-workflow
      [skill-imports  by name]   skills/git-epic-workflow/SKILL.md
      [references     by coord]  skills/git-epic-workflow/skill-manager.toml
                                 → https://github.com/haydenrear/skill-manager-skill
```

Each edge names **the file carrying it**, because the question is asked by
whoever is about to break that line.

**The coordinate half needs a join nobody was doing.**
`github:haydenrear/skill-manager-skill` installs a unit named
`skill-manager`; the repository name is not the unit name. Taking the
coordinate's last segment — the obvious shortcut — yields `skill-manager-skill`,
a unit no home contains. `UnitEdgeGraph` resolves through each unit's
`installed/<name>.json` `origin` field, and a coordinate that matches nothing
installed is **listed as unresolved rather than guessed at**.

## Recomputed, never stored

By owner decision, and the homes here are the argument: the same question
answers **8** in the operator root home and **6** in the project home, and
both are correct *for their home*. An index built in one home and copied with
it into another describes the home it was built in. There is no file to
drift.

## The cycle constraint

The loops are real — in the root home `git-epic-workflow` imports
`git-issue-workflow` and it imports back. `transitiveImporters` marks a name
before expanding it, so a loop is walked once.

The test asserts termination **under a deadline**, on a daemon thread. "It
returns" cannot be tested by calling it and seeing whether the suite
finishes: that is precisely how a looping implementation hangs a whole run
with no failure to read. It fails in ten seconds with a message instead.

## The harness stopped reading help text

OUN-0 decided "is there a command" by matching `--who-imports` in `--help`
output. Wave 1's review flagged that as too weak for the terminal evaluation:
a flag can exist and return nothing. The harness now **runs** the command in
every home, parses its answer, and compares it to an independently computed
one — different code, same trees. It also runs each invocation under a
timeout, because "terminates on cycles" is in the goal's target and therefore
something to measure rather than assume.

```
one command, agreeing with an independent walk in 31 of 31 homes
timeouts 0 | no_command 0 | disagreements 0
```

The python walker is kept for exactly this reason: not to ship a second
implementation, but so the product has something it can be *wrong* against.

## Six cases

| case | asserts |
| --- | --- |
| both mechanisms reach one unit | and the coordinate resolves through the origin join, not the URL's tail |
| the whole chain | `top` reaches `leaf` through `middle` |
| a cycle terminates | under a 10s deadline that FAILS rather than hangs |
| an unresolvable coordinate | is reported, never invented as an edge |
| a contained skill is its own importer | the plugin carrying the file does not inherit the edge |
| a plugin's entry skill of its own name | is not a second node |
