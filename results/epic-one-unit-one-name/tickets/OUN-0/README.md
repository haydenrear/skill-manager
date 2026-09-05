# OUN-0 — baselines for all four goals, and the addressing rule

Measured 2026-09-05 on `feature/OUN-0`, base `0583a11`.

## What the harnesses say

| goal | value | target | met |
| --- | --- | --- | --- |
| `GOAL-one-name-one-copy` | 0 live, 21 latent collisions over 29 homes | 0 live AND 0 latent | no |
| `GOAL-a-contained-skill-is-addressable` | no | yes | no |
| `GOAL-migration-lands-on-one-skt` | root `(1, 1, 1)`; 0 of 28 homes at target | `(0, 0, 1)` | no |
| `GOAL-who-imports-this` | no such command | one command, both mechanisms, transitive, cycle-safe | no |

Run them together:

```
python3 scripts/measure_goals.py                 # this epic
python3 scripts/measure_goals.py --fast          # skip the one that clones a home
python3 scripts/measure_goals.py --epic home-boundary-resolution
```

The plan's hand-counted numbers reproduce mechanically: **8 importers of
`skill-manager` in the root home, 6 in the project home**, and root
`(1, 1, 1)` for the migration triple.

## The finding that changes the next two tickets

**Every home that carries `skt` already claims the name `skt` twice**:
the plugin at `plugins/skt`, and a contained skill at
`plugins/skt/skills/skt`. Twenty-one homes on this machine.

It is latent only because a contained skill is unaddressable, which is the
very thing OUN-1 fixes. So OUN-1 turns 21 latent collisions live, and a
collision gate (OUN-2) written without allowing for it would refuse the
`skt` plugin in every home it is installed into — including during its own
migration.

Both tickets need a rule for it. The obvious one: a contained skill whose
name equals its carrying plugin's is that plugin's entry skill, one unit
under one name, not two claims on it. That is a decision for OUN-1, recorded
here because it was not visible when the plan was written.

## The two mechanisms, as measured

`skill-imports` addresses **by name**; `skill_references` addresses **by
coordinate**; neither is recorded in `installed/*.json`. The coordinate is
not the name and the mapping is not guessable — `github:haydenrear/skill-manager-skill`
installs `skill-manager`, `github:haydenrear/skill-publisher-skill` installs
`skt`. The only join between them is each unit's own `installed/<name>.json`
`origin` field, and nothing in the product performs that join. Taking the
tail of the coordinate instead — the obvious shortcut — yields
`skill-manager-skill`, a unit that exists in no home.

The rule is written down once, in
`skill-manager-skill/references/skill-imports.md`, under **How A Unit Name Is
Addressed**.

## Counts are a snapshot of this machine

The home walkers read every home on this machine, and two runs minutes apart
returned 29 and 30 homes (21 and 22 latent collisions) -- the JSON files here
and `ledger.txt` were captured at different moments and disagree by one home
for that reason. Other sessions create and remove worktrees while the harness
runs. The per-home rows in each JSON file are the durable part;
the totals are not. Nothing here is stored as an index — the graph is
recomputed on every invocation, deliberately.

## Files

- `goal-one-name-one-copy.json` — every name claimed twice, with both roots
- `goal-a-contained-skill-is-addressable.json` — the product's own verdict
- `goal-migration-lands-on-one-skt.json` — the triple, per home
- `goal-who-imports-this.json` — CLI probes plus the answer they cannot give
- `ledger.txt` — all four in one table
- `validation.md` — what was run and what it said
